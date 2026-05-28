package com.example.readingapp.core.audio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ChunkMeta(
    val index: Int,
    val charOffset: Int,    // Vị trí bắt đầu trong toàn bài (char)
    val charCount: Int,     // Số ký tự của chunk này
    var dataBytes: Long = 0,      // PCM byte size (biết sau khi Đọc WAV header hoặc cập nhật)
    var durationMs: Long = 0,     // Duration thực (biết sau khi ExoPlayer parse hoặc tự tính)
    var startMs: Long = 0         // Thời điểm bắt đầu trong toàn bài (tính dần)
)

class SeekTarget {
    data class SeekData(val chunkIndex: Int, val byteOffset: Long, val targetMs: Long)

    private val pending = AtomicReference<SeekData?>(null)

    fun set(chunk: Int, bytes: Long, ms: Long) {
        pending.set(SeekData(chunk, bytes, ms))
    }

    fun consume(): SeekData? = pending.getAndSet(null)

    val hasPendingSeek: Boolean get() = pending.get() != null
}

/**
 * Theo dõi, căn chỉnh và tính toán thời gian phát / byte offset của các đoạn PCM WAV được phân mảnh.
 * Hỗ trợ định vị vị trí tìm kiếm phát âm thanh (seeking).
 */
class ChunkMetadataTracker {
    private val metas = ConcurrentHashMap<Int, ChunkMeta>()
    private val byteRate = AtomicInteger(0)
    private val msPerChar = AtomicLong(120L) // Ước lượng mặc định
    @Volatile var wavHeader: ByteArray? = null
    @Volatile private var cachedChunks: List<String> = emptyList()

    // Lock bảo vệ mọi read/write trên ChunkMeta.var fields (dataBytes, durationMs, startMs)
    // và sortedMetas cache — tránh đọc giá trị nửa chừng khi recalculate đang chạy
    private val lock = Any()
    private var sortedMetas: List<ChunkMeta> = emptyList() // Cached, rebuilt trong lock

    fun init(chunks: List<String>) {
        synchronized(lock) {
            metas.clear()
            byteRate.set(0)
            wavHeader = null
            cachedChunks = chunks
            var offset = 0
            chunks.forEachIndexed { i, chunk ->
                metas[i] = ChunkMeta(
                    index = i,
                    charOffset = offset,
                    charCount = chunk.length
                )
                offset += chunk.length
            }
            sortedMetas = metas.values.sortedBy { it.index }
        }
    }

    // Gọi sau khi chunk 0 synthesize xong + AudioChunkDataSource parse xong WAV header
    fun calibrate(byteRateVal: Int, chunk0DataBytes: Long, headerBytes: ByteArray) {
        synchronized(lock) {
            if (wavHeader == null) {
                wavHeader = headerBytes.copyOf()
            }
            val meta0 = metas[0] ?: return
            if (byteRateVal <= 0 || chunk0DataBytes <= 0) return

            byteRate.set(byteRateVal)
            val chunk0DurationMs = chunk0DataBytes * 1000 / byteRateVal
            // Dùng floating-point để tránh integer division về 0 (vd: 80ms / 200 chars = 0L)
            msPerChar.set(
                if (meta0.charCount > 0 && chunk0DurationMs > 0)
                    maxOf(1L, (chunk0DurationMs.toDouble() / meta0.charCount).toLong())
                else 120L
            )

            meta0.dataBytes = chunk0DataBytes
            meta0.durationMs = chunk0DurationMs

            // Tính startMs cho tất cả chunk từ msPerChar
            recalculateFromLocked(0)
        }
    }

    // Cập nhật khi một chunk > 0 được mở và biết size
    fun onChunkReady(index: Int, dataBytes: Long) {
        synchronized(lock) {
            val meta = metas[index] ?: return
            val currentByteRate = byteRate.get()
            if (currentByteRate > 0) {
                meta.dataBytes = dataBytes
                meta.durationMs = dataBytes * 1000 / currentByteRate
                recalculateFromLocked(index)
            }
        }
    }

    // Phải được gọi trong synchronized(lock)
    private fun recalculateFromLocked(fromIndex: Int) {
        val sortedKeys = metas.keys().toList().sorted()
        val startIndexInList = sortedKeys.indexOf(fromIndex)
        if (startIndexInList < 0) return

        var accumulated = if (startIndexInList == 0) {
            0L
        } else {
            val prevKey = sortedKeys[startIndexInList - 1]
            val prevMeta = metas[prevKey]
            if (prevMeta != null) prevMeta.startMs + prevMeta.durationMs else 0L
        }

        for (idx in startIndexInList until sortedKeys.size) {
            val i = sortedKeys[idx]
            val meta = metas[i] ?: continue
            meta.startMs = accumulated

            // Nếu chưa có dữ liệu thực (dataBytes == 0), dùng ước tính
            if (meta.dataBytes == 0L) {
                meta.durationMs = meta.charCount * msPerChar.get()
            }
            accumulated += meta.durationMs
        }
        // Rebuild cache sau mỗi lần recalculate — O(n log n) chỉ xảy ra khi có chunk mới
        sortedMetas = metas.values.sortedBy { it.index }
    }

    fun findChunkForPosition(positionMs: Long): Pair<Int, Long> {
        synchronized(lock) {
            val sorted = sortedMetas
            if (sorted.isEmpty()) return Pair(0, 0L)

            val currentByteRate = byteRate.get()

            for (meta in sorted) {
                if (meta.startMs + meta.durationMs > positionMs) {
                    val offsetMs = positionMs - meta.startMs
                    val offsetBytes = if (meta.dataBytes > 0 && meta.durationMs > 0) {
                        // Dùng size thực nếu đã có
                        (offsetMs * meta.dataBytes / meta.durationMs)
                    } else if (currentByteRate > 0) {
                        // Ước tính từ byteRate
                        (offsetMs * currentByteRate / 1000)
                    } else {
                        0L
                    }
                    // Đảm bảo byteOffset là số chẵn (quan trọng cho âm thanh 16-bit)
                    return Pair(meta.index, offsetBytes and 1L.inv())
                }
            }

            // Vượt quá phần đã biết, trả về chunk cuối
            val last = sorted.last()
            return Pair(last.index, 0L)
        }
    }
 
    fun findChunkForBytePosition(bytePosition: Long): Pair<Int, Long> {
        synchronized(lock) {
            val sorted = sortedMetas
            if (sorted.isEmpty()) return Pair(0, 0L)
 
            val pcmPosition = (bytePosition - 44).coerceAtLeast(0L)
            var accumulatedBytes = 0L
 
            for (meta in sorted) {
                val currentByteRate = byteRate.get()
                // Dùng size thực tế, nếu chưa có thì ước tính
                val chunkBytes = if (meta.dataBytes > 0L) {
                    meta.dataBytes
                } else if (currentByteRate > 0) {
                    meta.durationMs * currentByteRate / 1000
                } else {
                    meta.charCount * msPerChar.get() * 16000 * 2 / 1000 // Fallback mặc định 16kHz 16-bit mono
                }
 
                if (accumulatedBytes + chunkBytes > pcmPosition) {
                    val offsetBytes = pcmPosition - accumulatedBytes
                    // Đảm bảo byteOffset là số chẵn (cho âm thanh 16-bit)
                    return Pair(meta.index, offsetBytes and 1L.inv())
                }
                accumulatedBytes += chunkBytes
            }
 
            // Vượt quá phần đã biết, trả về chunk cuối
            val last = sorted.last()
            return Pair(last.index, 0L)
        }
    }

    fun getTotalEstimatedDuration(): Long {
        synchronized(lock) {
            val sorted = sortedMetas
            if (sorted.isEmpty()) return 0L
            val last = sorted.last()
            return last.startMs + last.durationMs
        }
    }

    fun getTotalPcmBytes(): Long {
        val currentByteRate = byteRate.get()
        if (currentByteRate <= 0) return 0L
        return getTotalEstimatedDuration() * currentByteRate / 1000
    }

    fun getByteRate(): Int = byteRate.get()

    fun getChunkCount(): Int = metas.size

    fun getChunks(): List<String> = cachedChunks
}
