package com.videotyper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentVideo(val uri: String, val name: String)

/**
 * Most-recently-opened videos, persisted as a JSON array in SharedPreferences (front = newest).
 * Holds just the URI and a display name; thumbnails are generated on demand by ThumbnailLoader.
 */
class RecentsStore(context: Context) {
    private val prefs = context.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

    fun recents(): List<RecentVideo> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentVideo(o.getString("uri"), o.optString("name", o.getString("uri")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add or move a video to the front of the list, capped at [MAX] entries. */
    fun add(uri: String, name: String) {
        val updated = (listOf(RecentVideo(uri, name)) + recents().filterNot { it.uri == uri }).take(MAX)
        val arr = JSONArray()
        updated.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "recents"
        private const val MAX = 12
    }
}
