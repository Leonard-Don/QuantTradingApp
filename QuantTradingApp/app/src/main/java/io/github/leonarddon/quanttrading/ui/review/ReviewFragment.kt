package io.github.leonarddon.quanttrading.ui.review

import android.content.ClipData
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.databinding.FragmentReviewBinding
import io.github.leonarddon.quanttrading.model.DailyResearchBriefReport
import io.github.leonarddon.quanttrading.model.MarketAnalysisReport
import io.github.leonarddon.quanttrading.model.PortfolioHolding
import io.github.leonarddon.quanttrading.model.PortfolioHoldingReport
import io.github.leonarddon.quanttrading.model.PortfolioStressReport
import io.github.leonarddon.quanttrading.model.ResearchPlanReport
import io.github.leonarddon.quanttrading.model.ReviewData
import io.github.leonarddon.quanttrading.model.ReviewSnapshot
import io.github.leonarddon.quanttrading.model.StockInfo
import io.github.leonarddon.quanttrading.model.WatchlistHealthReport
import io.github.leonarddon.quanttrading.viewmodel.ReviewViewModel
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
        val tabs = listOf("市场总览", "板块轮动", "资金流向", "龙虎榜", "历史回溯", "自选体检", "持仓组合", "压力测试", "研究简报", "研究计划")
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

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { _ ->
            // 可以显示加载动画
        }
    }

    private fun updateMarketIndices(indices: List<io.github.leonarddon.quanttrading.model.MarketOverview>) {
        binding.tvMarketIndices.text = if (indices.isEmpty()) {
            "指数 quote 暂不可用，市场概况将使用当前行情池样本统计。"
        } else {
            indices.joinToString("\n") {
                "${it.indexName} ${priceFormat.format(it.price)} (${percentFormat.format(it.changePercent / 100)})"
            }
        }
    }

    private fun setReviewContent(text: String) {
        val sections = text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("暂无内容。") }

        binding.reviewContentContainer.removeAllViews()
        sections.forEachIndexed { index, section ->
            binding.reviewContentContainer.addView(createReviewSectionView(section, index))
        }
    }

    private fun createReviewSectionView(section: String, index: Int): TextView {
        return TextView(requireContext()).apply {
            text = section
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            textSize = if (index == 0) 14f else 13f
            setLineSpacing(0f, 1.4f)
            includeFontPadding = true
            if (index == 0) {
                setTypeface(typeface, Typeface.BOLD)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    topMargin = dp(14)
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun renderSelectedTab() {
        if (currentTab == 4) {
            renderHistoryTab()
            return
        }
        if (currentTab == 5) {
            renderWatchlistHealthTab()
            return
        }
        if (currentTab == 6) {
            renderPortfolioHoldingTab()
            return
        }
        if (currentTab == 7) {
            renderPortfolioStressTab()
            return
        }
        if (currentTab == 8) {
            renderDailyBriefTab()
            return
        }
        if (currentTab == 9) {
            renderResearchPlanTab()
            return
        }

        binding.btnReviewAction.visibility = View.GONE
        val data = currentReviewData ?: return
        when (currentTab) {
            0 -> {
                binding.tvReviewSectionTitle.text = "市场温度与结构判断"
                setReviewContent(buildMarketOverviewText(data))
            }
            1 -> {
                binding.tvReviewSectionTitle.text = "板块轮动分析"
                setReviewContent(if (data.hotSectors.isEmpty()) {
                    "暂无可用板块数据。"
                } else {
                    data.hotSectors.joinToString("\n") {
                        "${it.name}: ${formatPercent(it.changePercent)} | 样本代表: ${it.leadingStock} | 样本成交额: ${priceFormat.format(it.capitalFlow)}亿"
                    }
                })
            }
            2 -> {
                binding.tvReviewSectionTitle.text = "资金流向统计"
                setReviewContent(buildCapitalFlowText(data))
            }
            else -> {
                binding.tvReviewSectionTitle.text = "龙虎榜数据"
                setReviewContent(buildDragonListText(data))
            }
        }
    }

    private fun buildMarketOverviewText(data: ReviewData): String {
        val analysisText = data.marketAnalysisReport?.let {
            buildMarketAnalysisText(it)
        } ?: "市场温度：正在等待有效行情池样本。"
        val baseText = "$analysisText\n\n统计口径：当前行情池样本，不代表全市场覆盖。\n日期：${data.date}\n样本成交额：${priceFormat.format(data.totalAmount)}亿\n\n${buildWatchlistOverviewText(data.watchlistStocks)}"

        val total = (data.upCount + data.downCount).coerceAtLeast(1)
        val upRatio = data.upCount * 100.0 / total
        val leadingSector = data.hotSectors.firstOrNull()
        val strongNames = data.strongStocks.take(3).joinToString("、") { it.name }.ifBlank { "暂无" }
        val sectorText = leadingSector?.let {
            "${it.name}样本强度 ${formatPercent(it.changePercent)}，样本代表 ${it.leadingStock}"
        } ?: "暂无可用板块样本"

        return "$baseText\n\n样本拆解：上涨样本占比 ${String.format(Locale.CHINA, "%.1f", upRatio)}%；$sectorText；强势样本记录：$strongNames。\n说明：以上为历史记录和研究参考，不构成投资建议。"
    }

    private fun buildMarketAnalysisText(report: MarketAnalysisReport): String {
        val sectors = report.focusSectors.joinToString("\n") {
            "· ${it.name}：平均涨跌 ${formatPercent(it.changePercent)}，样本成交额 ${priceFormat.format(it.capitalFlow)}亿，代表样本 ${it.leadingStock}"
        }.ifBlank { "· 暂无可用板块样本。" }
        val stocks = report.focusStocks.joinToString("\n") {
            "· ${it.name}(${it.code})：涨跌 ${formatPercent(it.changePercent)}，成交额 ${priceFormat.format(it.turnover)}亿，行业 ${it.industry}"
        }.ifBlank { "· 暂无重点样本。" }
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }
        val diagnostics = listOf(
            report.indexAlignmentText,
            report.sourceText,
            report.qualityText
        ).filter { it.isNotBlank() }.joinToString("\n")

        return buildString {
            append("市场温度：${report.score}/100（${report.grade}｜${report.regime}）\n")
            append("${report.breadthText}\n")
            append("${report.turnoverText}\n")
            append("${report.sectorText}\n")
            append("${report.watchlistText}\n")
            if (diagnostics.isNotBlank()) {
                append("$diagnostics\n")
            }
            append("\n重点板块：\n$sectors\n\n")
            append("重点样本：\n$stocks\n\n")
            append("风险雷达：\n$risks\n\n")
            append("复盘动作：\n$actions\n\n")
            append("说明：市场温度只基于当前样本、成交额、板块字段和指数 quote 做结构判断，不构成投资建议。")
        }
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
            append("\n说明：主力资金、北向资金等字段需要合规数据源，当前不生成模拟资金流。")
        }

        val topText = topAmountStocks.joinToString("\n") {
            "${it.name}(${it.code})：成交额 ${priceFormat.format(it.turnover)}亿，涨跌幅 ${formatPercent(it.changePercent)}"
        }
        val sectorText = data.hotSectors.take(5).joinToString("\n") {
            "${it.name}：样本成交额 ${priceFormat.format(it.capitalFlow)}亿，平均涨跌 ${formatPercent(it.changePercent)}"
        }
        return "$baseText\n\n样本拆解：\n成交额前列样本：\n$topText\n\n板块样本成交额：\n$sectorText"
    }

    private fun buildDragonListText(data: ReviewData): String {
        val base = "龙虎榜需要交易所或合规数据源。当前版本不生成模拟龙虎榜名单，以下仅为当前行情池的波动/成交额样本观察，不等同于龙虎榜数据。"
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
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_refresh_snapshot)
        binding.btnReviewAction.setOnClickListener {
            viewModel.refresh()
        }
        setReviewContent(if (currentHistory.isEmpty()) {
            "暂无历史快照。刷新复盘页后会保存当天样本记录。"
        } else {
            buildHistorySummary(currentHistory) + "\n\n历史快照：\n\n" +
                currentHistory.joinToString("\n\n") { snapshot ->
                "${snapshot.date}\n" +
                    "上涨/下跌样本：${snapshot.upCount}/${snapshot.downCount}，涨停/跌停样本：${snapshot.limitUpCount}/${snapshot.limitDownCount}\n" +
                    "样本成交额：${priceFormat.format(snapshot.totalAmount)}亿\n" +
                    snapshotMarketLine(snapshot) +
                    "板块记录：${snapshot.sectorSummary.ifBlank { "暂无" }}\n" +
                    "强势样本：${snapshot.strongStockSummary.ifBlank { "暂无" }}"
            }
        })
    }

    private fun renderWatchlistHealthTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_watchlist_health_title)
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_refresh_snapshot)
        binding.btnReviewAction.setOnClickListener {
            viewModel.refresh()
        }

        val data = currentReviewData
        val report = data?.watchlistHealthReport
        setReviewContent(if (data == null) {
            "正在加载自选池体检数据。"
        } else if (report == null) {
            "暂无可体检的自选样本。请先在选股页点星标加入自选池，复盘页会基于自选池同步生成体检报告。"
        } else {
            buildWatchlistHealthText(report)
        })
    }

    private fun buildWatchlistHealthText(report: WatchlistHealthReport): String {
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val focus = report.focusStocks.joinToString("\n") {
            "· ${it.name}(${it.code})：涨跌 ${formatPercent(it.changePercent)}，成交额 ${priceFormat.format(it.turnover)}亿，PE ${formatNullableMetric(it.pe)}，PB ${formatNullableMetric(it.pb)}"
        }.ifBlank { "· 暂无重点样本。" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }

        return "健康评分：${report.score}/100（${report.grade}）\n" +
            "${report.breadthText}\n" +
            "集中度：${report.concentrationText}\n" +
            "估值：${report.valuationText}\n" +
            "趋势：${report.trendText}\n\n" +
            "风险雷达：\n$risks\n\n" +
            "重点复盘样本：\n$focus\n\n" +
            "建议研究动作：\n$actions\n\n" +
            "说明：以上为本机自选池研究体检，不构成投资建议或交易指令。"
    }

    private fun renderPortfolioHoldingTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_portfolio_holding_title)
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = "新增/更新持仓"
        binding.btnReviewAction.setOnClickListener {
            showPortfolioHoldingDialog()
        }

        val data = currentReviewData
        val report = data?.portfolioHoldingReport
        setReviewContent(if (data == null) {
            "正在加载持仓组合数据。"
        } else if (report == null) {
            "暂无本机持仓记录。点击下方按钮录入股票代码、成本价和数量后，复盘页会基于当前 quote 估算浮盈亏、权重和风险标签。\n\n说明：持仓记录只保存在本机，不连接券商账户。"
        } else {
            buildPortfolioHoldingText(report)
        })
    }

    private fun buildPortfolioHoldingText(report: PortfolioHoldingReport): String {
        val positions = report.positions.joinToString("\n\n") { position ->
            val quoteText = position.quote?.let {
                "现价 ${priceFormat.format(it.price)}，涨跌 ${formatPercent(it.changePercent)}"
            } ?: "行情缺失，按成本价估算"
            val tags = position.riskTags.joinToString("、")
            val note = position.holding.note.takeIf { it.isNotBlank() }?.let { "\n  备注：$it" }.orEmpty()
            "· ${position.holding.name}(${position.holding.code})：权重 ${formatPositivePercent(position.weightPercent)}，浮盈亏 ${formatSignedMoney(position.profitLoss)}（${formatPercent(position.profitLossPercent)}）\n" +
                "  $quoteText；成本 ${priceFormat.format(position.holding.costPrice)}，数量 ${formatHoldingQuantity(position.holding.quantity)}；标签：$tags$note"
        }
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }

        return "组合评分：${report.score}/100（${report.grade}）\n" +
            "${report.exposureText}\n" +
            "${report.concentrationText}\n" +
            "${report.quoteCoverageText}\n\n" +
            "持仓样本：\n$positions\n\n" +
            "风险雷达：\n$risks\n\n" +
            "研究动作：\n$actions\n\n" +
            "说明：组合报告只基于本机持仓样本和 quote 估算，不接券商、不下单、不构成投资建议。"
    }

    private fun showPortfolioHoldingDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_portfolio_holding, null)
        val etCode = dialogView.findViewById<TextInputEditText>(R.id.etHoldingCode)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etHoldingName)
        val etCost = dialogView.findViewById<TextInputEditText>(R.id.etHoldingCost)
        val etQuantity = dialogView.findViewById<TextInputEditText>(R.id.etHoldingQuantity)
        val etNote = dialogView.findViewById<TextInputEditText>(R.id.etHoldingNote)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("持仓样本")
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNeutralButton("删除代码", null)
            .setNegativeButton(getString(R.string.dialog_close), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val holding = readHoldingInput(etCode, etName, etCost, etQuantity, etNote)
                if (holding == null) return@setOnClickListener
                viewModel.savePortfolioHolding(
                    code = holding.code,
                    name = holding.name,
                    costPrice = holding.costPrice,
                    quantity = holding.quantity,
                    note = holding.note
                )
                Toast.makeText(requireContext(), "持仓样本已保存：${holding.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val code = etCode.text?.toString()?.trim().orEmpty()
                if (!code.matches(Regex("\\d{6}"))) {
                    Toast.makeText(requireContext(), "请输入要删除的 6 位股票代码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.deletePortfolioHolding(code)
                Toast.makeText(requireContext(), "已删除持仓样本：$code", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun readHoldingInput(
        etCode: TextInputEditText,
        etName: TextInputEditText,
        etCost: TextInputEditText,
        etQuantity: TextInputEditText,
        etNote: TextInputEditText
    ): PortfolioHolding? {
        val code = etCode.text?.toString()?.trim().orEmpty()
        val name = etName.text?.toString()?.trim().orEmpty()
        val cost = etCost.text?.toString()?.trim()?.toDoubleOrNull()
        val quantity = etQuantity.text?.toString()?.trim()?.toDoubleOrNull()
        val note = etNote.text?.toString()?.trim().orEmpty()
        return when {
            !code.matches(Regex("\\d{6}")) -> {
                Toast.makeText(requireContext(), "请输入 6 位股票代码", Toast.LENGTH_SHORT).show()
                null
            }
            cost == null || cost <= 0.0 -> {
                Toast.makeText(requireContext(), "请输入大于 0 的成本价", Toast.LENGTH_SHORT).show()
                null
            }
            quantity == null || quantity <= 0.0 -> {
                Toast.makeText(requireContext(), "请输入大于 0 的数量", Toast.LENGTH_SHORT).show()
                null
            }
            else -> PortfolioHolding(
                code = code,
                name = name.ifBlank { code },
                costPrice = cost,
                quantity = quantity,
                note = note
            )
        }
    }

    private fun renderPortfolioStressTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_portfolio_stress_title)
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_refresh_snapshot)
        binding.btnReviewAction.setOnClickListener {
            viewModel.refresh()
        }

        val data = currentReviewData
        val report = data?.portfolioStressReport
        setReviewContent(if (data == null) {
            "正在加载自选池压力测试数据。"
        } else if (report == null) {
            "暂无可压力测试的自选样本。请先在选股页点星标加入自选池，复盘页会基于自选池生成情景压力报告。"
        } else {
            buildPortfolioStressText(report)
        })
    }

    private fun buildPortfolioStressText(report: PortfolioStressReport): String {
        val scenarios = report.scenarios.joinToString("\n\n") { scenario ->
            val impacted = scenario.impactedStocks.joinToString("、") { "${it.name}(${it.code})" }
                .ifBlank { "暂无重点样本" }
            "· ${scenario.name}（市场冲击 ${formatPercent(scenario.marketShockPercent)}）：等权估算回撤 ${formatPositivePercent(scenario.estimatedDrawdownPercent)}\n" +
                "  重点承压：$impacted\n" +
                "  ${scenario.explanation}"
        }
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }

        return "压力评分：${report.score}/100（${report.grade}）\n" +
            "${report.exposureText}\n" +
            "${report.marketBreadthText}\n" +
            "集中度：${report.concentrationText}\n" +
            "流动性：${report.liquidityText}\n\n" +
            "情景估算：\n$scenarios\n\n" +
            "风险雷达：\n$risks\n\n" +
            "建议研究动作：\n$actions\n\n" +
            "说明：压力测试基于本机自选池等权估算，不使用真实仓位、成本价或交易指令。"
    }

    private fun renderDailyBriefTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_daily_brief_title)
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_refresh_snapshot)
        binding.btnReviewAction.setOnClickListener {
            viewModel.refresh()
        }

        val data = currentReviewData
        val report = data?.dailyResearchBriefReport
        setReviewContent(if (data == null || report == null) {
            "正在生成今日研究简报。"
        } else {
            buildDailyBriefText(report)
        })
    }

    private fun buildDailyBriefText(report: DailyResearchBriefReport): String {
        val focus = report.focusItems.joinToString("\n") { "· $it" }
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val actions = report.actionItems.joinToString("\n") { "· $it" }

        return "简报评分：${report.score}/100（${report.grade}）\n" +
            "${report.headline}\n\n" +
            "市场脉搏：\n${report.marketPulse}\n\n" +
            "板块脉搏：\n${report.sectorPulse}\n\n" +
            "自选脉搏：\n${report.watchlistPulse}\n\n" +
            "今日焦点：\n$focus\n\n" +
            "风险提醒：\n$risks\n\n" +
            "研究动作：\n$actions\n\n" +
            "说明：每日简报只做复盘整理和研究记录，不构成投资建议或交易指令。"
    }

    private fun renderResearchPlanTab() {
        binding.tvReviewSectionTitle.text = getString(R.string.review_research_plan_title)
        val report = currentReviewData?.researchPlanReport
        binding.btnReviewAction.visibility = View.VISIBLE
        binding.btnReviewAction.text = getString(R.string.review_copy_research_plan)
        binding.btnReviewAction.setOnClickListener {
            if (report == null) {
                Toast.makeText(requireContext(), "研究计划仍在生成中", Toast.LENGTH_SHORT).show()
            } else {
                copyResearchPlan(report)
            }
        }

        setReviewContent(if (report == null) {
            "正在编排今日研究计划。"
        } else {
            buildResearchPlanText(report)
        })
    }

    private fun buildResearchPlanText(report: ResearchPlanReport): String {
        val tasks = report.planItems.joinToString("\n\n") {
            "· [${it.priority}] ${it.title}（${it.source}）\n  ${it.detail}"
        }
        val risks = report.riskReviewItems.joinToString("\n") { "· $it" }
        val checklist = report.trackingChecklist.joinToString("\n") { "· $it" }

        return "计划评分：${report.score}/100（${report.grade}）\n" +
            "${report.headline}\n\n" +
            "今日主线：\n${report.dailyFocus}\n\n" +
            "研究任务：\n$tasks\n\n" +
            "风险复查：\n$risks\n\n" +
            "跟踪清单：\n$checklist\n\n" +
            "说明：研究计划用于复盘流程管理，不构成投资建议或交易指令。"
    }

    private fun copyResearchPlan(report: ResearchPlanReport) {
        val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Quant 交易台研究计划", report.exportText))
        Toast.makeText(requireContext(), getString(R.string.review_research_plan_copied), Toast.LENGTH_SHORT).show()
    }

    private fun buildHistorySummary(history: List<ReviewSnapshot>): String {
        val latest = history.first()
        val base = buildString {
            append("历史摘要：已保存 ${history.size} 条本机复盘快照。\n")
            append("最近快照：${latest.date}，上涨/下跌样本 ${latest.upCount}/${latest.downCount}，")
            append("样本成交额 ${priceFormat.format(latest.totalAmount)}亿。\n")
            append(buildMarketSnapshotTrend(history))
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

    private fun snapshotMarketLine(snapshot: ReviewSnapshot): String {
        if (snapshot.marketScore <= 0) return ""
        val summary = snapshot.marketSummary.takeIf { it.isNotBlank() }
            ?.let { "\n市场摘要：${it.take(120)}${if (it.length > 120) "..." else ""}" }
            .orEmpty()
        return "市场温度：${snapshot.marketScore}/100（${snapshot.marketGrade.ifBlank { "暂无" }}｜${snapshot.marketRegime.ifBlank { "暂无" }}）$summary\n"
    }

    private fun buildMarketSnapshotTrend(history: List<ReviewSnapshot>): String {
        val valid = history.filter { it.marketScore > 0 }
        if (valid.isEmpty()) {
            return "市场温度：旧快照暂无温度字段，刷新后会开始沉淀趋势。\n"
        }

        val latest = valid.first()
        val trendLine = valid.take(5).joinToString(" -> ") {
            "${it.date.takeLast(5)} ${it.marketScore}/${it.marketRegime.ifBlank { "暂无" }}"
        }
        val compareText = valid.getOrNull(1)?.let { previous ->
            val delta = latest.marketScore - previous.marketScore
            "，较上一快照 ${formatSignedCount(delta)} 分（${previous.marketRegime.ifBlank { "暂无" }} -> ${latest.marketRegime.ifBlank { "暂无" }}）"
        }.orEmpty()
        return "市场温度：${latest.marketScore}/100（${latest.marketGrade.ifBlank { "暂无" }}｜${latest.marketRegime.ifBlank { "暂无" }}）$compareText。\n最近趋势：$trendLine。\n"
    }

    private fun formatPercent(value: Double): String {
        return "${if (value >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", value)}%"
    }

    private fun formatPositivePercent(value: Double): String {
        return "${String.format(Locale.CHINA, "%.2f", value)}%"
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

    private fun formatSignedMoney(value: Double): String {
        return "${if (value >= 0) "+" else ""}${priceFormat.format(value)}元"
    }

    private fun formatHoldingQuantity(value: Double): String {
        return if (value % 1.0 == 0.0) {
            String.format(Locale.CHINA, "%.0f股", value)
        } else {
            String.format(Locale.CHINA, "%.2f股", value)
        }
    }

    private fun formatNullableMetric(value: Double): String {
        return if (value > 0) priceFormat.format(value) else "暂无"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
