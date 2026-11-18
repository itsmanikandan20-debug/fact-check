package com.mani.factcheck

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One wrong fact and its fix. */
data class Fix(
    val wrong: String,
    val correct: String,
    val reason: String = "",
    val source: String = ""
)

object FactEngine {

    private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    // "today is monday", "tomorrow is a friday", "yesterday was sunday", "today's tuesday"
    private val DAY_REGEX = Regex(
        "\\b(today|tomorrow|yesterday)(?:\\s+(?:is|was|will\\s+be)|'s|’s)\\s+(?:a\\s+)?(" +
            DAYS.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** Checks day names against the phone's own date. No internet needed. */
    fun localFixes(text: String, today: LocalDate = LocalDate.now()): List<Fix> {
        val fixes = mutableListOf<Fix>()
        for (m in DAY_REGEX.findAll(text)) {
            val word = m.groupValues[1].lowercase()
            val written = m.groupValues[2]
            val date = when (word) {
                "tomorrow" -> today.plusDays(1)
                "yesterday" -> today.minusDays(1)
                else -> today
            }
            val realDay = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            if (!written.equals(realDay, ignoreCase = true)) {
                fixes += Fix(
                    wrong = written,
                    correct = matchCase(written, realDay),
                    reason = "${word.replaceFirstChar { it.uppercase() }} is $realDay, ${date.dayOfMonth} ${
                        date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    }",
                    source = "Phone calendar"
                )
            }
        }
        return fixes
    }

    /** Asks Google Gemini (with Google Search) to find wrong facts. */
    fun aiFixes(text: String, apiKey: String, model: String, today: LocalDate = LocalDate.now()): List<Fix> {
        val prompt = """
            Today's date is $today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}).
            You are a strict fact checker. Find FACTUAL mistakes in the message below:
            wrong years, dates, names, numbers, places, titles, people's roles.
            Ignore greetings, opinions, plans, personal statements, spelling and grammar.
            Only report a mistake if you are sure. Use Google Search to confirm.
            "wrong" must be copied EXACTLY from the message and be as short as possible
            (only the wrong word or number, not the whole sentence).
            "correct" is the text that should replace it.
            Reply with ONLY this JSON and nothing else:
            {"fixes":[{"wrong":"...","correct":"...","reason":"very short reason","source":"source name"}]}
            If nothing is wrong reply {"fixes":[]}

            Message:
            <<<$text>>>
        """.trimIndent()

        return try {
            parseFixes(callGemini(prompt, apiKey, model, useSearch = true))
        } catch (e: GeminiException) {
            // Some models or keys don't allow the search tool. Try once without it.
            if (e.code == 400) parseFixes(callGemini(prompt, apiKey, model, useSearch = false)) else throw e
        }
    }

    class GeminiException(val code: Int, message: String) : Exception(message)

    private fun callGemini(prompt: String, apiKey: String, model: String, useSearch: Boolean): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            if (useSearch) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", 0))
        }

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(response).getJSONObject("error").getString("message")
                } catch (ignored: Exception) {
                    "HTTP $code"
                }
                throw GeminiException(code, msg)
            }
            val parts = JSONObject(response)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFixes(answer: String): List<Fix> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val arr = try {
            JSONObject(answer.substring(start, end + 1)).optJSONArray("fixes")
        } catch (ignored: Exception) {
            null
        } ?: return emptyList()
        val list = mutableListOf<Fix>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val wrong = o.optString("wrong").trim()
            val correct = o.optString("correct").trim()
            if (wrong.isNotEmpty() && correct.isNotEmpty() && wrong != correct) {
                list += Fix(wrong, correct, o.optString("reason"), o.optString("source"))
            }
        }
        return list
    }

    /**
     * Replaces only the wrong words. Returns the new text and the fixes
     * that were really found in the text.
     */
    fun apply(text: String, fixes: List<Fix>): Pair<String, List<Fix>> {
        var result = text
        val applied = mutableListOf<Fix>()
        for (fix in fixes) {
            var idx = result.indexOf(fix.wrong)
            if (idx < 0) idx = result.indexOf(fix.wrong, ignoreCase = true)
            if (idx < 0) continue
            result = result.substring(0, idx) + fix.correct + result.substring(idx + fix.wrong.length)
            applied += fix
        }
        return result to applied
    }

    private fun matchCase(original: String, replacement: String): String = when {
        original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
        original.first().isUpperCase() -> replacement.lowercase().replaceFirstChar { it.uppercase() }
        else -> replacement.lowercase()
    }
}
