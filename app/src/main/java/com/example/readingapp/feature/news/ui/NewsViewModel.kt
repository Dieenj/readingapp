package com.example.readingapp.feature.news.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.local.ArticleEntity
import com.example.readingapp.feature.news.data.remote.NewsFeedFetcher
import com.example.readingapp.core.datastore.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class NewsViewModel(
    private val repository: ArticleRepository,
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {

    val currentSource: StateFlow<String?> = settingsRepository.currentSource

    val currentCategory: StateFlow<String?> = settingsRepository.currentCategory

    private val _isRefreshLoading = MutableStateFlow(false)
    val isRefreshLoading: StateFlow<Boolean> = _isRefreshLoading.asStateFlow()

    private val _isSourceSelectionLoading = MutableStateFlow(false)
    val isSourceSelectionLoading: StateFlow<Boolean> = _isSourceSelectionLoading.asStateFlow()

    private val _shouldScrollToTop = MutableStateFlow(false)
    val shouldScrollToTop: StateFlow<Boolean> = _shouldScrollToTop.asStateFlow()

    private val _currentPlayingArticleId = MutableStateFlow<String?>(null)
    val currentPlayingArticleId: StateFlow<String?> = _currentPlayingArticleId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isMediaControlLoading = MutableStateFlow(false)
    val isMediaControlLoading: StateFlow<Boolean> = _isMediaControlLoading.asStateFlow()

    private var categoryLoadJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    // Lấy luồng dữ liệu (Flow) các bài báo dựa trên nguồn và danh mục hiện tại
    fun getArticlesFlow(): Flow<List<ArticleEntity>> {
        return combine(currentSource, currentCategory) { source, category ->
            Pair(source, category)
        }.flatMapLatest { (source, category) ->
            if (source != null && category != null) {
                repository.getArticlesBySourceAndCategory(source, category)
            } else {
                repository.allArticles
            }
        }
    }

    // Nạp dữ liệu cho một danh mục cụ thể
    fun loadCategory(sourceName: String, categoryName: String) {
        categoryLoadJob?.cancel()

        categoryLoadJob = viewModelScope.launch {
            try {
                settingsRepository.saveSourceAndCategory(sourceName, categoryName)

                fetchAndUpsertArticles(sourceName, categoryName)

                _isRefreshLoading.value = false
                _isSourceSelectionLoading.value = false

                if (currentSource.value == sourceName && currentCategory.value == categoryName) {
                    _shouldScrollToTop.value = true
                }
            } catch (e: CancellationException) {
                _isRefreshLoading.value = false
                _isSourceSelectionLoading.value = false
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NewsViewModel", "Failed to load category", e)
                _isRefreshLoading.value = false
                _isSourceSelectionLoading.value = false
            }
        }
    }

    // Tải và cập nhật bài báo mới vào cơ sở dữ liệu
    suspend fun fetchAndUpsertArticles(sourceName: String, categoryName: String) {
        val articles = NewsFeedFetcher.fetchFeedsByCategory(sourceName, categoryName)
        if (articles.isNotEmpty()) repository.upsertArticlesWithCleanup(articles)
    }

    // Lấy danh sách bài báo đồng bộ
    suspend fun getArticlesSync(): List<ArticleEntity> {
        return if (currentSource.value != null && currentCategory.value != null) {
            repository.getArticlesBySourceAndCategorySync(currentSource.value!!, currentCategory.value!!)
        } else {
            repository.getAllArticlesSync()
        }
    }

    // Lấy thông tin bài báo theo ID
    suspend fun getArticleById(id: String): ArticleEntity? = repository.getArticleById(id)

    // Cập nhật danh mục mà không kích hoạt các hiệu ứng tải dữ liệu phức tạp
    fun updateCategorySilently(source: String, category: String) {
        settingsRepository.saveSourceAndCategory(source, category)
        _isRefreshLoading.value = false
        _isSourceSelectionLoading.value = false
        _shouldScrollToTop.value = true
    }

    // Cập nhật trạng thái đang phát nhạc
    fun updateIsPlaying(playing: Boolean) {
        _isPlaying.value = playing
        if (!playing) _isMediaControlLoading.value = false
    }

    // Cập nhật ID bài báo đang phát
    fun updatePlayingArticleId(articleId: String?, playing: Boolean = false) {
        _currentPlayingArticleId.value = articleId
        _isPlaying.value = playing
    }

    // Thiết lập trạng thái hiển thị nạp dữ liệu khi chọn nguồn
    fun setSourceSelectionLoading(loading: Boolean) { _isSourceSelectionLoading.value = loading }
    // Yêu cầu danh sách cuộn lên đầu trang
    fun setShouldScrollToTop(shouldScroll: Boolean) { _shouldScrollToTop.value = shouldScroll }
    // Thiết lập trạng thái hiển thị nạp dữ liệu cho các nút điều khiển media
    fun setMediaControlLoading(loading: Boolean) { _isMediaControlLoading.value = loading }

    // Khôi phục trạng thái nguồn và danh mục đã lưu
    fun restoreSavedState(source: String, category: String) {
        settingsRepository.saveSourceAndCategory(source, category)
    }

    // Làm mới nội dung của danh mục hiện tại
    fun refreshCurrentCategory() {
        val source = currentSource.value
        val category = currentCategory.value
        if (source != null && category != null) {
            _isRefreshLoading.value = true
            _shouldScrollToTop.value = true
            loadCategory(source, category)
        }
    }

    // Hủy các tiến trình khi ViewModel bị giải phóng
    override fun onCleared() {
        super.onCleared()
        categoryLoadJob?.cancel()
    }
}
