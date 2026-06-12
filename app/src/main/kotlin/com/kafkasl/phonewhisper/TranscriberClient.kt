package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("candidates") -> {
                val candidates = obj.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val text = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    Result(text.trim(), null)
                } else {
                    Result(null, "No candidates in response")
                }
            }
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        language: String = DEFAULT_LANGUAGE,
        callback: (Result) -> Unit
    ) {
        val langName = APP_LANGUAGES.firstOrNull { it.code == language }?.englishName ?: "English"
        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "audio/wav")
                    put("data", Base64.getEncoder().encodeToString(wavData))
                })
            })
            put(JSONObject().apply {
                put("text", "The audio is spoken in $langName. Transcribe this audio verbatim in $langName. Return only the transcribed text, no commentary.")
            })
        }
        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply { put("parts", parts) })
            })
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseResponse(responseBody))
            }
        })
    }
}
