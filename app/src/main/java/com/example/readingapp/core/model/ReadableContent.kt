package com.example.readingapp.core.model

interface ReadableContent {
    val id: String
    val title: String
    val content: String
    val author: String?
    val publishedDate: Long
    val source: String
    val url: String?
    val language: String
    val category: String
    val imageUrl: String?
}

data class ReadableContentData(
    override val id: String,
    override val title: String,
    override val content: String,
    override val author: String? = null,
    override val publishedDate: Long,
    override val source: String,
    override val url: String? = null,
    override val language: String = "vi",
    override val category: String = "",
    override val imageUrl: String? = null
) : ReadableContent
