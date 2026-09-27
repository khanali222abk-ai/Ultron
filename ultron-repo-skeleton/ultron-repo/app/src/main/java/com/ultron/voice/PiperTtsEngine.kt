package com.ultron.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Offline neural TTS. Piper itself ships as a C++/ONNX voice model rather than
 * an Android library, so the practical way to run a Piper voice on-device
 * today is via sherpa-onnx (k2-fsa/sherpa-onnx), which loads Piper's VITS
 * .onnx models directly and gives you a Kotlin API + prebuilt Android AARs —
 * no NDK/JNI work required on your side.
 *
 * Setup (~15-20 minutes):
 * 1. Gradle (check the project's README for current AAR coordinates/version —
 *    sherpa-onnx publishes per-ABI artifacts; pick the one matching your
 *    target ABIs, or the "all ABIs" build for simplicity during development):
 *    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-android:<latest>")
 * 2. Download a Piper voice from https://github.com/rhasspy/piper (or the
 *    sherpa-onnx pre-converted voice list) — for the "deep male, 20-25"
 *    profile in the spec, "en_US-ryan-high" or "en_US-joe-medium" are close
 *    starting points; every Piper voice is a pair of files:
 *      <voice>.onnx        (the model)
 *      <voice>.onnx.json   (its config: sample rate, speaker id, etc.)
 * 3. Put both files under app/src/main/assets/tts/ and copy them to internal
 *    storage on first launch (same pattern as VoskSttEngine's StorageService
 *    step — sherpa-onnx loads from a filesystem path, not directly from assets).
 * 4. Class/field names below match sherpa-onnx's Kotlin API as of late-2024
 *    builds; pin a specific release tag and check its example app if a
 *    signature has moved — this layer changes faster than Vosk/Porcupine.
 */
class PiperTtsEngine(
    private val context: Context,
    modelPath: String,
    modelConfigPath: String,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
) : TtsEngine {

    private val tts: OfflineTts = OfflineTts(
        assetManager = null, // using absolute filesystem paths, not assets directly
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = modelConfigPath,
                ),
                numThreads = 2,
            ),
        ),
    )

    @Volatile private var stopRequested = false
    private var audioTrack: AudioTrack? = null

    override suspend fun speak(
        textChunks: Flow<String>,
        onAmplitude: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        stopRequested = false
        val sampleRate = tts.sampleRate()
        val track = buildAudioTrack(sampleRate).also { audioTrack = it }
        track.play()

        try {
            textChunks.collect { chunk ->
                if (stopRequested) return@collect
                if (chunk.isBlank()) return@collect

                // Synthesize this chunk (a sentence/clause from the LLM stream)
                // as soon as it arrives — don't wait for the full reply.
                val audio: GeneratedAudio = tts.generate(
                    text = chunk,
                    sid = speakerId,
                    speed = speed,
                )
                writeAndReportAmplitude(track, audio.samples, onAmplitude)
            }
        } finally {
            track.stop()
            track.release()
            audioTrack = null
        }
    }

    override fun stopSpeaking() {
        stopRequested = true
        audioTrack?.pause()
        audioTrack?.flush()
    }

    private fun buildAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Converts float PCM [-1,1] to 16-bit PCM, writes it, and reports RMS
     *  amplitude per buffer so AssistantOrb's SPEAKING state can pulse with it. */
    private fun writeAndReportAmplitude(
        track: AudioTrack,
        samples: FloatArray,
        onAmplitude: (Float) -> Unit,
    ) {
        val pcm16 = ShortArray(samples.size)
        var sumSquares = 0.0
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            pcm16[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
            sumSquares += clamped * clamped
        }
        track.write(pcm16, 0, pcm16.size)
        val rms = sqrt(sumSquares / samples.size.coerceAtLeast(1)).toFloat()
        onAmplitude(rms.coerceIn(0f, 1f))
    }
}
