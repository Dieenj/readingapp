package com.example.readingapp.feature.email.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {

    // Lấy tất cả email của một tài khoản (dùng cho tổng quan)
    @Query("SELECT * FROM emails WHERE accountEmail = :accountEmail ORDER BY sentDate DESC")
    fun getEmailsByAccount(accountEmail: String): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE accountEmail = :accountEmail ORDER BY sentDate DESC")
    suspend fun getEmailsByAccountSync(accountEmail: String): List<EmailEntity>

    // Lấy email theo thư mục cụ thể
    @Query("SELECT * FROM emails WHERE accountEmail = :accountEmail AND folderName = :folderName ORDER BY sentDate DESC")
    fun getEmailsByFolder(accountEmail: String, folderName: String): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE emailId = :emailId OR emailId = '<' || :emailId || '>' LIMIT 1")
    suspend fun getEmailById(emailId: String): EmailEntity?

    // Navigation: lấy email tiếp theo trong cùng thư mục (cũ hơn theo thời gian)
    @Query("""
        SELECT * FROM emails 
        WHERE accountEmail = :accountEmail 
          AND folderName = :folderName
          AND (sentDate < :currentDate OR (sentDate = :currentDate AND emailId < :emailId))
        ORDER BY sentDate DESC, emailId DESC LIMIT 1
    """)
    suspend fun getNextEmail(
        accountEmail: String, folderName: String, currentDate: Long, emailId: String
    ): EmailEntity?

    @Query("""
        SELECT * FROM emails 
        WHERE accountEmail = :accountEmail 
          AND folderName = :folderName
          AND (sentDate < :currentDate OR (sentDate = :currentDate AND emailId < :emailId))
        ORDER BY sentDate DESC, emailId DESC LIMIT :limit
    """)
    suspend fun getNextEmails(
        accountEmail: String, folderName: String, currentDate: Long, emailId: String, limit: Int
    ): List<EmailEntity>

    // Navigation: lấy email trước đó trong cùng thư mục (mới hơn theo thời gian)
    @Query("""
        SELECT * FROM emails 
        WHERE accountEmail = :accountEmail 
          AND folderName = :folderName
          AND (sentDate > :currentDate OR (sentDate = :currentDate AND emailId > :emailId))
        ORDER BY sentDate ASC, emailId ASC LIMIT 1
    """)
    suspend fun getPreviousEmail(
        accountEmail: String, folderName: String, currentDate: Long, emailId: String
    ): EmailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmails(emails: List<EmailEntity>)

    @Query("UPDATE emails SET body = :body WHERE emailId = :emailId OR emailId = '<' || :emailId || '>'")
    suspend fun updateEmailBody(emailId: String, body: String)

    @Query("UPDATE emails SET isRead = :isRead WHERE emailId = :emailId OR emailId = '<' || :emailId || '>'")
    suspend fun updateReadStatus(emailId: String, isRead: Boolean)

    @Query("DELETE FROM emails")
    suspend fun clearAll()
}
