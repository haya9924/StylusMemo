package com.stylusmemo.app.plugin.aimarkdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A vision-capable client that transcribes a page image into text. */
interface TextVisionClient {
    suspend fun completeTextAndImage(prompt: String, imagePng: ByteArray): String
}

@Serializable
internal data class InlineData(val mimeType: String, val data: String)

@Serializable
internal data class Part(val text: String? = null, val inlineData: InlineData? = null)

@Serializable
internal data class Content(val parts: List<Part> = emptyList())

@Serializable
internal data class GenerateContentRequest(val contents: List<Content> = emptyList())

@Serializable
internal data class Candidate(val content: Content? = null)

@Serializable
internal data class GenerateContentResponse(val candidates: List<Candidate> = emptyList())

/**
 * Minimal client for the Gemini `generateContent` endpoint with inline (base64)
 * image data. Uses plain REST so no Google SDK is needed.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val http: OkHttpClient = GeminiClient.defaultHttp(),
) : TextVisionClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun completeTextAndImage(prompt: String, imagePng: ByteArray): String =
        withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(
                GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = prompt),
                                Part(
                                    inlineData = InlineData(
                                        mimeType = "image/png",
                                        data = Base64.getEncoder().encodeToString(imagePng),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val url = "$baseUrl/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    throw IOException("Gemini API error ${it.code}: ${body.take(300)}")
                }
                val parsed = runCatching { json.decodeFromString(GenerateContentResponse.serializer(), body) }
                    .getOrElse { throw IOException("Gemini 応答の解析に失敗しました: ${body.take(200)}") }
                parsed.candidates
                    .flatMap { c -> c.content?.parts.orEmpty() }
                    .mapNotNull { p -> p.text }
                    .joinToString("\n")
                    .trim()
            }
        }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}