package com.videotyper.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Routes smb:// URIs to [SmbDataSource]; everything else (file://, content://, http(s)://)
 * goes through Media3's DefaultDataSource.
 */
@UnstableApi
class SchemeDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = SchemeDataSource(context)
}

@UnstableApi
private class SchemeDataSource(context: Context) : DataSource {
    private val defaultSource = DefaultDataSource.Factory(context).createDataSource()
    private val smbSource = SmbDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        defaultSource.addTransferListener(transferListener)
        smbSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "DataSource already open" }
        val source = if (dataSpec.uri.scheme == "smb") smbSource else defaultSource
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IllegalStateException("DataSource not open")

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}
