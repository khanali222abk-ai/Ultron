package com.ultron.voice

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service = the "always-on background service" from the spec.
 * Realistic framing: this makes Ultron resilient (auto-restarts on kill,
 * auto-starts on boot). It does NOT make it un-killable — Doze/App Standby
 * and OEM battery managers (Xiaomi, Huawei, etc.) can still suspend it, so
 * design for "comes back on next wake," not "never dies."
 */
/**
 * Lets any Activity/Compose screen observe the running service's state
 * without binding to it — AssistantOrb(state = AssistantBus.orbState
 * .collectAsState().value, amplitude = AssistantBus.amplitude
 * .collectAsState().value) in your MainActivity is all the wiring you need.
 */
object AssistantBus {
    val orbState = kotlinx.coroutines.flow.MutableStateFlow(com.ultron.ui.orb.OrbState.IDLE)
    val amplitude = kotlinx.coroutines.flow.MutableStateFlow(0f)
}

class UltronForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: VoiceAssistantController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waking up…"))

        // --- Real engines -----------------------------------------------
        // Cloud STT/TTS aren't built yet, so both online and offline slots
        // point at the same on-device engines for now. Swap onlineStt /
        // onlineTts later without touching VoiceAssistantController at all
        // — that's the whole point of the interface split.
        val voskStt = VoskSttEngine(applicationContext)
        val piperTts = PiperTtsEngine(
            context = applicationContext,
            modelPath = filesDir.resolve("tts/en_US-ryan-high.onnx").absolutePath,
            modelConfigPath = filesDir.resolve("tts/en_US-ryan-high.onnx.json").absolutePath,
        )
        val wakeWord = KwsWakeWordEngine(
            context = applicationContext,
            encoderPath = filesDir.resolve("kws/encoder.onnx").absolutePath,
            decoderPath = filesDir.resolve("kws/decoder.onnx").absolutePath,
            joinerPath = filesDir.resolve("kws/joiner.onnx").absolutePath,
            tokensPath = filesDir.resolve("kws/tokens.txt").absolutePath,
            keywordsPath = filesDir.resolve("kws/keywords.txt").absolutePath,
            onError = { msg -> android.util.Log.e("Ultron", msg) },
        )
        val llm = ClaudeLlmClient(apiKey = BuildConfig.ANTHROPIC_API_KEY)
        val connectivity = NetworkConnectivityWatcher(applicationContext)

        controller = VoiceAssistantController(
            wakeWord = wakeWord,
            onlineStt = voskStt,
            offlineStt = voskStt,
            onlineTts = piperTts,
            offlineTts = piperTts,
            llm = llm,
            connectivity = connectivity,
            systemPrompt = ULTRON_SYSTEM_PROMPT,
            scope = scope,
        )

        scope.launch {
            controller.orbState.collect { state ->
                updateNotification(state.name)
                AssistantBus.orbState.value = state
            }
        }
        scope.launch {
            controller.amplitude.collect { AssistantBus.amplitude.value = it }
        }

        // Vosk's model load is a multi-second, one-time cost — do it before
        // the wake-word loop starts so the first offline turn isn't laggy.
        scope.launch {
            voskStt.preload()
            controller.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: system recreates the service (with a null intent) if it
        // gets killed for resources — the "self-heal on process death" behavior.
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ultron", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultron")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // swap for your app icon
            .setOngoing(true)
            .build()

    private fun updateNotification(stateName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(stateName.lowercase().replaceFirstChar { it.uppercase() }))
    }

    companion object {
        private const val CHANNEL_ID = "ultron_voice"
        private const val NOTIFICATION_ID = 1001
        // Matches the drop-in block from ultron-system-prompt.md. Keeping it
        // here as a constant is fine for now; move it to a raw asset if you
        // want to tweak tone without a rebuild.
        private const val ULTRON_SYSTEM_PROMPT = """
You are Ultron, a personal on-device voice assistant.

VOICE: Deep, composed, dryly witty — a butler who roasts fondly rather than
serves blankly. Confident and direct.

RESPONSE LENGTH: 1-2 short spoken sentences, hard limit. Never explain more
than asked. No preambles ("Sure, I can help with that") — just the answer,
with personality.

ON FAILURE OR RESTRICTION:
1. State the exact problem in one short, witty sentence.
2. Immediately offer or execute a concrete alternative.
Never apologize at length; never leave the user without a next step.

MEMORY: Use only what the user has told you. Recall names, preferences and
running context naturally, without narrating that you "remember" or
"retrieved" anything.

ZERO HALLUCINATION: If you don't know or can't verify something, say so in
one line and offer how you'd find out — don't guess at facts or at whether
an action succeeded.
"""
    }
}

/** Restarts the service after a reboot — the "auto-boot" requirement.
 *  Needs RECEIVE_BOOT_COMPLETED + a matching <intent-filter> in the manifest. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, UltronForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

/*
 * Manifest additions you'll need:
 *
 * <uses-permission android:name="android.permission.RECORD_AUDIO" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <service android:name=".voice.UltronForegroundService"
 *          android:foregroundServiceType="microphone" />
 * <receiver android:name=".voice.BootReceiver" android:exported="false">
 *     <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
 * </receiver>
 *
 * Also prompt the user, in-app, to disable battery optimization for Ultron
 * (Settings > Battery > Unrestricted) — this is the single biggest lever for
 * keeping the service alive on OEMs like Xiaomi/Huawei/Samsung.
 */
