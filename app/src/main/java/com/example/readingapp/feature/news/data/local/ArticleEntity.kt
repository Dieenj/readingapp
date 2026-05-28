package com.example.readingapp.feature.news.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.readingapp.core.model.ReadableContent

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey
    override val id: String,

    override val title: String,

    val contentText: String,

    val summary: String,

    val fullContent: String? = null,

    override val source: String,
    override val category: String,

    override val imageUrl: String,

    override val url: String,

    override val publishedDate: Long,

    val addedDate: Long,

    val isRead: Boolean = false,

    override val language: String = "vi"
) : ReadableContent {

    override val content: String
        get() = fullContent?.takeIf { it.isNotBlank() } ?: contentText

    override val author: String?
        get() = null
}
