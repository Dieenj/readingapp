package com.example.readingapp.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaSession
import androidx.media3.common.ForwardingPlayer
import com.example.readingapp.core.audio.AudioPlayer
import com.example.readingapp.core.tts.TextToSpeechEngine
import com.example.readingapp.feature.settings.data.TTSSettingsManager
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.local.NewsDatabase
import kotlinx.coroutines.*
import android.content.Context
import android.content.Intent

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Dịch vụ MediaLibraryService (Media3) cho trình đọc tin tức, hỗ trợ Android Auto.
 */
@OptIn(UnstableApi::class)
class ReadingMediaService : MediaLibraryService() {
    private lateinit var textToSpeechEngine: TextToSpeechEngine
    private lateinit var repository: ArticleRepository
    private lateinit var ttsSettingsManager: TTSSettingsManager

    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var ttsAudioPlayer: AudioPlayer
    private lateinit var playbackCoordinator: com.example.readingapp.media.playback.PlaybackCoordinator
    private lateinit var settingsRepository: com.example.readingapp.core.datastore.AppSettingsRepository
    private lateinit var newsPlaylistProvider: com.example.readingapp.media.playback.playlist.NewsPlaylistProvider
    private lateinit var emailPlaylistProvider: com.example.readingapp.media.playback.playlist.EmailPlaylistProvider
    private lateinit var emailRepository: com.example.readingapp.feature.email.data.EmailRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CUSTOM_ACTION_REFRESH = "com.example.readingapp.REFRESH"
        const val CUSTOM_ACTION_PLAY_ARTICLE = "com.example.readingapp.PLAY_ARTICLE"
        const val EXTRA_ARTICLE_ID = "article_id"
    }

    // Khởi tạo dịch vụ, thiết lập repository, TTS và trình phát
    override fun onCreate() {
        super.onCreate()

        val database = NewsDatabase.getDatabase(this)
        repository = ArticleRepository(database.articleDao(), database)
        ttsSettingsManager = TTSSettingsManager(this)
        settingsRepository = com.example.readingapp.core.datastore.AppSettingsRepository.getInstance(this)
        textToSpeechEngine = TextToSpeechEngine(this)

        textToSpeechEngine.initialize(
            onReady = { },
            onError = { errorCode -> android.util.Log.e("ReadingMediaService", "TTS error: $errorCode") }
        )

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val audioFocusManager = com.example.readingapp.core.audio.AudioFocusManager(
            audioManager = audioManager,
            onFocusLoss = { serviceScope.launch { ttsAudioPlayer.pause() } },
            onFocusGain = { serviceScope.launch { ttsAudioPlayer.resume() } }
        )

        ttsAudioPlayer = AudioPlayer(this, textToSpeechEngine, ttsSettingsManager, audioFocusManager)

        emailRepository = com.example.readingapp.feature.email.data.EmailRepository(this)
        emailPlaylistProvider = com.example.readingapp.media.playback.playlist.EmailPlaylistProvider(emailRepository)
        newsPlaylistProvider = com.example.readingapp.media.playback.playlist.NewsPlaylistProvider(repository)

        setupMediaSession()
        
        playbackCoordinator = com.example.readingapp.media.playback.PlaybackCoordinator(
            serviceScope = serviceScope,
            ttsAudioPlayer = ttsAudioPlayer,
            playlistProvider = newsPlaylistProvider,
            ttsSettingsManager = ttsSettingsManager,
            onItemLoaded = { item ->
                // Chỉ cập nhật news-specific state khi đang phát News
                // (tránh ghi đè AppSettings với source/category của Email)
                if (playbackCoordinator.playlistProvider is com.example.readingapp.media.playback.playlist.NewsPlaylistProvider) {
                    newsPlaylistProvider.updateContext(item.source, item.category)
                    settingsRepository.saveSourceAndCategory(item.source, item.category)
                    mediaSession.notifyChildrenChanged("news_root", 0, null)
                    mediaSession.notifyChildrenChanged("by_source", 0, null)
                    val sourceDetailId = com.example.readingapp.media.androidauto.AndroidAutoBrowseTreeBuilder.buildSourceDetailMediaId(item.source, item.category)
                    mediaSession.notifyChildrenChanged(sourceDetailId, 0, null)
                }
            },
            updateSessionMediaItem = { contentId, metadata ->
                serviceScope.launch(Dispatchers.Main) {
                    val playerCount = mediaSession.player.mediaItemCount
                    for (i in 0 until playerCount) {
                        val item = mediaSession.player.getMediaItemAt(i)
                        if (item.mediaId == contentId || item.mediaId.startsWith("${contentId}_chunk_")) {
                            val updatedMediaItem = item.buildUpon().setMediaMetadata(metadata).build()
                            mediaSession.player.replaceMediaItem(i, updatedMediaItem)
                        }
                    }
                }
            }
        )
    }

    // Thiết lập phiên media và các điều khiển trình phát (ForwardingPlayer)
    private fun setupMediaSession() {
        val forwardingPlayer = object : ForwardingPlayer(ttsAudioPlayer.exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun play() {
                ttsAudioPlayer.resume()
            }

            override fun pause() {
                ttsAudioPlayer.pause()
            }

            override fun stop() {
                ttsAudioPlayer.stop()
            }

            override fun getCurrentPosition(): Long = ttsAudioPlayer.getCurrentPosition()
            override fun getDuration(): Long = ttsAudioPlayer.getDuration()
            override fun getBufferedPosition(): Long = ttsAudioPlayer.getCurrentPosition() // Đơn giản hóa buffered pos

            override fun seekTo(positionMs: Long) = ttsAudioPlayer.seekTo(positionMs)

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                val player = ttsAudioPlayer.exoPlayer
                val currentIndex = player.currentMediaItemIndex

                if (mediaItemIndex == currentIndex) {
                    ttsAudioPlayer.seekTo(positionMs)
                    return
                }

                if (mediaItemIndex > currentIndex) playbackCoordinator.loadNextItem()
                else if (mediaItemIndex < currentIndex) playbackCoordinator.loadPreviousItem()
            }

            override fun seekToNext() = playbackCoordinator.loadNextItem()
            override fun seekToPrevious() = playbackCoordinator.loadPreviousItem()
            override fun seekToNextMediaItem() = playbackCoordinator.loadNextItem()
            override fun seekToPreviousMediaItem() = playbackCoordinator.loadPreviousItem()

            @Deprecated("Deprecated in Java")
            override fun seekToNextWindow() = playbackCoordinator.loadNextItem()

            @Deprecated("Deprecated in Java")
            override fun seekToPreviousWindow() = playbackCoordinator.loadPreviousItem()
        }

        val mediaSessionCallback = object : MediaLibrarySession.Callback {
            private val autoTreeProvider by lazy {
                com.example.readingapp.media.androidauto.AndroidAutoTreeProvider(
                    serviceScope = serviceScope,
                    repository = repository,
                    emailRepository = emailRepository,
                    settingsRepository = settingsRepository
                )
            }

            private val sessionHandler by lazy {
                com.example.readingapp.media.playback.session.MediaSessionHandler(
                    serviceScope = serviceScope,
                    repository = repository,
                    emailRepository = emailRepository,
                    settingsRepository = settingsRepository,
                    serviceCallbacks = object : com.example.readingapp.media.playback.session.MediaSessionHandler.Callbacks {
                        override fun loadItemById(articleId: String, contentType: String) {
                            // Chọn đúng PlaylistProvider dựa trên loại nội dung
                            playbackCoordinator.playlistProvider = when (contentType) {
                                com.example.readingapp.media.playback.session.MediaSessionHandler.CONTENT_TYPE_EMAIL -> emailPlaylistProvider
                                else -> newsPlaylistProvider
                            }
                            playbackCoordinator.loadItemById(articleId)
                        }
                        override fun onStopCommand() = playbackCoordinator.stopPlayback()
                    }
                )
            }

            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                sessionHandler.onConnect(session)

            override fun onSetMediaItems(
                mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
            ) = sessionHandler.onSetMediaItems(mediaItems, startIndex)

            override fun onAddMediaItems(
                mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
            ) = sessionHandler.onAddMediaItems()

            @Suppress("DEPRECATION")
            override fun onPlayerCommandRequest(
                session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int
            ): Int {
                val result = sessionHandler.onPlayerCommandRequest(playerCommand)
                return if (result != 0) result else super.onPlayerCommandRequest(session, controller, playerCommand)
            }

            override fun onCustomCommand(
                session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle
            ) = sessionHandler.onCustomCommand(customCommand, args)

            override fun onGetLibraryRoot(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
            ) = autoTreeProvider.onGetLibraryRoot(params)

            override fun onGetChildren(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                parentId: String, page: Int, pageSize: Int, params: LibraryParams?
            ) = autoTreeProvider.onGetChildren(parentId, params)
        }

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer, mediaSessionCallback).build()
    }

    // Trả về phiên media hiện tại khi được yêu cầu
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession



    // Giải phóng toàn bộ tài nguyên khi dịch vụ bị hủy
    override fun onDestroy() {
        super.onDestroy()
        ttsAudioPlayer.release()
        textToSpeechEngine.release()
        mediaSession.release()
        serviceScope.cancel()
    }

    // Xử lý khi tác vụ bị xóa khỏi danh sách gần đây
    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackCoordinator.stopPlayback() // Dừng phát nhạc và hủy các tiến trình nạp dữ liệu ngầm sạch sẽ
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}
