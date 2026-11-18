package com.mani.factcheck

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F1B2E"))
            setPadding(0, dp(24), 0, dp(6))
        }
        fun body(t: String) = TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(Color.parseColor("#4A4560"))
            setPadding(0, 0, 0, dp(8))
        }

        col.addView(TextView(this).apply {
            text = "Fact Check"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#5B4BFF"))
        })
        col.addView(body("Fix wrong facts while you type. Works with Gboard. Type anywhere, tap the ✨ bubble, and only the wrong word changes."))

        status = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(status)

        // Step 1
        col.addView(title("1. Turn on the ✨ bubble"))
        col.addView(body("Open Accessibility → Installed apps → Fact Check → turn ON."))
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(body("Greyed out or \"Restricted setting\"? Tap below → ⋮ (top right) → \"Allow restricted settings\". Then try again."))
        col.addView(Button(this).apply {
            text = "Open App info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        // Step 2
        col.addView(title("2. Gemini key (for movies, cricket, other facts)"))
        col.addView(body("Day checks like \"Today is Monday\" work without a key. For other facts, get a free key at aistudio.google.com and paste it here."))
        val keyBox = EditText(this).apply {
            hint = "Paste Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.key(this@MainActivity))
        }
        col.addView(keyBox)
        val modelBox = EditText(this).apply {
            hint = "Model (default ${Prefs.DEFAULT_MODEL})"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.model(this@MainActivity))
        }
        col.addView(modelBox)
        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                Prefs.save(this@MainActivity, keyBox.text.toString(), modelBox.text.toString())
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        })

        // Step 3
        col.addView(title("3. Try it"))
        col.addView(body("Type below (or in WhatsApp with Gboard), then tap ✨."))
        col.addView(EditText(this).apply {
            hint = "Today is Monday. Fight Club came in 2001."
            minLines = 3
        })

        col.addView(title("Privacy"))
        col.addView(body("Text is read only when you tap ✨. Day checks stay on your phone. Other facts are sent to Google Gemini only if you add a key. Password boxes are never read."))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains(packageName)
        val hasKey = Prefs.key(this).isNotBlank()
        status.text = buildString {
            append(if (enabled) "✅ Bubble is ON" else "⭕ Bubble is OFF: do step 1")
            append("\n")
            append(if (hasKey) "✅ Gemini key saved" else "⭕ No Gemini key: only day checks work")
        }
        status.setTextColor(Color.parseColor(if (enabled) "#1B7F3B" else "#B3261E"))
    }
}
