package com.example.readingapp.media.playback.session

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.remote.NewsFeedFetcher
import com.example.readingapp.feature.news.data.remote.NewsSourceConfig
import com.example.readingapp.media.ReadingMediaService
import com.example.readingapp.media.androidauto.AndroidAutoTreeProvider
import com.example.readingapp.media.androidauto.AndroidAutoBrowseTreeBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.example.readingapp.core.datastore.AppSettingsRepository

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class MediaSessionHandler(
    private val serviceScope: CoroutineScope,
    private val repository: ArticleRepository,
    private val emailRepository: com.example.readingapp.feature.email.data.EmailRepository,
    private val settingsRepository: AppSettingsRepository,
    private val serviceCallbacks: Callbacks
) {
    private var refreshJob: kotlinx.coroutines.Job? = null
    companion object {
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val CONTENT_TYPE_NEWS  = "news"
        const val CONTENT_TYPE_EMAIL = "email"
    }

    interface Callbacks {
        fun loadItemById(articleId: String, contentType: String)
        fun onStopCommand()
    }

    fun onConnect(
        session: MediaSession
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(ReadingMediaService.CUSTOM_ACTION_PLAY_ARTICLE, Bundle.EMPTY))
            .add(SessionCommand(ReadingMediaService.CUSTOM_ACTION_REFRESH, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    fun onSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int = 0
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (mediaItems.isNotEmpty()) {
            val index = if (startIndex >= 0 && startIndex < mediaItems.size) startIndex else 0
            val mediaId = mediaItems[index].mediaId
            if (mediaId.isNotEmpty()) {
                serviceScope.launch {
                    val isEmail = mediaId.startsWith("emailid_") || mediaId.startsWith("emailfolder_")
                    val contentType = if (isEmail) CONTENT_TYPE_EMAIL else CONTENT_TYPE_NEWS

                    val targetId = when {
                        mediaId.startsWith("emailid_") -> {
                            mediaId.removePrefix("emailid_")
                        }
                        mediaId.startsWith("emailfolder_") -> {
                            val folderName = mediaId.removePrefix("emailfolder_")
                            val accountEmail = emailRepository.getAccountEmail()
                            val emails = emailRepository.getEmailDao().getEmailsByAccountSync(accountEmail)
                                .filter { it.folderName == folderName }
                            emails.firstOrNull()?.id
                        }
                        // 1. Click vào danh mục chi tiết (Ví dụ: sourcedetail_TuoiTre__TheThao)
                        mediaId.startsWith(AndroidAutoBrowseTreeBuilder.SOURCE_DETAIL_PREFIX) -> {
                            val parsed = AndroidAutoBrowseTreeBuilder.parseSourceDetailMediaId(mediaId)
                            val source = parsed?.first
                            val category = parsed?.second
                            if (source != null && category != null) {
                                val articles = repository.getArticlesBySourceAndCategorySync(source, category)
                                articles.firstOrNull()?.id
                            } else null
                        }
                        // 2. Click vào nguồn tin (Ví dụ: source_tuoitre)
                        mediaId.startsWith(AndroidAutoBrowseTreeBuilder.SOURCE_PREFIX) -> {
                            val sourceName = AndroidAutoBrowseTreeBuilder.resolveSourceName(
                                mediaId,
                                NewsSourceConfig.NEWS_SOURCES.keys
                            )
                            val categories = NewsSourceConfig.NEWS_SOURCES[sourceName]?.keys?.toList() ?: emptyList()
                            if (sourceName != null && categories.isNotEmpty()) {
                                val articles = repository.getArticlesBySourceAndCategorySync(sourceName, categories[0])
                                articles.firstOrNull()?.id
                            } else null
                        }
                        // 3. Click vào bài viết thông thường
                        else -> mediaId
                    }

                    if (!targetId.isNullOrEmpty()) {
                        serviceCallbacks.loadItemById(targetId, contentType)
                    }
                }
            }
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                emptyList(),
                androidx.media3.common.C.INDEX_UNSET,
                androidx.media3.common.C.TIME_UNSET
            )
        )
    }

    fun onAddMediaItems(): ListenableFuture<MutableList<MediaItem>> = 
        Futures.immediateFuture(mutableListOf())

    fun onPlayerCommandRequest(
        playerCommand: Int
    ): Int {
        when (playerCommand) {
            androidx.media3.common.Player.COMMAND_STOP -> {
                serviceCallbacks.onStopCommand()
                return SessionResult.RESULT_SUCCESS
            }
        }
        // Let the default logic handle it if not intercepted
        return 0 
    }

    fun onCustomCommand(
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            ReadingMediaService.CUSTOM_ACTION_PLAY_ARTICLE -> {
                val articleId = args.getString(ReadingMediaService.EXTRA_ARTICLE_ID)
                val contentType = args.getString(EXTRA_CONTENT_TYPE) ?: CONTENT_TYPE_NEWS
                if (!articleId.isNullOrEmpty()) {
                    serviceCallbacks.loadItemById(articleId, contentType)
                }
            }
            ReadingMediaService.CUSTOM_ACTION_REFRESH -> {
                val sourceName = settingsRepository.getSource()
                val categoryName = settingsRepository.getCategory()

                if (sourceName != null && categoryName != null) {
                    refreshJob?.cancel() // Hủy tiến trình nạp cũ trước khi chạy tiến trình mới
                    refreshJob = serviceScope.launch {
                        try {
                            val articles = NewsFeedFetcher.fetchFeedsByCategory(sourceName, categoryName)
                            if (articles.isNotEmpty()) repository.upsertArticlesWithCleanup(articles)
                        } catch (e: Exception) {
                            android.util.Log.w("MediaSessionHandler", "Refresh failed for $sourceName/$categoryName", e)
                        }
                    }
                }
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}
