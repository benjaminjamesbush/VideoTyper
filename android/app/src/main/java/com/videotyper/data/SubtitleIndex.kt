package com.videotyper.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videotyper.game.WordSelector
import com.videotyper.player.SmbMediaDataSource
import com.videotyper.player.SmbSupport
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * One-time offline pull of the embedded subtitle track's cue timeline, so the star system can jump
 * to a random line that still has ≥3 typing opportunities after it (the live cue engine only ever
 * sees the current line). Returns the start times (ms) of cues that yield a typeable word — the same
 * eligibility the game uses — sorted ascending. Best-effort: returns empty on any failure (then the
 * feature just degrades, and we can fall back to sidecar .srt later).
 */
object SubtitleIndex {

    fun typeableCueStartsMs(context: Context, uriString: String): List<Long> {
        val ex = MediaExtractor()
        return try {
            setDataSource(context, ex, uriString)
            val track = (0 until ex.trackCount).firstOrNull { isTextSubtitle(ex.getTrackFormat(it)) }
                ?: return emptyList()
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(128 * 1024)
            val starts = ArrayList<Long>()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val timeUs = ex.sampleTime
                if (timeUs >= 0) {
                    val bytes = ByteArray(size)
                    buf.position(0)
                    buf.get(bytes, 0, size)
                    val text = cleanCue(String(bytes, StandardCharsets.UTF_8))
                    if (text.isNotEmpty() && WordSelector.selectWord(text) != null) {
                        starts.add(timeUs / 1000)
                    }
                }
                if (!ex.advance()) break
            }
            starts.sort()
            starts
        } catch (e: Exception) {
            emptyList()
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun isTextSubtitle(fmt: MediaFormat): Boolean {
        val mime = fmt.getString(MediaFormat.KEY_MIME)?.lowercase() ?: return false
        return mime.startsWith("text/") ||
            mime.contains("subrip") || mime.contains("subtitle") ||
            mime.contains("vtt") || mime.contains("ttml") || mime.contains("cea")
    }

    private fun setDataSource(context: Context, ex: MediaExtractor, uriString: String) {
        val uri = Uri.parse(uriString)
        when (uri.scheme?.lowercase()) {
            "smb" -> ex.setDataSource(SmbMediaDataSource(SmbSupport.smbFile(uriString)))
            "http", "https" -> ex.setDataSource(uriString)
            "file" -> ex.setDataSource(uri.path ?: uriString)
            else -> ex.setDataSource(context, uri, null) // content:// and anything else
        }
    }

    /** Strip subtitle markup so word eligibility matches the on-screen text. */
    private fun cleanCue(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace('\n', ' ').replace('\r', ' ')
            .trim()
}
