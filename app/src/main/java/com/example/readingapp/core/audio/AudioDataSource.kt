package com.example.readingapp.core.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource

/**
 * Custom DataSource nối các chunk audio thành một stream duy nhất cho ExoPlayer.
 */
@OptIn(UnstableApi::class)
class AudioChunkDataSource(
    private val cacheManager: AudioCacheManager,
    private val articleId: String,
    private val expectedChunks: Int,
    private val metaStore: ChunkMetadataTracker,
    private val seekTarget: SeekTarget
) : BaseDataSource(false) {

    private var currentChunkIndex = 0
    private var currentInputStream: InputStream? = null
    private var opened = false
    private var uri: Uri? = null
    private var isNewlyOpened = false
    private var shouldPrependHeader = false

    override fun open(dataSpec: DataSpec): Long {
        android.util.Log.d("AudioChunkDS", "open() called, position=${dataSpec.position} uri=${dataSpec.uri}")
        currentInputStream?.close()
        currentInputStream = null
        uri = dataSpec.uri
        opened = true
        isNewlyOpened = true
        shouldPrependHeader = (dataSpec.position == 0L)
 
        val position = dataSpec.position
        val pendingSeek = seekTarget.consume()
        var skipBytesOnOpen = 0L
        
        if (pendingSeek != null) {
            currentChunkIndex = pendingSeek.chunkIndex
            skipBytesOnOpen = pendingSeek.byteOffset
            android.util.Log.d("AudioChunkDS", "Open with pending seek: chunkIndex=$currentChunkIndex offset=$skipBytesOnOpen")
        } else if (position > 0) {
            // Re-open tự động của ExoPlayer khi đứt đệm: tìm vị trí chunk và offset tương ứng với byte position!
            val (chunkIndex, byteOffset) = metaStore.findChunkForBytePosition(position)
            currentChunkIndex = chunkIndex
            skipBytesOnOpen = byteOffset
            android.util.Log.d("AudioChunkDS", "Auto-reopen by ExoPlayer: position=$position -> chunkIndex=$currentChunkIndex offset=$skipBytesOnOpen")
        } else {
            currentChunkIndex = 0
            skipBytesOnOpen = 0L
            android.util.Log.d("AudioChunkDS", "Open from start (index 0)")
        }
 
        transferStarted(dataSpec)
        openChunk(currentChunkIndex, skipBytesOnOpen)
        isNewlyOpened = false
        return -1L // Explicit Long for C.LENGTH_UNSET
    }

    private fun openChunk(index: Int, skipBytes: Long = 0L) {
        currentInputStream?.close()
        currentInputStream = null

        val file = cacheManager.awaitChunk(articleId, index) ?: return
        android.util.Log.d("AudioChunk", "openChunk index=$index skipBytes=$skipBytes isNewlyOpened=$isNewlyOpened shouldPrependHeader=$shouldPrependHeader")
        try {
            val fis = FileInputStream(file)

            if (index == 0) {
                // Đọc WAV header để lấy byteRate và calibrate
                val headerBytes = ByteArray(44)
                val readHeader = fis.read(headerBytes)

                if (readHeader == 44 && String(headerBytes, 0, 4) == "RIFF") {
                    val byteRate = ByteBuffer.wrap(headerBytes, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val dataBytes = file.length() - 44
                    metaStore.calibrate(byteRate, dataBytes, headerBytes)
                }

                if (skipBytes > 0) {
                    fis.skip(skipBytes)
                }

                if (isNewlyOpened && shouldPrependHeader) {
                    val totalPcmBytes = metaStore.getTotalPcmBytes()
                    val syntheticHeader = buildSyntheticHeader(metaStore.wavHeader ?: headerBytes, totalPcmBytes)
                    val headerStream = ByteArrayInputStream(syntheticHeader)
                    currentInputStream = object : SequenceInputStream(headerStream, fis) {
                        override fun markSupported() = false
                    }
                } else {
                    currentInputStream = fis
                }
            } else {
                val headerSize = skipToDataChunk(fis)
                // Lưu ý: KHÔNG gọi metaStore.onChunkReady() ở đây.
                // AudioPlayer.synthesizeChunk() đã gọi onChunkReady() với (file.length()-44)
                // ngay khi synthesis xong — đó là nguồn sự thật duy nhất.
                // Gọi lại ở đây với (file.length()-headerSize) sẽ ghi đè durationMs bằng
                // giá trị khác (headerSize có thể > 44 nếu WAV có chunk phụ), gây lệch seek.

                if (skipBytes > 0) fis.skip(skipBytes)

                if (isNewlyOpened && shouldPrependHeader) {
                    val totalPcmBytes = metaStore.getTotalPcmBytes()
                    val syntheticHeader = buildSyntheticHeader(metaStore.wavHeader ?: ByteArray(44), totalPcmBytes)
                    val headerStream = ByteArrayInputStream(syntheticHeader)
                    currentInputStream = object : SequenceInputStream(headerStream, fis) {
                        override fun markSupported() = false
                    }
                } else {
                    currentInputStream = fis
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioChunkDataSource", "Failed to open chunk $index", e)
        }
    }

    private fun buildSyntheticHeader(originalHeader: ByteArray, remainingBytes: Long): ByteArray {
        val header = originalHeader.copyOf(44)
        // Patch dataSize tại byte 40-43 (little-endian)
        val dataSize = remainingBytes.coerceAtMost(0xFFFFFFFFL)
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = ((dataSize shr 8) and 0xFF).toByte()
        header[42] = ((dataSize shr 16) and 0xFF).toByte()
        header[43] = ((dataSize shr 24) and 0xFF).toByte()
        // Patch RIFF size tại byte 4-7
        val riffSize = dataSize + 36
        header[4] = (riffSize and 0xFF).toByte()
        header[5] = ((riffSize shr 8) and 0xFF).toByte()
        header[6] = ((riffSize shr 16) and 0xFF).toByte()
        header[7] = ((riffSize shr 24) and 0xFF).toByte()
        return header
    }

    private fun skipToDataChunk(fis: FileInputStream): Long {
        var skipped = 0L
        try {
            val riff = ByteArray(12)
            val readRiff = fis.read(riff)
            if (readRiff < 12) return skipped
            skipped += readRiff

            if (String(riff, 0, 4) != "RIFF") {
                val skipFallback = 32L
                fis.skip(skipFallback)
                return skipped + skipFallback
            }

            val tmp = ByteArray(8)
            while (fis.read(tmp) == 8) {
                skipped += 8
                val chunkId = String(tmp, 0, 4)
                val size = ByteBuffer.wrap(tmp, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                if (chunkId == "data") {
                    val channelSize = try { fis.channel.size() } catch (e: Exception) { -1L }
                    android.util.Log.d("AudioChunk", "skipToDataChunk headerSize=$skipped fileSize=$channelSize")
                    return skipped
                }

                val toSkip = size.toLong()
                fis.skip(toSkip)
                skipped += toSkip
            }
        } catch (e: Exception) {
            // Fallback
        }
        return skipped
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return -1

        while (true) {
            val stream = currentInputStream

            if (stream == null) {
                val totalChunks = metaStore.getChunkCount().takeIf { it > 0 } ?: expectedChunks
                if (currentChunkIndex >= totalChunks) return -1
                openChunk(currentChunkIndex)
                if (currentInputStream == null) return -1
                continue
            }

            val bytesRead = stream.read(buffer, offset, length)
            if (bytesRead > 0) {
                bytesTransferred(bytesRead)
                return bytesRead
            }

            stream.close()
            currentInputStream = null
            currentChunkIndex++
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (!opened) return
        currentInputStream?.close()
        currentInputStream = null
        opened = false
        transferEnded()
    }
}

@OptIn(UnstableApi::class)
class UriDataSource(
    private val context: Context,
    private val cacheManager: AudioCacheManager,
    private val expectedChunksMapProvider: () -> Map<String, Int>,
    private val metaStoreProvider: (String) -> ChunkMetadataTracker?,
    private val seekTargetProvider: (String) -> SeekTarget?
) : BaseDataSource(false) {

    private var delegate: DataSource? = null
    private var openedDataSpec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        openedDataSpec = dataSpec
        val uri = dataSpec.uri

        if (uri.scheme != "data" && uri.scheme != "tts") {
            val defaultDataSource = DefaultDataSource(context, true)
            delegate = defaultDataSource
            val length = defaultDataSource.open(dataSpec)
            transferStarted(dataSpec)
            return length
        }

        val articleId = when {
            uri.toString().startsWith("tts://") -> uri.toString().substringAfter("tts://")
            uri.toString().startsWith("tts:") -> uri.toString().substringAfter("tts:")
            else -> uri.host
        } ?: throw java.io.IOException("Missing article ID in URI: $uri")
        if (articleId.isEmpty()) {
            throw java.io.IOException("Empty article ID in URI: $uri")
        }

        val metaStore = metaStoreProvider(articleId) ?: throw java.io.IOException("MetaStore not found for article $articleId")
        val seekTarget = seekTargetProvider(articleId) ?: throw java.io.IOException("SeekTarget not found for article $articleId")
        val expected = metaStore.getChunkCount().takeIf { it > 0 } ?: expectedChunksMapProvider()[articleId] ?: 1

        val chunkDataSource = AudioChunkDataSource(cacheManager, articleId, expected, metaStore, seekTarget)
        delegate = chunkDataSource
        val length = chunkDataSource.open(dataSpec)
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate?.read(buffer, offset, length) ?: -1
        if (bytesRead > 0) bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = delegate?.getUri()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            val spec = openedDataSpec
            openedDataSpec = null
            if (spec != null) transferEnded()
        }
    }
}
