package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test
import com.kafkasl.phonewhisper.TranscriptionRouter.Route

class TranscriptionRouterTest {
    @Test fun `uses local when enabled and available`() {
        assertEquals(Route.LOCAL, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = true, apiKeyBlank = true))
    }
    @Test fun `falls back to api when local unavailable and key present`() {
        assertEquals(Route.API, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = false, apiKeyBlank = false))
    }
    @Test fun `uses api when local disabled and key present`() {
        assertEquals(Route.API, TranscriptionRouter.decide(useLocal = false, hasLocalTranscriber = true, apiKeyBlank = false))
    }
    @Test fun `errors when no local and no api key`() {
        assertEquals(Route.ERROR_NO_API, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = false, apiKeyBlank = true))
    }
}
