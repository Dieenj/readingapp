package com.example.readingapp.feature.email.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.readingapp.R
import com.example.readingapp.feature.email.data.EmailRepository
import com.example.readingapp.feature.email.data.local.EmailEntity
import java.util.concurrent.TimeUnit

class EmailSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = EmailRepository(context.applicationContext)

    companion object {
        private const val CHANNEL_ID = "email_sync_channel"
        private const val NOTIFICATION_ID = 1002
        const val WORK_NAME = "email_background_sync_work"

        fun schedulePeriodicRefresh(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val refreshRequest = PeriodicWorkRequestBuilder<EmailSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    refreshRequest
                )
            } catch (e: Exception) {
                android.util.Log.e("EmailSyncWorker", "Failed to schedule email periodic refresh", e)
            }
        }

        fun cancelPeriodicRefresh(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                android.util.Log.e("EmailSyncWorker", "Failed to cancel email periodic refresh", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        if (!repository.hasCredentials()) {
            return Result.success()
        }

        return try {
            val accountEmail = repository.getAccountEmail()
            // Tải và lưu các email mới nhất cục bộ
            val emailsBeforeSync = repository.getEmailDao().getEmailsByAccountSync(accountEmail)
            val updatedEmails = repository.fetchInbox(count = 20)

            // Lọc ra các email mới chưa đọc chưa từng xuất hiện trong SQLite trước đó
            val newUnreadEmails = updatedEmails.filter { newEmail ->
                !newEmail.isRead && emailsBeforeSync.none { it.emailId == newEmail.emailId }
            }

            if (newUnreadEmails.isNotEmpty()) {
                showNotification(newUnreadEmails)
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("EmailSyncWorker", "Background sync failed", e)
            Result.retry()
        }
    }

    // Hiển thị thông báo đẩy hệ thống cho email mới
    private fun showNotification(newEmails: List<EmailEntity>) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Đồng bộ Email",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Thông báo khi có email mới chưa đọc"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (newEmails.size == 1) {
            "Thư mới chưa đọc"
        } else {
            "${newEmails.size} thư mới chưa đọc"
        }

        val contentText = if (newEmails.size == 1) {
            val first = newEmails.first()
            "Từ: ${first.fromName.ifEmpty { first.fromAddress ?: "Người gửi ẩn danh" }}\n${first.title}"
        } else {
            newEmails.take(3).joinToString("\n") { it.title }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(title)
            .setContentText(if (newEmails.size == 1) newEmails.first().title else "Bấm để xem các thư mới")
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
