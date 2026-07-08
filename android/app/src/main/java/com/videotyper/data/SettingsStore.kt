package com.videotyper.data

import android.content.Context
import com.videotyper.BuildConfig

/** App settings persisted in SharedPreferences. Currently just the optional TMDB API key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

    /** The user's own TMDB v3 API key, or null if they haven't entered one. */
    fun tmdbKey(): String? = prefs.getString(KEY_TMDB, null)?.takeIf { it.isNotBlank() }

    fun setTmdbKey(key: String) {
        prefs.edit().putString(KEY_TMDB, key.trim()).apply()
    }

    /** Key baked into the build, or null if this build has none. */
    fun bundledTmdbKey(): String? = BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() }

    /** Key actually used for lookups: the user's own if set, else the bundled default. */
    fun effectiveTmdbKey(): String? = tmdbKey() ?: bundledTmdbKey()

    companion object {
        private const val KEY_TMDB = "tmdb_api_key"
    }
}
