package com.kafkasl.phonewhisper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppDiagnostics {
    private const val PREFS = "phonewhisper"
    private const val KEY_HISTORY = "transcript_history"
    private const val KEY_LOGS = "debug_log"
    private const val MAX_HISTORY = 8
    private const val MAX_LOGS = 40
    private const val SEP = "\u001e"

    fun addTranscript(ctx: Context, text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        val items = listOf("${stamp()}  $clean") + recentTranscripts(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, items.distinct().take(MAX_HISTORY).joinToString(SEP))
            .apply()
    }

    fun recentTranscripts(ctx: Context): List<String> =
        readList(ctx, KEY_HISTORY)

    fun addLog(ctx: Context, message: String) {
        val clean = message.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        val items = listOf("${stamp()}  $clean") + recentLogs(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOGS, items.take(MAX_LOGS).joinToString(SEP))
            .apply()
    }

    fun recentLogs(ctx: Context): List<String> =
        readList(ctx, KEY_LOGS)

    fun modelStatus(ctx: Context, activeModel: String, language: String, useLocal: Boolean): String {
        if (!useLocal) return "Cloud Gemini mode active"
        if (activeModel.isBlank()) return "No local model selected"
        val installed = File(ctx.filesDir, "models/$activeModel").exists()
        val supports = archiveSupportsLanguage(activeModel, language)
        val modelName = MODEL_CATALOG.firstOrNull { it.archive == activeModel }?.name ?: activeModel
        return when {
            !installed -> "$modelName is selected but not installed"
            supports -> "$modelName installed; supports ${language.uppercase()}"
            else -> "$modelName installed; does not support ${language.uppercase()}"
        }
    }

    fun benchmarkHint(activeModel: String): String {
        val model = MODEL_CATALOG.firstOrNull { it.archive == activeModel }
        return when {
            model == null -> "Download Malay Whisper Small for a balanced local benchmark"
            activeModel.contains("medium") -> "Best Malay quality; expect slower transcription and higher RAM use"
            activeModel.contains("small") -> "Recommended Malay balance for speed, size, and quality"
            activeModel.contains("tiny") -> "Fastest Malay-capable option, lower quality"
            activeModel.contains(".en") -> "English-only model; not recommended for Bahasa Melayu"
            else -> "${model.sizeMb} MB model; use Malay readiness before dictation"
        }
    }

    private fun readList(ctx: Context, key: String): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
            .split(SEP)
            .filter { it.isNotBlank() }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
