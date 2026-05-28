package com.example.readingapp.feature.email.data

import android.content.Context
import com.example.readingapp.feature.email.data.local.EmailDatabase
import com.example.readingapp.feature.email.data.local.EmailEntity
import com.example.readingapp.feature.email.data.remote.EmailAuthException
import com.example.readingapp.feature.email.data.remote.EmailConnectionException
import com.example.readingapp.feature.email.data.remote.EmailFetcher
import com.example.readingapp.feature.email.data.remote.ImapConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kho lưu trữ (Repository) cho dữ liệu email.
 */
class EmailRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("email_prefs", Context.MODE_PRIVATE)
    }

    private val database = EmailDatabase.getDatabase(context)
    private val emailDao = database.emailDao()
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val KEY_EMAIL    = "imap_email"
        private const val KEY_PASSWORD = "imap_password"
        private const val KEY_HOST     = "imap_host"
        private const val KEY_PORT     = "imap_port"
    }

    // Credentials

    // Xác minh kết nối IMAP trước khi đăng nhập
    suspend fun validateConnection(email: String, password: String): Boolean {
        val config = EmailFetcher.guessConfig(email).copy(username = email, password = password)
        return EmailFetcher.testConnection(config)
    }

    // Lưu thông tin xác thực vào bộ nhớ
    fun saveCredentials(email: String, password: String) {
        val config = EmailFetcher.guessConfig(email)
        prefs.edit().apply {
            putString(KEY_EMAIL,    email)
            putString(KEY_PASSWORD, password)
            putString(KEY_HOST,     config.host)
            putInt(KEY_PORT,        config.port)
            apply()
        }
    }

    // Tải thông tin xác thực đã lưu
    fun loadCredentials(): ImapConfig? {
        val email    = prefs.getString(KEY_EMAIL, null)    ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        val host     = prefs.getString(KEY_HOST, null)     ?: EmailFetcher.guessConfig(email).host
        val port     = prefs.getInt(KEY_PORT, 993)
        return ImapConfig(host = host, port = port, username = email, password = password)
    }

    // Kiểm tra xem đã có thông tin xác thực chưa
    fun hasCredentials(): Boolean =
        prefs.getString(KEY_EMAIL, null) != null &&
        prefs.getString(KEY_PASSWORD, null) != null

    // Xóa toàn bộ thông tin xác thực và cơ sở dữ liệu
    fun clearCredentials() {
        prefs.edit().clear().apply()
        repositoryScope.launch {
            emailDao.clearAll()
        }
    }

    // Lấy địa chỉ email của tài khoản đang dùng
    fun getAccountEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""

    // Data fetching

    // Lắng nghe danh sách email thời gian thực theo thư mục (Flow)
    fun getEmailsFlow(accountEmail: String, folderName: String = "INBOX"): Flow<List<EmailEntity>> =
        emailDao.getEmailsByFolder(accountEmail, folderName)

    // Lắng nghe tất cả email của tài khoản (không lọc folder — cho overview)
    fun getAllEmailsFlow(accountEmail: String): Flow<List<EmailEntity>> =
        emailDao.getEmailsByAccount(accountEmail)

    // Lấy đối tượng EmailDao phục vụ PlaylistProvider
    fun getEmailDao() = emailDao

    /**
     * Lấy danh sách thư mục từ IMAP server.
     */
    suspend fun fetchFolders(): List<String> {
        val config = loadCredentials()
            ?: throw IllegalStateException("Chưa đăng nhập tài khoản email.")
        return EmailFetcher.fetchFolders(config)
    }

    /**
     * Tải danh sách email mới nhất từ một thư mục cụ thể và lưu vào Room.
     */
    suspend fun fetchEmailsByFolder(folderName: String, count: Int = 20): List<EmailEntity> {
        val config = loadCredentials()
            ?: throw IllegalStateException("Chưa đăng nhập tài khoản email.")
        try {
            val remoteEmails = EmailFetcher.fetchEmails(config, folderName, count)
            if (remoteEmails.isNotEmpty()) {
                emailDao.insertEmails(remoteEmails)
            }
        } catch (e: EmailAuthException) {
            throw e
        } catch (e: EmailConnectionException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("EmailRepository", "Failed to fetch folder '$folderName', using cache", e)
        }
        return withContext(Dispatchers.IO) {
            emailDao.getEmailsByAccountSync(config.username)
                .filter { it.folderName == folderName }
        }
    }

    /** Alias cho inbox — tương thích ngược */
    suspend fun fetchInbox(count: Int = 20) = fetchEmailsByFolder("INBOX", count)

    /**
     * Tải nội dung đầy đủ của email trước khi tổng hợp TTS và cập nhật vào database Room.
     */
    suspend fun fetchFullBody(email: EmailEntity): String {
        val localEmail = withContext(Dispatchers.IO) {
            emailDao.getEmailById(email.id)
        }
        if (localEmail != null && localEmail.body.length > 300 && localEmail.body != "(Nội dung trống)") {
            return localEmail.body
        }

        val config = loadCredentials() ?: return email.content
        return try {
            val fullBody = EmailFetcher.fetchBody(config, email.id, email.folderName) ?: email.content
            withContext(Dispatchers.IO) {
                emailDao.updateEmailBody(email.id, fullBody)
            }
            fullBody
        } catch (e: Exception) {
            android.util.Log.w("EmailRepository", "Failed to fetch full body for ${email.id}", e)
            email.content
        }
    }
}
