package com.ultron.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

/**
 * On-device wake-word detection via Picovoice Porcupine. Runs entirely
 * offline, low CPU/battery footprint — a good fit for the "24/7 background
 * listener" requirement.
 *
 * Setup (one-time, ~10 minutes):
 * 1. Sign up free at https://console.picovoice.ai — get an AccessKey.
 * 2. In the console, go to Porcupine -> train a custom wake word: type
 *    "Ultron", pick Android as the target platform, download the .ppn file.
 *    (Free tier: personal/dev use, a handful of active wake-word files.)
 * 3. Drop the downloaded file into app/src/main/assets/ultron_wake_word.ppn
 * 4. Gradle: implementation("ai.picovoice:porcupine-android:3.0.2")
 * 5. Pass your AccessKey in at construction time — do NOT hardcode it in
 *    source you commit; read it from local.properties / BuildConfig instead.
 *
 * Built-in keywords (no custom training needed) are also available via
 * Porcupine.BuiltInKeyword — e.g. "Jarvis" ships out of the box — useful for
 * prototyping before your custom "Ultron" model is ready.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val keywordAssetPath: String = "ultron_wake_word.ppn",
    private val sensitivity: Float = 0.6f, // 0f (fewer false accepts) .. 1f (fewer misses)
    private val onError: (String) -> Unit = {},
) : WakeWordEngine {

    private var manager: PorcupineManager? = null

    override fun start(onWake: () -> Unit) {
        if (manager != null) return // already listening — avoid double-start

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Mic permission's missing — can't listen for the wake word without it.")
            return
        }

        try {
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordAssetPath)
                .setSensitivity(sensitivity)
                .build(context, PorcupineManagerCallback { keywordIndex ->
                    // keywordIndex matters only if you registered multiple
                    // keyword files at once; with a single "Ultron" model
                    // any callback firing means that one matched.
                    onWake()
                })
            manager?.start()
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine failed to start", e)
            onError("Wake-word engine wouldn't start: ${e.message ?: "unknown error"}.")
            manager = null
        }
    }

    override fun stop() {
        try {
            manager?.stop()
            manager?.delete()
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine failed to stop cleanly", e)
        } finally {
            manager = null
        }
    }

    companion object {
        private const val TAG = "PorcupineWakeWordEngine"
    }
}
