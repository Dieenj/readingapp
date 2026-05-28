package com.example.readingapp.feature.email.data.remote

import android.util.Log
import com.example.readingapp.feature.email.data.local.EmailEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Lớp cấu hình IMAP đại diện cho thông tin kết nối và xác thực email.
 */
data class ImapConfig(
    val host: String,
    val port: Int,
    val socketType: String = "ssl",
    val username: String = "",
    val password: String = ""
)

/**
 * Ngoại lệ xảy ra khi xác thực email thất bại (sai tài khoản hoặc mật khẩu).
 */
class EmailAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ngoại lệ xảy ra khi không thể kết nối đến máy chủ email.
 */
class EmailConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Bộ điều phối tải và phân tích dữ liệu Email qua giao thức IMAP/JavaMail.
 */
object EmailFetcher {
    private const val TAG = "EmailFetcher"
    private const val DEFAULT_FETCH_COUNT = 20

    // Các máy chủ IMAP được cấu hình sẵn
    private val KNOWN_PROVIDERS = mapOf(
        "gmail.com"       to ImapConfig("imap.gmail.com",      993, "ssl"),
        "googlemail.com"  to ImapConfig("imap.gmail.com",      993, "ssl"),
        "outlook.com"     to ImapConfig("outlook.office365.com", 993, "ssl"),
        "hotmail.com"     to ImapConfig("outlook.office365.com", 993, "ssl"),
        "yahoo.com"       to ImapConfig("imap.mail.yahoo.com", 993, "ssl")
    )

    /**
     * Dự đoán cấu hình IMAP dựa trên địa chỉ email.
     */
    fun guessConfig(email: String): ImapConfig {
        val domain = email.substringAfterLast("@").lowercase()
        return KNOWN_PROVIDERS[domain]
            ?: ImapConfig("imap.$domain", 993, "ssl")
    }

    /**
     * Khởi tạo JavaMail Session.
     */
    private fun buildSession(config: ImapConfig): Session {
        val props = Properties().apply {
            put("mail.imaps.host",               config.host)
            put("mail.imaps.port",               config.port.toString())
            put("mail.imaps.ssl.enable",         "true")
            put("mail.imaps.connectiontimeout",  "15000")
            put("mail.imaps.timeout",            "15000")
            put("mail.imaps.ssl.socketFactory.fallback", "false")
        }
        return Session.getInstance(props)
    }

    /**
     * Mở kết nối và trả về Store đang hoạt động.
     */
    fun connectStore(config: ImapConfig): Store {
        val session = buildSession(config)
        val store = session.getStore("imaps")
        store.connect(config.host, config.port, config.username, config.password)
        return store
    }

    /**
     * Kiểm tra nhanh kết nối xác thực đến máy chủ IMAP.
     */
    suspend fun testConnection(config: ImapConfig): Boolean = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            val session = buildSession(config)
            store = session.getStore("imaps")
            store.connect(config.host, config.port, config.username, config.password)
            return@withContext store.isConnected
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed: ${e.message}")
            false
        } finally {
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Lấy danh sách thư mục có thể đọc được từ IMAP server.
     */
    suspend fun fetchFolders(config: ImapConfig): List<String> = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            store = connectStore(config)

            store.defaultFolder.list("*")
                .filter { folder ->
                    val holdsMessages = (folder.type and Folder.HOLDS_MESSAGES) != 0
                    val hasNoSelect = try {
                        val imapFolder = folder as? com.sun.mail.imap.IMAPFolder
                        imapFolder?.attributes?.contains("\\Noselect") == true
                    } catch (_: Exception) { false }
                    holdsMessages && !hasNoSelect
                }
                .map { it.fullName }
                .ifEmpty { listOf("INBOX") }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFolders failed: ${e.message}")
            listOf("INBOX")
        } finally {
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Tải danh sách email mới nhất từ thư mục.
     */
    suspend fun fetchEmails(
        config: ImapConfig,
        folderName: String = "INBOX",
        count: Int = DEFAULT_FETCH_COUNT
    ): List<EmailEntity> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var imapFolder: Folder? = null

        try {
            store = connectStore(config)
            imapFolder = store.getFolder(folderName).also { it.open(Folder.READ_ONLY) }

            val total = imapFolder.messageCount
            if (total == 0) return@withContext emptyList()

            val start = maxOf(1, total - count + 1)
            val messages = imapFolder.getMessages(start, total)

            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            imapFolder.fetch(messages, fp)

            messages.reversed().mapNotNull { message ->
                try {
                    parseMessage(message, config.username, folderName)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping message: ${e.message}")
                    null
                }
            }
        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, "Authentication failed: ${e.message}")
            throw EmailAuthException("Sai tài khoản hoặc mật khẩu. Với Gmail hãy dùng App Password.")
        } catch (e: MessagingException) {
            Log.e(TAG, "IMAP error: ${e.message}")
            throw EmailConnectionException("Không thể kết nối đến máy chủ email: ${e.message}")
        } finally {
            try { imapFolder?.close(false) } catch (_: Exception) {}
            try { store?.close() }           catch (_: Exception) {}
        }
    }

    /**
     * Tải nội dung đầy đủ của một email theo messageId.
     */
    suspend fun fetchBody(
        config: ImapConfig,
        messageId: String,
        folderName: String = "INBOX"
    ): String? = withContext(Dispatchers.IO) {
        var store: Store? = null
        var imapFolder: Folder? = null

        try {
            store = connectStore(config)
            imapFolder = store.getFolder(folderName).also { it.open(Folder.READ_ONLY) }

            val term = javax.mail.search.MessageIDTerm(messageId)
            var matched = imapFolder.search(term)
            if (matched.isEmpty() && !messageId.startsWith("<")) {
                val wrappedTerm = javax.mail.search.MessageIDTerm("<$messageId>")
                matched = imapFolder.search(wrappedTerm)
            }
            matched.firstOrNull()?.let { extractPlainText(it) }

        } catch (e: Exception) {
            Log.e(TAG, "fetchBody error: ${e.message}")
            null
        } finally {
            try { imapFolder?.close(false) } catch (_: Exception) {}
            try { store?.close() }           catch (_: Exception) {}
        }
    }

    /**
     * Phân tích đối tượng JavaMail Message sang EmailEntity.
     */
    private fun parseMessage(message: Message, accountEmail: String, folderName: String): EmailEntity {
        val msgId = ((message as? MimeMessage)?.messageID
            ?: "${accountEmail}_${message.sentDate?.time ?: System.currentTimeMillis()}")
            .trim('<', '>')

        val subject = message.subject?.trim() ?: "(Không có tiêu đề)"
        val from    = message.from?.firstOrNull() as? InternetAddress
        val sentMs  = message.sentDate?.time ?: System.currentTimeMillis()
        val isRead  = message.flags.contains(Flags.Flag.SEEN)
        val hasAttachments = message.contentType?.contains("multipart/mixed", ignoreCase = true) == true

        val bodyPreview = try {
            extractPlainText(message).take(300).ifBlank { "(Nội dung trống)" }
        } catch (_: Exception) {
            "(Không thể đọc nội dung)"
        }

        return EmailEntity(
            emailId        = msgId,
            subject        = subject,
            body           = bodyPreview,
            fromAddress    = from?.personal ?: from?.address,
            sentDate       = sentMs,
            accountEmail   = accountEmail,
            emailLanguage  = "vi",
            fromName       = from?.personal ?: "",
            fromEmail      = from?.address  ?: "",
            toRecipients   = message.getRecipients(Message.RecipientType.TO)
                                ?.map { (it as? InternetAddress)?.address ?: "" }
                                ?: emptyList(),
            isRead         = isRead,
            hasAttachments = hasAttachments,
            folderName     = folderName
        )
    }

    /**
     * Trích xuất văn bản thuần túy từ một phần của MIME message.
     */
    fun extractPlainText(part: Part): String {
        return when {
            part.isMimeType("text/plain") -> {
                (part.content as? String)?.trim() ?: ""
            }
            part.isMimeType("text/html") -> {
                val html = part.content as? String ?: return ""
                android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                    .toString().replace(Regex("\\s{3,}"), "\n\n").trim()
            }
            part.isMimeType("multipart/*") -> {
                val mp = part.content as MimeMultipart
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) return extractPlainText(bp)
                }
                if (mp.count > 0) extractPlainText(mp.getBodyPart(0)) else ""
            }
            else -> ""
        }
    }
}
