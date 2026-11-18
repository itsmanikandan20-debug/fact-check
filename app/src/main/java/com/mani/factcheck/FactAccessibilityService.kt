package com.mani.factcheck

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Shows a floating ✨ bubble. When tapped, it reads the text box that has focus
 * (works with Gboard or any keyboard), finds wrong facts and replaces only the wrong words.
 */
class FactAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var card: View? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideCard: Runnable? = null
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        removeCard()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- Bubble ----------

    private fun addBubble() {
        val size = dp(52)
        val view = TextView(this).apply {
            text = "✨"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5B4BFF"))
            }
            elevation = dp(6).toFloat()
            contentDescription = "Fact Check: tap to fix wrong facts"
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = (resources.displayMetrics.heightPixels * 0.35).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
            }
            true
        }

        wm.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    // ---------- Main action ----------

    private fun onBubbleTap() {
        if (busy) return
        val node = findInputNode()
        if (node == null) {
            showCard("Tap a text box first, then tap ✨", null)
            return
        }
        val original = node.text?.toString() ?: ""
        if (original.isBlank() || node.isShowingHintText) {
            showCard("Type something first", null)
            return
        }

        busy = true
        bubble?.text = "…"
        val apiKey = Prefs.key(this)
        val model = Prefs.model(this)

        thread {
            val fixes = mutableListOf<Fix>()
            fixes += FactEngine.localFixes(original)
            var error: String? = null
            if (apiKey.isNotBlank()) {
                try {
                    for (f in FactEngine.aiFixes(original, apiKey, model)) {
                        if (fixes.none { it.wrong.equals(f.wrong, ignoreCase = true) }) fixes += f
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Couldn't reach Gemini"
                }
            }

            main.post {
                busy = false
                bubble?.text = "✨"

                // The user may have typed more while we were checking. Use the latest text.
                node.refresh()
                val current = node.text?.toString() ?: original
                val (newText, applied) = FactEngine.apply(current, fixes)

                when {
                    applied.isNotEmpty() -> {
                        if (setText(node, newText)) {
                            val summary = applied.joinToString("\n") { "${it.wrong} → ${it.correct}" }
                            val detail = applied.first().let { f ->
                                listOf(f.reason, f.source).filter { it.isNotBlank() }.joinToString(" · ")
                            }
                            showCard("✅ $summary", detail) { setText(node, current) }
                        } else {
                            showCard("Couldn't edit this text box", null)
                        }
                    }
                    error != null -> showCard("⚠️ Couldn't check: $error", null)
                    apiKey.isBlank() ->
                        showCard("✅ No date mistakes", "Add a Gemini key in the app to check other facts")
                    else -> showCard("✅ All facts look correct", null)
                }
            }
        }
    }

    private fun findInputNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        for (w in windows) {
            w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    // ---------- Result card ----------

    private fun showCard(message: String, detail: String?, undo: (() -> Unit)? = null) {
        removeCard()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1F1B2E"))
            }
            elevation = dp(8).toFloat()
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        if (!detail.isNullOrBlank()) {
            texts.addView(TextView(this).apply {
                text = detail
                setTextColor(Color.parseColor("#B8B3D1"))
                textSize = 12f
            })
        }
        box.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (undo != null) {
            box.addView(TextView(this).apply {
                text = "UNDO"
                setTextColor(Color.parseColor("#FFD54F"))
                textSize = 14f
                setPadding(dp(16), dp(8), dp(8), dp(8))
                setOnClickListener {
                    undo()
                    showCard("↩️ Undone", null)
                }
            })
        }

        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(32),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }

        wm.addView(box, params)
        card = box
        val r = Runnable { removeCard() }
        hideCard = r
        main.postDelayed(r, if (undo != null) 6000 else 3000)
    }

    private fun removeCard() {
        hideCard?.let { main.removeCallbacks(it) }
        hideCard = null
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }
}
