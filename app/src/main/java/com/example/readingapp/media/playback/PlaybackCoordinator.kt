package com.example.readingapp.media.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.MediaMetadata
import com.example.readingapp.core.model.ReadableContentData
import com.example.readingapp.core.audio.AudioPlayer
import com.example.readingapp.core.model.PlayerState
import com.example.readingapp.feature.settings.data.TTSSettingsManager
import kotlinx.coroutines.*
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri
import com.example.readingapp.media.playback.playlist.PlaylistProvider

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class PlaybackCoordinator(
    private val serviceScope: CoroutineScope,
    private val ttsAudioPlayer: AudioPlayer,
    var playlistProvider: PlaylistProvider? = null,
    private val ttsSettingsManager: TTSSettingsManager,
    private val onItemLoaded: ((ReadableContentData) -> Unit)? = null,
    private val updateSessionMediaItem: (String, MediaMetadata) -> Unit
) {
    
    var currentContentId: String? = null
        private set
    private var currentLoadingJob: Job? = null
    private val loadingLock = Any()
    private val preloadWindow = ConcurrentHashMap<String, Long>() // articleId -> timestamp
    private val maxPreloadWindowSize = 5
    private var preloadJob: Job? = null

    init {
        setupPlayerStateObserver()
    }

    // Thiết lập bộ quan sát trạng thái trình phát
    private fun setupPlayerStateObserver() {
        ttsAudioPlayer.onArticleCompleted = { articleId ->
            serviceScope.launch {
                android.util.Log.d("PlaybackCoordinator", "Article completed: $articleId")
                playlistProvider?.markAsRead(articleId)
                
                val settings = ttsSettingsManager.getCurrentSettings()
                if (settings.autoPlayNext) {
                    // Fail-safe: Nếu player dừng hẳn (do chưa nạp kịp playlist), hãy cưỡng bức nạp bài mới
                    if (ttsAudioPlayer.state.value is PlayerState.Stopped) {
                        android.util.Log.d("PlaybackCoordinator", "Auto-next fail-safe triggered")
                        loadNextItem()
                    }
                    
                    // Trì hoãn một chút trước khi preload bài mới để nhường engine cho bài tiếp theo khởi tạo
                    delay(500)
                    maintainPreloadBuffer(articleId)
                }
            }
        }

        ttsAudioPlayer.onContentFullyEnqueued = { articleId ->
            // Khi bài hiện tại đã nạp đủ tất cả các đoạn vào playlist:
            // Bắt đầu chuẩn bị bài tiếp theo ngay để đạt gapless playback
            prepareNextItem(articleId)
        }

        serviceScope.launch {
            ttsAudioPlayer.state.collect { state ->
                when (state) {
                    is PlayerState.Stopped -> { /* Nothing needed */ }
                    is PlayerState.Error -> { /* Nothing needed */ }
                    else -> { }
                }
            }
        }

        serviceScope.launch {
            ttsAudioPlayer.currentContent.collect { content ->
                if (content != null) {
                    currentContentId = content.id
                    updateMediaMetadata(content.id, content.title, content.source, content.category, content.imageUrl)
                }
            }
        }
    }

    // Dừng phát nhạc và hủy các tiến trình nạp dữ liệu
    fun stopPlayback() {
        currentContentId = null
        currentLoadingJob?.cancel()
        preloadJob?.cancel()
        ttsAudioPlayer.stop()
    }

    // Nạp bài báo theo ID và bắt đầu đọc
    fun loadItemById(articleId: String) {
        synchronized(loadingLock) {
            currentLoadingJob?.cancel()
            preloadJob?.cancel()
            currentLoadingJob = serviceScope.launch {
                val item = withContext(Dispatchers.IO) { playlistProvider?.getItemById(articleId) }
                currentContentId = item?.id
                item?.let {
                    onItemLoaded?.invoke(it)
                    playArticleContent(it, it.content)
                }
            }
        }
    }

    // Nạp bài báo dựa trên logic chọn (tiếp theo/trước đó)
    private fun loadArticle(isNext: Boolean, selector: suspend (String?) -> ReadableContentData?) {
        synchronized(loadingLock) {
            currentLoadingJob?.cancel()
            preloadJob?.cancel()
            currentLoadingJob = serviceScope.launch {
                val currentId = currentContentId
                val item = withContext(Dispatchers.IO) { selector(currentId) }
                
                if (item != null) {
                    android.util.Log.d("PlaybackCoordinator", "Loading article: ${item.title} (isNext=$isNext)")
                    currentContentId = item.id
                    updateMediaMetadata(item.id, item.title, item.source, item.category, item.imageUrl)
                    
                    // Kiểm tra xem bài báo đã có trong playlist chưa
                    var jumped = false
                    val mediaItemCount = ttsAudioPlayer.getMediaItemCount()
                    for (i in 0 until mediaItemCount) {
                        val mediaId = ttsAudioPlayer.getMediaItemAt(i)?.mediaId ?: continue
                        if (mediaId == item.id) {
                            ttsAudioPlayer.seekToMediaItem(i)
                            jumped = true
                            break
                        }
                    }

                    if (jumped) {
                        android.util.Log.d("PlaybackCoordinator", "Jumped to existing article in playlist")
                        return@launch
                    }

                    playArticleContent(item, item.content)
                } else {
                    android.util.Log.d("PlaybackCoordinator", "No more articles to load")
                }
            }
        }
    }

    // Nạp bài báo tiếp theo (thủ công)
    fun loadNextItem() {
        loadArticle(isNext = true) { currentId -> playlistProvider?.getNextItem(currentId) }
    }

    // Chuẩn bị bài tiếp theo một cách liền mạch (tự động)
    fun prepareNextItem(completedId: String) {
        val userAutoPlaySetting = ttsSettingsManager.getCurrentSettings().autoPlayNext
        if (!userAutoPlaySetting) return

        serviceScope.launch {
            val nextItem = playlistProvider?.getNextItem(completedId) ?: return@launch
            // Dùng isContentActive() thay cho isArticleInPlaylist():
            // isArticleInPlaylist() đọc ExoPlayer.mediaItemCount (Main thread), có thể trả false
            // sai nếu gọi đúng lúc play() đang clearMediaItems() → enqueue trùng lặp.
            // isContentActive() dùng ConcurrentHashMap (activeContentMap), an toàn mọi thread.
            if (ttsAudioPlayer.isContentActive(nextItem.id)) return@launch

            // Nếu đã có full file cache thì enqueue trực tiếp, tránh synthesis lại từ đầu
            val cachedFullFile = ttsAudioPlayer.getCachedFullFile(nextItem.id)
            if (cachedFullFile != null) {
                android.util.Log.d("PlaybackCoordinator", "Sync load: Full file found for ${nextItem.id}, enqueuing directly.")
                ttsAudioPlayer.enqueueFullArticle(nextItem, cachedFullFile)
            } else {
                ttsAudioPlayer.enqueueNextArticleWithSynthesis(nextItem)
            }
        }
    }

    // Duy trì bộ đệm nạp trước cho các bài tiếp theo (sliding window)
    fun maintainPreloadBuffer(currentId: String) {
        preloadJob?.cancel()
        preloadJob = serviceScope.launch {
            val cacheStats = ttsAudioPlayer.getCacheStats()
            val cacheUsagePercent = (cacheStats.sizeBytes.toFloat() / cacheStats.maxSizeBytes * 100).toInt()

            val effectiveWindowSize = when {
                cacheUsagePercent > 90 -> 2
                cacheUsagePercent > 70 -> 3
                else -> maxPreloadWindowSize
            }

            // Dùng isContentActive() — thread-safe, không block Main thread
            val nextItems = (playlistProvider?.getNextItems(currentId, effectiveWindowSize) ?: emptyList())
                .filter { !ttsAudioPlayer.isContentActive(it.id) }
            val nextItemIds = nextItems.map { it.id }.toSet()

            // Smart cleanup: xóa cache của các bài cũ hơn 10 phút và không còn trong window
            preloadWindow.keys.toList()
                .filter { it !in nextItemIds && it != currentId }
                .filter { System.currentTimeMillis() - (preloadWindow[it] ?: 0L) > 10 * 60 * 1000 }
                .forEach { articleId ->
                    preloadWindow.remove(articleId)
                    ttsAudioPlayer.removeCachedChunks(articleId)
                }

            // Tuần tự theo priority: bài gần nhất trước.
            // Chỉ preload chunk 0 — đủ để khởi động nhanh khi bài đó được enqueue mà không đánh cướp TTS engine.
            for (item in nextItems) {
                if (!isActive) break
                try {
                    ttsAudioPlayer.preSynthesize(item, maxChunks = 1)
                    preloadWindow[item.id] = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackCoordinator", "Preload failed for article ${item.id}", e)
                }
            }
        }
    }

    // Nạp bài báo trước đó
    fun loadPreviousItem() {
        loadArticle(isNext = false) { currentId -> playlistProvider?.getPreviousItem(currentId) }
    }

    // Phát nội dung bài báo qua trình phát
    private fun playArticleContent(item: ReadableContentData, content: String) {
        val readableContent = item.copy(content = content)

        serviceScope.launch {
            ttsAudioPlayer.play(readableContent)
        }
    }

    // Cập nhật thông tin media cho phiên phát
    fun updateMediaMetadata(contentId: String, title: String, source: String, category: String, imageUrl: String?) {
        serviceScope.launch(Dispatchers.IO) {
            val albumArt = if (!imageUrl.isNullOrEmpty()) loadAlbumArt(imageUrl) else null

            withContext(Dispatchers.Main) {
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(source)
                    .setAlbumTitle(category)

                if (!imageUrl.isNullOrEmpty()) {
                    metadataBuilder.setArtworkUri(imageUrl.toUri())
                }

                if (albumArt != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    albumArt.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    metadataBuilder.setArtworkData(stream.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }

                val metadata = metadataBuilder.build()
                updateSessionMediaItem(contentId, metadata)
            }
        }
    }

    // Tải ảnh bìa từ URL
    private fun loadAlbumArt(imageUrl: String): Bitmap? {
        if (imageUrl.isEmpty()) return null
        return try {
            val connection = URL(imageUrl).openConnection().apply {
                connectTimeout = 5000; readTimeout = 5000; connect()
            }
            val originalBitmap = BitmapFactory.decodeStream(connection.getInputStream())
            if (originalBitmap != null) {
                scaleBitmap(originalBitmap, 512) // Giới hạn kích thước ảnh bìa để tránh crash
            } else null
        } catch (e: Exception) {
            android.util.Log.d("PlaybackCoordinator", "Failed to load album art from $imageUrl", e)
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        var width = bitmap.width
        var height = bitmap.height
        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
