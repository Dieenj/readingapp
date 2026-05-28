package com.example.readingapp.feature.news.worker

import android.content.Context
import androidx.work.*
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.local.NewsDatabase
import com.example.readingapp.feature.news.data.remote.NewsFeedFetcher
import com.example.readingapp.core.datastore.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

class NewsBackgroundSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "news_background_sync_work"
        private const val TIMEOUT_MILLIS = 300_000L

        fun schedulePeriodicRefresh(context: Context) {
            try {
                val settingsRepository = AppSettingsRepository.getInstance(context.applicationContext)
                val sourceName = settingsRepository.getSource()
                val categoryName = settingsRepository.getCategory()

                if (sourceName.isNullOrBlank() || categoryName.isNullOrBlank()) return

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val refreshRequest = PeriodicWorkRequestBuilder<NewsBackgroundSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    refreshRequest
                )
            } catch (e: Exception) {
                android.util.Log.e("NewsBackgroundSyncWorker", "Failed to schedule periodic refresh", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            withTimeout(TIMEOUT_MILLIS) { refreshNews() }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("NewsBackgroundSyncWorker", "Failed to refresh news", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun refreshNews() {
        val settingsRepository = AppSettingsRepository.getInstance(applicationContext)
        val sourceName = settingsRepository.getSource()
        val categoryName = settingsRepository.getCategory()

        if (sourceName != null && categoryName != null) {
            val database = NewsDatabase.getDatabase(applicationContext)
            val repository = ArticleRepository(database.articleDao(), database)

            val articles = NewsFeedFetcher.fetchFeedsByCategory(sourceName, categoryName)
            if (articles.isNotEmpty()) {
                repository.upsertArticlesWithCleanup(articles)
                optimizeLatestArticles(repository)
            }
        }
    }

    private suspend fun optimizeLatestArticles(repository: ArticleRepository) {
        val settingsRepository = AppSettingsRepository.getInstance(applicationContext)
        val latestArticles = repository.getArticlesBySourceAndCategorySync(
            settingsRepository.getSource() ?: "",
            settingsRepository.getCategory() ?: ""
        )

        val articlesToOptimize = latestArticles.take(5)

        coroutineScope {
            articlesToOptimize.map { article ->
                async(Dispatchers.IO) {
                    try {
                        repository.getOrFetchFullContent(article)
                    } catch (e: Exception) {
                        android.util.Log.d("NewsBackgroundSyncWorker", "Failed to optimize article: ${article.id}", e)
                    }
                }
            }.awaitAll()
        }
    }
}
