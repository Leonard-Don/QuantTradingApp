package io.github.leonarddon.quanttrading.ui.quant

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.databinding.ItemStrategyBinding
import io.github.leonarddon.quanttrading.model.Strategy
import java.util.Locale

class StrategyAdapter(
    private val onItemClick: (Strategy) -> Unit,
    private val onBacktestClick: (Strategy) -> Unit = {}
) : ListAdapter<Strategy, StrategyAdapter.ViewHolder>(DiffCallback()) {

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
                tvStrategyName.text = strategy.name
                tvDescription.text = strategy.description
                tvWinRate.text = root.context.getString(
                    R.string.strategy_sample_win_rate,
                    String.format(Locale.CHINA, "%.1f", strategy.winRate)
                )
                tvMaxDrawdown.text = root.context.getString(
                    R.string.strategy_max_drawdown,
                    String.format(Locale.CHINA, "%.1f", strategy.maxDrawdown)
                )
                tvSharpe.text = root.context.getString(
                    R.string.strategy_sharpe,
                    String.format(Locale.CHINA, "%.2f", strategy.sharpeRatio)
                )
                btnBacktest.text = root.context.getString(R.string.backtest)

                root.setOnClickListener { onItemClick(strategy) }

                btnBacktest.setOnClickListener {
                    onBacktestClick(strategy)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Strategy>() {
        override fun areItemsTheSame(old: Strategy, new: Strategy) = old.id == new.id
        override fun areContentsTheSame(old: Strategy, new: Strategy) = old == new
    }
}
