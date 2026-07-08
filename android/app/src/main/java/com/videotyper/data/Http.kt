package com.videotyper.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal HTTPS helpers (no third-party client) for the poster metadata search and image fetch. */
object Http {

    suspend fun getString(urlString: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VideoTyper/1.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Download to [dest] (via a temp file, then rename) so a partial download never looks cached. */
    suspend fun downloadTo(urlString: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        val tmp = File(dest.absolutePath + ".tmp")
        try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VideoTyper/1.0")
            }
            if (conn.responseCode !in 200..299) return@withContext false
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
