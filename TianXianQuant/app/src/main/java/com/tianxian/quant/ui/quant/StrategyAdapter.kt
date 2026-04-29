package com.tianxian.quant.ui.quant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tianxian.quant.R
import com.tianxian.quant.databinding.ItemStrategyBinding
import com.tianxian.quant.model.Strategy
import java.util.Locale

class StrategyAdapter(
    private val onItemClick: (Strategy) -> Unit,
    private val onBacktestClick: (Strategy) -> Unit = {},
    private val onVipClick: (Strategy) -> Unit = {}
) : ListAdapter<Strategy, StrategyAdapter.ViewHolder>(DiffCallback()) {

    private var isVipActive: Boolean = false

    fun setVipActive(active: Boolean) {
        if (isVipActive == active) return
        isVipActive = active
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStrategyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemStrategyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(strategy: Strategy) {
            binding.apply {
                val locked = strategy.isVip && !isVipActive
                tvStrategyName.text = strategy.name
                tvDescription.text = if (locked) {
                    "VIP 模型预览：${strategy.tags.joinToString("、")}。开通量化 VIP 后查看完整公式、样本指标与历史模拟。"
                } else {
                    strategy.description
                }
                tvWinRate.text = if (locked) "VIP预览" else "样本胜率 ${String.format(Locale.CHINA, "%.1f", strategy.winRate)}%"
                tvMaxDrawdown.text = if (locked) "公式锁定" else "回撤 ${String.format(Locale.CHINA, "%.1f", strategy.maxDrawdown)}%"
                tvSharpe.text = if (locked) "回测锁定" else "夏普 ${String.format(Locale.CHINA, "%.2f", strategy.sharpeRatio)}"
                ivVip.visibility = if (strategy.isVip) View.VISIBLE else View.GONE
                btnBacktest.text = if (locked) "去订阅" else root.context.getString(R.string.backtest)

                root.setOnClickListener { onItemClick(strategy) }

                btnBacktest.setOnClickListener {
                    if (locked) onVipClick(strategy) else onBacktestClick(strategy)
                }

                ivVip.setOnClickListener { onVipClick(strategy) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Strategy>() {
        override fun areItemsTheSame(old: Strategy, new: Strategy) = old.id == new.id
        override fun areContentsTheSame(old: Strategy, new: Strategy) = old == new
    }
}
