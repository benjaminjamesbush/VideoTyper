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
                is ThumbnailChoice.Poster -> poster(context, choice.artUrl) ?: frame(context, uri)
                ThumbnailChoice.Frame -> frame(context, uri)
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
        return artUrl?.let { poster(context, it) } ?: frame(context, uri)
    }

    private fun frame(context: Context, uriString: String): Bitmap? {
        val cacheFile = cacheFile(context, "frames", uriString)
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return it }
        }
        val bmp = extractFrame(context, uriString) ?: return null
        try {
            cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
        }
        return bmp
    }

    private fun extractFrame(context: Context, uriString: String): Bitmap? {
        val scheme = Uri.parse(uriString).scheme?.lowercase()
        val mmr = MediaMetadataRetriever()
        var smbSource: SmbMediaDataSource? = null
        return try {
            when (scheme) {
                "smb" -> {
                    smbSource = SmbMediaDataSource(SmbSupport.smbFile(uriString))
                    mmr.setDataSource(smbSource)
                }
                "http", "https" -> mmr.setDataSource(uriString, HashMap())
                else -> mmr.setDataSource(context, Uri.parse(uriString))
            }
            val frame = mmr.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
            frame?.let { scaleDown(it, 320) }
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
