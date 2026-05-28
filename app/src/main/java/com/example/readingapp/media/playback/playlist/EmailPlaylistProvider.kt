package com.example.readingapp.media.playback.playlist

import com.example.readingapp.core.model.ReadableContentData
import com.example.readingapp.feature.email.data.EmailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class EmailPlaylistProvider(
    private val repository: EmailRepository
) : PlaylistProvider {

    private val emailDao = repository.getEmailDao()

    override suspend fun getItemById(id: String): ReadableContentData? {
        val email = withContext(Dispatchers.IO) {
            emailDao.getEmailById(id)
        } ?: return null

        // Tải nội dung đầy đủ (lazy load) với timeout 5 giây phòng thủ bao ngoài withContext
        val fullContent = kotlinx.coroutines.withTimeoutOrNull(5000L) {
            withContext(Dispatchers.IO) {
                repository.fetchFullBody(email)
            }
        } ?: email.content

        return ReadableContentData(
            id = email.id,
            title = email.title,
            content = fullContent,
            publishedDate = email.publishedDate,
            source = email.source,
            url = email.url,
            language = email.language,
            category = email.category,
            imageUrl = email.imageUrl
        )
    }

    override suspend fun getNextItem(currentId: String?): ReadableContentData? {
        if (currentId.isNullOrEmpty()) return null // Trả về null khi không có bài hiện tại để đồng nhất hành vi
        
        val currentEmail = withContext(Dispatchers.IO) { emailDao.getEmailById(currentId) } ?: return null

        val nextEmail = withContext(Dispatchers.IO) {
            emailDao.getNextEmail(
                accountEmail = currentEmail.accountEmail,
                folderName   = currentEmail.folderName,
                currentDate  = currentEmail.sentDate,
                emailId      = currentEmail.emailId
            )
        }
        return nextEmail?.let { getItemById(it.id) }
    }

    override suspend fun getPreviousItem(currentId: String?): ReadableContentData? {
        if (currentId.isNullOrEmpty()) return null // Trả về null khi không có bài hiện tại để đồng nhất hành vi
        
        val currentEmail = withContext(Dispatchers.IO) { emailDao.getEmailById(currentId) } ?: return null

        val prevEmail = withContext(Dispatchers.IO) {
            emailDao.getPreviousEmail(
                accountEmail = currentEmail.accountEmail,
                folderName   = currentEmail.folderName,
                currentDate  = currentEmail.sentDate,
                emailId      = currentEmail.emailId
            )
        }
        return prevEmail?.let { getItemById(it.id) }
    }

    override suspend fun getNextItems(currentId: String?, count: Int): List<ReadableContentData> = withContext(Dispatchers.Default) {
        if (currentId.isNullOrEmpty()) return@withContext emptyList() // Trả về danh sách rỗng khi chưa phát bài nào để đồng nhất hành vi
        
        val currentEmail = withContext(Dispatchers.IO) { emailDao.getEmailById(currentId) } ?: return@withContext emptyList()

        val nextEmails = withContext(Dispatchers.IO) {
            emailDao.getNextEmails(
                accountEmail = currentEmail.accountEmail,
                folderName   = currentEmail.folderName,
                currentDate  = currentEmail.sentDate,
                emailId      = currentEmail.emailId,
                limit        = count
            )
        }

        val deferreds = nextEmails.map { email ->
            async { getItemById(email.id) }
        }
        deferreds.awaitAll().filterNotNull()
    }

    override suspend fun markAsRead(id: String) {
        withContext(Dispatchers.IO) {
            emailDao.updateReadStatus(id, true)
        }
    }
}
