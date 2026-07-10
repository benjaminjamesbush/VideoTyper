package com.videotyper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.videotyper.data.Http
import com.videotyper.data.PosterSearch
import com.videotyper.data.SettingsStore
import com.videotyper.data.ThumbnailChoice
import com.videotyper.data.ThumbnailStore
import com.videotyper.data.TitleParser
import com.videotyper.player.SmbMediaDataSource
import com.videotyper.player.SmbSupport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a video's thumbnail, Plex/Emby-style: an official online poster matched from the
 * filename is preferred, with a decoded video frame as the fallback. The user can override the
 * choice per video (a specific poster, or force the frame) via the thumbnail manager. Everything
 * is disk-cached, so the recents ribbon is instant after the first resolve.
 */
object ThumbnailLoader {

    /** Resolve the thumbnail for [uri] (a recents entry), using [name] to search online. */
    suspend fun load(context: Context, uri: String, name: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val store = ThumbnailStore(context)
            when (val choice = store.choiceFor(uri)) {
                is ThumbnailChoice.Poster -> poster(context, choice.artUrl) ?: frame(context, uri, ThumbnailChoice.DEFAULT_FRAME_MS, 0.5f)
                is ThumbnailChoice.Frame -> frame(context, uri, choice.timeMs, choice.cropBias)
                ThumbnailChoice.Auto -> auto(context, uri, name, store)
            }
        }

    /** Download + cache + decode an online poster (used by the resolver and the manager grid). */
    suspend fun poster(context: Context, artUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheFile = cacheFile(context, "posters", artUrl)
        if (!cacheFile.exists()) {
            if (!Http.downloadTo(artUrl, cacheFile)) return@withContext null
        }
        BitmapFactory.decodeFile(cacheFile.absolutePath)
    }

    private suspend fun auto(context: Context, uri: String, name: String, store: ThumbnailStore): Bitmap? {
        val remembered = store.autoArt(uri)
        val artUrl = when {
            remembered == null -> {
                val parsed = TitleParser.parse(name)
                val tmdbKey = SettingsStore(context).effectiveTmdbKey()
                val candidate = PosterSearch
                    .search(parsed.title, preferTv = parsed.isTv, tmdbKey = tmdbKey)
                    .firstOrNull()
                store.setAutoArt(uri, candidate?.artUrl ?: "")
                candidate?.artUrl
            }
            remembered.isBlank() -> null
            else -> remembered
        }
        return artUrl?.let { poster(context, it) } ?: frame(context, uri, ThumbnailChoice.DEFAULT_FRAME_MS, 0.5f)
    }

    private fun frame(context: Context, uriString: String, timeMs: Long, cropBias: Float): Bitmap? {
        // Cache per (video, timestamp, crop) so each chosen frame+crop is remembered and a new pick re-decodes.
        val cacheFile = cacheFile(context, "frames", "$uriString@$timeMs@$cropBias")
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return it }
        }
        val full = extractFrame(context, uriString, timeMs, 640) ?: return null
        // Bake the 2:3 poster crop at the chosen horizontal bias so the ribbon shows it directly.
        val bmp = cropToPoster(full, cropBias)
        try {
            cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
        }
        return bmp
    }

    /** Crop [src] to a 2:3 portrait poster, panning along the longer axis by [bias] (0..1). */
    private fun cropToPoster(src: Bitmap, bias: Float): Bitmap {
        val targetAspect = 2f / 3f // width / height
        val srcAspect = src.width.toFloat() / src.height.coerceAtLeast(1)
        val b = bias.coerceIn(0f, 1f)
        return try {
            if (srcAspect > targetAspect) {
                // Wider than 2:3 (landscape frame): keep full height, pan horizontally.
                val cropW = (src.height * targetAspect).toInt().coerceIn(1, src.width)
                val x = (b * (src.width - cropW)).toInt().coerceIn(0, src.width - cropW)
                Bitmap.createBitmap(src, x, 0, cropW, src.height)
            } else {
                // Taller than 2:3: keep full width, pan vertically.
                val cropH = (src.width / targetAspect).toInt().coerceIn(1, src.height)
                val y = (b * (src.height - cropH)).toInt().coerceIn(0, src.height - cropH)
                Bitmap.createBitmap(src, 0, y, src.width, cropH)
            }
        } catch (e: Exception) {
            src
        }
    }

    /**
     * Decode a single frame at [timeMs] for the thumbnail manager's live scrub preview (not cached,
     * larger than the ribbon thumbnail). Returns null on failure.
     */
    suspend fun decodeFrameAt(context: Context, uriString: String, timeMs: Long, maxWidth: Int = 640): Bitmap? =
        withContext(Dispatchers.IO) { extractFrame(context, uriString, timeMs, maxWidth) }

    /** Total duration of [uriString] in ms, or 0 if unknown. */
    suspend fun durationMs(context: Context, uriString: String): Long = withContext(Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        var smbSource: SmbMediaDataSource? = null
        try {
            setSource(context, mmr, uriString) { smbSource = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) {}
            try { smbSource?.close() } catch (_: Exception) {}
        }
    }

    private fun extractFrame(context: Context, uriString: String, timeMs: Long, maxWidth: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        var smbSource: SmbMediaDataSource? = null
        return try {
            setSource(context, mmr, uriString) { smbSource = it }
            val frame = mmr.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
            frame?.let { scaleDown(it, maxWidth) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
            try {
                smbSource?.close()
            } catch (_: Exception) {
            }
        }
    }

    private inline fun setSource(
        context: Context,
        mmr: MediaMetadataRetriever,
        uriString: String,
        onSmb: (SmbMediaDataSource) -> Unit,
    ) {
        when (Uri.parse(uriString).scheme?.lowercase()) {
            "smb" -> SmbMediaDataSource(SmbSupport.smbFile(uriString)).also { onSmb(it); mmr.setDataSource(it) }
            "http", "https" -> mmr.setDataSource(uriString, HashMap())
            else -> mmr.setDataSource(context, Uri.parse(uriString))
        }
    }

    private fun scaleDown(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
    }

    private fun cacheFile(context: Context, subdir: String, key: String): File {
        val dir = File(context.cacheDir, subdir).apply { mkdirs() }
        val hash = MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }
}
