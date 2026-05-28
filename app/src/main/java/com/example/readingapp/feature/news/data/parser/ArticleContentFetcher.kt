package com.example.readingapp.feature.news.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

class ArticleContentFetcher {

    companion object {
        private const val TIMEOUT = 10000
        private const val MAX_RETRIES = 2
        private const val MIN_CONTENT_LENGTH = 100
        private const val MIN_FALLBACK_LENGTH = 200
        private const val MIN_PARAGRAPH_LENGTH = 20

        private val CONTENT_SELECTORS = mapOf(
            "vnexpress.net" to "article.fck_detail p.Normal, article.fck_detail p, section.section.page-detail.top-detail p, article p",
            "tuoitre.vn" to "div.detail-content p, div.main-detail p, div.main-detail-body p, div[data-role='content'] p, article p",
            "thanhnien.vn" to "div.detail-content-body p, div.detail__cmain p, div.detail-content p, article p",
            "dantri.com.vn" to "article.singular-content p, div.singular-content p, div.dt-news__body p, article p",
            "theguardian.com" to "div[class*='article-body'] p, article p, div#maincontent p, div.content__article-body p"
        )

        private val NOISE_PATTERNS = listOf(
            Regex("^Xem thêm", RegexOption.IGNORE_CASE),
            Regex("^Mời bạn đọc", RegexOption.IGNORE_CASE),
            Regex("^Theo dõi", RegexOption.IGNORE_CASE),
            Regex("^Đọc thêm", RegexOption.IGNORE_CASE),
            Regex("^>>"),
            Regex("^\\s*Advertisement\\s*$", RegexOption.IGNORE_CASE)
        )
    }

    suspend fun fetchFullContent(url: String): String? = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val doc = Jsoup.connect(url)
                    .timeout(TIMEOUT)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .get()

                val source = extractSourceFromUrl(url)
                val selector = CONTENT_SELECTORS[source]

                if (selector != null) {
                    val paragraphs = doc.select(selector)
                    if (paragraphs.isNotEmpty()) {
                        val content = buildContent(paragraphs.map { it.text() })
                        if (content.length >= MIN_CONTENT_LENGTH) return@withContext content
                    }
                }

                val fallbackContent = fallbackExtract(doc)
                if (fallbackContent != null) return@withContext fallbackContent

                if (attempt < MAX_RETRIES - 1) kotlinx.coroutines.delay(1000)

            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) kotlinx.coroutines.delay(2000)
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) kotlinx.coroutines.delay(1000)
            } catch (e: Exception) {
                android.util.Log.w("ArticleContentFetcher", "Failed to fetch content from $url (attempt $attempt)", e)
                return@withContext null
            }
        }
        null
    }

    private fun extractSourceFromUrl(url: String): String {
        return try {
            val host = URL(url).host.lowercase()
            CONTENT_SELECTORS.keys.find { host.contains(it) } ?: ""
        } catch (e: Exception) {
            android.util.Log.d("ArticleContentFetcher", "Failed to extract source from URL: $url", e)
            ""
        }
    }

    private fun fallbackExtract(doc: Document): String? {
        return try {
            val fallbackSelectors = listOf(
                "article p", "main p", "div.content p", "div.article-content p",
                "div.post-content p", ".entry-content p", "div[class*='content'] p",
                "div[class*='article'] p", "div[class*='body'] p", "p"
            )

            for (selector in fallbackSelectors) {
                val paragraphs = doc.select(selector)
                if (paragraphs.isNotEmpty()) {
                    val content = buildContent(paragraphs.map { it.text() })
                    if (content.length >= MIN_FALLBACK_LENGTH) return content
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.d("ArticleContentFetcher", "Fallback extraction failed", e)
            null
        }
    }

    suspend fun fetchOgImage(url: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val doc = Jsoup.connect(url).timeout(TIMEOUT)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .followRedirects(true).ignoreHttpErrors(true).get()

            val imageSelectors = listOf(
                "meta[property=og:image]", "meta[name=og:image]",
                "meta[property=twitter:image]", "meta[name=twitter:image]",
                "meta[itemprop=image]"
            )

            for (selector in imageSelectors) {
                val element = doc.selectFirst(selector)
                val imageUrl = element?.attr("content") ?: element?.attr("href")
                if (!imageUrl.isNullOrEmpty() && imageUrl.startsWith("http")) return@withContext imageUrl
            }
            null
        } catch (e: Exception) {
            android.util.Log.d("ArticleContentFetcher", "Failed to fetch OG image from $url", e)
            null
        }
    }

    private fun buildContent(paragraphs: List<String>): String {
        return paragraphs.asSequence()
            .map { it.replace("\u00A0", " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > MIN_PARAGRAPH_LENGTH }
            .filterNot { paragraph -> NOISE_PATTERNS.any { it.containsMatchIn(paragraph) } }
            .distinct()
            .joinToString("\n\n")
    }
}
