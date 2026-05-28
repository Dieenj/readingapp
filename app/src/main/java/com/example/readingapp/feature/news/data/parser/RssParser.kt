package com.example.readingapp.feature.news.data.parser

import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream

/**
 * Bộ phân tích cú pháp XML RSS Feed chuẩn hóa (RssChannel, RssItem).
 */
class RssParser {

    companion object {
        const val SUMMARY_MAX_LENGTH = 200
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun parse(inputStream: InputStream): RssParseResult {
        val rssItems = mutableListOf<RssItem>()
        var currentItem: RssItem? = null
        var currentText: String? = null

        var channelTitle = ""
        var channelLink = ""
        var channelDescription = ""
        var channelImage = ""

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var insideItem = false
            var insideChannel = true

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tagName.equals("item", ignoreCase = true) -> {
                                insideItem = true
                                insideChannel = false
                                currentItem = RssItem()
                            }
                            tagName.equals("channel", ignoreCase = true) -> {
                                insideChannel = true
                            }
                            insideItem -> {
                                when {
                                    tagName.equals("enclosure", ignoreCase = true) -> {
                                        val url = parser.getAttributeValue(null, "url")
                                        val type = parser.getAttributeValue(null, "type")
                                        if (url != null && url.isNotEmpty() &&
                                            (type == null || type.startsWith("image/"))) {
                                            currentItem?.imageUrl = url
                                        }
                                    }
                                    tagName.equals("media:content", ignoreCase = true) -> {
                                        val url = parser.getAttributeValue(null, "url")
                                        val medium = parser.getAttributeValue(null, "medium")
                                        val type = parser.getAttributeValue(null, "type")
                                        if (url != null && url.isNotEmpty() &&
                                            (medium == "image" || type?.startsWith("image/") == true || medium == null)) {
                                            currentItem?.imageUrl = url
                                        }
                                    }
                                    tagName.equals("media:thumbnail", ignoreCase = true) -> {
                                        val url = parser.getAttributeValue(null, "url")
                                        if (url != null && url.isNotEmpty()) {
                                            currentItem?.imageUrl = url
                                        }
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        currentText = parser.text
                    }

                    XmlPullParser.END_TAG -> {
                        when {
                            tagName.equals("item", ignoreCase = true) -> {
                                currentItem?.let {
                                    if (it.title.isNotEmpty()) rssItems.add(it)
                                }
                                insideItem = false
                            }
                            insideChannel && !insideItem -> {
                                when {
                                    tagName.equals("title", ignoreCase = true) -> channelTitle = currentText?.trim() ?: ""
                                    tagName.equals("link", ignoreCase = true) -> channelLink = currentText?.trim() ?: ""
                                    tagName.equals("description", ignoreCase = true) -> channelDescription = currentText?.trim() ?: ""
                                    tagName.equals("url", ignoreCase = true) -> channelImage = currentText?.trim() ?: ""
                                }
                            }
                            insideItem -> {
                                when {
                                    tagName.equals("title", ignoreCase = true) -> {
                                        val rawTitle = currentText?.trim() ?: ""
                                        currentItem?.title = if (rawTitle.contains("&")) stripHtml(rawTitle) else rawTitle
                                    }
                                    tagName.equals("link", ignoreCase = true) -> currentItem?.link = currentText?.trim() ?: ""
                                    tagName.equals("description", ignoreCase = true) -> {
                                        val desc = currentText?.trim() ?: ""
                                        currentItem?.description = desc
                                        if (currentItem?.imageUrl.isNullOrEmpty()) {
                                            extractImageUrlFromHtml(desc)?.let { currentItem?.imageUrl = it }
                                        }
                                    }
                                    tagName.equals("pubDate", ignoreCase = true) -> currentItem?.pubDate = currentText?.trim() ?: ""
                                    tagName.equals("category", ignoreCase = true) -> currentItem?.category = currentText?.trim() ?: ""
                                    tagName.equals("guid", ignoreCase = true) -> currentItem?.guid = currentText?.trim() ?: ""
                                    tagName.equals("author", ignoreCase = true) ||
                                    tagName.equals("dc:creator", ignoreCase = true) -> currentItem?.author = currentText?.trim() ?: ""
                                    tagName.equals("image", ignoreCase = true) -> {
                                        currentText?.trim()?.let { url ->
                                            if (url.startsWith("http") && currentItem?.imageUrl.isNullOrEmpty()) {
                                                currentItem?.imageUrl = url
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                eventType = parser.next()
            }
        } catch (e: Exception) {
            android.util.Log.e("RssParser", "Parse error: ${e.message}", e)
            throw e
        }

        return RssParseResult(channelTitle, channelLink, channelDescription, channelImage, rssItems.toList())
    }

    private fun stripHtml(html: String?): String {
        if (html == null) return ""
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun extractImageUrlFromHtml(html: String?): String? {
        if (html.isNullOrEmpty()) return null
        val patterns = listOf(
            Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("<img[^>]+src=([^\\s>]+)", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"'<>]+\\.(jpg|jpeg|png|gif|webp)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val url = match.groups[1]?.value ?: match.value
                if (url.startsWith("http")) return url
            }
        }
        return null
    }
}

data class RssItem(
    var title: String = "",
    var link: String = "",
    var description: String = "",
    var pubDate: String = "",
    var category: String = "",
    var guid: String = "",
    var author: String = "",
    var imageUrl: String = ""
) {
    fun getCleanDescription(): String {
        if (description.isEmpty()) return ""
        return Html.fromHtml(description, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")
            .replace("\uFEFF", "").replace("\u00A0", " ")
            .replace("\\s+".toRegex(), " ").trim()
    }

    fun getSummary(): String {
        val cleanDesc = getCleanDescription()
        return if (cleanDesc.length > RssParser.SUMMARY_MAX_LENGTH) {
            cleanDesc.substring(0, RssParser.SUMMARY_MAX_LENGTH) + "..."
        } else cleanDesc
    }
}

data class RssParseResult(
    val channelTitle: String,
    val channelLink: String,
    val channelDescription: String,
    val channelImage: String,
    val items: List<RssItem>
)
