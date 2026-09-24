package com.flowpilot.ai

import android.util.Log
import com.flowpilot.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class GeminiClient(
    private val apiKey: String = Constants.GEMINI_API_KEY,
    private val groqApiKey: String = Constants.GROQ_API_KEY,
    private val openRouterApiKey: String = Constants.OPENROUTER_API_KEY
) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Send a prompt to AI with Groq as primary ultra-fast engine:
     * 1. Groq (openai/gpt-oss-120b, openai/gpt-oss-20b, qwen/qwen3.8-27b) — ~0.4s to 2.5s
     * 2. Only if Groq key is missing or all Groq models fail, fallback to OpenRouter / Gemini.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        // 1. Try Groq (Primary ultra-fast engine)
        if (groqApiKey.isNotBlank()) {
            val groqModels = listOf(
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "qwen/qwen3.8-27b"
            )
            for (model in groqModels) {
                val result = callGroq(model, systemPrompt, userPrompt, jsonMode)
                if (!result.isNullOrBlank()) {
                    return@withContext result
                }
            }
            // If jsonMode caused validation failure across models, try once without strict json_object wrapper
            if (jsonMode) {
                for (model in groqModels) {
                    val result = callGroq(model, systemPrompt, userPrompt, jsonMode = false)
                    if (!result.isNullOrBlank()) {
                        return@withContext result
                    }
                }
            }
        }

        // 2. Try OpenRouter (only if Groq didn't succeed)
        if (openRouterApiKey.isNotBlank()) {
            val openRouterModels = listOf("openrouter/free")
            for (model in openRouterModels) {
                val result = callOpenRouter(model, systemPrompt, userPrompt, jsonMode)
                if (!result.isNullOrBlank()) {
                    return@withContext result
                }
            }
        }

        // 3. Try Google Gemini (only if configured)
        if (apiKey.isNotBlank() && apiKey != "YOUR_GEMINI_API_KEY_HERE") {
            val geminiModels = listOf("gemini-2.5-flash", "gemini-2.0-flash")
            for (model in geminiModels) {
                val result = callGemini(model, systemPrompt, userPrompt, jsonMode)
                if (!result.isNullOrBlank()) {
                    return@withContext result
                }
            }
        }

        Log.e(TAG, "All candidate AI models failed")
        null
    }

    private fun callGroq(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String? {
        if (groqApiKey.isBlank()) return null
        return try {
            val effectiveSysPrompt = if (jsonMode && !systemPrompt.contains("json", ignoreCase = true)) {
                "$systemPrompt\nRespond strictly in valid JSON object format."
            } else {
                systemPrompt
            }
            val requestBody = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", effectiveSysPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                }
                put("temperature", 0.1)
                if (jsonMode) {
                    put("response_format", buildJsonObject {
                        put("type", "json_object")
                    })
                }
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $groqApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                // Check if Groq returned HTTP 400 json_validate_failed with a usable failed_generation
                if (response.code == 400 && body != null && body.contains("failed_generation")) {
                    try {
                        val errObj = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                        val failedGen = errObj?.get("failed_generation")?.jsonPrimitive?.contentOrNull
                        if (!failedGen.isNullOrBlank() && (failedGen.contains("{") || failedGen.contains("["))) {
                            Log.i(TAG, "Recovered JSON from Groq ($model) failed_generation (${failedGen.length} chars)")
                            return failedGen
                        }
                    } catch (_: Exception) {}
                }
                Log.w(TAG, "Groq ($model) returned HTTP ${response.code}: ${body?.take(200)}. Trying next fallback...")
                return null
            }

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val choices = jsonResponse["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) return null
            val content = choices[0].jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrBlank()) {
                // Strip <think>...</think> blocks if a reasoning model emitted them
                val cleaned = content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                Log.i(TAG, "AI response generated via Groq ($model), ${cleaned.length} chars")
                cleaned.ifBlank { content }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Groq ($model) call failed: ${e.message}. Trying next fallback...", e)
            null
        }
    }

    private fun callOpenRouter(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String? {
        if (openRouterApiKey.isBlank()) return null
        return try {
            val requestBody = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                }
                put("temperature", 0.1)
                if (jsonMode) {
                    put("response_format", buildJsonObject {
                        put("type", "json_object")
                    })
                }
            }

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $openRouterApiKey")
                .addHeader("HTTP-Referer", "https://flowpilot.local")
                .addHeader("X-Title", "FlowPilot")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "OpenRouter ($model) returned HTTP ${response.code}: $body. Trying next fallback...")
                return null
            }

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val choices = jsonResponse["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) return null
            val content = choices[0].jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrBlank()) {
                Log.i(TAG, "AI response generated via OpenRouter ($model), ${content.length} chars")
                content
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter ($model) call failed: ${e.message}. Trying next fallback...", e)
            null
        }
    }

    private fun callGemini(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String? {
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") return null
        return try {
            val requestBody = buildJsonObject {
                put("system_instruction", buildJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPrompt) }
                    }
                })
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", userPrompt) }
                        }
                    }
                }
                if (jsonMode) {
                    put("generationConfig", buildJsonObject {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.1)
                    })
                }
            }
            val mediaType = "application/json".toMediaType()
            val bodyContent = requestBody.toString()

            val request = Request.Builder()
                .url("$baseUrl/$model:generateContent?key=$apiKey")
                .post(bodyContent.toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "Model $model returned HTTP ${response.code}: $body. Trying next fallback...")
                return null
            }

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val candidates = jsonResponse["candidates"]?.jsonArray ?: return null
            if (candidates.isEmpty()) return null
            val content = candidates[0].jsonObject["content"]?.jsonObject
            val parts = content?.get("parts")?.jsonArray ?: return null

            val textParts = parts.mapNotNull { p ->
                val pObj = p.jsonObject
                if (pObj["thought"]?.jsonPrimitive?.booleanOrNull == true) null
                else pObj["text"]?.jsonPrimitive?.contentOrNull
            }.filter { it.isNotBlank() }

            val text = if (textParts.isNotEmpty()) {
                textParts.joinToString("\n")
            } else {
                parts.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            }

            if (!text.isNullOrBlank()) {
                Log.i(TAG, "AI response generated via Gemini ($model), ${text.length} chars")
                text
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Model $model call failed: ${e.message}. Trying next fallback...", e)
            null
        }
    }

    /**
     * Compute embedding for a text string.
     * Uses instant on-device semantic n-gram embedding when Groq is primary (zero network delay).
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        if (groqApiKey.isNotBlank() || apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            return@withContext computeLocalSemanticEmbedding(text)
        }
        try {
            val requestBody = buildJsonObject {
                put("model", "models/text-embedding-004")
                put("content", buildJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", text) }
                    }
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext computeLocalSemanticEmbedding(text)

            if (!response.isSuccessful) {
                return@withContext computeLocalSemanticEmbedding(text)
            }

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val values = jsonResponse["embedding"]?.jsonObject?.get("values")?.jsonArray
            values?.map { it.jsonPrimitive.float }?.toFloatArray() ?: computeLocalSemanticEmbedding(text)
        } catch (e: Exception) {
            computeLocalSemanticEmbedding(text)
        }
    }

    private fun computeLocalSemanticEmbedding(text: String, dims: Int = 256): FloatArray {
        val vec = FloatArray(dims)
        val normalized = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim()
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (w in words) {
            val h1 = (w.hashCode() and 0x7fffffff) % dims
            vec[h1] += 2.0f
            // Character trigrams for morphological / typo resilience
            val padded = "#$w#"
            for (i in 0..padded.length - 3) {
                val trigram = padded.substring(i, i + 3)
                val h2 = (trigram.hashCode() and 0x7fffffff) % dims
                vec[h2] += 0.7f
            }
        }
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dotProduct / denom
    }
}
