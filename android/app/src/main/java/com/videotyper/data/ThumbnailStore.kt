package com.videotyper.data

import android.content.Context
import org.json.JSONObject

/**
 * Per-video thumbnail choice. Default is [Auto] (search online by filename, fall back to a video
 * frame). The user can override to a specific online [Poster] or force the video [Frame] via the
 * thumbnail manager.
 */
sealed interface ThumbnailChoice {
    data object Auto : ThumbnailChoice
    /**
     * A decoded video frame at [timeMs] (the user picks which one; the first frame is often bad),
     * cropped to the poster's 2:3 shape at horizontal bias [cropBias] (0 = left … 0.5 = center …
     * 1 = right), so the user can also choose which part of a wide frame to keep.
     */
    data class Frame(val timeMs: Long = DEFAULT_FRAME_MS, val cropBias: Float = 0.5f) : ThumbnailChoice
    data class Poster(val artUrl: String) : ThumbnailChoice

    companion object {
        const val DEFAULT_FRAME_MS = 3_000L
    }
}

/** Persists per-video thumbnail overrides (video URI -> choice) in SharedPreferences. */
class ThumbnailStore(context: Context) {
    private val prefs = context.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

    fun choiceFor(uri: String): ThumbnailChoice {
        val raw = prefs.getString(KEY, null) ?: return ThumbnailChoice.Auto
        return try {
            val o = JSONObject(raw).optJSONObject(uri) ?: return ThumbnailChoice.Auto
            when (o.getString("type")) {
                "frame" -> ThumbnailChoice.Frame(
                    o.optLong("timeMs", ThumbnailChoice.DEFAULT_FRAME_MS),
                    o.optDouble("cropBias", 0.5).toFloat()
                )
                "poster" -> ThumbnailChoice.Poster(o.getString("artUrl"))
                else -> ThumbnailChoice.Auto
            }
        } catch (e: Exception) {
            ThumbnailChoice.Auto
        }
    }

    fun setChoice(uri: String, choice: ThumbnailChoice) {
        val root = readObj(KEY)
        when (choice) {
            ThumbnailChoice.Auto -> root.remove(uri)
            is ThumbnailChoice.Frame ->
                root.put(uri, JSONObject().put("type", "frame").put("timeMs", choice.timeMs)
                    .put("cropBias", choice.cropBias.toDouble()))
            is ThumbnailChoice.Poster ->
                root.put(uri, JSONObject().put("type", "poster").put("artUrl", choice.artUrl))
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    /**
     * Auto-mode's remembered online lookup so we don't re-search every time the menu opens:
     * null = never searched, "" = searched and found nothing (use a frame), else the poster URL.
     */
    fun autoArt(uri: String): String? {
        val o = readObj(AUTO_KEY)
        return if (o.has(uri)) o.getString(uri) else null
    }

    fun setAutoArt(uri: String, artUrl: String) {
        val o = readObj(AUTO_KEY)
        o.put(uri, artUrl)
        prefs.edit().putString(AUTO_KEY, o.toString()).apply()
    }

    fun clearAutoArt(uri: String) {
        val o = readObj(AUTO_KEY)
        o.remove(uri)
        prefs.edit().putString(AUTO_KEY, o.toString()).apply()
    }

    /** Forget all remembered auto lookups so every auto-mode video re-searches (e.g. after the
     *  TMDB key changes and previously-unmatched videos might now resolve). */
    fun clearAllAutoArt() {
        prefs.edit().remove(AUTO_KEY).apply()
    }

    private fun readObj(key: String): JSONObject = try {
        JSONObject(prefs.getString(key, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    companion object {
        private const val KEY = "thumbnail_choices"
        private const val AUTO_KEY = "thumbnail_auto_art"
    }
}
