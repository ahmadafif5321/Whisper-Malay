package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object PostProcessor {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text."

    const val DEV_PROMPT = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  1. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  2. Fix grammatical mistakes.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an
     answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode ssh into morty user at rubicon</input>
<output>ssh morty@rubicon</output>
</example>
<example>
<input>List files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = DEV_PROMPT

    const val MALAY_PROMPT = """Clean up a Bahasa Melayu (Malaysia) transcript — fix punctuation, capitalization and obvious transcription spacing errors ONLY. DO NOT translate. Preserve Manglish/bahasa rojak code-switching exactly (English words stay English, Malay stays Malay). Preserve particles like lah, kan, je, kot, eh. Return only the cleaned text.

Examples:
- "boleh tak you hantar kat saya esok" → "Boleh tak you hantar kat saya esok?"
- "dia cakap okay je kan tak payah risau lah" → "Dia cakap okay je kan, tak payah risau lah."""

    /**
     * Returns MALAY_PROMPT when language is "ms" and the active prompt is one of the built-in
     * English presets. Otherwise returns the activePrompt unchanged.
     */
    fun promptForLanguage(activePrompt: String, language: String): String {
        if (language == "ms" &&
            (activePrompt == DEV_PROMPT || activePrompt == SIMPLE_PROMPT || activePrompt == DEFAULT_PROMPT)
        ) {
            return MALAY_PROMPT
        }
        return activePrompt
    }

    fun parseResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("candidates")) {
                val candidates = obj.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    Result(content.trim(), null)
                } else {
                    Result(null, "No candidates in response")
                }
            } else if (obj.has("error")) {
                Result(null, obj.getJSONObject("error").getString("message"))
            } else {
                Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    fun process(text: String, prompt: String, apiKey: String, callback: (Result) -> Unit) {
        val parts = JSONArray().apply {
            put(JSONObject().apply { put("text", prompt) })
            put(JSONObject().apply { put("text", text) })
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
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

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
