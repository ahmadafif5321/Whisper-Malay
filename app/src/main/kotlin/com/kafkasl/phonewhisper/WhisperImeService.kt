package com.kafkasl.phonewhisper

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice-only keyboard: a mic button plus backspace / space / enter and a
 * switch-keyboard key. Types transcribed text via commitText — no accessibility,
 * no overlay, so banking apps do not block it.
 */
class WhisperImeService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: DictationEngine
    private var status: TextView? = null
    private var mic: Button? = null

    private val listener = object : DictationEngine.Listener {
        override fun onState(state: DictationEngine.State) {
            handler.post {
                when (state) {
                    DictationEngine.State.RECORDING -> setStatus(getString(R.string.ime_mic_recording))
                    DictationEngine.State.TRANSCRIBING -> setStatus(getString(R.string.ime_mic_busy))
                    DictationEngine.State.IDLE -> setStatus(getString(R.string.ime_mic_idle))
                }
            }
        }
        override fun onResult(text: String) { handler.post { currentInputConnection?.commitText(text, 1) } }
        override fun onInfo(message: String) { handler.post { setStatus(message) } }
        override fun onError(message: String) { handler.post { setStatus(message) } }
    }

    override fun onCreate() {
        super.onCreate()
        engine = DictationEngine(this)
        Thread { runCatching { engine.loadModel() } }.start()
    }

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
        }
        status = TextView(this).apply {
            text = getString(R.string.ime_mic_idle)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(8))
        }
        mic = Button(this).apply {
            text = "🎤"
            textSize = 28f
            setOnClickListener { onMicTap() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(Button(this@WhisperImeService).apply { text = "⌫"; setOnClickListener { backspace() } }, lp)
            addView(Button(this@WhisperImeService).apply { text = "espasi"; setOnClickListener { commit(" ") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(Button(this@WhisperImeService).apply { text = "⏎"; setOnClickListener { commit("\n") } }, lp)
            addView(Button(this@WhisperImeService).apply { text = "🌐"; setOnClickListener { switchKeyboard() } }, lp)
        }
        root.addView(status)
        root.addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64)))
        root.addView(row)
        return root
    }

    private fun onMicTap() {
        when (engine.state) {
            DictationEngine.State.IDLE -> {
                if (!engine.startRecording(listener)) {
                    setStatus(getString(R.string.ime_grant_mic))
                    startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            DictationEngine.State.RECORDING -> engine.stopAndTranscribe(listener)
            DictationEngine.State.TRANSCRIBING -> {}
        }
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }
    private fun backspace() { currentInputConnection?.deleteSurroundingText(1, 0) }
    private fun switchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
    private fun setStatus(text: String) { status?.text = text }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (engine.state != DictationEngine.State.IDLE) engine.cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() { engine.release(); super.onDestroy() }
}
