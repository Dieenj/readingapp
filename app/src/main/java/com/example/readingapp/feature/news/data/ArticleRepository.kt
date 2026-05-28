package com.example.readingapp.feature.news.data

import androidx.room.withTransaction
import com.example.readingapp.feature.news.data.local.ArticleDao
import com.example.readingapp.feature.news.data.local.ArticleEntity
import com.example.readingapp.feature.news.data.local.NewsDatabase
import com.example.readingapp.feature.news.data.parser.ArticleContentFetcher
import kotlinx.coroutines.flow.Flow

class ArticleRepository(
    private val articleDao: ArticleDao,
    private val database: NewsDatabase,
    private val contentFetcher: ArticleContentFetcher = ArticleContentFetcher()
) {

    companion object {
        private const val ARTICLE_RETENTION_DAYS = 5
        private const val DEFAULT_ARTICLE_LIMIT = 50
    }

    val allArticles: Flow<List<ArticleEntity>> = articleDao.getAllArticles()

    // Lấy danh sách bài báo theo nguồn và danh mục (dưới dạng Flow)
    fun getArticlesBySourceAndCategory(source: String, category: String): Flow<List<ArticleEntity>> =
        articleDao.getArticlesBySourceAndCategory(source, category)

    // Lấy danh sách bài báo theo nguồn và danh mục (đồng bộ)
    suspend fun getArticlesBySourceAndCategorySync(source: String, category: String): List<ArticleEntity> =
        articleDao.getArticlesBySourceAndCategorySync(source, category)

    // Lấy toàn bộ danh sách bài báo (đồng bộ)
    suspend fun getAllArticlesSync(): List<ArticleEntity> =
        articleDao.getAllArticlesList(DEFAULT_ARTICLE_LIMIT)

    // Lấy thông tin bài báo theo ID
    suspend fun getArticleById(articleId: String): ArticleEntity? =
        articleDao.getArticleById(articleId)

    // Lấy bài báo tiếp theo trong cùng nguồn và danh mục
    suspend fun getNextArticle(currentArticle: ArticleEntity): ArticleEntity? =
        articleDao.getNextArticleBySourceAndCategory(
            currentArticle.source, currentArticle.category,
            currentArticle.publishedDate, currentArticle.id
        )

    // Lấy bài báo tiếp theo dựa trên ID bài báo hiện tại và lọc theo source/category tùy chọn
    suspend fun getNextArticle(
        currentArticleId: String,
        source: String? = null,
        category: String? = null
    ): ArticleEntity? {
        val current = articleDao.getArticleById(currentArticleId)
        val finalSource = source ?: current?.source ?: return null
        val finalCategory = category ?: current?.category ?: return null
        val publishedDate = current?.publishedDate ?: System.currentTimeMillis()
        val id = current?.id ?: ""
        
        val next = articleDao.getNextArticleBySourceAndCategory(finalSource, finalCategory, publishedDate, id)
        return next ?: articleDao.getArticlesBySourceAndCategorySync(finalSource, finalCategory).firstOrNull()
    }

    // Lấy danh sách các bài báo tiếp theo
    suspend fun getNextArticles(
        currentArticleId: String,
        limit: Int,
        source: String? = null,
        category: String? = null
    ): List<ArticleEntity> {
        val current = articleDao.getArticleById(currentArticleId)
        val finalSource = source ?: current?.source ?: return emptyList()
        val finalCategory = category ?: current?.category ?: return emptyList()
        val publishedDate = current?.publishedDate ?: System.currentTimeMillis()
        val id = current?.id ?: ""
        
        val list = articleDao.getNextArticlesBySourceAndCategory(
            finalSource, finalCategory,
            publishedDate, id, limit
        )
        return list.ifEmpty {
            articleDao.getArticlesBySourceAndCategorySync(finalSource, finalCategory).take(limit)
        }
    }

    // Lấy bài báo trước đó trong cùng nguồn và danh mục
    suspend fun getPreviousArticle(currentArticle: ArticleEntity): ArticleEntity? =
        articleDao.getPreviousArticleBySourceAndCategory(
            currentArticle.source, currentArticle.category,
            currentArticle.publishedDate, currentArticle.id
        )

    // Lấy bài báo trước đó dựa trên ID bài báo hiện tại
    suspend fun getPreviousArticle(
        currentArticleId: String,
        source: String? = null,
        category: String? = null
    ): ArticleEntity? {
        val current = articleDao.getArticleById(currentArticleId)
        val finalSource = source ?: current?.source ?: return null
        val finalCategory = category ?: current?.category ?: return null
        val publishedDate = current?.publishedDate ?: 0L
        val id = current?.id ?: ""
        
        val prev = articleDao.getPreviousArticleBySourceAndCategory(
            finalSource, finalCategory,
            publishedDate, id
        )
        return prev ?: articleDao.getArticlesBySourceAndCategorySync(finalSource, finalCategory).lastOrNull()
    }

    // Đánh dấu bài báo là đã đọc
    suspend fun markAsRead(articleId: String) =
        articleDao.updateReadStatus(articleId, true)

    // Cập nhật nội dung đầy đủ của bài báo
    suspend fun updateFullContent(articleId: String, fullContent: String) =
        articleDao.updateFullContentIfEmpty(articleId, fullContent)

    // Thêm bài báo mới và dọn dẹp các bài báo cũ
    suspend fun upsertArticlesWithCleanup(newArticles: List<ArticleEntity>) {
        if (newArticles.isEmpty()) return

        database.withTransaction {
            val existingMap = articleDao
                .getArticlesByIds(newArticles.map { it.id })
                .associateBy { it.id }

            val articlesToInsert = newArticles.map { new ->
                val existing = existingMap[new.id]
                if (existing != null) {
                    new.copy(
                        fullContent = existing.fullContent ?: new.fullContent,
                        isRead = existing.isRead,
                        addedDate = existing.addedDate
                    )
                } else {
                    new
                }
            }

            articleDao.insertArticles(articlesToInsert)

            val cutoff = System.currentTimeMillis() -
                    ARTICLE_RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000
            articleDao.deleteArticlesOlderThan(cutoff)
        }
    }

    // Lấy nội dung đầy đủ từ DB hoặc tải từ URL nếu chưa có
    suspend fun getOrFetchFullContent(article: ArticleEntity): String? {
        if (!article.fullContent.isNullOrEmpty()) {
            return article.fullContent
        }

        val fetchedContent = contentFetcher.fetchFullContent(article.url)
        if (!fetchedContent.isNullOrEmpty()) {
            updateFullContent(article.id, fetchedContent)
            return fetchedContent
        }

        return null
    }
}
