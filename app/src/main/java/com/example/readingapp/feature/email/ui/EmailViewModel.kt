package com.example.readingapp.feature.email.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readingapp.feature.email.data.local.EmailEntity
import com.example.readingapp.feature.email.data.EmailRepository
import com.example.readingapp.feature.email.data.remote.EmailAuthException
import com.example.readingapp.feature.email.data.remote.EmailConnectionException
import com.example.readingapp.feature.email.data.local.EmailEntity.Companion.folderDisplayName
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class EmailUiState {
    object NotLoggedIn  : EmailUiState()
    object Loading      : EmailUiState()
    /** Đang hiển thị danh sách thư mục để người dùng chọn */
    data class FolderList(val folders: List<String>) : EmailUiState()
    /** Đang hiển thị danh sách email trong thư mục đã chọn */
    data class EmailList(val emails: List<EmailEntity>, val folderName: String) : EmailUiState()
    data class Error(val message: String) : EmailUiState()
}

class EmailViewModel(private val context: Context) : ViewModel() {

    private val repository = EmailRepository(context.applicationContext)

    private val _uiState = MutableStateFlow<EmailUiState>(
        if (repository.hasCredentials()) EmailUiState.Loading else EmailUiState.NotLoggedIn
    )
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    private val _currentPlayingId = MutableStateFlow<String?>(null)
    val currentPlayingId: StateFlow<String?> = _currentPlayingId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val accountEmail: String get() = repository.getAccountEmail()

    // Thư mục đang được chọn (null = chưa chọn, hiển thị danh sách folder)
    private var selectedFolder: String? = null

    private var fetchJob: Job? = null
    private var dbCollectJob: Job? = null

    init {
        if (repository.hasCredentials()) {
            loadFolders()
        }
    }

    // Auth

    fun login(email: String, password: String) {
        fetchJob?.cancel()
        _uiState.value = EmailUiState.Loading

        viewModelScope.launch {
            try {
                val isValid = repository.validateConnection(email, password)
                if (isValid) {
                    repository.saveCredentials(email, password)
                    com.example.readingapp.feature.email.data.remote.EmailSyncWorker.schedulePeriodicRefresh(context)
                    loadFolders()
                } else {
                    _uiState.value = EmailUiState.Error(
                        "Không thể kết nối IMAP. Hãy chắc chắn rằng bạn đã bật IMAP trong cài đặt Email và sử dụng App Password."
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("EmailViewModel", "Login error", e)
                _uiState.value = EmailUiState.Error("Xác thực lỗi: ${e.message}")
            }
        }
    }

    fun logout() {
        dbCollectJob?.cancel()
        fetchJob?.cancel()
        selectedFolder = null
        com.example.readingapp.feature.email.data.remote.EmailSyncWorker.cancelPeriodicRefresh(context)
        repository.clearCredentials()
        _uiState.value = EmailUiState.NotLoggedIn
        _currentPlayingId.value = null
        _isPlaying.value = false
    }

    // Folder navigation

    /**
     * Tải danh sách thư mục từ IMAP server và hiển thị màn hình chọn folder.
     */
    fun loadFolders() {
        fetchJob?.cancel()
        dbCollectJob?.cancel()
        selectedFolder = null
        _uiState.value = EmailUiState.Loading

        fetchJob = viewModelScope.launch {
            try {
                val folders = repository.fetchFolders()
                _uiState.value = EmailUiState.FolderList(folders)
            } catch (e: EmailAuthException) {
                _uiState.value = EmailUiState.Error("Xác thực thất bại: ${e.message}")
            } catch (e: EmailConnectionException) {
                // Nếu offline, thử show INBOX từ cache
                _uiState.value = EmailUiState.FolderList(listOf("INBOX"))
            } catch (e: Exception) {
                android.util.Log.e("EmailViewModel", "Failed to load folders", e)
                _uiState.value = EmailUiState.Error("Lỗi tải danh sách thư mục: ${e.message}")
            }
        }
    }

    /**
     * Người dùng chọn một thư mục — bắt đầu tải email trong thư mục đó.
     */
    fun selectFolder(folderName: String) {
        selectedFolder = folderName
        observeLocalEmails(folderName)
        fetchEmailsByFolder(folderName)
    }

    /** Quay lại màn hình danh sách thư mục */
    fun backToFolderList() {
        dbCollectJob?.cancel()
        fetchJob?.cancel()
        selectedFolder = null
        loadFolders()
    }

    // Email data

    private fun observeLocalEmails(folderName: String) {
        dbCollectJob?.cancel()
        dbCollectJob = viewModelScope.launch {
            repository.getEmailsFlow(repository.getAccountEmail(), folderName).collect { emails ->
                // Chỉ cập nhật nếu đang ở đúng folder này
                if (selectedFolder == folderName && repository.hasCredentials()) {
                    if (_uiState.value is EmailUiState.Loading && emails.isEmpty()) {
                        return@collect // Chờ network fetch hoàn tất
                    }
                    _uiState.value = EmailUiState.EmailList(emails, folderName)
                }
            }
        }
    }

    private fun fetchEmailsByFolder(folderName: String, count: Int = 20) {
        if (!repository.hasCredentials()) {
            _uiState.value = EmailUiState.NotLoggedIn
            return
        }

        fetchJob?.cancel()
        if (_uiState.value !is EmailUiState.EmailList) {
            _uiState.value = EmailUiState.Loading
        }

        fetchJob = viewModelScope.launch {
            try {
                repository.fetchEmailsByFolder(folderName, count)
                // Dữ liệu tự chảy từ Room qua Flow trong observeLocalEmails
            } catch (e: EmailAuthException) {
                _uiState.value = EmailUiState.Error("Xác thực thất bại: ${e.message}")
            } catch (e: EmailConnectionException) {
                _uiState.value = EmailUiState.Error("Lỗi kết nối: ${e.message}")
            } catch (e: IllegalStateException) {
                _uiState.value = EmailUiState.NotLoggedIn
            } catch (e: Exception) {
                android.util.Log.e("EmailViewModel", "Failed to fetch emails", e)
                _uiState.value = EmailUiState.Error("Lỗi không xác định: ${e.message}")
            }
        }
    }

    /** Làm mới email trong folder đang xem */
    fun refreshCurrentFolder() {
        val folder = selectedFolder ?: return
        fetchEmailsByFolder(folder)
    }

    // Playback state sync

    fun updatePlayingState(emailId: String?, playing: Boolean) {
        _currentPlayingId.value = emailId
        _isPlaying.value = playing
    }

    fun clearPlayingState() {
        _currentPlayingId.value = null
        _isPlaying.value = false
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
        dbCollectJob?.cancel()
    }
}
