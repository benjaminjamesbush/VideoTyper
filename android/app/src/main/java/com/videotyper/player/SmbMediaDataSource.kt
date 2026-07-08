package com.videotyper.player

import android.media.MediaDataSource
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlin.math.min

/**
 * Adapts a jcifs SmbFile to MediaDataSource so MediaMetadataRetriever can decode a poster frame
 * straight off a network share (used by ThumbnailLoader for smb:// videos). Random-access reads
 * are what let the retriever seek to a frame a few seconds in without downloading the whole file.
 */
class SmbMediaDataSource(smbFile: SmbFile) : MediaDataSource() {
    private val raf = SmbRandomAccessFile(smbFile, "r")
    private val length = smbFile.length()

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        raf.seek(position)
        val toRead = min(size.toLong(), length - position).toInt()
        if (toRead <= 0) return -1
        return raf.read(buffer, offset, toRead)
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }
}
