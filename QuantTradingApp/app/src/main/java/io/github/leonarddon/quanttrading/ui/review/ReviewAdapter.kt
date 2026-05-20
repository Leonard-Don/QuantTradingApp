package io.github.leonarddon.quanttrading.ui.review

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.leonarddon.quanttrading.databinding.ItemSectorBinding
import io.github.leonarddon.quanttrading.model.SectorInfo
import java.util.Locale

class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.ViewHolder>() {

    private var items: List<SectorInfo> = emptyList()

    fun submitList(newItems: List<SectorInfo>) {
        val oldItems = items
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].name == newItems[newItemPosition].name
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSectorBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemSectorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SectorInfo) {
            binding.tvSectorName.text = item.name
            binding.tvSectorChange.text = String.format(Locale.CHINA, "%.2f%%", item.changePercent)
            val color = if (item.changePercent >= 0) {
                binding.root.context.getColor(io.github.leonarddon.quanttrading.R.color.stock_up)
            } else {
                binding.root.context.getColor(io.github.leonarddon.quanttrading.R.color.stock_down)
            }
            binding.tvSectorChange.setTextColor(color)
        }
    }
}
