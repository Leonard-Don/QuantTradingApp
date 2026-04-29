package com.tianxian.quant.ui.stock

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.tianxian.quant.R
import com.tianxian.quant.databinding.FragmentStockSelectBinding
import com.tianxian.quant.model.StockFilterCriteria
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.model.StockResearchPolicy
import com.tianxian.quant.model.StockResearchReport
import com.tianxian.quant.ui.vip.VipActivity
import com.tianxian.quant.viewmodel.StockSelectViewModel
import java.util.Locale

class StockSelectFragment : Fragment() {

    private var _binding: FragmentStockSelectBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StockSelectViewModel by viewModels()
    private lateinit var adapter: StockListAdapter
    private val filterChipMap = mutableMapOf<String, Chip>()
    private var pendingVipFilter: String? = null

    private val stockVipLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val filter = pendingVipFilter
        pendingVipFilter = null
        if (result.resultCode == Activity.RESULT_OK && filter != null) {
            viewModel.applyVipFilterAfterReturn(filter)
        } else {
            viewModel.refreshVipState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStockSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupFilterChips()
        setupSwipeRefresh()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = StockListAdapter(
            onItemClick = { stock ->
                if (stock.isVip) {
                    openStockSubscription()
                } else {
                    showStockDetail(stock)
                }
            },
            onWatchlistClick = { stock ->
                viewModel.toggleWatchlist(stock)
            }
        )

        // 根据屏幕宽度自动选择列数
        val spanCount = resources.getInteger(R.integer.stock_grid_span)
        if (spanCount > 1) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        } else {
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        }
        binding.recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            val keyword = text?.toString()?.trim() ?: ""
            if (keyword.length >= 2 || keyword.isEmpty()) {
                viewModel.searchStocks(keyword)
            }
        }
    }

    private fun setupFilterChips() {
        binding.filterChips.removeAllViews()
        filterChipMap.clear()
        val filters = viewModel.getFilterOptions()
        filters.forEach { filter ->
            val chip = Chip(requireContext()).apply {
                text = filter
                isCheckable = true
                isChecked = filter == "全部"
                setOnClickListener {
                    val applied = viewModel.filterByCategory(filter)
                    if (!applied) {
                        isChecked = false
                        openStockSubscription(filter)
                    }
                }
            }
            filterChipMap[filter] = chip
            binding.filterChips.addView(chip)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    private fun setupFab() {
        binding.fabFilter?.setOnClickListener {
            showAdvancedFilterDialog()
        }
    }

    private fun observeData() {
        viewModel.stocks.observe(viewLifecycleOwner) { stocks ->
            adapter.submitList(stocks) {
                if (stocks.isNotEmpty()) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
            binding.tvEmptyState.visibility = if (stocks.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefresh.isRefreshing = isLoading
        }
        viewModel.dataStatus.observe(viewLifecycleOwner) { status ->
            binding.tvDataStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvDataStatus.text = status.orEmpty()
        }
        viewModel.criteriaState.observe(viewLifecycleOwner) { criteria ->
            updateFilterSummary(criteria)
            updateCheckedChip(criteria)
        }
        viewModel.watchlistCodes.observe(viewLifecycleOwner) { codes ->
            adapter.setWatchlistCodes(codes)
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVipState()
    }

    private fun showAdvancedFilterDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_stock_filter, null)
        val etMinChange = dialogView.findViewById<TextInputEditText>(R.id.etMinChange)
        val etMinVolume = dialogView.findViewById<TextInputEditText>(R.id.etMinVolume)
        val etMinTurnover = dialogView.findViewById<TextInputEditText>(R.id.etMinTurnover)
        val etMaxPe = dialogView.findViewById<TextInputEditText>(R.id.etMaxPe)
        val etMaxPb = dialogView.findViewById<TextInputEditText>(R.id.etMaxPb)
        val etMinMarketCap = dialogView.findViewById<TextInputEditText>(R.id.etMinMarketCap)
        val current = viewModel.getCurrentCriteria()

        etMinChange.setText(current.minChangePercent?.let(::formatFilterDecimal).orEmpty())
        etMinVolume.setText(current.minVolume?.let(::formatFilterLong).orEmpty())
        etMinTurnover.setText(current.minTurnover?.let(::formatFilterDecimal).orEmpty())
        etMaxPe.setText(current.maxPe?.let(::formatFilterDecimal).orEmpty())
        etMaxPb.setText(current.maxPb?.let(::formatFilterDecimal).orEmpty())
        etMinMarketCap.setText(current.minMarketCap?.let(::formatFilterDecimal).orEmpty())

        fun TextInputEditText.doubleValue(): Double? {
            return text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        }

        fun TextInputEditText.longValue(): Long? {
            return text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("基础条件筛选")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                viewModel.applyAdvancedFilter(
                    minChangePercent = etMinChange.doubleValue(),
                    minVolume = etMinVolume.longValue(),
                    minTurnover = etMinTurnover.doubleValue(),
                    maxPe = etMaxPe.doubleValue(),
                    maxPb = etMaxPb.doubleValue(),
                    minMarketCap = etMinMarketCap.doubleValue()
                )
            }
            .setNeutralButton("清空") { _, _ ->
                viewModel.applyAdvancedFilter(null, null, null, null, null, null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showStockDetail(stock: StockInfo) {
        val message = buildString {
            append("代码：${stock.code}\n")
            append("行业标签：${stock.industry}\n\n")
            append("现价：${formatPrice(stock.price)}\n")
            append("涨跌幅：${formatSignedPercent(stock.changePercent)}\n")
            append("成交量：${formatVolume(stock.volume)}\n")
            append("成交额：${formatAmount(stock.turnover)}\n")
            append("市值：${formatAmount(stock.marketCap)}\n")
            append("PE：${stock.pe.takeIf { it > 0 }?.let { formatPrice(it) } ?: "暂无"}\n")
            append("PB：${stock.pb.takeIf { it > 0 }?.let { formatPrice(it) } ?: "暂无"}\n")
            if (stock.ma5 > 0 && stock.ma10 > 0 && stock.ma20 > 0) {
                append("\n均线观察：\n")
                append("MA5 ${formatPrice(stock.ma5)} · MA10 ${formatPrice(stock.ma10)} · MA20 ${formatPrice(stock.ma20)}\n")
            }
            if (stock.open > 0 || stock.high > 0 || stock.low > 0 || stock.yesterdayClose > 0) {
                append("\n日内记录：\n")
                append("开盘 ${formatPrice(stock.open)} · 最高 ${formatPrice(stock.high)} · 最低 ${formatPrice(stock.low)} · 昨收 ${formatPrice(stock.yesterdayClose)}\n")
            }
            append("\n说明：以上为公开行情字段和本机筛选记录，仅作数据观察和研究参考。")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(stock.name)
            .setMessage(message)
            .setPositiveButton(getString(R.string.stock_research_button)) { _, _ ->
                showStockResearchReport(stock)
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun showStockResearchReport(stock: StockInfo) {
        if (viewModel.isVipActive.value != true) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.stock_research_locked_title))
                .setMessage(getString(R.string.stock_research_locked_message, stock.name))
                .setPositiveButton(getString(R.string.review_open_vip)) { _, _ ->
                    openStockSubscription()
                }
                .setNegativeButton(getString(R.string.dialog_close), null)
                .show()
            return
        }

        val report = StockResearchPolicy.evaluate(stock)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stock_research_title, stock.name))
            .setMessage(buildStockResearchText(report))
            .setPositiveButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun buildStockResearchText(report: StockResearchReport): String {
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }
        return "研究评分：${report.score}/100（${report.grade}）\n" +
            "趋势：${report.trendText}\n" +
            "估值：${report.valuationText}\n" +
            "流动性：${report.liquidityText}\n\n" +
            "风险雷达：\n$risks\n\n" +
            "建议研究动作：\n$actions\n\n" +
            "说明：个股诊断只做行情字段研究和风险识别，不构成投资建议或交易指令。"
    }

    private fun openStockSubscription(pendingFilter: String? = null) {
        pendingVipFilter = pendingFilter
        stockVipLauncher.launch(VipActivity.createIntent(requireContext(), finishOnSuccess = true))
    }

    private fun updateFilterSummary(criteria: StockFilterCriteria) {
        binding.tvFilterSummary.visibility = View.VISIBLE
        binding.tvFilterSummary.text = buildFilterSummary(criteria)
    }

    private fun buildFilterSummary(criteria: StockFilterCriteria): String {
        val parts = mutableListOf<String>()
        if (criteria.sortMode != "全部") parts += "模式 ${criteria.sortMode}"
        criteria.industry?.let { parts += "行业 $it" }
        criteria.minChangePercent?.let { parts += "涨跌幅 >= ${formatSignedPercent(it)}" }
        criteria.minVolume?.let { parts += "成交量 >= ${formatVolume(it)}" }
        criteria.minTurnover?.let { parts += "成交额 >= ${formatAmount(it)}" }
        criteria.maxPe?.let { parts += "PE <= ${formatPrice(it)}" }
        criteria.maxPb?.let { parts += "PB <= ${formatPrice(it)}" }
        criteria.minMarketCap?.let { parts += "市值 >= ${formatAmount(it)}" }
        return if (parts.isEmpty()) {
            "当前条件：全部样本"
        } else {
            "当前条件：" + parts.joinToString(" · ")
        }
    }

    private fun updateCheckedChip(criteria: StockFilterCriteria) {
        val selected = criteria.industry ?: criteria.sortMode
        filterChipMap.forEach { (label, chip) ->
            chip.isChecked = label == selected
        }
        if (filterChipMap.none { it.value.isChecked }) {
            filterChipMap["全部"]?.isChecked = true
        }
    }

    private fun formatPrice(value: Double): String {
        return String.format(Locale.CHINA, "%.2f", value)
    }

    private fun formatFilterDecimal(value: Double): String {
        return String.format(Locale.CHINA, "%s", value)
    }

    private fun formatFilterLong(value: Long): String {
        return String.format(Locale.CHINA, "%d", value)
    }

    private fun formatSignedPercent(value: Double): String {
        return "${if (value >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", value)}%"
    }

    private fun formatAmount(value: Double): String {
        return if (value > 0) "${String.format(Locale.CHINA, "%.2f", value)}亿" else "暂无"
    }

    private fun formatVolume(value: Long): String {
        return when {
            value >= 100000000 -> String.format(Locale.CHINA, "%.2f亿股", value / 100000000.0)
            value >= 10000 -> String.format(Locale.CHINA, "%.2f万股", value / 10000.0)
            else -> "${value}股"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
