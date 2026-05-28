package com.example.readingapp.feature.email.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.example.readingapp.R
import com.example.readingapp.feature.email.data.local.EmailEntity
import com.example.readingapp.media.ReadingMediaService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment cho tính năng đọc Email.
 *
 * Quy trình:
 *  1. Người dùng nhập thông tin xác thực IMAP (email + mật khẩu ứng dụng) → lưu vào bộ nhớ.
 *  2. ImapStoreManager và ImapFolderFetcher tải danh sách hộp thư qua JavaMail.
 *  3. Khi nhấn vào mục → tải nội dung đầy đủ → gửi lệnh PLAY_ARTICLE tới ReadingMediaService.
 *  4. ReadingMediaService nhận EmailEntity → AudioPlayer → TTS → ExoPlayer.
 *
 * Lưu ý: EmailEntity triển khai ReadableContent, nên nó hoạt động với pipeline của
 * ReadingMediaService mà không cần thay đổi gì ở lớp core hoặc service.
 *
 * TODO:
 *  - Thay thế username/password bằng OAuth2 (Gmail XOAUTH2).
 *  - Thêm bộ chọn thư mục (Đã gửi, Nháp, v.v.).
 *  - Thêm tính năng vuốt để làm mới.
 */
class EmailFragment : Fragment() {

    private lateinit var viewModel: EmailViewModel
    private lateinit var adapter: EmailAdapter

    // Views
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnLogout: android.widget.ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var cardAccountFolder: View
    private lateinit var textAccountName: TextView
    private lateinit var btnSelectFolder: Button
    private lateinit var btnRefresh: android.widget.ImageButton

    // Media3 controller (same service as News)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    // Tạo view cho fragment
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_email, container, false)
    }

    // Khởi tạo các thành phần giao diện và dữ liệu sau khi view đã tạo
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = EmailViewModel(requireContext())

        setupToolbar(view.findViewById(R.id.toolbar))

        recyclerView      = view.findViewById(R.id.recyclerView)
        emptyView         = view.findViewById(R.id.textEmpty)
        btnLogin          = view.findViewById(R.id.btnLogin)
        btnLogout         = view.findViewById(R.id.btnLogout)
        progressBar       = view.findViewById(R.id.progressBar)
        cardAccountFolder = view.findViewById(R.id.cardAccountFolder)
        textAccountName   = view.findViewById(R.id.textAccountName)
        btnSelectFolder   = view.findViewById(R.id.btnSelectFolder)
        btnRefresh        = view.findViewById(R.id.btnRefresh)

        setupRecyclerView()
        setupButtons()
        observeState()
        initializeMediaController()
    }

    // Thiết lập thanh công cụ (Toolbar)
    private fun setupToolbar(toolbar: Toolbar) {
        (requireActivity() as AppCompatActivity).apply {
            setSupportActionBar(toolbar)
            supportActionBar?.title = "Đọc Email"
        }
        toolbar.setNavigationOnClickListener {
            // Nếu đang xem email trong folder, quay lại danh sách folder
            if (viewModel.uiState.value is EmailUiState.EmailList) {
                viewModel.backToFolderList()
            } else {
                parentFragmentManager.popBackStack()
            }
        }
    }


    // Thiết lập danh sách hiển thị email (RecyclerView)
    private fun setupRecyclerView() {
        adapter = EmailAdapter { email -> startReading(email) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                adapter.setClickable(newState == RecyclerView.SCROLL_STATE_IDLE)
            }
        })
    }

    // Gán sự kiện cho các nút bấm
    private fun setupButtons() {
        btnLogin.setOnClickListener { showLoginDialog() }

        btnRefresh.setOnClickListener {
            viewModel.refreshCurrentFolder()
        }

        // Nút chọn folder — mở lại danh sách folder (giống nút chọn category bên News)
        btnSelectFolder.setOnClickListener {
            viewModel.backToFolderList()
        }

        btnLogout.setOnClickListener {
            controller?.stop()
            viewModel.logout()
        }
    }

    // Theo dõi trạng thái từ ViewModel để cập nhật giao diện
    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val ab = (requireActivity() as? AppCompatActivity)?.supportActionBar
                ab?.setDisplayHomeAsUpEnabled(true)
                when (state) {
                    is EmailUiState.NotLoggedIn -> {
                        showLoading(false)
                        hideCard()
                        showEmpty("Đăng nhập để kết nối tài khoản email.")
                        btnLogin.visibility  = View.VISIBLE
                        btnLogout.visibility = View.GONE
                        ab?.subtitle = null
                    }
                    is EmailUiState.Loading -> {
                        showLoading(true)
                        btnLogin.visibility  = View.GONE
                        btnLogout.visibility = View.GONE
                    }
                    is EmailUiState.FolderList -> {
                        showLoading(false)
                        btnLogin.visibility  = View.GONE
                        btnLogout.visibility = View.VISIBLE
                        showCard(
                            account = viewModel.accountEmail,
                            folderLabel = "Chọn thư mục",
                            showRefresh = false
                        )
                        ab?.subtitle = viewModel.accountEmail
                        showEmpty("Vui lòng nhấn nút \"Chọn thư mục\" để bắt đầu đọc.")
                        showFolderSelectionDialog(state.folders)
                    }
                    is EmailUiState.EmailList -> {
                        showLoading(false)
                        btnLogin.visibility  = View.GONE
                        btnLogout.visibility = View.VISIBLE
                        val folderLabel = EmailEntity.folderDisplayName(state.folderName)
                        showCard(
                            account = viewModel.accountEmail,
                            folderLabel = folderLabel,
                            showRefresh = true
                        )
                        ab?.subtitle = folderLabel
                        // Đảm bảo EmailAdapter đang active
                        if (recyclerView.adapter !is EmailAdapter) {
                            recyclerView.adapter = adapter
                        }
                        if (state.emails.isEmpty()) {
                            showEmpty("Thư mục này không có email nào.")
                        } else {
                            showContent()
                            adapter.submitList(state.emails)
                        }
                    }
                    is EmailUiState.Error -> {
                        showLoading(false)
                        hideCard()
                        showEmpty("Lỗi: ${state.message}")
                        btnLogin.visibility  = View.VISIBLE
                        btnLogout.visibility = View.GONE
                        ab?.subtitle = null
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.currentPlayingId.collectLatest { id ->
                adapter.updatePlayingState(id, viewModel.isPlaying.value)
            }
        }

        lifecycleScope.launch {
            viewModel.isPlaying.collectLatest { playing ->
                adapter.updatePlayingState(viewModel.currentPlayingId.value, playing)
            }
        }
    }

    // Hiển thị ProgressBar, ẩn toàn bộ nội dung khác
    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            recyclerView.visibility = View.GONE
            emptyView.visibility    = View.GONE
            btnLogin.visibility     = View.GONE
        }
    }

    // Chỉ hiện RecyclerView, ẩn emptyView
    private fun showContent() {
        emptyView.visibility    = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    // Chỉ hiện emptyView, ẩn RecyclerView
    private fun showEmpty(msg: String) {
        emptyView.text          = msg
        emptyView.visibility    = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    // Hiện card header với thông tin tài khoản / folder
    private fun showCard(account: String, folderLabel: String, showRefresh: Boolean) {
        cardAccountFolder.visibility = View.VISIBLE
        textAccountName.text = account.ifBlank { "Email" }
        btnSelectFolder.text = folderLabel
        btnRefresh.visibility = if (showRefresh) View.VISIBLE else View.GONE
        btnLogout.visibility  = View.VISIBLE
    }

    // Ẩn card header (khi chưa đăng nhập hoặc lỗi)
    private fun hideCard() {
        cardAccountFolder.visibility = View.GONE
    }

    // Hiển thị hộp thoại chọn thư mục Email dưới dạng AlertDialog popup giống bên News
    private fun showFolderSelectionDialog(folders: List<String>) {
        val folderNames = folders.map { EmailEntity.folderDisplayName(it) }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Chọn thư mục Email")
            .setItems(folderNames) { _, which ->
                val selected = folders[which]
                viewModel.selectFolder(selected)
            }
            .setCancelable(viewModel.uiState.value !is EmailUiState.FolderList) // Chỉ cho phép cancel nếu đã ở trong một folder cụ thể trước đó
            .show()
    }




    // Login dialog

    // Hiển thị hộp thoại đăng nhập
    private fun showLoginDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_email_login, null)

        val etEmail    = dialogView.findViewById<EditText>(R.id.etEmail)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val tvAppPasswordHelp = dialogView.findViewById<TextView>(R.id.tvAppPasswordHelp)

        tvAppPasswordHelp.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://myaccount.google.com/apppasswords")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể mở trình duyệt.", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Đăng nhập Email (IMAP)")
            .setView(dialogView)
            .setPositiveButton("Đăng nhập") { _, _ ->
                val email = etEmail.text.toString().trim()
                val pass  = etPassword.text.toString()
                if (email.isBlank() || pass.isBlank()) {
                    Toast.makeText(requireContext(), "Vui lòng nhập đầy đủ thông tin.", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.login(email, pass)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Playback

    /**
     * Báo Service phát email theo ID — content sẽ được lazy-load trong EmailPlaylistProvider.
     */
    private fun startReading(email: EmailEntity) {
        val mc = controller
        if (mc == null) {
            Toast.makeText(requireContext(), "Đang khởi động dịch vụ phát…", Toast.LENGTH_SHORT).show()
            return
        }

        val isPlaying = viewModel.isPlaying.value
        val playingId = viewModel.currentPlayingId.value

        // Nếu đang phát đúng email này thì pause/resume
        if (playingId == email.id && isPlaying) {
            mc.pause()
            return
        }

        if (isPlaying || playingId != null) mc.stop()

        viewModel.updatePlayingState(email.id, false)

        // Chỉ gửi ID và loại nội dung sang Service — EmailPlaylistProvider sẽ tự lazy-load full body
        val args = Bundle().apply {
            putString(ReadingMediaService.EXTRA_ARTICLE_ID, email.id)
            putString(
                com.example.readingapp.media.playback.session.MediaSessionHandler.EXTRA_CONTENT_TYPE,
                com.example.readingapp.media.playback.session.MediaSessionHandler.CONTENT_TYPE_EMAIL
            )
        }
        mc.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                ReadingMediaService.CUSTOM_ACTION_PLAY_ARTICLE,
                Bundle.EMPTY
            ),
            args
        )
    }

    // MediaController

    // Khởi tạo MediaController để điều khiển phát nhạc
    private fun initializeMediaController() {
        val token = SessionToken(
            requireContext(),
            ComponentName(requireContext(), ReadingMediaService::class.java)
        )
        controllerFuture = MediaController.Builder(requireContext(), token).buildAsync()
        controllerFuture?.addListener({ setupPlayerListener() }, MoreExecutors.directExecutor())
    }

    // Thiết lập bộ lắng nghe sự kiện từ trình phát
    private fun setupPlayerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { syncPlaybackUi() }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                syncPlaybackUi(mediaItem?.mediaId)
            }
            override fun onPlaybackStateChanged(state: Int) { syncPlaybackUi() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                viewModel.clearPlayingState()
            }
        })
    }

    private fun syncPlaybackUi(articleIdOverride: String? = null) {
        val mediaController = controller ?: return
        val playbackState = mediaController.playbackState

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            viewModel.clearPlayingState()
            return
        }

        val rawMediaId = articleIdOverride ?: mediaController.currentMediaItem?.mediaId
        if (rawMediaId.isNullOrEmpty()) return

        val articleId = when {
            rawMediaId.contains("_chunk_") -> rawMediaId.split("_chunk_")[0]
            rawMediaId.contains("_seeking") -> rawMediaId.split("_seeking")[0]
            else -> rawMediaId
        }

        viewModel.updatePlayingState(articleId, mediaController.isPlaying)
    }



    // Giải phóng MediaController khi fragment bị hủy
    override fun onDestroyView() {
        super.onDestroyView()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}
