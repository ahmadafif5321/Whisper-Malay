package com.kafkasl.phonewhisper

/** Pure decision for how a captured utterance should be transcribed. */
object TranscriptionRouter {
    enum class Route { LOCAL, API, ERROR_NO_API }

    fun decide(useLocal: Boolean, hasLocalTranscriber: Boolean, apiKeyBlank: Boolean): Route = when {
        useLocal && hasLocalTranscriber -> Route.LOCAL
        !apiKeyBlank -> Route.API
        else -> Route.ERROR_NO_API
    }
}
