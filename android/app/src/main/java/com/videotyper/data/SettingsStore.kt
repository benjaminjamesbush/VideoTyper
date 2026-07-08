package com.videotyper.data

import android.content.Context

/** App settings persisted in SharedPreferences. Currently just the optional TMDB API key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

    /** TMDB v3 API key, or null/blank if the user hasn't set one. */
    fun tmdbKey(): String? = prefs.getString(KEY_TMDB, null)?.takeIf { it.isNotBlank() }

    fun setTmdbKey(key: String) {
        prefs.edit().putString(KEY_TMDB, key.trim()).apply()
    }

    companion object {
        private const val KEY_TMDB = "tmdb_api_key"
    }
}
