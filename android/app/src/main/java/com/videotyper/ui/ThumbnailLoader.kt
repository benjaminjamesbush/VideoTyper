package com.videotyper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates and disk-caches a poster frame for a video URI. Works for local (content://, file://)
 * and http(s) sources via MediaMetadataRetriever; smb:// returns null (the recents ribbon shows a
 * placeholder for those, since decoding a frame over a network share is slow and fragile).
 */
object ThumbnailLoader {

    suspend fun load(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(context, uriString)
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return@withContext it }
        }
        val bmp = extract(context, uriString) ?: return@withContext null
        try {
            cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
            // A failed cache write just means we regenerate next time.
        }
        bmp
    }

    private fun extract(context: Context, uriString: String): Bitmap? {
        val scheme = Uri.parse(uriString).scheme?.lowercase()
        if (scheme == "smb") return null
        val mmr = MediaMetadataRetriever()
        return try {
            when (scheme) {
                "http", "https" -> mmr.setDataSource(uriString, HashMap())
                else -> mmr.setDataSource(context, Uri.parse(uriString))
            }
            val frame = mmr.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime // fall back to whatever it can grab
            frame?.let { scaleDown(it, 320) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleDown(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
    }

    private fun cacheFileFor(context: Context, uriString: String): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val hash = MessageDigest.getInstance("MD5")
            .digest(uriString.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }
}
