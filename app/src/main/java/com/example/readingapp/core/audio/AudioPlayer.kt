package com.example.readingapp.core.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.readingapp.core.model.ReadableContent
import com.example.readingapp.core.model.PlayerState
import com.example.readingapp.core.tts.TextToSpeechEngine
import com.example.readingapp.feature.settings.data.TTSSettingsManager
import com.example.readingapp.core.tts.TextSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/**
 * Trình phát âm thanh chính điều phối ExoPlayer và chu kỳ tổng hợp TTS chunk-by-chunk.
 */
@OptIn(UnstableApi::class)
class AudioPlayer(
    context: Context,
    private val ttsEngine: TextToSpeechEngine,
    private val settingsManager: TTSSettingsManager,
    private val audioFocusManager: AudioFocusManager
) {
    var onArticleCompleted: ((String) -> Unit)? = null
    var onContentFullyEnqueued: ((String) -> Unit)? = null

    private val cacheManager = AudioCacheManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expectedChunksMap = ConcurrentHashMap<String, Int>()
    // Theo dõi seek offset và metadata của từng chunk
    private val metaStores = ConcurrentHashMap<String, ChunkMetadataTracker>()
    private val seekTargets = ConcurrentHashMap<String, SeekTarget>()
    private val committedSeekMs = AtomicLong(0L)
    private val pendingSeekMs = AtomicLong(-1L)
    private val contentFullyEnqueued = ConcurrentHashMap<String, Boolean>()
    private var seekDebounceJob: Job? = null

    private val dataSourceFactory = DataSource.Factory {
        UriDataSource(
            context = context,
            cacheManager = cacheManager,
            expectedChunksMapProvider = { expectedChunksMap },
            metaStoreProvider = { id -> metaStores[id] },
            seekTargetProvider = { id -> seekTargets[id] }
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    _state.value = PlayerState.Stopped
                    audioFocusManager.abandonFocus()
                }
                Player.STATE_ENDED -> {
                    val id = currentContentId.get()
                    if (id != null) {
                        if (id != lastCompletedArticleId.get()) {
                            lastCompletedArticleId.set(id)
                            onArticleCompleted?.invoke(id)
                        }
                    }
                    _state.value = PlayerState.Stopped
                    audioFocusManager.abandonFocus()
                }
                Player.STATE_BUFFERING -> {
                    _state.value = PlayerState.Buffering(currentContentId.get() ?: "unknown")
                }
                Player.STATE_READY -> {
                    if (exoPlayer.playWhenReady) {
                        updatePlayingState()
                    } else {
                        updatePausedState()
                    }
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                updatePlayingState()
            } else if (exoPlayer.playbackState == Player.STATE_READY) {
                updatePausedState()
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val nextId = mediaItem?.mediaId
            val oldId = currentContentId.get()

            // Tự động báo bài cũ đã hoàn thành nếu chuyển bài tự động
            if (oldId != null && nextId != oldId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (oldId != lastCompletedArticleId.get()) {
                    lastCompletedArticleId.set(oldId)
                    onArticleCompleted?.invoke(oldId)
                }
            }

            if (nextId != null) {
                val content = activeContentMap[nextId]
                if (content != null) {
                    _currentContent.value = content
                    currentContentId.set(content.id)
                    if (nextId != oldId) {
                        lastCompletedArticleId.set(null)
                    }
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                        committedSeekMs.set(0L)
                        pendingSeekMs.set(-1L)
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = PlayerState.Error(currentContentId.get(), "Playback error: ${error.message}")
        }
    }

    internal val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
        .build().apply {
            addListener(playerListener)
        }

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _currentContent = MutableStateFlow<ReadableContent?>(null)
    val currentContent: StateFlow<ReadableContent?> = _currentContent.asStateFlow()

    private val currentContentId = AtomicReference<String?>(null)
    private val activeContentMap = ConcurrentHashMap<String, ReadableContent>()

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastCompletedArticleId = AtomicReference<String?>(null)

    private val activeSynthesisJobs = ConcurrentHashMap<String, Job>()
    // Jobs preload nền — được cancel ngay khi bài đó trở thành bài đang phát
    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val jobLock = Any()

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    suspend fun play(content: ReadableContent) {
        cancelAllSynthesis(excludeIdFromCancelAwait = content.id)
        contentFullyEnqueued.remove(content.id) // Giải phóng cờ hiệu của bài viết hiện tại
        currentContentId.set(content.id)
        committedSeekMs.set(0L)
        pendingSeekMs.set(-1L)
        _currentContent.value = content
        activeContentMap[content.id] = content
        lastCompletedArticleId.set(null)

        val fullText = content.getFullText()
        val splitResult = TextSplitter.split(text = fullText, language = content.language)
        val chunks = splitResult.chunks
        expectedChunksMap[content.id] = splitResult.totalChunks

        val metaStore = ChunkMetadataTracker()
        metaStore.init(chunks)
        metaStores[content.id] = metaStore
        seekTargets[content.id] = SeekTarget()

        // Synthesize chunk 0 trước để phát lập tức
        val chunk0Ready = synthesizeChunk(content, chunks, 0)
        if (!chunk0Ready) {
            _state.value = PlayerState.Error(content.id, "Failed to synthesize first chunk")
            return
        }

        val ttsUri = "tts://${content.id}".toUri()
        val ttsItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId(content.id)
            .setUri(ttsUri)
            .setMediaMetadata(content.toMediaMetadata())
            .build()

        withContext(Dispatchers.Main) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(ttsItem)
            exoPlayer.prepare()
            audioFocusManager.requestFocus()
            exoPlayer.play()
        }

        synchronized(jobLock) {
            val job = backgroundScope.launch {
                try {
                    // Song song: Synthesize chunk 1 và 2 trước
                    val bufferJobs = (1..minOf(2, chunks.size - 1)).map { i ->
                        async { synthesizeChunk(content, chunks, i) }
                    }
                    bufferJobs.awaitAll()

                    // Tiếp tục tuần tự các chunk còn lại
                    val nextStartIndex = if (chunks.size > 1) minOf(2, chunks.size - 1) + 1 else 1
                    synthesizeRemainingChunks(content, chunks, nextStartIndex)
                } finally {
                    activeSynthesisJobs.remove(content.id)
                }
            }
            activeSynthesisJobs[content.id] = job
        }
    }

    private fun cancelAllSynthesis(excludeIdFromCancelAwait: String? = null) {
        synchronized(jobLock) {
            activeSynthesisJobs.values.forEach { it.cancel() }
            activeSynthesisJobs.clear()
        }
        // Cancel cả preload jobs — giải phóng TTS engine cho bài sắp phát
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
        ttsEngine.stop()
        expectedChunksMap.keys.toList().forEach { id ->
            if (id != excludeIdFromCancelAwait) {
                val total = expectedChunksMap[id] ?: 20
                cacheManager.cancelAwait(id, total)
                expectedChunksMap.remove(id)
                activeContentMap.remove(id)
                metaStores.remove(id)
                seekTargets.remove(id)
                contentFullyEnqueued.remove(id) // Dọn dẹp cờ hiệu của những bài KHÁC
            }
        }
    }

    fun pause() { runOnMainThread { exoPlayer.pause() } }
    fun resume() { runOnMainThread { audioFocusManager.requestFocus(); exoPlayer.play() } }
    fun stop() {
        cancelAllSynthesis()
        committedSeekMs.set(0L)
        pendingSeekMs.set(-1L)
        seekDebounceJob?.cancel()
        seekDebounceJob = null
        runOnMainThread { exoPlayer.stop() }
    }

    fun enqueueNextArticleWithSynthesis(content: ReadableContent) {
        val fullText = content.getFullText()
        val splitResult = TextSplitter.split(text = fullText, language = content.language)

        // Khởi tạo nguyên tử tránh ghi đè mất metaStore đã preload
        if (activeContentMap.putIfAbsent(content.id, content) == null) {
            expectedChunksMap[content.id] = splitResult.totalChunks
            val metaStore = ChunkMetadataTracker()
            metaStore.init(splitResult.chunks)
            metaStores[content.id] = metaStore
        }

        // Luôn ghi đè SeekTarget sạch để tránh bị nhiễm rác seek của bài viết từ chu kỳ trước
        seekTargets[content.id] = SeekTarget()

        // Lấy chunks từ metaStore để đảm bảo nhất quán tuyệt đối với preload trước đó
        val chunks = metaStores[content.id]?.getChunks() ?: splitResult.chunks

        val ttsUri = "tts://${content.id}".toUri()
        val ttsItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId(content.id)
            .setUri(ttsUri)
            .setMediaMetadata(content.toMediaMetadata())
            .build()

        // Hủy preload job cũ nếu có (preload đã làm một phần) — tránh tranh chấp TTS engine
        preloadJobs.remove(content.id)?.cancel()

        synchronized(jobLock) {
            val job = backgroundScope.launch {
                try {
                    // Nếu chunk 0 đã được preload sẵn, synthesizeChunk() sẽ return ngay từ cache
                    synthesizeChunk(content, chunks, 0)
                    runOnMainThread { exoPlayer.addMediaItem(ttsItem) }
                    
                    // Thêm parallel buffer giống play() để nạp nhanh chunk 1 và 2 song song chống đứt đệm
                    val bufferJobs = (1..minOf(2, chunks.size - 1)).map { i ->
                        async { synthesizeChunk(content, chunks, i) }
                    }
                    bufferJobs.awaitAll()
                    
                    val nextStart = if (chunks.size > 1) minOf(2, chunks.size - 1) + 1 else 1
                    synthesizeRemainingChunks(content, chunks, nextStart)
                } finally {
                    activeSynthesisJobs.remove(content.id)
                }
            }
            activeSynthesisJobs[content.id] = job
        }
    }

    private suspend fun synthesizeChunk(
        content: ReadableContent,
        chunks: List<String>,
        index: Int
    ): Boolean {
        val contentId = content.id
        if (cacheManager.isCached(contentId, index)) {
            val file = cacheManager.getSync(contentId, index)
            if (file != null) {
                if (index > 0) {
                    android.util.Log.d("AudioPlayer", "onChunkReady (cached) index=$index pcmBytes=${file.length()-44} fileSize=${file.length()}")
                    metaStores[contentId]?.onChunkReady(index, file.length() - 44)
                }
                cacheManager.notifyChunkReady(contentId, index)
                return true
            }
            // isCached=true nhưng getSync=null: file đã bị evict giữa 2 lần gọi
            // (DiskLruCache journal lệch filesystem). Fall-through để re-synthesize.
            android.util.Log.w("AudioPlayer", "Cache desync for chunk $index of $contentId — re-synthesizing")
        }

        val file = cacheManager.getTempFile(contentId, index)

        for (attempt in 0 until 3) {
            if (!coroutineContext.isActive) return false
            try {
                val success = ttsEngine.synthesizeTextToFile(
                    chunks[index],
                    content.language,
                    settingsManager.getCurrentSettings(),
                    file
                ) {}
                if (success && file.exists()) {
                    cacheManager.put(contentId, file, index)
                    if (index > 0) {
                        android.util.Log.d("AudioPlayer", "onChunkReady (synthesis) index=$index pcmBytes=${file.length()-44} fileSize=${file.length()}")
                        metaStores[contentId]?.onChunkReady(index, file.length() - 44)
                    }
                    return true
                }
                if (attempt < 2) delay(500)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "Error synthesizing chunk $index, attempt ${attempt + 1}", e)
                if (attempt < 2) delay(500)
            }
        }
        return false
    }

    private suspend fun synthesizeRemainingChunks(content: ReadableContent, chunks: List<String>, startIndex: Int) {
        val contentId = content.id
        for (i in startIndex until chunks.size) {
            if (!coroutineContext.isActive) break
            val success = synthesizeChunk(content, chunks, i)
            if (!success && coroutineContext.isActive) {
                android.util.Log.e("AudioPlayer", "Failed to synthesize chunk $i after 3 attempts. Aborting remaining chunks.")
                val total = expectedChunksMap[contentId] ?: chunks.size
                cacheManager.cancelAwait(contentId, total)
                break
            }
        }
        if (coroutineContext.isActive) {
            // Chỉ gọi callback nếu bài viết chưa từng được đánh dấu là fully enqueued
            if (contentFullyEnqueued.putIfAbsent(contentId, true) == null) {
                onContentFullyEnqueued?.invoke(contentId)
            }
        }
    }

    // Preload nhẹ (sliding window): chỉ synthesize chunk 0 để khởi động nhanh khi cần.
    // Không dung hết TTS engine vào việc preload toàn bộ bài ngăn bài đang phát tổng hợp tiếp.
    // Job được track riêng trong preloadJobs và bị cancel ngay khi bài đó chuyển sang enqueue.
    suspend fun preSynthesize(content: ReadableContent, maxChunks: Int = 1) {
        if (isContentActive(content.id)) return  // Bài này đang chạy riêng rồi
        val fullText = content.getFullText()
        val splitResult = TextSplitter.split(text = fullText, language = content.language)
        val chunks = splitResult.chunks
        
        // Thao tác atomic chống ghi đè chồng chéo state
        if (activeContentMap.putIfAbsent(content.id, content) == null) {
            expectedChunksMap[content.id] = splitResult.totalChunks
            val metaStore = ChunkMetadataTracker()
            metaStore.init(chunks)
            metaStores[content.id] = metaStore
            seekTargets[content.id] = SeekTarget()
        }

        val actualMax = minOf(maxChunks, chunks.size)
        for (i in 0 until actualMax) {
            if (!coroutineContext.isActive) break
            val success = synthesizeChunk(content, chunks, i)
            if (!success && coroutineContext.isActive) break
        }
    }

    suspend fun getCachedFullFile(contentId: String): File? = cacheManager.get(contentId)
    suspend fun getCacheStats(): AudioCacheManager.CacheStats = cacheManager.getStats()
    suspend fun removeCachedChunks(contentId: String) {
        withContext(Dispatchers.IO) {
            cacheManager.remove(contentId, -1)
            val expected = expectedChunksMap[contentId] ?: 20
            for (i in 0 until expected) cacheManager.remove(contentId, i)
        }
        activeContentMap.remove(contentId)
        metaStores.remove(contentId)
        seekTargets.remove(contentId)
    }

    fun isArticleInPlaylist(articleId: String): Boolean = runOnMainThreadWithResult {
        for (i in 0 until exoPlayer.mediaItemCount) {
            if (exoPlayer.getMediaItemAt(i).mediaId == articleId) return@runOnMainThreadWithResult true
        }
        false
    } ?: false

    fun isContentActive(contentId: String): Boolean = activeContentMap.containsKey(contentId)

    fun enqueueFullArticle(content: ReadableContent, audioFile: File) {
        if (!audioFile.exists()) return
        activeContentMap[content.id] = content
        expectedChunksMap[content.id] = 1

        val metaStore = ChunkMetadataTracker()
        metaStore.init(listOf("Full Article"))
        metaStores[content.id] = metaStore
        seekTargets[content.id] = SeekTarget()

        runOnMainThread {
            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(audioFile))
                .setMediaId(content.id)
                .setMediaMetadata(content.toMediaMetadata())
                .build()
            exoPlayer.addMediaItem(mediaItem)
        }
    }

    private fun updatePlayingState() {
        val content = _currentContent.value ?: return
        _state.value = PlayerState.Playing(content.id, getCurrentPosition(), getDuration())
    }

    private fun updatePausedState() {
        val content = _currentContent.value ?: return
        _state.value = PlayerState.Paused(content.id, getCurrentPosition(), getDuration())
    }

    fun getCurrentPosition(): Long {
        val playerPos = runOnMainThreadWithResult { exoPlayer.currentPosition } ?: 0L
        val pending = pendingSeekMs.get()
        if (pending >= 0) return pending  // UI dùng giá trị pending khi đang kéo thanh
        return committedSeekMs.get() + playerPos
    }

    fun getDuration(): Long {
        val contentId = currentContentId.get() ?: return 0L
        return metaStores[contentId]?.getTotalEstimatedDuration() ?: 0L
    }

    private fun prioritizeSynthesisFrom(contentId: String, startIndex: Int, targetMs: Long) {
        val content = activeContentMap[contentId] ?: return
        // Dùng chunks đã cache sẵn trong metaStore, không re-split mỗi lần seek
        val chunks = metaStores[contentId]?.getChunks() ?: return
        if (chunks.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, chunks.size - 1)

        synchronized(jobLock) {
            activeSynthesisJobs[contentId]?.cancel()
            val job = backgroundScope.launch {
                try {
                    // 1. Tổng hợp chunk đích trước — điều kiện bắt buộc
                    val chunkReady = synthesizeChunk(content, chunks, clampedIndex)
                    if (!chunkReady || !isActive) return@launch

                    // 2. ExoPlayer chỉ seek sau khi chunk đích đã sẵn sàng
                    withContext(Dispatchers.Main) {
                        exoPlayer.seekTo(0L)
                        committedSeekMs.set(targetMs)
                        pendingSeekMs.compareAndSet(targetMs, -1L)
                    }

                    // 3. Tổng hợp các chunk phía sau để phát liên tục
                    for (i in (clampedIndex + 1) until chunks.size) {
                        if (!isActive) break
                        synthesizeChunk(content, chunks, i)
                    }
                    // 4. Bù lại các chunk trước đó (ít ưu tiên hơn, đề phòng seek ngược lại)
                    for (i in 0 until clampedIndex) {
                        if (!isActive) break
                        synthesizeChunk(content, chunks, i)
                    }

                    // Kích hoạt chuẩn bị bài tiếp theo sau khi seek hoàn thành tổng hợp toàn bộ bài
                    // và bài viết chưa từng được đánh dấu là fully enqueued trước đó
                    if (isActive) {
                        if (contentFullyEnqueued.putIfAbsent(contentId, true) == null) {
                            onContentFullyEnqueued?.invoke(contentId)
                        }
                    }
                } finally {
                    activeSynthesisJobs.remove(contentId)
                }
            }
            activeSynthesisJobs[contentId] = job
        }
    }

    fun seekTo(positionMs: Long) {
        // Cập nhật UI ngay để không bị lag cảm giác khi kéo thanh
        pendingSeekMs.set(positionMs.coerceAtLeast(0L))
        updatePlayingState()

        seekDebounceJob?.cancel()
        seekDebounceJob = backgroundScope.launch {
            delay(150) // Chờ user dừng kéo
            executeSeek(positionMs) // Chạy trên background thread (Default), tránh deadlock Main thread
        }
    }

    private fun executeSeek(positionMs: Long) {
        val contentId = currentContentId.get() ?: return
        val metaStore = metaStores[contentId] ?: return
        val seekTarget = seekTargets[contentId] ?: return

        val (chunkIndex, byteOffset) = metaStore.findChunkForPosition(positionMs)

        if (positionMs <= 0) {
            seekTarget.set(0, 0L, 0L)
            pendingSeekMs.set(0L)
            // Seek về đầu cũng cần đảm bảo chunk 0 trong cache
            prioritizeSynthesisFrom(contentId, 0, 0L)
        } else {
            seekTarget.set(chunkIndex, byteOffset, positionMs)
            // exoPlayer.seekTo(0L) sẽ được gọi trong prioritizeSynthesisFrom sau khi chunk sẵn sàng
            prioritizeSynthesisFrom(contentId, chunkIndex, positionMs)
        }
    }

    fun getMediaItemCount(): Int = runOnMainThreadWithResult { exoPlayer.mediaItemCount } ?: 0
    fun getMediaItemAt(index: Int): androidx.media3.common.MediaItem? = runOnMainThreadWithResult { exoPlayer.getMediaItemAt(index) }

    fun seekToMediaItem(index: Int) {
        runOnMainThread {
            val item = exoPlayer.getMediaItemAt(index)
            seekTargets[item.mediaId]?.set(0, 0L, 0L)
            committedSeekMs.set(0L)
            pendingSeekMs.set(-1L)
            
            val needPrepare = exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED
            if (needPrepare) {
                exoPlayer.prepare()
                exoPlayer.seekTo(index, 0L)
            } else {
                exoPlayer.seekTo(index, 0L)
            }
            exoPlayer.play()
        }
    }

    fun release() {
        cancelAllSynthesis()
        runOnMainThread {
            exoPlayer.release()
            audioFocusManager.abandonFocus()
            cacheManager.close()
            backgroundScope.cancel()
            activeContentMap.clear()
            metaStores.clear()
            seekTargets.clear()
            expectedChunksMap.clear()
        }
    }

    private fun <T> runOnMainThreadWithResult(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val lock = Object()
        synchronized(lock) {
            mainHandler.post { synchronized(lock) { result = block(); lock.notifyAll() } }
            lock.wait(1000)
        }
        if (result == null) android.util.Log.w("AudioPlayer", "runOnMainThreadWithResult: timeout waiting for Main thread")
        return result
    }
}

private fun ReadableContent.getFullText(): String = "$title. $content".trim()
private fun ReadableContent.toMediaMetadata(): androidx.media3.common.MediaMetadata {
    return androidx.media3.common.MediaMetadata.Builder()
        .setTitle(title).setArtist(source).setAlbumTitle(category)
        .setArtworkUri(imageUrl?.takeIf { it.isNotEmpty() }?.toUri()).build()
}
