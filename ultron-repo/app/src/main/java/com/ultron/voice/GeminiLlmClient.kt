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
 * Free LLM client via Google's Gemini API — no credit card, no expiring
 * trial credits. This is a drop-in swap for ClaudeLlmClient: same LlmClient
 * interface, so VoiceAssistantController doesn't change at all.
 *
 * Setup:
 * 1. Go to https://aistudio.google.com/apikey — sign in with any Google
 *    account, click "Create API key". No card required for the free tier.
 * 2. That's it — no separate signup/console like Picovoice needed.
 * 3. Free tier limits are generous for a personal assistant (well over what
 *    one person talking to Ultron will hit) but do change over time — check
 *    https://ai.google.dev/gemini-api/docs/rate-limits if you ever get
 *    throttled, and https://ai.google.dev/gemini-api/docs/models for the
 *    current fastest/free-tier-friendly model name if "gemini-2.0-flash"
 *    below has been superseded.
 *
 * Uses the same okhttp/okhttp-sse dependency already added for
 * ClaudeLlmClient — nothing new to add to build.gradle.kts.
 */
class GeminiLlmClient(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash",
    private val maxOutputTokens: Int = 300, // Ultron's replies are 1-2 sentences — keep this small for latency
) : LlmClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE stream stays open
        .build()

    override fun streamReply(userText: String, systemPrompt: String): Flow<String> = callbackFlow {
        val body = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userText)))
                    },
                ),
            )
            put("generationConfig", JSONObject().put("maxOutputTokens", maxOutputTokens))
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent" +
            "?key=$apiKey&alt=sse"

        val request = Request.Builder()
            .url(url)
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
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    if (text.isNotEmpty()) trySend(text)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(t ?: RuntimeException("Gemini stream failed: ${response?.code}"))
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            },
        )

        awaitClose { eventSource.cancel() }
    }.flowOn(Dispatchers.IO)
}
