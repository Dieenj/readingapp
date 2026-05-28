package com.example.readingapp.feature.news.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.readingapp.R
import com.example.readingapp.feature.news.data.local.ArticleEntity

class ArticleAdapter(
    private val onItemClick: (ArticleEntity) -> Unit
) : RecyclerView.Adapter<ArticleAdapter.ArticleViewHolder>() {

    private var articles = listOf<ArticleEntity>()
    private var currentPlayingId: String? = null
    private var isCurrentlyPlaying = false
    private var isClickable = true

    fun submitList(newArticles: List<ArticleEntity>) {
        val diffCallback = ArticleDiffCallback(articles, newArticles)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        articles = newArticles
        diffResult.dispatchUpdatesTo(this)
    }

    fun setClickable(clickable: Boolean) { isClickable = clickable }

    fun getArticlePosition(articleId: String): Int = articles.indexOfFirst { it.id == articleId }

    fun updatePlayingIndicator(playingArticleId: String?) {
        val oldPlayingId = currentPlayingId
        currentPlayingId = playingArticleId
        isCurrentlyPlaying = false
        articles.forEachIndexed { index, article ->
            if (article.id == oldPlayingId || article.id == playingArticleId) notifyItemChanged(index)
        }
    }

    fun updatePlayingState(playingArticleId: String?, playing: Boolean) {
        val oldPlayingId = currentPlayingId
        currentPlayingId = playingArticleId
        isCurrentlyPlaying = playing
        articles.forEachIndexed { index, article ->
            if (article.id == oldPlayingId || article.id == playingArticleId) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false)
        return ArticleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(articles[position])
    }

    override fun getItemCount() = articles.size

    inner class ArticleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageArticle)
        private val titleView: TextView = itemView.findViewById(R.id.textTitle)
        private val summaryView: TextView = itemView.findViewById(R.id.textSummary)

        fun bind(article: ArticleEntity) {
            titleView.text = article.title

            if (article.summary.isNotBlank()) {
                val sum = article.summary
                summaryView.text = if (sum.length > 80) sum.substring(0, 80) + "..." else sum
                summaryView.visibility = View.VISIBLE
            } else {
                summaryView.visibility = View.GONE
            }

            val isThisArticlePlaying = (article.id == currentPlayingId && isCurrentlyPlaying)
            if (isThisArticlePlaying) {
                titleView.setTextColor(itemView.context.getColor(R.color.newsreader_indicator))
            } else {
                titleView.setTextColor(itemView.context.getColor(R.color.newsreader_text_primary))
            }

            if (article.imageUrl.isNotEmpty()) {
                imageView.visibility = View.VISIBLE
                Glide.with(itemView.context).load(article.imageUrl).into(imageView)
            } else {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageDrawable(null)
                imageView.visibility = View.INVISIBLE
            }

            itemView.alpha = if (isThisArticlePlaying) 1.0f else if (article.isRead) 0.6f else 1.0f

            itemView.setOnClickListener { if (isClickable) onItemClick(article) }
        }
    }
}

class ArticleDiffCallback(
    private val oldList: List<ArticleEntity>,
    private val newList: List<ArticleEntity>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition].id == newList[newItemPosition].id
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val o = oldList[oldItemPosition]; val n = newList[newItemPosition]
        return o.title == n.title && o.summary == n.summary && o.imageUrl == n.imageUrl && o.isRead == n.isRead
    }
}
