package com.ultron.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

/**
 * Fully offline, fully free wake-word detection via sherpa-onnx's Keyword
 * Spotter — no Picovoice account, no AccessKey, no per-wake-word training
 * wait. This reuses the same sherpa-onnx dependency already added for
 * PiperTtsEngine, so there's nothing new to add to build.gradle.kts.
 *
 * The real advantage over Porcupine here: this is an *open-vocabulary*
 * keyword spotter. You don't submit "Ultron" to a website and wait for a
 * trained .ppn file — you give it the phrase as text (converted to tokens
 * once, offline) and the same general acoustic model detects it directly.
 *
 * Setup (~20-30 minutes, all doable from a phone browser via Google Colab —
 * no PC/Android Studio needed for this part):
 * 1. Download a pretrained KWS model from
 *    https://github.com/k2-fsa/sherpa-onnx/releases (search the release
 *    notes/assets for "kws" — e.g. a small Zipformer-based English/bilingual
 *    keyword-spotting model). Each one ships:
 *      encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
 * 2. Generate keywords.txt for the phrase "ultron" using the model's own
 *    generate-keywords.py script (linked in the model's README on the same
 *    releases page) — open it as a Google Colab notebook in your phone's
 *    browser, run the two setup cells, then run it with your keyword text.
 *    It outputs a single line of token IDs — save that as keywords.txt.
 * 3. Put all 5 files under app/src/main/assets/kws/ in the project.
 * 4. Same StorageService-style copy-to-internal-storage step as
 *    VoskSttEngine/PiperTtsEngine — sherpa-onnx loads from a filesystem path.
 * 5. Class/field names below match sherpa-onnx's Kotlin KWS API as of
 *    late-2024 builds. Check the "kws" example app in the sherpa-onnx GitHub
 *    repo if a signature has moved — same caveat as PiperTtsEngine, this
 *    library updates fast.
 */
class KwsWakeWordEngine(
    private val context: Context,
    private val encoderPath: String,
    private val decoderPath: String,
    private val joinerPath: String,
    private val tokensPath: String,
    private val keywordsPath: String,
    private val onError: (String) -> Unit = {},
) : WakeWordEngine {

    private val sampleRate = 16000
    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var spotter: KeywordSpotter? = null

    override fun start(onWake: () -> Unit) {
        if (running) return // already listening

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Mic permission's missing — can't listen for the wake word without it.")
            return
        }

        try {
            spotter = buildSpotter()
        } catch (e: Exception) {
            onError("Wake-word model wouldn't load: ${e.message ?: "unknown error"}.")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        audioRecord = record
        running = true
        record.startRecording()

        val stream = spotter!!.createStream()
        recordThread = thread(start = true, name = "kws-listener") {
            val buffer = ShortArray(1600) // ~100ms chunks at 16kHz
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val floatSamples = FloatArray(read) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(floatSamples, sampleRate)
                    while (spotter?.isReady(stream) == true) {
                        spotter?.decode(stream)
                    }
                    val result = spotter?.getResult(stream)
                    if (!result?.keyword.isNullOrEmpty()) {
                        spotter?.reset(stream)
                        onWake()
                    }
                }
            }
        }
    }

    override fun stop() {
        running = false
        recordThread?.join(500)
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        spotter?.release()
        spotter = null
    }

    private fun buildSpotter(): KeywordSpotter = KeywordSpotter(
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    joiner = joinerPath,
                ),
                tokens = tokensPath,
                numThreads = 2,
            ),
            keywordsFile = keywordsPath,
        ),
    )
}
