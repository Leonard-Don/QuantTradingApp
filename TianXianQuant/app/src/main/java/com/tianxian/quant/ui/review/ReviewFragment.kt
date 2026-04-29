package com.tianxian.quant.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.tabs.TabLayout
import com.tianxian.quant.R
import com.tianxian.quant.databinding.FragmentReviewBinding
import com.tianxian.quant.model.ReviewData
import com.tianxian.quant.model.ReviewSnapshot
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.ui.vip.VipActivity
import com.tianxian.quant.viewmodel.ReviewViewModel
import kotlin.math.abs
import java.text.DecimalFormat
import java.util.Locale

class ReviewFragment : Fragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReviewViewModel by viewModels()

    private val priceFormat = DecimalFormat("#,##0.00")
    private val percentFormat = DecimalFormat("+0.00%;-0.00%")
    private var currentReviewData: ReviewData? = null
    private var currentHistory: List<ReviewSnapshot> = emptyList()
    private var isVipActive: Boolean = false
    private var currentTab: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        observeData()
    }

    private fun setupTabs() {
        val tabs = listOf("市场总览", "板块轮动", "资金流向", "龙虎榜", "历史回溯")
        tabs.forEach { tab ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(tab))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewModel.selectTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun observeData() {
        // 观察大盘指数
        viewModel.marketOverview.observe(viewLifecycleOwner) { indices ->
            updateMarketIndices(indices)
        }

        viewModel.reviewStatus.observe(viewLifecycleOwner) { status ->
            binding.tvReviewStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvReviewStatus.text = status.orEmpty()
        }

        // 观察复盘数据
        viewModel.reviewData.observe(viewLifecycleOwner) { data ->
            currentReviewData = data
            binding.apply {
                tvUpCount.text = formatInteger(data.upCount)
                tvDownCount.text = formatInteger(data.downCount)
                tvLimitUp.text = formatInteger(data.limitUpCount)
                tvLimitDown.text = formatInteger(data.limitDownCount)
                tvTotalAmount.text = getString(
                    R.string.review_amount_yi,
                    priceFormat.format(data.totalAmount)
                )

                renderSelectedTab()
            }
        }

        viewModel.selectedTab.observe(viewLifecycleOwner) { tab ->
            currentTab = tab
            renderSelectedTab()
        }

        viewModel.reviewHistory.observe(viewLifecycleOwner) { history ->
            currentHistory = history
            renderSelectedTab()
        }

        viewModel.isVipActive.observe(viewLifecycleOwner) { active ->
            isVipActive = active
            renderSelectedTab()
        }

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { _ ->
            // 可以显示加载动画
        }
    }

    private fun updateMarketIndices(indices: List<com.tianxian.quant.model.MarketOverview>) {
        binding.tvMarketIndices.text = if (indices.isEmpty()) {
            "指数 quote 暂不可用，市场概况将使用当前行情池样本统计。"
        } else {
            indices.joinToString("\n") {
                "${it.indexName} ${priceFormat.format(it.price)} (${percentFormat.format(it.changePercent / 100)})"
            }
        }
    }

    private fun renderSelectedTab() {
        if (currentTab == 4) {
            renderHistoryTab()
            return
        }

        binding.btnReviewAction.visibility = View.GONE
        val data = currentReviewData ?: return
        when (currentTab) {
            0 -> {
                binding.tvReviewSectionTitle.text = "市场总览说明"
                binding.tvHotSectors.text = buildMarketOverviewText(data)
            }
            1 -> {
                binding.tvReviewSectionTitle.text = "板块轮动分析"
                binding.tvHotSectors.text = if (data.hotSectors.isEmpty()) {
                    "暂无可用板块数据。"
                } else {
                    data.hotSectors.joinToString("\n") {
                        "${it.name}: ${formatPercent(it.changePercent)} | 样本代表: ${it.leadingStock} | 样本成交额: ${priceFormat.format(it.capitalFlow)}亿"
                    }
                }
            }
            2 -> {
                binding.tvReviewSectionTitle.text = "资金流向统计"
                binding.tvHotSectors.text = buildCapitalFlowText(data)
            }
            else -> {
                binding.tvReviewSectionTitle.text = "龙虎榜数据"
                binding.tvHotSectors.text = buildDragonListText(data)
            }
        }
    }

    private fun buildMarketOverviewText(data: ReviewData): String {
        val baseText = "统计口径：当前行情池样本，不代表全市场覆盖。\n日期：${data.date}\n样本成交额：${priceFormat.format(data.totalAmount)}亿\n\n${buildWatchlistOverviewText(data.watchlistStocks)}"
        if (!isVipActive) return baseText

        val total = (data.upCount + data.downCount).coerceAtLeast(1)
        val upRatio = data.upCount * 100.0 / total
        val leadingSector = data.hotSectors.firstOrNull()
        val strongNames = data.strongStocks.take(3).joinToString("、") { it.name }.ifBlank { "暂无" }
        val sectorText = leadingSector?.let {
            "${it.name}样本强度 ${formatPercent(it.changePercent)}，样本代表 ${it.leadingStock}"
        } ?: "暂无可用板块样本"

        return "$baseText\n\nVIP样本拆解：上涨样本占比 ${String.format(Locale.CHINA, "%.1f", upRatio)}%；$sectorText；强势样本记录：$strongNames。\n说明：以上为历史记录和研究参考，不构成投资建议。"
    }

    private fun buildWatchlistOverviewText(stocks: List<StockInfo>): String {
        if (stocks.isEmpty()) {
            return "自选池跟踪：暂无本机自选。可在选股页点星标加入，复盘页会同步展示自选样本变化。"
        }

        val upCount = stocks.count { it.changePercent > 0 }
        val downCount = stocks.count { it.changePercent < 0 }
        val avgChange = stocks.map { it.changePercent }.average()
        val turnover = stocks.sumOf { it.turnover }
        val records = stocks
            .sortedWith(compareByDescending<StockInfo> { abs(it.changePercent) }.thenByDescending { it.turnover })
            .take(3)
            .joinToString("；") {
                "${it.name} ${formatPercent(it.changePercent)}，成交额 ${priceFormat.format(it.turnover)}亿"
            }

        return "自选池跟踪：${stocks.size} 只；上涨 ${upCount} 只，下跌 ${downCount} 只，平均涨跌 ${formatPercent(avgChange)}；样本成交额 ${priceFormat.format(turnover)}亿。\n自选波动记录：$records。\n说明：自选池仅为本机数据跟踪和研究参考，不构成投资建议。"
    }

    private fun buildCapitalFlowText(data: ReviewData): String {
        if (data.sampleStocks.isEmpty()) {
            return "暂无样本成交额数据。主力资金、北向资金等字段需要接入专门数据源，当前版本不展示模拟资金流。"
        }

        val totalAmount = data.totalAmount.coerceAtLeast(0.0001)
        val upAmount = data.sampleStocks.filter { it.changePercent > 0 }.sumOf { it.turnover }
        val downAmount = data.sampleStocks.filter { it.changePercent < 0 }.sumOf { it.turnover }
        val flatAmount = data.sampleStocks.filter { it.changePercent == 0.0 }.sumOf { it.turnover }
        val topAmountStocks = data.sampleStocks.sortedByDescending { it.turnover }.take(5)

        val baseText = buildString {
            append("统计口径：当前行情池样本成交额方向，不代表全市场资金流。\n")
            append("上涨样本成交额：${priceFormat.format(upAmount)}亿，占比 ${formatRatio(upAmount, totalAmount)}\n")
            append("下跌样本成交额：${priceFormat.format(downAmount)}亿，占比 ${formatRatio(downAmount, totalAmount)}\n")
            if (flatAmount > 0) {
                append("平盘样本成交额：${priceFormat.format(flatAmount)}亿，占比 ${formatRatio(flatAmount, totalAmount)}\n")
            }
            append("\n说明：主力资金、北向资金等字段需要授权数据源，当前不生成模拟资金流。")
        }

        if (!isVipActive) return baseText

        val topText = topAmountStocks.joinToString("\n") {
            "${it.name}(${it.code})：成交额 ${priceFormat.format(it.turnover)}亿，涨跌幅 ${formatPercent(it.changePercent)}"
        }
        val sectorText = data.hotSectors.take(5).joinToString("\n") {
            "${it.name}：样本成交额 ${priceFormat.format(it.capitalFlow)}亿，平均涨跌 ${formatPercent(it.changePercent)}"
        }
        return "$baseText\n\nVIP样本拆解：\n成交额前列样本：\n$topText\n\n板块样本成交额：\n$sectorText"
    }

    private fun buildDragonListText(data: ReviewData): String {
        val base = "龙虎榜需要交易所或授权数据源。当前版本不生成模拟龙虎榜名单，以下仅为当前行情池的波动/成交额样本观察，不等同于龙虎榜数据。"
        if (data.sampleStocks.isEmpty()) return base

        val candidates = data.sampleStocks
            .sortedWith(compareByDescending<StockInfo> { abs(it.changePercent) }.thenByDescending { it.turnover })
            .take(5)
        val candidateText = candidates.joinToString("\n") {
            "${it.name}(${it.code})：涨跌幅 ${formatPercent(it.changePercent)}，成交额 ${priceFormat.format(it.turnover)}亿"
        }
        return "$base\n\n样本波动记录：\n$candidateText\n\n说明：以上为研究参考和数据记录，不构成投资建议。"
    }

    private fun renderHistoryTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_history_title)
        if (!isVipActive) {
            binding.tvHotSectors.text = getString(R.string.review_history_vip_locked)
            binding.btnReviewAction.visibility = View.VISIBLE
            binding.btnReviewAction.text = getString(R.string.review_open_vip)
            binding.btnReviewAction.setOnClickListener {
                startActivity(VipActivity.createIntent(requireContext(), finishOnSuccess = true))
            }
            return
        }

        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_refresh_snapshot)
        binding.btnReviewAction.setOnClickListener {
            viewModel.refresh()
        }
        binding.tvHotSectors.text = if (currentHistory.isEmpty()) {
            "暂无历史快照。刷新复盘页后会保存当天样本记录。"
        } else {
            buildHistorySummary(currentHistory) + "\n\n历史快照：\n\n" +
                currentHistory.joinToString("\n\n") { snapshot ->
                "${snapshot.date}\n" +
                    "上涨/下跌样本：${snapshot.upCount}/${snapshot.downCount}，涨停/跌停样本：${snapshot.limitUpCount}/${snapshot.limitDownCount}\n" +
                    "样本成交额：${priceFormat.format(snapshot.totalAmount)}亿\n" +
                    "板块记录：${snapshot.sectorSummary.ifBlank { "暂无" }}\n" +
                    "强势样本：${snapshot.strongStockSummary.ifBlank { "暂无" }}"
            }
        }
    }

    private fun buildHistorySummary(history: List<ReviewSnapshot>): String {
        val latest = history.first()
        val base = buildString {
            append("VIP历史摘要：已保存 ${history.size} 条本机复盘快照。\n")
            append("最近快照：${latest.date}，上涨/下跌样本 ${latest.upCount}/${latest.downCount}，")
            append("样本成交额 ${priceFormat.format(latest.totalAmount)}亿。\n")
        }

        if (history.size < 2) {
            return base + "积累至少两个交易日快照后，将自动展示样本强弱、成交额和涨跌停记录变化。"
        }

        val previous = history[1]
        val latestStrength = latest.upCount - latest.downCount
        val previousStrength = previous.upCount - previous.downCount
        val amountDelta = latest.totalAmount - previous.totalAmount
        return base +
            "较上一快照（${previous.date}）：样本成交额 ${formatAmountDelta(amountDelta)}，" +
            "上涨样本 ${formatSignedCount(latest.upCount - previous.upCount)}，" +
            "强弱差 ${formatSignedCount(latestStrength - previousStrength)}，" +
            "涨停样本 ${formatSignedCount(latest.limitUpCount - previous.limitUpCount)}，" +
            "跌停样本 ${formatSignedCount(latest.limitDownCount - previous.limitDownCount)}。\n" +
            "说明：历史回溯仅比较本机样本记录，用于复盘观察，不构成投资建议。"
    }

    private fun formatPercent(value: Double): String {
        return "${if (value >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", value)}%"
    }

    private fun formatInteger(value: Int): String {
        return String.format(Locale.CHINA, "%d", value)
    }

    private fun formatRatio(value: Double, total: Double): String {
        return "${String.format(Locale.CHINA, "%.1f", value * 100.0 / total)}%"
    }

    private fun formatSignedCount(value: Int): String {
        return if (value >= 0) "+$value" else value.toString()
    }

    private fun formatAmountDelta(value: Double): String {
        return "${if (value >= 0) "+" else ""}${priceFormat.format(value)}亿"
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVipState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
