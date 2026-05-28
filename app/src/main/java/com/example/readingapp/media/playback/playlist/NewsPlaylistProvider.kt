package com.example.readingapp.media.playback.playlist

import com.example.readingapp.core.model.ReadableContentData
import com.example.readingapp.feature.news.data.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class NewsPlaylistProvider(
    private val repository: ArticleRepository
) : PlaylistProvider {

    private var currentSource: String? = null
    private var currentCategory: String? = null

    fun updateContext(source: String?, category: String?) {
        currentSource = source
        currentCategory = category
    }

    override suspend fun getItemById(id: String): ReadableContentData? {
        val article = repository.getArticleById(id) ?: return null
        val fullContent = kotlinx.coroutines.withTimeoutOrNull(5000L) {
            withContext(Dispatchers.IO) {
                repository.getOrFetchFullContent(article)
            }
        } ?: article.contentText

        return ReadableContentData(
            id = article.id,
            title = article.title,
            content = fullContent,
            publishedDate = article.publishedDate,
            source = article.source,
            url = article.url,
            language = article.language,
            category = article.category,
            imageUrl = article.imageUrl
        )
    }

    override suspend fun getNextItem(currentId: String?): ReadableContentData? {
        val nextArticle = repository.getNextArticle(
            currentId ?: "",
            source = currentSource,
            category = currentCategory
        ) ?: return null
        return getItemById(nextArticle.id)
    }

    override suspend fun getPreviousItem(currentId: String?): ReadableContentData? {
        val previousArticle = repository.getPreviousArticle(
            currentId ?: "",
            source = currentSource,
            category = currentCategory
        ) ?: return null
        return getItemById(previousArticle.id)
    }

    override suspend fun getNextItems(currentId: String?, count: Int): List<ReadableContentData> = withContext(Dispatchers.Default) {
        if (currentId.isNullOrEmpty()) return@withContext emptyList() // Trả về danh sách rỗng khi chưa phát bài nào
        
        val nextArticles = repository.getNextArticles(
            currentId,
            count,
            source = currentSource,
            category = currentCategory
        )
        val deferreds = nextArticles.map { article ->
            async { getItemById(article.id) }
        }
        deferreds.awaitAll().filterNotNull()
    }

    override suspend fun markAsRead(id: String) {
        repository.markAsRead(id)
    }
}
