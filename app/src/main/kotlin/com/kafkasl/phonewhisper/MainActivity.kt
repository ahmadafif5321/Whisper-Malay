package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var malayReadinessRow: LinearLayout
    private lateinit var malayReadinessSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout
    private lateinit var customModelContainer: LinearLayout
    private lateinit var importRowSub: TextView

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()
    private val languageRows = mutableMapOf<String, LanguageRowViews>()

    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    private data class LanguageRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))
        
        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Language Section ---
        root.addView(sectionHeader("Language"))
        for (lang in APP_LANGUAGES) root.addView(buildLanguageRow(lang))
        malayReadinessRow = settingsRow("Malay readiness", "Checking selected model...")
        malayReadinessSub = malayReadinessRow.findViewWithTag("subtitle")
        root.addView(malayReadinessRow)

        // --- Engine Section ---
        
        val isCloud = !prefs().getBoolean("use_local", true)
        
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires Gemini API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        root.addView(cloudRow)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))

        customModelContainer = vertical(0)
        modelContainer.addView(customModelContainer)

        val importRow = settingsRow("Import model", IMPORT_HINT) {
            importPicker.launch(arrayOf("*/*"))
        }
        importRowSub = importRow.findViewWithTag("subtitle")
        modelContainer.addView(importRow)
        modelContainer.addView(settingsRow("Download model URL", URL_IMPORT_HINT) {
            promptModelUrl()
        })
        root.addView(modelContainer)

        // --- Post-Processing Section ---
        root.addView(sectionHeader("Post-Processing"))
        
        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Cleanup transcript", "Uses Gemini API to fix grammar and punctuation", postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        root.addView(postProcessRow)

        promptContainer = vertical(0)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- Settings Section ---
        root.addView(sectionHeader("Settings"))
        
        val keyRow = settingsRow("Gemini API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        
        refresh()
        maybePromptRecommendedMalayModel()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn
        )
        refreshCard(model)
        
        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        if (model.archive == "sherpa-onnx-whisper-medium") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Large model")
                .setMessage("Malay Whisper Medium is about ${model.sizeMb} MB and needs more RAM. Use it for best quality; use Malay Whisper Small for a safer default.")
                .setPositiveButton("Download") { _, _ -> startModelDownload(model) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        startModelDownload(model)
    }

    private fun startModelDownload(model: Model) {
        val views = modelRows[model.archive] ?: return
        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready!")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                    is DownloadState.Imported -> {} // only emitted by importModel
                }
            }
        }
    }

    private fun maybePromptRecommendedMalayModel() {
        if (prefs().getBoolean("recommended_model_prompted", false)) return
        if (LocalTranscriber.availableModels(this).isNotEmpty()) return
        val model = MODEL_CATALOG.first { it.archive == "sherpa-onnx-whisper-small" }
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            prefs().edit().putBoolean("recommended_model_prompted", true).apply()
            android.app.AlertDialog.Builder(this)
                .setTitle("Download Malay model?")
                .setMessage("${model.name} is the recommended local model for Bahasa Melayu Malaysia.")
                .setPositiveButton("Download ${model.sizeMb} MB") { _, _ -> onModelAction(model) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).commit()
        WhisperAccessibilityService.instance?.reloadModel()
        val lang = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        if (lang != DEFAULT_LANGUAGE && !archiveSupportsLanguage(archive, lang)) {
            val modelName = MODEL_CATALOG.firstOrNull { it.archive == archive }?.name ?: archive
            val langLabel = APP_LANGUAGES.firstOrNull { it.code == lang }?.label ?: lang
            toast("$modelName: English only — $langLabel dictation won't work with this model")
        }
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB · ${languageTag(model)}"
        }
    }

    private fun languageTag(model: Model) =
        model.languages.joinToString(" + ") { it.uppercase() }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- Language Rows ---

    private fun buildLanguageRow(lang: AppLanguage): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val row = settingsRow(lang.label, lang.hint, radio) { selectLanguage(lang.code) }
        languageRows[lang.code] = LanguageRowViews(radio, row.findViewWithTag("subtitle"))
        return row
    }

    private fun selectLanguage(code: String) {
        prefs().edit().putString("language", code).commit()
        val activeModel = prefs().getString("model_name", "") ?: ""
        if (code != DEFAULT_LANGUAGE && activeModel.isNotBlank() && !archiveSupportsLanguage(activeModel, code)) {
            val langLabel = APP_LANGUAGES.firstOrNull { it.code == code }?.label ?: code
            val installed = LocalTranscriber.availableModels(this)
            val best = bestModelForLanguage(installed, code)
            if (best != null) {
                val bestName = MODEL_CATALOG.firstOrNull { it.archive == best }?.name ?: best
                selectModel(best)
                toast("Switched to $bestName for $langLabel")
            } else {
                val smallModel = MODEL_CATALOG.first { it.archive == "sherpa-onnx-whisper-small" }
                val activeModelName = MODEL_CATALOG.firstOrNull { it.archive == activeModel }?.name ?: activeModel
                if (!isFinishing && !isDestroyed) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("$langLabel needs a multilingual model")
                        .setMessage("The active model ($activeModelName) is English-only and cannot transcribe $langLabel. Download a multilingual model to use $langLabel dictation.")
                        .setPositiveButton("Download ${smallModel.name} (${smallModel.sizeMb} MB)") { _, _ ->
                            onModelAction(smallModel)
                        }
                        .setNegativeButton("Not now") { _, _ ->
                            WhisperAccessibilityService.instance?.reloadModel()
                        }
                        .show()
                }
            }
        } else {
            WhisperAccessibilityService.instance?.reloadModel()
        }
        refresh()
    }

    private fun refreshLanguageRows() {
        val current = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        val useLocal = prefs().getBoolean("use_local", true)
        val activeModel = prefs().getString("model_name", "") ?: ""
        for (lang in APP_LANGUAGES) {
            val views = languageRows[lang.code] ?: continue
            views.radio.isChecked = current == lang.code
            val unsupported = useLocal && activeModel.isNotBlank() &&
                !archiveSupportsLanguage(activeModel, lang.code)
            views.subtitle.text =
                if (unsupported) "Selected model is English-only — use a Multilingual model"
                else lang.hint
        }
    }

    // --- Custom (imported) Model Rows ---

    private fun rebuildCustomModelRows() {
        customModelContainer.removeAllViews()
        val catalogArchives = MODEL_CATALOG.map { it.archive }.toSet()
        LocalTranscriber.availableModels(this)
            .filter { it !in catalogArchives }
            .sorted()
            .forEach { customModelContainer.addView(buildCustomModelRow(it)) }
    }

    private fun buildCustomModelRow(name: String): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            isChecked = prefs().getString("model_name", "") == name
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val delBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "✕"
            textSize = 16f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { confirmDeleteCustomModel(name) }
        }
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(delBtn)
            addView(radio)
        }
        return settingsRow(name, "Imported model", rightContainer) { selectModel(name) }
    }

    private fun confirmDeleteCustomModel(name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage(name)
            .setPositiveButton("Delete") { _, _ ->
                File(filesDir, "models/$name").deleteRecursively()
                if (prefs().getString("model_name", "") == name) {
                    prefs().edit().remove("model_name").apply()
                }
                WhisperAccessibilityService.instance?.reloadModel()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Model Import ---

    private fun importModel(uri: android.net.Uri) {
        val name = queryDisplayName(uri) ?: "model.tar.bz2"
        importRowSub.text = "Copying $name..."
        ModelDownloader.importModel(this, uri, name) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Extracting -> importRowSub.text = "Extracting $name..."
                    is DownloadState.Imported -> {
                        importRowSub.text = IMPORT_HINT
                        selectModel(state.modelName)
                        toast("${state.modelName} imported!")
                    }
                    is DownloadState.Error -> importRowSub.text = "Error: ${state.message}"
                    else -> {}
                }
            }
        }
    }

    private fun promptModelUrl() {
        val input = EditText(this).apply {
            hint = "https://huggingface.co/.../resolve/main/model.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Download model URL")
            .setMessage("Use a direct sherpa-onnx archive or RTranslator-style Whisper ONNX zip URL.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Download") { _, _ ->
                downloadModelUrl(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadModelUrl(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        importRowSub.text = "Downloading URL..."
        ModelDownloader.downloadFromUrl(this, cleanUrl) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading ->
                        importRowSub.text = "Downloading URL: ${(state.progress * 100).toInt()}%"
                    is DownloadState.Extracting -> importRowSub.text = "Extracting URL model..."
                    is DownloadState.Imported -> {
                        importRowSub.text = IMPORT_HINT
                        selectModel(state.modelName)
                        toast("${state.modelName} downloaded!")
                    }
                    is DownloadState.Error -> importRowSub.text = "URL error: ${state.message}"
                    is DownloadState.Done -> {}
                }
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows(); refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT &&
                current != PostProcessor.SIMPLE_PROMPT &&
                current != PostProcessor.MALAY_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasKey = !prefs().getString("api_key", "").isNullOrBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        val apiKey = prefs().getString("api_key", "") ?: ""
        keyRowSub.text = if (apiKey.isBlank()) "Tap to set"
                         else if (apiKey.length > 7) "•••${apiKey.takeLast(4)}"
                         else "•••***"

        val prompt = currentPrompt()
        promptRowSub.text = prompt

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
            val installed = LocalTranscriber.availableModels(this)
            val fallback = bestModelForLanguage(installed, language)
                ?: MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }?.archive
                ?: installed.firstOrNull()
            fallback?.let { selectModel(it) }
        }
        val activeModel = prefs().getString("model_name", "") ?: ""
        val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        val localModelReady = activeModel.isNotBlank() &&
            File(filesDir, "models/$activeModel").exists() &&
            archiveSupportsLanguage(activeModel, language)

        // Ready logic
        val localReady = useLocal && hasModel && localModelReady
        val cloudReady = !useLocal && hasKey
        val postReady = !usePostProcessing || hasKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = when {
            ready -> "Ready — tap the overlay dot to dictate"
            useLocal && hasModel && !localModelReady -> "Selected model does not support ${language.uppercase()}"
            else -> "Setup required"
        }
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        refreshMalayReadiness(useLocal, activeModel)
        
        refreshAllCards()
        rebuildCustomModelRows()
        refreshLanguageRows()
        refreshPromptRows()
    }

    private fun refreshMalayReadiness(useLocal: Boolean, activeModel: String) {
        if (!useLocal) {
            malayReadinessSub.text = "Cloud mode sends Bahasa Melayu transcription instructions"
            malayReadinessSub.setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            return
        }

        if (activeModel.isBlank() || !File(filesDir, "models/$activeModel").exists()) {
            malayReadinessSub.text = "Download Malay Whisper Small to use Bahasa Melayu locally"
            malayReadinessSub.setTextColor(attrColor(android.R.attr.textColorSecondary))
            return
        }

        val modelName = MODEL_CATALOG.firstOrNull { it.archive == activeModel }?.name ?: activeModel
        val supportsMalay = archiveSupportsLanguage(activeModel, "ms")
        malayReadinessSub.text = if (supportsMalay) {
            "$modelName supports Bahasa Melayu Malaysia"
        } else {
            "$modelName is English-only; choose Malay Whisper Small or Medium"
        }
        malayReadinessSub.setTextColor(
            if (supportsMalay) attrColor(com.google.android.material.R.attr.colorPrimary)
            else Color.rgb(176, 0, 32)
        )
    }

    private fun promptApiKey() {
        val input = EditText(this).apply {
            hint = "AIza..."
            setText(prefs().getString("api_key", ""))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "malay",
            title = "Malay cleanup",
            subtitle = "Untuk Bahasa Melayu & Manglish",
            prompt = PostProcessor.MALAY_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val IMPORT_HINT =
            "Add a sherpa-onnx archive or Whisper ONNX zip (.tar.bz2, .tar.gz, .zip)"
        private const val URL_IMPORT_HINT =
            "Pull a direct archive URL, including Hugging Face /resolve/ links"
    }
}
