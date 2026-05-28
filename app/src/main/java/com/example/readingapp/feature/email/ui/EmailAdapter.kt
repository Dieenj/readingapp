package com.example.readingapp.feature.email.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.readingapp.R
import com.example.readingapp.feature.email.data.local.EmailEntity
import java.text.SimpleDateFormat
import java.util.*

class EmailAdapter(
    private val onItemClick: (EmailEntity) -> Unit
) : RecyclerView.Adapter<EmailAdapter.EmailViewHolder>() {

    private var emails = listOf<EmailEntity>()
    private var currentPlayingId: String? = null
    private var isCurrentlyPlaying = false
    private var isClickable = true

    private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    // Bảng màu cho avatar — mỗi chữ cái đầu có màu khác nhau
    private val avatarColors = intArrayOf(
        Color.parseColor("#E53935"), Color.parseColor("#8E24AA"),
        Color.parseColor("#1E88E5"), Color.parseColor("#00897B"),
        Color.parseColor("#F4511E"), Color.parseColor("#6D4C41"),
        Color.parseColor("#3949AB"), Color.parseColor("#039BE5"),
        Color.parseColor("#43A047"), Color.parseColor("#FB8C00")
    )

    fun submitList(newEmails: List<EmailEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = emails.size
            override fun getNewListSize() = newEmails.size
            override fun areItemsTheSame(o: Int, n: Int) = emails[o].id == newEmails[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = emails[o]; val b = newEmails[n]
                return a.title == b.title && a.fromName == b.fromName && a.isRead == b.isRead
            }
        })
        emails = newEmails
        diff.dispatchUpdatesTo(this)
    }

    fun setClickable(clickable: Boolean) { isClickable = clickable }

    fun updatePlayingState(emailId: String?, playing: Boolean) {
        val old = currentPlayingId
        currentPlayingId = emailId
        isCurrentlyPlaying = playing
        emails.forEachIndexed { i, e ->
            if (e.id == old || e.id == emailId) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmailViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_email, parent, false)
        return EmailViewHolder(v)
    }

    override fun onBindViewHolder(holder: EmailViewHolder, position: Int) =
        holder.bind(emails[position])

    override fun getItemCount() = emails.size

    inner class EmailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView      = itemView.findViewById(R.id.tvAvatar)
        private val tvSender: TextView      = itemView.findViewById(R.id.tvSender)
        private val tvSubject: TextView     = itemView.findViewById(R.id.tvSubject)
        private val tvPreview: TextView     = itemView.findViewById(R.id.tvPreview)
        private val tvDate: TextView        = itemView.findViewById(R.id.tvDate)
        private val ivAttachment: ImageView = itemView.findViewById(R.id.ivAttachment)

        fun bind(email: EmailEntity) {
            val senderName = email.fromName.ifBlank { email.fromEmail }.ifBlank { email.author ?: "?" }

            // Avatar: chữ cái đầu tên người gửi với màu nhất quán
            val initial = senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            tvAvatar.text = initial
            val colorIndex = (senderName.hashCode() and 0x7FFFFFFF) % avatarColors.size
            tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(avatarColors[colorIndex])

            tvSender.text  = senderName
            tvSubject.text = email.title
            tvPreview.text = email.content.take(100).replace("\n", " ")
            tvDate.text    = dateFormat.format(Date(email.publishedDate))

            ivAttachment.visibility = if (email.hasAttachments) View.VISIBLE else View.GONE

            val isThisPlaying = email.id == currentPlayingId && isCurrentlyPlaying
            if (isThisPlaying) {
                tvSubject.setTextColor(itemView.context.getColor(R.color.newsreader_indicator))
            } else {
                tvSubject.setTextColor(itemView.context.getColor(R.color.newsreader_text_primary))
            }

            // Dim nếu đã đọc (như News)
            itemView.alpha = if (email.isRead && !isThisPlaying) 0.65f else 1.0f
            // Bold tiêu đề nếu chưa đọc
            tvSubject.setTypeface(null,
                if (!email.isRead) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            itemView.setOnClickListener { if (isClickable) onItemClick(email) }
        }
    }
}
