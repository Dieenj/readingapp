package com.example.readingapp.feature.news.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.readingapp.R
import com.example.readingapp.media.ReadingMediaService
import com.example.readingapp.feature.news.data.ArticleRepository
import com.example.readingapp.feature.news.data.local.ArticleEntity
import com.example.readingapp.feature.news.data.local.NewsDatabase
import com.example.readingapp.feature.news.data.remote.NewsSourceConfig
import com.example.readingapp.feature.news.worker.NewsBackgroundSyncWorker
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NewsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var sourceNameText: TextView
    private lateinit var selectSourceButton: Button
    private lateinit var refreshButton: ImageButton
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArticleAdapter
    private lateinit var viewModel: NewsViewModel

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private var articlesCollectJob: Job? = null

    private val settingsRepository by lazy {
        com.example.readingapp.core.datastore.AppSettingsRepository.getInstance(requireContext())
    }

    // Tạo view cho fragment
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_news, container, false)
    }

    // Khởi tạo fragment
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // Khởi tạo các thành phần giao diện và dữ liệu sau khi view đã tạo
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ViewModelProvider.Factory đảm bảo ViewModel sống sót qua xoay màn hình
        val database = NewsDatabase.getDatabase(requireContext().applicationContext)
        val repository = ArticleRepository(database.articleDao(), database)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NewsViewModel(repository, settingsRepository) as T
        }
        viewModel = ViewModelProvider(this, factory)[NewsViewModel::class.java]

        setupToolbar(view.findViewById(R.id.toolbar))

        recyclerView = view.findViewById(R.id.recyclerView)
        sourceNameText = view.findViewById(R.id.textSourceName)
        selectSourceButton = view.findViewById(R.id.btnSelectSource)
        refreshButton = view.findViewById(R.id.btnRefresh)
        emptyView = view.findViewById(R.id.textEmpty)

        setupRecyclerView()
        setupButtons()
        setupObservers()
        initializeMediaController()

        val savedSource = settingsRepository.getSource()
        val savedCategory = settingsRepository.getCategory()
        if (savedSource != null && savedCategory != null) {
            viewModel.restoreSavedState(savedSource, savedCategory)
            sourceNameText.text = savedSource
            selectSourceButton.text = savedCategory
            refreshButton.visibility = View.VISIBLE
            NewsBackgroundSyncWorker.schedulePeriodicRefresh(requireContext())

            loadArticles()

            lifecycleScope.launch {
                try {
                    viewModel.fetchAndUpsertArticles(savedSource, savedCategory)
                } catch (e: Exception) {
                    android.util.Log.e("NewsFragment", "Background refresh failed", e)
                    val rootView = view
                    com.google.android.material.snackbar.Snackbar
                        .make(rootView, "Không thể tải tin tức. Kiểm tra kết nối mạng.", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction("Thử lại") {
                            lifecycleScope.launch {
                                try { viewModel.fetchAndUpsertArticles(savedSource, savedCategory) }
                                catch (_: Exception) {}
                            }
                        }
                        .show()
                }
            }
        } else {
            loadArticles()
            showSourceSelectionDialog()
        }

        setupMenu()
    }

    // Thiết lập Menu sử dụng MenuProvider (thay thế cho các hàm deprecated)
    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        startActivity(Intent(requireContext(), com.example.readingapp.feature.settings.ui.SettingsActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    // Thiết lập thanh công cụ (Toolbar)
    private fun setupToolbar(toolbar: Toolbar) {
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.title = getString(R.string.read_news_title)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        activity.invalidateOptionsMenu()
    }

    // Thiết lập danh sách hiển thị bài báo (RecyclerView)
    private fun setupRecyclerView() {
        adapter = ArticleAdapter(onItemClick = { article -> startReading(article) })
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                adapter.setClickable(newState == RecyclerView.SCROLL_STATE_IDLE)
            }
        })
    }

    // Gán sự kiện cho các nút bấm chọn nguồn và làm mới
    private fun setupButtons() {
        sourceNameText.setOnClickListener {
            if (viewModel.isSourceSelectionLoading.value) return@setOnClickListener
            if (viewModel.isPlaying.value) {
                controller?.stop()
                viewModel.updatePlayingArticleId(null)
            }
            showSourceSelectionDialog()
        }

        selectSourceButton.setOnClickListener {
            if (viewModel.isSourceSelectionLoading.value) return@setOnClickListener
            if (viewModel.isPlaying.value) {
                controller?.stop()
                viewModel.updatePlayingArticleId(null)
            }
            val currentSource = viewModel.currentSource.value
            if (currentSource != null) showCategorySelectionDialog(currentSource)
            else showSourceSelectionDialog()
        }

        refreshButton.setOnClickListener {
            if (viewModel.isRefreshLoading.value || viewModel.isSourceSelectionLoading.value) return@setOnClickListener
            if (viewModel.isPlaying.value) {
                controller?.stop()
                viewModel.updatePlayingArticleId(null)
            }
            viewModel.setShouldScrollToTop(true)
            viewModel.refreshCurrentCategory()
        }
    }

    // Theo dõi các luồng dữ liệu từ ViewModel để cập nhật giao diện
    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.isRefreshLoading.collectLatest { isLoading ->
                refreshButton.alpha = if (isLoading) 0.5f else 1.0f
            }
        }

        lifecycleScope.launch {
            viewModel.currentSource.collectLatest { source ->
                sourceNameText.text = source ?: ""
                refreshButton.visibility = if (source != null) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.currentCategory.collectLatest { category ->
                selectSourceButton.text = category ?: getString(R.string.btn_select_source)
            }
        }

        lifecycleScope.launch {
            viewModel.currentPlayingArticleId.collectLatest { articleId ->
                adapter.updatePlayingState(articleId, viewModel.isPlaying.value)
            }
        }

        lifecycleScope.launch {
            viewModel.isPlaying.collectLatest { playing ->
                adapter.updatePlayingState(viewModel.currentPlayingArticleId.value, playing)
            }
        }
    }

    // Bắt đầu thu thập dữ liệu bài báo từ ViewModel
    private fun loadArticles() {
        articlesCollectJob?.cancel()

        articlesCollectJob = lifecycleScope.launch {
            viewModel.getArticlesFlow().collectLatest { articles ->
                if (articles.isEmpty()) {
                    emptyView.text = if (viewModel.isRefreshLoading.value || viewModel.isSourceSelectionLoading.value) {
                        getString(R.string.loading_news)
                    } else if (viewModel.currentSource.value != null) {
                        "Không có bài báo nào. Hãy nhấn nút làm mới."
                    } else {
                        getString(R.string.empty_articles)
                    }
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.submitList(articles)

                    // UX BUG 7: Re-apply playing state sau khi DiffUtil hoàn tất
                    val currentId = viewModel.currentPlayingArticleId.value
                    if (currentId != null) {
                        adapter.updatePlayingState(currentId, viewModel.isPlaying.value)
                    }

                    if (viewModel.shouldScrollToTop.value) {
                        recyclerView.post { recyclerView.scrollToPosition(0) }
                        viewModel.setShouldScrollToTop(false)
                    }
                }
            }
        }
    }

    // Hiển thị trạng thái đang nạp dữ liệu trên giao diện
    private fun showLoadingState() {
        articlesCollectJob?.cancel()
        adapter.submitList(emptyList())
        emptyView.text = getString(R.string.loading)
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    // Hiển thị hộp thoại chọn nguồn tin tức
    private fun showSourceSelectionDialog() {
        val sources = NewsSourceConfig.NEWS_SOURCES.keys.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Chọn nguồn tin / Select Source")
            .setItems(sources) { _, which ->
                if (viewModel.isSourceSelectionLoading.value) return@setItems
                showCategorySelectionDialog(sources[which])
            }
            .setCancelable(viewModel.currentSource.value != null && viewModel.currentCategory.value != null)
            .show()
    }

    // Hiển thị hộp thoại chọn chủ đề (danh mục) của nguồn tin
    private fun showCategorySelectionDialog(sourceName: String) {
        val categories = NewsSourceConfig.NEWS_SOURCES[sourceName]?.keys?.toList() ?: emptyList()
        if (categories.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle("Chọn chủ đề - $sourceName")
            .setItems(categories.toTypedArray()) { _, which ->
                if (viewModel.isSourceSelectionLoading.value) return@setItems
                viewModel.setSourceSelectionLoading(true)
                val categoryName = categories[which]

                // Không gọi settingsRepository.saveSourceAndCategory() ở đây vì
                // viewModel.loadCategory() đã gọi bên trong để tránh duplicate
                NewsBackgroundSyncWorker.schedulePeriodicRefresh(requireContext())
                viewModel.setShouldScrollToTop(true)
                showLoadingState()
                viewModel.loadCategory(sourceName, categoryName)
                loadArticles()
            }
            .setNegativeButton("Quay lại") { _, _ -> showSourceSelectionDialog() }
            .setCancelable(false)
            .show()
    }

    // Khởi tạo MediaController để điều khiển phát tin tức
    private fun initializeMediaController() {
        val sessionToken = SessionToken(
            requireContext(),
            ComponentName(requireContext(), ReadingMediaService::class.java)
        )

        controllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()
        controllerFuture?.addListener({ setupPlayerListener() }, MoreExecutors.directExecutor())
    }

    // Thiết lập bộ lắng nghe sự kiện từ trình phát media
    private fun setupPlayerListener() {
        val mediaController = controller ?: return
        
        // UX BUG 2 & 6: Đồng bộ lại trạng thái từ MediaController ngay khi kết nối thành công (giúp xử lý khi xoay màn hình)
        val isBuffering = mediaController.playbackState == Player.STATE_BUFFERING
        viewModel.setMediaControlLoading(isBuffering)
        
        val currentMediaId = mediaController.currentMediaItem?.mediaId
        if (!currentMediaId.isNullOrEmpty()) {
            syncPlaybackUi(currentMediaId)
        }

        mediaController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { syncPlaybackUi() }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // UX BUG 1: Clear trạng thái bài cũ rõ ràng khi chuyển bài tự động
                val oldId = viewModel.currentPlayingArticleId.value
                if (oldId != null && oldId != mediaItem?.mediaId) {
                    adapter.updatePlayingState(null, false)
                }
                syncPlaybackUi(mediaItem?.mediaId)
            }
            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) { syncPlaybackUi() }
            override fun onPlaybackStateChanged(playbackState: Int) { syncPlaybackUi() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { clearCurrentPlayingArticle() }
        })
    }    // Đồng bộ hóa trạng thái giao diện với trạng thái phát nhạc thực tế
    private fun syncPlaybackUi(articleIdOverride: String? = null) {
        val mediaController = controller ?: return
        val playbackState = mediaController.playbackState

        viewModel.setMediaControlLoading(playbackState == Player.STATE_BUFFERING)

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            clearCurrentPlayingArticle()
            return
        }

        val rawMediaId = articleIdOverride ?: mediaController.currentMediaItem?.mediaId
        if (rawMediaId.isNullOrEmpty()) return

        val articleId = when {
            rawMediaId.contains("_chunk_") -> rawMediaId.split("_chunk_")[0]
            rawMediaId.contains("_seeking") -> rawMediaId.split("_seeking")[0]
            else -> rawMediaId
        }

        lifecycleScope.launch {
            val currentArticles = viewModel.getArticlesSync()
            var matchingArticle = currentArticles.find { it.id == articleId }

            if (matchingArticle == null) {
                val dbArticle = viewModel.getArticleById(articleId)
                if (dbArticle != null) {
                    viewModel.updateCategorySilently(dbArticle.source, dbArticle.category)
                    loadArticles()
                    matchingArticle = dbArticle
                }
            }

            matchingArticle?.let { article ->
                focusArticle(article.id)
                val isPlaying = mediaController.isPlaying
                viewModel.updateIsPlaying(isPlaying)
                adapter.updatePlayingState(article.id, isPlaying)
            }
        }
    }

    // Xóa trạng thái bài báo đang phát hiện tại trên giao diện
    private fun clearCurrentPlayingArticle() {
        viewModel.updatePlayingArticleId(null, false)
        viewModel.setMediaControlLoading(false)
        adapter.updatePlayingState(null, false)
    }

    // Bắt đầu quy trình đọc bài báo qua trình phát
    private fun startReading(article: ArticleEntity) {
        val mediaController = controller
        if (mediaController == null) {
            android.widget.Toast.makeText(requireContext(), "Đang khởi động dịch vụ phát...", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.isMediaControlLoading.value) return

        val currentPlayingId = viewModel.currentPlayingArticleId.value
        val isPlaying = viewModel.isPlaying.value

        if (currentPlayingId == article.id && isPlaying) {
            viewModel.setMediaControlLoading(true)
            mediaController.pause()
        } else {
            // UX BUG 3: Clear indicator bài cũ NGAY trước khi bắt đầu bài mới để tránh giật/nháy indicator
            if (currentPlayingId != null && currentPlayingId != article.id) {
                adapter.updatePlayingState(null, false)
                viewModel.updatePlayingArticleId(null, false)
            }
            if (isPlaying || currentPlayingId != null) mediaController.stop()
            viewModel.setMediaControlLoading(true)
            focusArticle(article.id)

            val args = Bundle().apply {
                putString(ReadingMediaService.EXTRA_ARTICLE_ID, article.id)
            }
            mediaController.sendCustomCommand(
                androidx.media3.session.SessionCommand(ReadingMediaService.CUSTOM_ACTION_PLAY_ARTICLE, Bundle.EMPTY),
                args
            )
        }
    }

    // Tập trung vào bài báo đang phát (cập nhật indicator và cuộn tới)
    private fun focusArticle(articleId: String) {
        val previousArticleId = viewModel.currentPlayingArticleId.value
        if (previousArticleId == articleId) return

        viewModel.updatePlayingArticleId(articleId)
        adapter.updatePlayingIndicator(articleId)

        val position = adapter.getArticlePosition(articleId)
        if (position != -1) scrollToArticle(position)
    }

    // Cuộn mượt mà danh sách tới vị trí bài báo cụ thể
    private fun scrollToArticle(position: Int) {
        val smoothScroller = object : LinearSmoothScroller(requireContext()) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics) = 100f / displayMetrics.densityDpi
        }
        smoothScroller.targetPosition = position
        recyclerView.layoutManager?.startSmoothScroll(smoothScroller)
    }

    // Giải phóng các tài nguyên khi view của fragment bị hủy
    override fun onDestroyView() {
        super.onDestroyView()
        articlesCollectJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}
