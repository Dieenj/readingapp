package com.example.readingapp.feature.news.data.remote

import com.example.readingapp.feature.news.data.local.ArticleEntity
import com.example.readingapp.feature.news.data.parser.ArticleContentFetcher
import com.example.readingapp.feature.news.data.parser.RssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Phụ trách kết nối mạng HTTP, fetch XML feed, gọi RssParser, và enhance ảnh OG.
 */
object NewsFeedFetcher {
    private val rssParser = RssParser()
    private val contentFetcher = ArticleContentFetcher()
    private val ogImageSemaphore = Semaphore(5)

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
    )

    private fun parsePubDate(pubDate: String): Long {
        if (pubDate.isEmpty()) return System.currentTimeMillis()
        for (format in dateFormats) {
            try {
                return format.parse(pubDate)?.time ?: continue
            } catch (_: Exception) {
                // Bỏ qua định dạng không khớp, thử định dạng tiếp theo
            }
        }
        return System.currentTimeMillis()
    }

    private fun extractSourceFromUrl(feedUrl: String): String =
        NewsSourceConfig.URL_TO_SOURCE.entries.find { feedUrl.contains(it.key) }?.value
            ?: try {
                URL(feedUrl).host.split(".")[0]
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } catch (e: Exception) {
                android.util.Log.w("NewsFeedFetcher", "Failed to extract source from $feedUrl", e)
                "Unknown"
            }

    suspend fun fetchNewsFeed(feedUrl: String, categoryName: String = "Uncategorized"): List<ArticleEntity> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(feedUrl).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val inputStream = connection.getInputStream()
                val result = rssParser.parse(inputStream)
                inputStream.close()

                val sourceName = extractSourceFromUrl(feedUrl)
                val language = NewsSourceConfig.getSourceLanguage(sourceName)

                val articles = result.items.mapNotNull { rssItem ->
                    try {
                        val guid = rssItem.guid.ifEmpty { UUID.randomUUID().toString() }
                        ArticleEntity(
                            id = "${sourceName}_${categoryName}_${guid}",
                            title = rssItem.title,
                            contentText = rssItem.getCleanDescription(),
                            summary = rssItem.getSummary(),
                            source = sourceName,
                            category = categoryName,
                            imageUrl = rssItem.imageUrl,
                            url = rssItem.link,
                            publishedDate = parsePubDate(rssItem.pubDate),
                            addedDate = System.currentTimeMillis(),
                            language = language
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("NewsFeedFetcher", "Failed to parse article item", e)
                        null
                    }
                }

                enhanceArticlesWithOgImages(articles, sourceName)
            } catch (e: Exception) {
                android.util.Log.e("NewsFeedFetcher", "Failed to fetch News feed from $feedUrl", e)
                emptyList()
            }
        }

    suspend fun fetchFeedsByCategory(sourceName: String, categoryName: String): List<ArticleEntity> =
        withContext(Dispatchers.IO) {
            val feedUrl = NewsSourceConfig.NEWS_SOURCES[sourceName]?.get(categoryName) ?: return@withContext emptyList()
            fetchNewsFeed(feedUrl, categoryName)
        }

    private suspend fun enhanceArticlesWithOgImages(
        articles: List<ArticleEntity>,
        sourceName: String
    ): List<ArticleEntity> = withContext(Dispatchers.IO) {
        if (sourceName !in NewsSourceConfig.SOURCES_NEEDING_OG_IMAGE) return@withContext articles

        articles.map { article ->
            async {
                if (article.imageUrl.isEmpty()) {
                    ogImageSemaphore.withPermit {
                        try {
                            val ogImage = contentFetcher.fetchOgImage(article.url)
                            ogImage?.let { article.copy(imageUrl = it) } ?: article
                        } catch (e: Exception) {
                            android.util.Log.d("NewsFeedFetcher", "Failed to fetch OG image for ${article.url}", e)
                            article
                        }
                    }
                } else {
                    article
                }
            }
        }.map { it.await() }
    }
}
