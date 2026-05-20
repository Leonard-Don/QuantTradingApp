package io.github.leonarddon.quanttrading.ui.community

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.databinding.ItemPostBinding
import io.github.leonarddon.quanttrading.model.Post
import java.util.Locale

class PostAdapter(
    private val onItemClick: (Post) -> Unit,
    private val onVipClick: (Post) -> Unit = {},
    private val onLikeClick: (Post) -> Unit = {}
) : ListAdapter<Post, PostAdapter.ViewHolder>(DiffCallback()) {

    private var isVipActive: Boolean = false

    fun setVipActive(active: Boolean) {
        if (isVipActive == active) return
        isVipActive = active
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPostBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemPostBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: Post) {
            binding.apply {
                val locked = post.isVip && !isVipActive
                tvAuthor.text = post.author
                tvTime.text = post.time
                tvTitle.text = post.title
                tvContent.text = if (locked) {
                    "VIP 专属内容预览：订阅后可查看完整正文、本机评论和私密圈子交流记录。"
                } else if (post.content.length > 100) {
                    post.content.substring(0, 100) + "..."
                } else {
                    post.content
                }
                tvLikes.text = formatCount(post.likes)
                tvComments.text = formatCount(post.comments)
                tvCategory.text = post.category
                ivVip.visibility = if (post.isVip) View.VISIBLE else View.GONE

                root.setOnClickListener { onItemClick(post) }
                btnComment.setOnClickListener { onItemClick(post) }

                ivVip.setOnClickListener { onVipClick(post) }

                btnLike.setOnClickListener { onLikeClick(post) }
            }
        }

        private fun formatCount(count: Int): String {
            return when {
                count >= 10000 -> String.format(Locale.CHINA, "%.1fw", count / 10000.0)
                count >= 1000 -> String.format(Locale.CHINA, "%.1fk", count / 1000.0)
                else -> count.toString()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Post>() {
        override fun areItemsTheSame(old: Post, new: Post) = old.id == new.id
        override fun areContentsTheSame(old: Post, new: Post) = old == new
    }
}
