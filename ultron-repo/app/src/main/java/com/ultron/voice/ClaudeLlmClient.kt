package com.ultron.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Online-only LLM client — this is the piece with no offline equivalent, which
 * is exactly why ConnectivityWatcher.isOnline routes to handleOfflineIntent()
 * instead of here when there's no network.
 *
 * Streams the reply token-by-token over Server-Sent Events so TtsEngine.speak()
 * can start synthesizing the first sentence before the model has finished
 * generating the rest — this is what makes the <500ms target realistic.
 *
 * Setup:
 * 1. Get an API key from https://console.anthropic.com
 * 2. Gradle:
 *    implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
 * 3. Never ship the raw key in the APK — read it from BuildConfig (populated
 *    from local.properties / a secrets manager at build time) or better,
 *    proxy the call through your own backend so the key never sits on-device.
 */
class ClaudeLlmClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5",
    private val maxTokens: Int = 300, // Ultron's replies are 1-2 sentences — keep this small for latency
) : LlmClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE stream stays open; don't let OkHttp time it out
        .build()

    override fun streamReply(userText: String, systemPrompt: String): Flow<String> = callbackFlow {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("stream", true)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                },
            ))
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), body))
            .build()

        val eventSource = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    // Anthropic streams several event types; only
                    // content_block_delta carries actual reply text.
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return
                    if (json.optString("type") == "content_block_delta") {
                        val text = json.optJSONObject("delta")?.optString("text").orEmpty()
                        if (text.isNotEmpty()) trySend(text)
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(t ?: RuntimeException("LLM stream failed: ${response?.code}"))
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            },
        )

        awaitClose { eventSource.cancel() }
    }.flowOn(Dispatchers.IO)
}
