package com.kafkasl.phonewhisper

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice-only keyboard: a prominent mic button plus backspace / space / return and a
 * switch-keyboard key. Types transcribed text via commitText — no accessibility,
 * no overlay, so banking apps do not block it.
 */
class WhisperImeService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: DictationEngine
    private var status: TextView? = null
    private var micCircle: FrameLayout? = null
    private var micIcon: ImageView? = null

    // --- palette (day / night aware) ---
    private var night = false
    private val panelBg get() = if (night) 0xFF16181C.toInt() else 0xFFE6E9EF.toInt()
    private val keyBg get() = if (night) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt()
    private val keyPressed get() = if (night) 0xFF3A3E45.toInt() else 0xFFD7DCE4.toInt()
    private val keyText get() = if (night) 0xFFE8EAED.toInt() else 0xFF1C1F24.toInt()
    private val hintText get() = if (night) 0xFF9AA0A6.toInt() else 0xFF5A6472.toInt()
    private val micIdle = 0xFF2563EB.toInt()
    private val micRec = 0xFFEF4444.toInt()
    private val micBusy = 0xFF8A9099.toInt()

    private val listener = object : DictationEngine.Listener {
        override fun onState(state: DictationEngine.State) {
            handler.post {
                when (state) {
                    DictationEngine.State.RECORDING -> render(getString(R.string.ime_mic_recording), micRec)
                    DictationEngine.State.TRANSCRIBING -> render(getString(R.string.ime_mic_busy), micBusy)
                    DictationEngine.State.IDLE -> render(getString(R.string.ime_mic_idle), micIdle)
                }
            }
        }
        override fun onResult(text: String) { handler.post { currentInputConnection?.commitText(text, 1) } }
        override fun onInfo(message: String) { handler.post { status?.text = message } }
        override fun onError(message: String) { handler.post { status?.text = message } }
    }

    override fun onCreate() {
        super.onCreate()
        engine = DictationEngine(this)
        Thread { runCatching { engine.loadModel() } }.start()
    }

    override fun onCreateInputView(): View {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }

        status = TextView(this).apply {
            text = getString(R.string.ime_mic_idle)
            setTextColor(hintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

        // Big circular mic button (uses the app's existing ic_mic vector)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(16); setPadding(p, p, p, p)
        }
        micIcon = icon
        val circle = FrameLayout(this).apply {
            background = oval(micIdle)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            setOnClickListener { onMicTap() }
        }
        micCircle = circle
        val micWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(circle, LinearLayout.LayoutParams(dp(84), dp(84)))
        }

        // Key row: backspace · space (wide) · return · switch-keyboard
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(key("⌫", 1f) { backspace() })
            addView(key("space", 3f) { commit(" ") })
            addView(key("return", 1f) { commit("\n") })
            addView(key("🌐", 1f) { switchKeyboard() })
        }

        root.addView(status)
        root.addView(micWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    /** Build one keyboard key with rounded background + pressed feedback. */
    private fun key(label: String, weight: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            background = keyBackground()
            val lp = LinearLayout.LayoutParams(0, dp(50), weight).apply {
                val m = dp(3); setMargins(m, 0, m, 0)
            }
            layoutParams = lp
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun keyBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(keyPressed))
        addState(intArrayOf(), roundRect(keyBg))
    }

    private fun roundRect(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(color)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    /** Update status text + mic colour for the current state (main thread). */
    private fun render(statusText: String, micColor: Int) {
        status?.text = statusText
        micCircle?.background = oval(micColor)
    }

    private fun onMicTap() {
        when (engine.state) {
            DictationEngine.State.IDLE -> {
                if (!engine.startRecording(listener)) {
                    status?.text = getString(R.string.ime_grant_mic)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onFinishInputView(finishingInput: Boolean) {
        if (engine.state != DictationEngine.State.IDLE) engine.cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() { engine.release(); super.onDestroy() }
}
