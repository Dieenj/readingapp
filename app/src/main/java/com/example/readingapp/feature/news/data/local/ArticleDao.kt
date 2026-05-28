package com.example.readingapp.feature.news.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY publishedDate DESC")
    fun getAllArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE source = :source AND category = :category ORDER BY publishedDate DESC")
    fun getArticlesBySourceAndCategory(source: String, category: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE source = :source AND category = :category ORDER BY publishedDate DESC")
    suspend fun getArticlesBySourceAndCategorySync(source: String, category: String): List<ArticleEntity>

    @Query("SELECT * FROM articles ORDER BY publishedDate DESC LIMIT :limit")
    suspend fun getAllArticlesList(limit: Int = 50): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :articleId LIMIT 1")
    suspend fun getArticleById(articleId: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id IN (:articleIds)")
    suspend fun getArticlesByIds(articleIds: List<String>): List<ArticleEntity>

    @Query("""
        SELECT * FROM articles 
        WHERE source = :source AND category = :category 
          AND (publishedDate < :currentDate OR (publishedDate = :currentDate AND id < :articleId))
        ORDER BY publishedDate DESC, id DESC LIMIT 1
    """)
    suspend fun getNextArticleBySourceAndCategory(
        source: String, category: String, currentDate: Long, articleId: String
    ): ArticleEntity?

    @Query("""
        SELECT * FROM articles 
        WHERE source = :source AND category = :category 
          AND (publishedDate < :currentDate OR (publishedDate = :currentDate AND id < :articleId))
        ORDER BY publishedDate DESC, id DESC LIMIT :limit
    """)
    suspend fun getNextArticlesBySourceAndCategory(
        source: String, category: String, currentDate: Long, articleId: String, limit: Int
    ): List<ArticleEntity>

    @Query("""
        SELECT * FROM articles 
        WHERE source = :source AND category = :category 
          AND (publishedDate > :currentDate OR (publishedDate = :currentDate AND id > :articleId))
        ORDER BY publishedDate ASC, id ASC LIMIT 1
    """)
    suspend fun getPreviousArticleBySourceAndCategory(
        source: String, category: String, currentDate: Long, articleId: String
    ): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Query("UPDATE articles SET isRead = :isRead WHERE id = :articleId")
    suspend fun updateReadStatus(articleId: String, isRead: Boolean)

    @Query("UPDATE articles SET fullContent = :fullContent WHERE id = :articleId AND (fullContent IS NULL OR fullContent = '')")
    suspend fun updateFullContentIfEmpty(articleId: String, fullContent: String): Int

    @Query("DELETE FROM articles WHERE publishedDate < :timestamp")
    suspend fun deleteArticlesOlderThan(timestamp: Long)
}
