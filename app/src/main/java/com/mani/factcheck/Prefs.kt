package com.mani.factcheck

import android.content.Context

object Prefs {
    private const val FILE = "factcheck"
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    fun key(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("gemini_key", "") ?: ""

    fun model(ctx: Context): String =
        (ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("model", "") ?: "")
            .ifBlank { DEFAULT_MODEL }

    fun save(ctx: Context, key: String, model: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("gemini_key", key.trim())
            .putString("model", model.trim())
            .apply()
    }
}
