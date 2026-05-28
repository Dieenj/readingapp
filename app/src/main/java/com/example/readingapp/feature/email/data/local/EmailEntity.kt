package com.example.readingapp.feature.email.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.readingapp.core.model.ReadableContent

/**
 * Email entity – implements ReadableContent so it plugs directly into
 * AudioPlayer / TextToSpeechEngine without any changes to the core layer.
 */
@Entity(tableName = "emails")
data class EmailEntity(
    @PrimaryKey
    val emailId: String,
    val subject: String,
    val body: String,
    val fromAddress: String?,
    val sentDate: Long,
    val accountEmail: String,
    val emailUrl: String? = null,
    val emailLanguage: String = "vi",
    val emailImageUrl: String? = null,

    // Email-specific extras
    val fromName: String = "",
    val fromEmail: String = "",
    val toRecipients: List<String> = emptyList(),
    val isRead: Boolean = false,
    val hasAttachments: Boolean = false,
    val folderName: String = "INBOX"
) : ReadableContent {
    // Luôn trả về ID sạch, không có dấu <> từ IMAP Message-ID (ví dụ: <abc@mail.gmail.com> → abc@mail.gmail.com)
    override val id: String            get() = emailId.trim('<', '>')
    override val title: String         get() = subject
    override val content: String       get() = body
    override val author: String?       get() = fromAddress
    override val publishedDate: Long   get() = sentDate
    override val source: String
        get() {
            val domain = accountEmail.substringAfterLast("@").lowercase()
            return when {
                domain.contains("gmail") || domain.contains("googlemail") -> "Google"
                domain.contains("outlook") || domain.contains("hotmail") || domain.contains("live") -> "Outlook"
                domain.contains("yahoo") -> "Yahoo"
                else -> domain.substringBefore(".").replaceFirstChar { it.uppercase() }
            }
        }
    override val url: String?          get() = emailUrl
    override val language: String      get() = emailLanguage
    // category = thư mục hiển thị (Hộp thư đến, Đã gửi, ...) — tương tự category của News
    override val category: String      get() = folderDisplayName(folderName)
    override val imageUrl: String?     get() = emailImageUrl

    companion object {
        /** Map IMAP folder name sang tên hiển thị tiếng Việt thân thiện */
        fun folderDisplayName(folder: String): String = when (folder.uppercase()) {
            "INBOX"          -> "Hộp thư đến"
            "SENT", "[GMAIL]/SENT MAIL", "Sent Messages" -> "Đã gửi"
            "DRAFTS", "[GMAIL]/DRAFTS"                   -> "Bản nháp"
            "SPAM", "[GMAIL]/SPAM", "Junk"               -> "Thư rác"
            "TRASH", "[GMAIL]/TRASH", "Deleted Messages" -> "Đã xóa"
            "STARRED", "[GMAIL]/STARRED"                 -> "Có gắn sao"
            else -> folder
        }
    }
}

