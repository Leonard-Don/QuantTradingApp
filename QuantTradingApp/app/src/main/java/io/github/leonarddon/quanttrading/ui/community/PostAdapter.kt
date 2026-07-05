package io.github.leonarddon.quanttrading.ui.community

import android.view.LayoutInflater
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
    private val onLikeClick: (Post) -> Unit = {}
) : ListAdapter<Post, PostAdapter.ViewHolder>(DiffCallback()) {

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
                tvAuthor.text = post.author
                tvTime.text = post.time
                tvTitle.text = post.title
                tvContent.text = if (post.content.length > 100) {
                    post.content.substring(0, 100) + "..."
                } else {
                    post.content
                }
                tvLikes.text = formatCount(post.likes)
                tvComments.text = formatCount(post.comments)
                tvCategory.text = post.category

                root.setOnClickListener { onItemClick(post) }
                btnComment.setOnClickListener { onItemClick(post) }

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
