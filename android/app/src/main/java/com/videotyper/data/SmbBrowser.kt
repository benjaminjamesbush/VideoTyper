package com.videotyper.data

import com.videotyper.player.SmbSupport
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry in an SMB directory listing (a share, folder, or video file). */
data class SmbEntry(
    val name: String,
    val url: String,       // credential-free smb:// URL (dirs end in '/')
    val isDirectory: Boolean,
)

/** Result of listing a directory: either the entries, or an error message to show the user. */
sealed interface SmbListing {
    data class Ok(val entries: List<SmbEntry>) : SmbListing
    data class Error(val message: String) : SmbListing
}

object SmbBrowser {

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts"
    )

    /**
     * List [dirUrl] (a credential-free smb:// URL) using [server]'s credentials. Directories come
     * first, then video files; other files are hidden. Runs on IO. Never throws — connection and
     * auth failures come back as [SmbListing.Error].
     */
    suspend fun list(server: SmbServer, dirUrl: String): SmbListing = withContext(Dispatchers.IO) {
        try {
            val ctx = SmbSupport.contextFor(userInfoOf(server))
            val dir = SmbFile(dirUrl, ctx).apply { connectTimeout = 15_000; readTimeout = 15_000 }
            val children = dir.listFiles() ?: emptyArray()
            val entries = children.mapNotNull { child ->
                val isDir = try {
                    child.isDirectory
                } catch (e: Exception) {
                    // Unreadable share/entry (e.g. print$, IPC$) — skip it.
                    return@mapNotNull null
                }
                val rawName = child.name.trimEnd('/')
                when {
                    isDir -> SmbEntry(rawName, child.url.toString(), true)
                    rawName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS ->
                        SmbEntry(rawName, child.url.toString(), false)
                    else -> null
                }
            }.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            SmbListing.Ok(entries)
        } catch (e: Exception) {
            SmbListing.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun userInfoOf(server: SmbServer): String? {
        if (server.username.isBlank()) return null
        return buildString {
            if (server.domain.isNotBlank()) append(server.domain).append(';')
            append(server.username)
            append(':').append(server.password)
        }
    }
}
