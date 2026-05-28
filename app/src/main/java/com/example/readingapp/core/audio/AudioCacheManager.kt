package com.example.readingapp.core.audio

import android.content.Context
import com.jakewharton.disklrucache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Quản lý bộ nhớ đệm âm thanh sử dụng DiskLruCache.
 */
class AudioCacheManager(context: Context) {

    private val cacheDir = File(context.cacheDir, "tts_audio")
    private var cache: DiskLruCache

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    // Notification slots: chunkKey -> queue nhận tín hiệu "đã sẵn sàng"
    private val chunkReadySlots = ConcurrentHashMap<String, LinkedBlockingQueue<Boolean>>()

    companion object {
        private const val APP_VERSION = 1
        private const val VALUE_COUNT = 1
        private const val MAX_CACHE_SIZE_BYTES = 200L * 1024 * 1024
        private const val AUDIO_EXTENSION = ".wav"
    }

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        cache = try {
            DiskLruCache.open(cacheDir, APP_VERSION, VALUE_COUNT, MAX_CACHE_SIZE_BYTES)
        } catch (e: Exception) {
            android.util.Log.e("AudioCacheManager", "Failed to open DiskLruCache, cleaning up...", e)
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            DiskLruCache.open(cacheDir, APP_VERSION, VALUE_COUNT, MAX_CACHE_SIZE_BYTES)
        }
    }

    suspend fun get(contentId: String, chunkIndex: Int = -1): File? = withContext(Dispatchers.IO) {
        try {
            val key = if (chunkIndex < 0) contentId.toMD5() else "${contentId}_chunk_$chunkIndex".toMD5()
            val snapshot = cache.get(key)
            if (snapshot == null) {
                missCount.incrementAndGet()
                return@withContext null
            }
            try {
                val file = File(cacheDir, "$key.0")
                if (file.exists() && file.length() > 0) {
                    hitCount.incrementAndGet()
                    file
                } else {
                    missCount.incrementAndGet()
                    null
                }
            } finally {
                snapshot.close()
            }
        } catch (e: Exception) {
            missCount.incrementAndGet()
            null
        }
    }

    fun getSync(contentId: String, chunkIndex: Int = -1): File? {
        try {
            val key = if (chunkIndex < 0) contentId.toMD5() else "${contentId}_chunk_$chunkIndex".toMD5()
            val snapshot = cache.get(key)
            if (snapshot == null) return null
            try {
                val file = File(cacheDir, "$key.0")
                return if (file.exists() && file.length() > 0) file else null
            } finally {
                snapshot.close()
            }
        } catch (e: Exception) {
            return null
        }
    }

    // DataSource gọi hàm này thay vì polling
    fun awaitChunk(contentId: String, chunkIndex: Int): File? {
        val key = "${contentId}_chunk_$chunkIndex".toMD5()

        // 1. Đăng ký slot chờ TRƯỚC khi check cache để tránh race condition mất tín hiệu notify
        val queue = chunkReadySlots.getOrPut(key) { LinkedBlockingQueue(1) }

        // 2. Kiểm tra xem cache đã có sẵn chưa sau khi đã đăng ký
        val existing = getSync(contentId, chunkIndex)
        if (existing != null) {
            chunkReadySlots.remove(key)
            return existing
        }

        // Chunk 0: timeout ngắn vì đã synthesize trước khi play
        // Chunk 1,2: timeout trung bình vì đang được tổng hợp song song/nhanh
        // Chunk 3+: timeout dài vì tổng hợp tuần tự
        val timeoutMs = when (chunkIndex) {
            0 -> 5_000L
            1, 2 -> 15_000L
            else -> 60_000L
        }

        return try {
            // Block IO thread có timeout - không dùng sleep
            val signalled = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (signalled == true) getSync(contentId, chunkIndex) else {
                android.util.Log.e("AudioCacheManager", "Timeout waiting for chunk $chunkIndex of $contentId")
                null
            }
        } catch (e: InterruptedException) {
            null
        } finally {
            chunkReadySlots.remove(key)
        }
    }

    private fun notifyChunkReady(key: String) {
        chunkReadySlots[key]?.offer(true)
    }

    fun notifyChunkReady(contentId: String, chunkIndex: Int) {
        val key = "${contentId}_chunk_$chunkIndex".toMD5()
        notifyChunkReady(key)
    }

    fun cancelAwait(contentId: String, totalChunks: Int) {
        for (i in 0 until totalChunks) {
            val key = "${contentId}_chunk_$i".toMD5()
            // remove() trước rồi mới offer() — tránh stale false signal còn sót trong queue
            // nếu awaitChunk được gọi lại sau đó cho cùng chunk
            chunkReadySlots.remove(key)?.offer(false)
        }
    }

    suspend fun isCached(contentId: String, chunkIndex: Int = -1): Boolean = withContext(Dispatchers.IO) {
        val key = if (chunkIndex < 0) contentId.toMD5() else "${contentId}_chunk_$chunkIndex".toMD5()
        val snapshot = cache.get(key) ?: return@withContext false
        try {
            // Verify file thực sự tồn tại — DiskLruCache journal có thể desync với filesystem
            // (mất điện, thiếu disk space) dẫn đến snapshot != null nhưng file bị corrupt/mất.
            // Nhất quán với getSync() để tránh synthesizeChunk bỏ qua chunk không hợp lệ.
            val file = File(cacheDir, "$key.0")
            file.exists() && file.length() > 0
        } finally {
            snapshot.close()
        }
    }

    suspend fun put(contentId: String, audioFile: File, chunkIndex: Int = -1): Boolean = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) return@withContext false
        val key = if (chunkIndex < 0) contentId.toMD5() else "${contentId}_chunk_$chunkIndex".toMD5()
        val editor = cache.edit(key) ?: return@withContext false
        try {
            editor.newOutputStream(0).use { output ->
                audioFile.inputStream().use { input -> input.copyTo(output) }
            }
            editor.commit()
            notifyChunkReady(key) // Báo hiệu đã sẵn sàng
            true
        } catch (e: Exception) {
            editor.abort()
            false
        }
    }

    fun getTempFile(contentId: String, chunkIndex: Int = -1): File {
        val tempDir = File(cacheDir, "temp").apply { mkdirs() }
        val suffix = if (chunkIndex < 0) "" else "_chunk_$chunkIndex"
        return File(tempDir, "${(contentId + suffix).toMD5()}$AUDIO_EXTENSION")
    }

    suspend fun remove(contentId: String, chunkIndex: Int = -1): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = if (chunkIndex < 0) contentId.toMD5() else "${contentId}_chunk_$chunkIndex".toMD5()
            cache.remove(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getStats(): CacheStats = withContext(Dispatchers.IO) {
        CacheStats(
            sizeBytes = cache.size(),
            maxSizeBytes = MAX_CACHE_SIZE_BYTES,
            hitCount = hitCount.get(),
            missCount = missCount.get()
        )
    }

    fun close() {
        try { cache.flush(); cache.close() } catch (e: Exception) { }
    }

    private fun String.toMD5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class CacheStats(
        val sizeBytes: Long,
        val maxSizeBytes: Long,
        val hitCount: Long,
        val missCount: Long
    )
}
