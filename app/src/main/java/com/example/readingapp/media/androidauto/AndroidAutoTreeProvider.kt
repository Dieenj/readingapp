package com.example.readingapp.media.androidauto

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.local.ArticleEntity
import com.example.readingapp.feature.news.data.remote.NewsSourceConfig
import com.example.readingapp.feature.news.data.remote.NewsFeedFetcher
import com.example.readingapp.core.datastore.AppSettingsRepository
import com.example.readingapp.feature.email.data.EmailRepository
import com.example.readingapp.feature.email.data.local.EmailEntity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class AndroidAutoTreeProvider(
    private val serviceScope: CoroutineScope,
    private val repository: ArticleRepository,
    private val emailRepository: EmailRepository,
    private val settingsRepository: AppSettingsRepository
) {
    companion object {
        const val MEDIA_ROOT_ID = "news_root"
        const val BY_SOURCE_ID = "by_source"
        const val SOURCE_DETAIL_PREFIX = "sourcedetail_"
        const val BY_EMAIL_ID = "by_email"
        const val EMAIL_FOLDER_PREFIX = "emailfolder_"
        const val EMAIL_NOT_LOGGED_IN = "email_not_logged_in"
    }

    fun onGetLibraryRoot(params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = MediaItem.Builder()
            .setMediaId(MEDIA_ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false).setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Reading App")
                    .build()
            ).build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    fun onGetChildren(
        parentId: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when (parentId) {
            MEDIA_ROOT_ID -> {
                val newsItem = createBrowsableMediaItem(BY_SOURCE_ID, "Đọc báo", "Chọn theo tờ báo")
                val emailItem = createBrowsableMediaItem(BY_EMAIL_ID, "Đọc email", "Duyệt thư điện tử")
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(newsItem, emailItem), params))
            }
            BY_SOURCE_ID -> {
                val items = NewsSourceConfig.NEWS_SOURCES.keys.map { sourceName ->
                    createBrowsableMediaItem(AndroidAutoBrowseTreeBuilder.buildSourceMediaId(sourceName), sourceName, "Báo $sourceName")
                }
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            in NewsSourceConfig.NEWS_SOURCES.keys.map { AndroidAutoBrowseTreeBuilder.buildSourceMediaId(it) } -> {
                val sourceName = AndroidAutoBrowseTreeBuilder.resolveSourceName(parentId, NewsSourceConfig.NEWS_SOURCES.keys)
                if (sourceName != null) loadSourceCategories(sourceName, params)
                else Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            BY_EMAIL_ID -> {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                if (!emailRepository.hasCredentials()) {
                    val item = MediaItem.Builder().setMediaId(EMAIL_NOT_LOGGED_IN)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsPlayable(false).setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setTitle("Chưa đăng nhập")
                                .setSubtitle("Vui lòng đăng nhập trên điện thoại")
                                .build()
                        ).build()
                    future.set(LibraryResult.ofItemList(ImmutableList.of(item), params))
                } else {
                    serviceScope.launch {
                        try {
                            val folders = emailRepository.fetchFolders()
                            val items = folders.map { folderName ->
                                val displayName = EmailEntity.folderDisplayName(folderName)
                                createBrowsableMediaItem("$EMAIL_FOLDER_PREFIX$folderName", displayName, "Thư mục email")
                            }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                        } catch (e: Exception) {
                            android.util.Log.e("AndroidAutoTreeProvider", "Failed to fetch folders", e)
                            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                    }
                }
                future
            }
            else -> {
                when {
                    parentId.startsWith(SOURCE_DETAIL_PREFIX) -> loadArticlesForCategory(parentId, params)
                    parentId.startsWith(EMAIL_FOLDER_PREFIX) -> loadEmailsForFolder(parentId, params)
                    else -> Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
        }
    }

    private fun createBrowsableMediaItem(mediaId: String, title: String, subtitle: String): MediaItem {
        return MediaItem.Builder().setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false).setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle(title).setSubtitle(subtitle).build()
            ).build()
    }

    private fun createPlayableMediaItem(article: ArticleEntity): MediaItem {
        return MediaItem.Builder().setMediaId(article.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true).setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle(article.title).setArtist(article.source)
                    .setSubtitle(article.summary)
                    .setArtworkUri(article.imageUrl.takeIf { it.isNotEmpty() }?.toUri())
                    .build()
            ).build()
    }

    private fun loadSourceCategories(sourceName: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val categories = NewsSourceConfig.NEWS_SOURCES[sourceName] ?: emptyMap()
        val items = categories.map { (categoryName, _) ->
            val id = AndroidAutoBrowseTreeBuilder.buildSourceDetailMediaId(sourceName, categoryName)
            createBrowsableMediaItem(id, categoryName, "Tin $categoryName từ $sourceName")
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
    }

    private fun loadArticlesForCategory(parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        serviceScope.launch {
            try {
                val parsed = AndroidAutoBrowseTreeBuilder.parseSourceDetailMediaId(parentId)
                val sourceName = parsed?.first
                val categoryName = parsed?.second

                if (sourceName != null && categoryName != null) {
                    settingsRepository.saveSourceAndCategory(sourceName, categoryName)
                    
                    val articles = NewsFeedFetcher.fetchFeedsByCategory(sourceName, categoryName)
                    if (articles.isNotEmpty()) repository.upsertArticlesWithCleanup(articles)

                    val dbArticles = repository.getArticlesBySourceAndCategorySync(sourceName, categoryName)
                    val items = dbArticles.map { createPlayableMediaItem(it) }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } else {
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            } catch (e: Exception) {
                android.util.Log.e("AndroidAutoTreeProvider", "Failed to load articles for $parentId", e)
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        return future
    }

    private fun loadEmailsForFolder(parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        val folderName = parentId.removePrefix(EMAIL_FOLDER_PREFIX)
        serviceScope.launch {
            try {
                val emails = emailRepository.fetchEmailsByFolder(folderName)
                val items = emails.map { email ->
                    createPlayableEmailMediaItem(email)
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: Exception) {
                android.util.Log.e("AndroidAutoTreeProvider", "Failed to load emails for folder $folderName", e)
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }
        return future
    }

    private fun createPlayableEmailMediaItem(email: EmailEntity): MediaItem {
        return MediaItem.Builder().setMediaId("emailid_${email.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true).setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle(email.subject)
                    .setArtist(email.fromName.ifBlank { email.fromAddress ?: "" })
                    .setSubtitle(email.body)
                    .build()
            ).build()
    }
}
