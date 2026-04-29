package com.tianxian.quant.ui.stock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tianxian.quant.R
import com.tianxian.quant.databinding.ItemStockBinding
import com.tianxian.quant.model.StockInfo
import java.text.DecimalFormat
import java.util.Locale

class StockListAdapter(
    private val onItemClick: (StockInfo) -> Unit,
    private val onWatchlistClick: (StockInfo) -> Unit
) : ListAdapter<StockInfo, StockListAdapter.ViewHolder>(DiffCallback()) {

    private var watchlistCodes: Set<String> = emptySet()

    fun setWatchlistCodes(codes: Set<String>) {
        val changedCodes = (watchlistCodes - codes) + (codes - watchlistCodes)
        watchlistCodes = codes
        currentList.forEachIndexed { index, stock ->
            if (stock.code in changedCodes) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStockBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemStockBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val priceFormat = DecimalFormat("0.00")
        fun bind(stock: StockInfo) {
            binding.apply {
                val context = root.context
                tvStockCode.text = stock.code
                tvStockName.text = stock.name
                tvPrice.text = priceFormat.format(stock.price)
                tvChange.text = context.getString(
                    R.string.stock_change_percent,
                    formatSignedDecimal(stock.changePercent)
                )
                tvIndustry.text = stock.industry
                tvChange.setTextColor(
                    context.getColor(
                        if (stock.changePercent >= 0) R.color.stock_up else R.color.stock_down
                    )
                )
                val volumeStr = when {
                    stock.volume >= 100000000 -> String.format(Locale.CHINA, "%.2f亿", stock.volume / 100000000.0)
                    stock.volume >= 10000 -> String.format(Locale.CHINA, "%.2f万", stock.volume / 10000.0)
                    else -> stock.volume.toString()
                }
                val turnoverText = if (stock.turnover > 0) {
                    context.getString(R.string.stock_turnover_summary, priceFormat.format(stock.turnover))
                } else {
                    context.getString(R.string.stock_volume_summary, volumeStr)
                }
                val valuationParts = buildList {
                    if (stock.pe > 0) add(context.getString(R.string.stock_pe_summary, priceFormat.format(stock.pe)))
                    if (stock.pb > 0) add(context.getString(R.string.stock_pb_summary, priceFormat.format(stock.pb)))
                }
                tvVolume.text = if (valuationParts.isEmpty()) {
                    turnoverText
                } else {
                    context.getString(
                        R.string.stock_quote_summary_with_valuation,
                        turnoverText,
                        valuationParts.joinToString(" ")
                    )
                }
                if (stock.ma5 > 0 && stock.ma10 > 0 && stock.ma20 > 0) {
                    tvMa.visibility = View.VISIBLE
                    tvMa.text = context.getString(
                        R.string.stock_moving_average_summary,
                        priceFormat.format(stock.ma5),
                        priceFormat.format(stock.ma10),
                        priceFormat.format(stock.ma20)
                    )
                } else {
                    tvMa.visibility = View.GONE
                }
                ivVip.visibility = if (stock.isVip) View.VISIBLE else View.GONE
                val watched = stock.code in watchlistCodes
                tvWatchlistToggle.text = if (watched) "★" else "☆"
                tvWatchlistToggle.contentDescription = if (watched) "移出自选池" else "加入自选池"
                tvWatchlistToggle.setTextColor(
                    context.getColor(if (watched) R.color.primary else R.color.text_hint)
                )
                tvWatchlistToggle.setOnClickListener {
                    onWatchlistClick(stock)
                }

                root.setOnClickListener { onItemClick(stock) }
            }
        }

        private fun formatSignedDecimal(value: Double): String {
            return String.format(Locale.CHINA, "%+.2f", value)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StockInfo>() {
        override fun areItemsTheSame(old: StockInfo, new: StockInfo) = old.code == new.code
        override fun areContentsTheSame(old: StockInfo, new: StockInfo) = old == new
    }
}
