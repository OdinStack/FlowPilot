package com.flowpilot.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class GeminiClient(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val model = "gemini-2.5-flash"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Send a prompt to Gemini and get a text response.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        try {
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

            val request = Request.Builder()
                .url("$baseUrl/$model:generateContent?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $body")
                return@withContext null
            }

            if (body == null) return@withContext null

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val candidates = jsonResponse["candidates"]?.jsonArray ?: return@withContext null
            val content = candidates[0].jsonObject["content"]?.jsonObject
            val parts = content?.get("parts")?.jsonArray ?: return@withContext null
            val text = parts[0].jsonObject["text"]?.jsonPrimitive?.content

            Log.d(TAG, "Gemini response (${text?.length ?: 0} chars)")
            text
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            null
        }
    }

    /**
     * Compute embedding for a text string.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("model", "models/gemini-embedding-2")
                put("content", buildJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", text) }
                    }
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-2:embedContent?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.e(TAG, "Embedding error ${response.code}: $body")
                return@withContext null
            }

            val jsonResponse = json.parseToJsonElement(body).jsonObject
            val values = jsonResponse["embedding"]?.jsonObject?.get("values")?.jsonArray
            values?.map { it.jsonPrimitive.float }?.toFloatArray()
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed", e)
            null
        }
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
