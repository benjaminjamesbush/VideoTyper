package com.videotyper.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlin.math.min

/**
 * Media3 DataSource for smb:// URLs (Windows/NAS shares) backed by jcifs-ng.
 * Credentials go in the URL: smb://user:password@host/share/path.mkv (or
 * smb://domain;user:password@host/...). Without credentials, guest access is attempted.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpecUri: Uri? = null
    private var file: SmbFile? = null
    private var raf: SmbRandomAccessFile? = null
    private var bytesRemaining = 0L
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        dataSpecUri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val smbFile = SmbSupport.smbFile(dataSpec.uri.toString())
            file = smbFile
            val length = smbFile.length()
            val access = SmbRandomAccessFile(smbFile, "r")
            raf = access
            access.seek(dataSpec.position)

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                length - dataSpec.position
            }
            if (bytesRemaining < 0) {
                throw IOException("SMB open: position ${dataSpec.position} beyond end of file ($length)")
            }
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            closeQuietly()
            throw if (e is IOException) e else IOException("SMB open failed: ${e.message}", e)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = min(length.toLong(), bytesRemaining).toInt()
        val read = raf?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpecUri

    @Throws(IOException::class)
    override fun close() {
        val wasOpened = opened
        closeQuietly()
        if (wasOpened) transferEnded()
    }

    private fun closeQuietly() {
        opened = false
        try {
            raf?.close()
        } catch (_: Exception) {
        } finally {
            raf = null
            file = null
        }
    }

}
