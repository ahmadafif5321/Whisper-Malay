package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {

    @Test
    fun parseSuccess() {
        val json = """
        {
            "candidates": [{
                "content": {
                    "parts": [{
                        "text": "Hello there, how are you?"
                    }],
                    "role": "model"
                },
                "finishReason": "STOP"
            }],
            "usageMetadata": {
                "promptTokenCount": 9,
                "candidatesTokenCount": 12,
                "totalTokenCount": 21
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals("Hello there, how are you?", result.text)
        assertEquals(null, result.error)
    }

    @Test
    fun parseError() {
        val json = """
        {
            "error": {
                "code": 400,
                "message": "Incorrect API key provided.",
                "status": "INVALID_ARGUMENT"
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("Incorrect API key provided.", result.error)
    }

    @Test
    fun parseEmptyCandidates() {
        val json = """
        {
            "candidates": []
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("No candidates in response", result.error)
    }

    @Test
    fun parseInvalidJson() {
        val result = PostProcessor.parseResponse("invalid json")
        assertEquals(null, result.text)
        assertTrue(
            result.error?.contains("JSONObject") == true ||
                result.error?.contains("must begin with '{'") == true
        )
    }

    // --- MALAY_PROMPT content ---

    @Test
    fun malayPromptContainsNoTranslateInstruction() {
        val prompt = PostProcessor.MALAY_PROMPT.lowercase()
        assertTrue(
            "MALAY_PROMPT must contain a no-translate instruction",
            prompt.contains("do not translate") || prompt.contains("jangan") || prompt.contains("tidak")
        )
    }

    @Test
    fun malayPromptDiffersFromDevPrompt() {
        assertTrue(PostProcessor.MALAY_PROMPT != PostProcessor.DEV_PROMPT)
    }

    @Test
    fun malayPromptDiffersFromSimplePrompt() {
        assertTrue(PostProcessor.MALAY_PROMPT != PostProcessor.SIMPLE_PROMPT)
    }

    // --- promptForLanguage ---

    @Test
    fun promptForLanguageMsWithDevPromptReturnsMalay() {
        assertEquals(
            PostProcessor.MALAY_PROMPT,
            PostProcessor.promptForLanguage(PostProcessor.DEV_PROMPT, "ms")
        )
    }

    @Test
    fun promptForLanguageMsWithSimplePromptReturnsMalay() {
        assertEquals(
            PostProcessor.MALAY_PROMPT,
            PostProcessor.promptForLanguage(PostProcessor.SIMPLE_PROMPT, "ms")
        )
    }

    @Test
    fun promptForLanguageMsWithMalayPromptReturnsMalay() {
        assertEquals(
            PostProcessor.MALAY_PROMPT,
            PostProcessor.promptForLanguage(PostProcessor.MALAY_PROMPT, "ms")
        )
    }

    @Test
    fun promptForLanguageEnWithDevPromptReturnsUnchanged() {
        assertEquals(
            PostProcessor.DEV_PROMPT,
            PostProcessor.promptForLanguage(PostProcessor.DEV_PROMPT, "en")
        )
    }

    @Test
    fun promptForLanguageMsWithCustomPromptReturnsUnchanged() {
        val custom = "My custom prompt that is not a built-in preset"
        assertEquals(
            custom,
            PostProcessor.promptForLanguage(custom, "ms")
        )
    }
}
