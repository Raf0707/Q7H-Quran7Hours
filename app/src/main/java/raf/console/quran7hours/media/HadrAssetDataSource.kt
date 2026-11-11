package raf.console.quran7hours.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.FileInputStream
import java.io.IOException

/**
 * Random-access reader for the one local, UNCOMPRESSED seven-hour M4A.
 *
 * Unlike AssetManager.open(path), this opens an AssetFileDescriptor and seeks
 * directly to its byte offset inside the APK. A slider jump never has to skip
 * from the beginning of a seven-hour stream.
 *
 * Requires androidResources.noCompress += "m4a" in app/build.gradle.kts.
 * Does not copy the recording into the app's files/cache directory.
 */
@UnstableApi
class HadrAssetDataSource(private val context: Context) : BaseDataSource(false) {
    companion object {
        const val ASSET_PATH = "quran/quran_7_hours.m4a"
        const val ASSET_URI = "asset:///$ASSET_PATH"
    }

    class Factory(context: Context) : DataSource.Factory {
        private val applicationContext = context.applicationContext
        override fun createDataSource(): DataSource = HadrAssetDataSource(applicationContext)
    }

    private var descriptor: AssetFileDescriptor? = null
    private var stream: FileInputStream? = null
    private var resolvedUri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.toString() != ASSET_URI) {
            throw IOException("Ожидался только $ASSET_URI, получено ${dataSpec.uri}")
        }
        transferInitializing(dataSpec)
        try {
            val afd = context.assets.openFd(ASSET_PATH)
            descriptor = afd
            val assetLength = afd.length
            if (assetLength <= 0L) {
                throw IOException("Файл M4A имеет нулевой или неизвестный размер: $ASSET_PATH")
            }
            if (dataSpec.position < 0L || dataSpec.position > assetLength) {
                throw IOException("Позиция ${dataSpec.position} за пределами M4A ($assetLength байт)")
            }
            val input = FileInputStream(afd.fileDescriptor)
            stream = input
            // A bad (truncated) APK must never masquerade as a valid seekable file.
            val apkSize = input.channel.size()
            val assetEnd = Math.addExact(afd.startOffset, assetLength)
            if (afd.startOffset < 0 || assetEnd > apkSize) {
                throw IOException("Неполный M4A в APK: assetEnd=$assetEnd, apkSize=$apkSize")
            }
            // Seek to absolute position inside the APK, NOT the start of the APK.
            input.channel.position(Math.addExact(afd.startOffset, dataSpec.position))
            val available = assetLength - dataSpec.position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(available, dataSpec.length)
            }
            resolvedUri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
            close()
            throw if (error is IOException) error else IOException(
                "Невозможно открыть $ASSET_PATH. Проверьте noCompress для m4a.", error
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(bytesRemaining, length.toLong()).toInt()
        val count = stream?.read(buffer, offset, requested)
            ?: throw IOException("M4A не открыт")
        if (count < 0) throw IOException("M4A оборвался раньше ожидаемой позиции")
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = resolvedUri

    override fun close() {
        val hadOpened = opened
        opened = false
        resolvedUri = null
        bytesRemaining = 0L
        // FileInputStream and AssetFileDescriptor may share the same FD.
        // Both close calls are safe; clear fields even after an IOException.
        try {
            stream?.close()
        } finally {
            stream = null
            try {
                descriptor?.close()
            } finally {
                descriptor = null
                if (hadOpened) transferEnded()
            }
        }
    }
}
