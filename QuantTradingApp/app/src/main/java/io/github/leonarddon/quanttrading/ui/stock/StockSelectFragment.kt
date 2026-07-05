package io.github.leonarddon.quanttrading.ui.stock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.databinding.DialogPriceAlertBinding
import io.github.leonarddon.quanttrading.databinding.DialogStockDetailBinding
import io.github.leonarddon.quanttrading.databinding.FragmentStockSelectBinding
import io.github.leonarddon.quanttrading.model.PriceAlert
import io.github.leonarddon.quanttrading.model.PriceAlertDirection
import io.github.leonarddon.quanttrading.model.StockBoardPolicy
import io.github.leonarddon.quanttrading.model.StockBoardSnapshot
import io.github.leonarddon.quanttrading.model.StockFilterCriteria
import io.github.leonarddon.quanttrading.model.StockSectorPulse
import io.github.leonarddon.quanttrading.model.StockInfo
import io.github.leonarddon.quanttrading.model.StockPriceAlertPolicy
import io.github.leonarddon.quanttrading.model.StockResearchPolicy
import io.github.leonarddon.quanttrading.model.StockResearchReport
import io.github.leonarddon.quanttrading.viewmodel.StockSelectViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class StockSelectFragment : Fragment() {

    private var _binding: FragmentStockSelectBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StockSelectViewModel by viewModels()
    private lateinit var adapter: StockListAdapter
    private val filterChipMap = mutableMapOf<String, Chip>()

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
                showStockDetail(stock)
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
                    viewModel.filterByCategory(filter)
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
        binding.btnClearFilter?.setOnClickListener {
            viewModel.clearFilters()
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
        viewModel.boardSnapshot.observe(viewLifecycleOwner) { snapshot ->
            renderBoardSnapshot(snapshot)
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
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
        val dialogBinding = DialogStockDetailBinding.inflate(layoutInflater)
        val insight = StockBoardPolicy.evaluateDetail(stock)
        dialogBinding.tvDetailName.text = stock.name
        dialogBinding.tvDetailCode.text = getString(
            R.string.stock_detail_code_industry,
            stock.code,
            stock.industry
        )
        dialogBinding.tvDetailPrice.text = formatPrice(stock.price)
        dialogBinding.tvDetailChange.text = formatSignedPercent(stock.changePercent)
        dialogBinding.tvDetailChange.setTextColor(
            requireContext().getColor(
                if (stock.changePercent >= 0) R.color.stock_up else R.color.stock_down
            )
        )
        dialogBinding.tvDetailTone.text = insight.toneText
        dialogBinding.tvDetailStrength.text = getString(
            R.string.stock_detail_strength,
            insight.score,
            insight.grade
        )
        dialogBinding.progressDetailStrength.progress = insight.score
        dialogBinding.tvDetailQuote.text = getString(R.string.stock_detail_quote, insight.priceActionText)
        dialogBinding.tvDetailValuation.text = getString(R.string.stock_detail_valuation, insight.valuationText)
        dialogBinding.tvDetailLiquidity.text = getString(R.string.stock_detail_liquidity, insight.liquidityText)
        dialogBinding.tvDetailRisks.text = insight.riskItems.joinToString("\n") { "· $it" }
        dialogBinding.tvDetailActions.text = insight.researchActions.joinToString("\n") { "· $it" }

        AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.stock_research_button)) { _, _ ->
                showStockResearchReport(stock)
            }
            .setNeutralButton(getString(R.string.stock_price_alert_button)) { _, _ ->
                showPriceAlertDialog(stock)
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun showPriceAlertDialog(stock: StockInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existingAlert = viewModel.getPriceAlert(stock.code)
            val dialogBinding = DialogPriceAlertBinding.inflate(layoutInflater)

            dialogBinding.etAlertTargetPrice.setText(
                existingAlert?.targetPrice?.let(::formatPrice).orEmpty()
            )
            if (existingAlert?.direction == PriceAlertDirection.BELOW) {
                dialogBinding.rbAlertBelow.isChecked = true
            } else {
                dialogBinding.rbAlertAbove.isChecked = true
            }

            fun selectedDirection(): PriceAlertDirection {
                return if (dialogBinding.rbAlertBelow.isChecked) {
                    PriceAlertDirection.BELOW
                } else {
                    PriceAlertDirection.ABOVE
                }
            }

            fun renderStatus() {
                val targetPrice = dialogBinding.etAlertTargetPrice.text
                    ?.toString()
                    ?.trim()
                    ?.toDoubleOrNull()
                if (targetPrice == null || targetPrice <= 0.0) {
                    dialogBinding.tvAlertStatus.text = getString(
                        R.string.price_alert_status_hint,
                        formatPrice(stock.price)
                    )
                    return
                }
                val draftAlert = PriceAlert(
                    code = stock.code,
                    name = stock.name,
                    targetPrice = targetPrice,
                    direction = selectedDirection()
                )
                dialogBinding.tvAlertStatus.text =
                    StockPriceAlertPolicy.evaluate(draftAlert, stock.price).statusText
            }

            dialogBinding.etAlertTargetPrice.doOnTextChanged { _, _, _, _ -> renderStatus() }
            dialogBinding.rgAlertDirection.setOnCheckedChangeListener { _, _ -> renderStatus() }
            renderStatus()

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.price_alert_dialog_title, stock.name))
                .setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.price_alert_save), null)
                .setNeutralButton(getString(R.string.price_alert_delete), null)
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val targetPrice = dialogBinding.etAlertTargetPrice.text
                        ?.toString()
                        ?.trim()
                        ?.toDoubleOrNull()
                    if (targetPrice == null || targetPrice <= 0.0) {
                        dialogBinding.etAlertTargetPrice.error =
                            getString(R.string.price_alert_invalid_target)
                        return@setOnClickListener
                    }
                    viewModel.savePriceAlert(stock, targetPrice, selectedDirection())
                    dialog.dismiss()
                }
                val deleteButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                deleteButton.visibility = if (existingAlert == null) View.GONE else View.VISIBLE
                deleteButton.setOnClickListener {
                    viewModel.deletePriceAlert(stock.code, stock.name)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }

    private fun renderBoardSnapshot(snapshot: StockBoardSnapshot?) {
        if (snapshot == null) {
            binding.stockBoardSummary.visibility = View.GONE
            return
        }

        binding.stockBoardSummary.visibility = View.VISIBLE
        binding.tvBoardHeadline.text = getString(
            R.string.stock_board_headline,
            snapshot.sampleCount,
            formatPrice(snapshot.totalTurnover)
        )
        binding.tvBoardBreadth.text = getString(
            R.string.stock_board_breadth,
            snapshot.upCount,
            snapshot.downCount,
            snapshot.flatCount
        )
        binding.tvBoardGainers.text = getString(
            R.string.stock_board_gainers,
            formatStockList(snapshot.topGainers)
        )
        binding.tvBoardLosers.text = getString(
            R.string.stock_board_losers,
            formatStockList(snapshot.topLosers)
        )
        binding.tvBoardSectors.text = getString(
            R.string.stock_board_sectors,
            formatSectorList(snapshot.hotSectors)
        )
    }

    private fun showStockResearchReport(stock: StockInfo) {
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

    private fun updateFilterSummary(criteria: StockFilterCriteria) {
        binding.tvFilterSummary.visibility = View.VISIBLE
        binding.tvFilterSummary.text = buildFilterSummary(criteria)
        binding.btnClearFilter?.visibility = if (criteria.hasActiveFilter()) View.VISIBLE else View.GONE
    }

    private fun StockFilterCriteria.hasActiveFilter(): Boolean {
        return this != StockFilterCriteria()
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

    private fun formatStockList(stocks: List<StockInfo>): String {
        return stocks.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { "${it.name} ${formatSignedPercent(it.changePercent)}" }
            ?: getString(R.string.stock_board_empty)
    }

    private fun formatSectorList(sectors: List<StockSectorPulse>): String {
        return sectors.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") {
                "${it.name} ${formatSignedPercent(it.averageChangePercent)}，${it.leadingStockName} ${formatSignedPercent(it.leadingChangePercent)}"
            }
            ?: getString(R.string.stock_board_empty)
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
