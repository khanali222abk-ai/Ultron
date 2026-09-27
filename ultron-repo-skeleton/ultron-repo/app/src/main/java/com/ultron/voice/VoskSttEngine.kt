package com.ultron.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fully on-device STT via Vosk — no network call, this is what runs when
 * ConnectivityWatcher.isOnline is false.
 *
 * Setup (~15 minutes, mostly download time):
 * 1. Gradle:
 *    repositories { maven { url = uri("https://jitpack.io") } } // in settings.gradle
 *    implementation("com.alphacephei:vosk-android:0.3.47")
 *    implementation("net.java.dev.jna:jna:5.13.0@aar")
 * 2. Download a small English model from https://alphacephei.com/vosk/models
 *    — "vosk-model-small-en-us-0.15" (~40MB) is the right size/accuracy
 *    trade-off for a phone; bigger models are more accurate but slower to
 *    load and heavier on storage.
 * 3. Unzip it and place the folder contents under
 *    app/src/main/assets/model-en-us/ (keep Vosk's internal folder structure
 *    — am, conf, graph, etc. — exactly as unzipped).
 * 4. First app launch copies it out of assets into internal storage via
 *    StorageService.unpack (one-time cost, a couple of seconds); after that
 *    it loads instantly from the copied path.
 *
 * Call [preload] once at app/service startup (e.g. in UltronForegroundService
 * .onCreate, before controller.start()) so the model is warm before the first
 * wake-word fires — loading it lazily inside listen() would add a multi-
 * second stall to the very first offline turn.
 */
class VoskSttEngine(private val context: Context) : SttEngine {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    /** Unpacks + loads the model once. Safe to call multiple times (no-ops if already loaded). */
    suspend fun preload(): Unit = suspendCancellableCoroutine { cont ->
        if (model != null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        StorageService.unpack(
            context, "model-en-us", "model",
            { unpackedModel ->
                model = unpackedModel
                cont.resume(Unit)
            },
            { exception ->
                Log.e(TAG, "Vosk model failed to load", exception)
                cont.resumeWithException(exception)
            },
        )
    }

    override suspend fun listen(
        onPartial: (String) -> Unit,
        onEndOfSpeech: (finalText: String) -> Unit,
        onSilenceTimeout: () -> Unit,
    ) {
        val loadedModel = model ?: run {
            preload()
            model
        } ?: throw IllegalStateException("Vosk model not available — check assets/model-en-us")

        suspendCancellableCoroutine<Unit> { cont ->
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also { service ->
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        hypothesis?.let {
                            val text = JSONObject(it).optString("partial")
                            if (text.isNotBlank()) onPartial(text)
                        }
                    }

                    override fun onResult(hypothesis: String?) {
                        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: ""
                        if (text.isNotBlank()) {
                            onEndOfSpeech(text)
                        } else {
                            onSilenceTimeout()
                        }
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        // Fired on explicit stop() — onResult already handled the
                        // normal end-of-utterance case above.
                    }

                    override fun onError(exception: Exception?) {
                        Log.e(TAG, "Vosk recognition error", exception)
                        if (cont.isActive) cont.resumeWithException(
                            exception ?: RuntimeException("Unknown Vosk error"),
                        )
                    }

                    override fun onTimeout() {
                        onSilenceTimeout()
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }

            cont.invokeOnCancellation { stop() }
        }
    }

    override fun stop() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    companion object {
        private const val TAG = "VoskSttEngine"
        private const val SAMPLE_RATE = 16000.0f
    }
}
