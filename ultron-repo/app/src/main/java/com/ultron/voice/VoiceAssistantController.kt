package com.ultron.voice

import com.ultron.ui.orb.OrbState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pluggable interfaces so you can swap online <-> offline engines at runtime
 * (that's what "seamless offline fallback" from the spec actually means in
 * code: same call sites, different implementation picked by ConnectivityWatcher).
 */

/** Fires once when the wake word is detected. Backed by Porcupine (recommended,
 *  on-device, low-power) or openWakeWord if you want a fully open-source engine. */
interface WakeWordEngine {
    fun start(onWake: () -> Unit)
    fun stop()
}

/** Streams partial + final transcripts. Online impl: your cloud STT of choice.
 *  Offline impl: Vosk (small model, ~50MB, runs fully on-device). */
interface SttEngine {
    suspend fun listen(
        onPartial: (String) -> Unit,
        onEndOfSpeech: (finalText: String) -> Unit,
        onSilenceTimeout: () -> Unit,
    )
    fun stop()
}

/** Synthesizes speech. Online impl: your TTS API, chunked for low latency.
 *  Offline impl: Piper (on-device neural TTS) or Android's built-in TextToSpeech
 *  as the last-resort fallback (lower quality, but always available). */
interface TtsEngine {
    /** Speaks [text] chunk by chunk as it arrives from the LLM stream. */
    suspend fun speak(textChunks: kotlinx.coroutines.flow.Flow<String>, onAmplitude: (Float) -> Unit)
    fun stopSpeaking() // used for barge-in
}

/** Your LLM call. Must support streaming tokens so TTS can start before the
 *  full reply is generated (this is what gets you under the 500ms target). */
interface LlmClient {
    fun streamReply(userText: String, systemPrompt: String): kotlinx.coroutines.flow.Flow<String>
}

interface ConnectivityWatcher {
    val isOnline: Boolean
}

data class VoiceError(val message: String, val fallbackAction: (() -> Unit)? = null)

/**
 * The state machine described in the spec:
 * IDLE -> (wake word) -> LISTENING -> (end of speech) -> THINKING
 *      -> (first token) -> SPEAKING -> IDLE
 * ALERT is a transient overlay fired on any VoiceError, not a queue state.
 */
class VoiceAssistantController(
    private val wakeWord: WakeWordEngine,
    private val onlineStt: SttEngine,
    private val offlineStt: SttEngine,
    private val onlineTts: TtsEngine,
    private val offlineTts: TtsEngine,
    private val llm: LlmClient,
    private val connectivity: ConnectivityWatcher,
    private val systemPrompt: String,
    private val scope: CoroutineScope,
) {
    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _lastError = MutableStateFlow<VoiceError?>(null)
    val lastError: StateFlow<VoiceError?> = _lastError.asStateFlow()

    private var pipelineJob: Job? = null

    private val stt get() = if (connectivity.isOnline) onlineStt else offlineStt
    private val tts get() = if (connectivity.isOnline) onlineTts else offlineTts

    fun start() {
        wakeWord.start(onWake = { onWakeDetected() })
    }

    fun stop() {
        wakeWord.stop()
        pipelineJob?.cancel()
        stt.stop()
        tts.stopSpeaking()
        _orbState.value = OrbState.IDLE
    }

    /** Full-duplex barge-in: user speaking again while Ultron talks cuts it off instantly. */
    fun onUserBargeIn() {
        tts.stopSpeaking()
        pipelineJob?.cancel()
        onWakeDetected()
    }

    private fun onWakeDetected() {
        pipelineJob?.cancel()
        pipelineJob = scope.launch { runTurn() }
    }

    private suspend fun runTurn() {
        _orbState.value = OrbState.LISTENING
        var heardSomething = false

        try {
            stt.listen(
                onPartial = { /* wire to a live captions view if you want one */ },
                onEndOfSpeech = { finalText ->
                    heardSomething = true
                    scope.launch { respond(finalText) }
                },
                onSilenceTimeout = {
                    if (!heardSomething) {
                        raiseError("Nothing but silence. Try again when you're ready to talk.")
                    }
                },
            )
        } catch (e: Exception) {
            raiseError("Mic pipeline choked: ${e.message ?: "unknown reason"}. Restarting listener.") {
                start()
            }
        }
    }

    private suspend fun respond(userText: String) {
        _orbState.value = OrbState.THINKING
        try {
            val tokenFlow = llm.streamReply(userText, systemPrompt)
            _orbState.value = OrbState.SPEAKING
            tts.speak(tokenFlow, onAmplitude = { _amplitude.value = it })
        } catch (e: Exception) {
            raiseError("Brain's offline for a second: ${e.message ?: "no response"}. Falling back to local intents.") {
                handleOfflineIntent(userText)
            }
        } finally {
            if (_orbState.value != OrbState.ALERT) _orbState.value = OrbState.IDLE
        }
    }

    /** Rule-based fallback for the handful of commands that must never depend
     *  on the network: alarms, timers, flashlight, volume, local file ops. */
    private fun handleOfflineIntent(userText: String) {
        // Match against a small fixed grammar here (regex or a simple intent
        // table) and call straight into system APIs — no LLM round trip.
        // e.g. "set an alarm for 7" -> AlarmManager, "flashlight on" -> CameraManager.
    }

    private fun raiseError(message: String, fallback: (() -> Unit)? = null) {
        _lastError.value = VoiceError(message, fallback)
        _orbState.value = OrbState.ALERT
        scope.launch {
            delay(1400) // matches the orb's own auto-return-to-idle timing
            if (_orbState.value == OrbState.ALERT) _orbState.value = OrbState.IDLE
        }
        fallback?.invoke()
    }
}
