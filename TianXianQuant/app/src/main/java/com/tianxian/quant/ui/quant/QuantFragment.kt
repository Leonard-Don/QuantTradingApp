package com.tianxian.quant.ui.quant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.tianxian.quant.R
import com.tianxian.quant.databinding.FragmentQuantBinding
import com.tianxian.quant.model.BacktestAnalysisPolicy
import com.tianxian.quant.model.BacktestAnalysisReport
import com.tianxian.quant.model.BacktestMetrics
import com.tianxian.quant.model.QuantDiagnosticReport
import com.tianxian.quant.model.Strategy
import com.tianxian.quant.ui.vip.VipActivity
import com.tianxian.quant.viewmodel.QuantViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

class QuantFragment : Fragment() {

    private var _binding: FragmentQuantBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuantViewModel by viewModels()
    private lateinit var adapter: StrategyAdapter
    private var currentDiagnosticReport: QuantDiagnosticReport? = null
    private var scrollToCreatedStrategy = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        setupDiagnosticAction()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = StrategyAdapter(
            onItemClick = { strategy -> showStrategyDetail(strategy) },
            onBacktestClick = { strategy -> runBacktest(strategy) },
            onVipClick = { openQuantSubscription() }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadStrategies()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    private fun setupFab() {
        binding.fabCreate.setOnClickListener {
            showCreateStrategyDialog()
        }
    }

    private fun setupDiagnosticAction() {
        binding.btnQuantDiagnostic.setOnClickListener {
            showDiagnosticReport()
        }
    }

    private fun observeData() {
        viewModel.strategies.observe(viewLifecycleOwner) { strategies ->
            adapter.submitList(strategies) {
                if (scrollToCreatedStrategy && strategies.isNotEmpty()) {
                    binding.recyclerView.smoothScrollToPosition(0)
                    scrollToCreatedStrategy = false
                }
            }
        }
        viewModel.isVipActive.observe(viewLifecycleOwner) { active ->
            adapter.setVipActive(active)
            updateDiagnosticButton()
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefresh.isRefreshing = isLoading
        }
        viewModel.backtestResult.observe(viewLifecycleOwner) { result ->
            result?.let { showBacktestResult(it) }
        }
        viewModel.backtestError.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearBacktestError()
            }
        }
        viewModel.signalStatus.observe(viewLifecycleOwner) { status ->
            binding.tvSignalStatus.text = status
        }
        viewModel.quantSignals.observe(viewLifecycleOwner) { signals ->
            binding.tvQuantSignals.text = if (signals.isEmpty()) {
                "暂无可展示的模型信号观察。"
            } else {
                signals.joinToString("\n\n") { signal ->
                    "${signal.name}(${signal.code}) · ${signal.modelName}\n" +
                        "状态：${signal.state} · 强度 ${signal.strength}/100\n" +
                        signal.factors.joinToString(" · ")
                }
            }
        }
        viewModel.diagnosticReport.observe(viewLifecycleOwner) { report ->
            currentDiagnosticReport = report
            updateDiagnosticButton()
        }
    }

    private fun updateDiagnosticButton() {
        val report = currentDiagnosticReport
        binding.btnQuantDiagnostic.text = if (viewModel.isVipActive.value == true && report != null) {
            getString(R.string.quant_diagnostic_button_score, report.score)
        } else {
            getString(R.string.quant_diagnostic_button_locked)
        }
    }

    private fun showStrategyDetail(strategy: Strategy) {
        if (strategy.isVip && viewModel.isVipActive.value != true) {
            val preview = buildString {
                append("VIP 模型预览：\n")
                append(strategy.description)
                append("\n\n可查看方向：${strategy.tags.joinToString("、")}")
                append("\n\n开通量化 VIP 后可查看完整研究公式、样本指标，并运行历史模拟回测。")
                append("\n\n说明：模型仅用于历史样本研究，不提供具体交易指令。")
            }
            AlertDialog.Builder(requireContext())
                .setTitle(strategy.name)
                .setMessage(preview)
                .setPositiveButton("去订阅") { _, _ ->
                    openQuantSubscription()
                }
                .setNegativeButton("关闭", null)
                .show()
            return
        }

        val message = buildString {
            append("策略描述：\n${strategy.description}\n\n")
            if (strategy.formula.isNotBlank()) {
                append("研究公式：\n${strategy.formula}\n\n")
            }
            append("历史样本指标：\n")
            append("• 样本年化：${String.format(Locale.CHINA, "%.1f", strategy.annualReturn)}%\n")
            append("• 样本胜率：${String.format(Locale.CHINA, "%.1f", strategy.winRate)}%\n")
            append("• 最大回撤：${String.format(Locale.CHINA, "%.1f", strategy.maxDrawdown)}%\n")
            append("• 夏普比率：${String.format(Locale.CHINA, "%.2f", strategy.sharpeRatio)}\n")
            append("• 样本次数：${strategy.totalTrades}\n")
            append("• 盈亏比：${String.format(Locale.CHINA, "%.2f", strategy.profitFactor)}\n")
            append("\n标签：${strategy.tags.joinToString("、")}")
            append("\n\n说明：以上为历史模拟和样本统计，不构成投资建议。")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(strategy.name)
            .setMessage(message)
            .setPositiveButton("历史模拟") { _, _ -> runBacktest(strategy) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun runBacktest(strategy: Strategy) {
        if (viewModel.isVipActive.value != true) {
            AlertDialog.Builder(requireContext())
                .setTitle("开通量化 VIP")
                .setMessage("历史模拟回测为量化 VIP 功能。免费用户可查看模型说明和样本指标。")
                .setPositiveButton("去订阅") { _, _ ->
                    openQuantSubscription()
                }
                .setNegativeButton("稍后再说", null)
                .show()
            return
        }

        showBacktestConfigDialog(strategy)
    }

    private fun showBacktestConfigDialog(strategy: Strategy) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_backtest_config, null)
        val tvStrategy = dialogView.findViewById<android.widget.TextView>(R.id.tvBacktestStrategy)
        val etStockCode = dialogView.findViewById<TextInputEditText>(R.id.etBacktestStockCode)
        val etStartDate = dialogView.findViewById<TextInputEditText>(R.id.etBacktestStartDate)
        val etEndDate = dialogView.findViewById<TextInputEditText>(R.id.etBacktestEndDate)
        val defaultEndDate = LocalDate.now()
        val defaultStartDate = defaultEndDate.minusYears(1)

        tvStrategy.text = strategy.name
        etStockCode.setText(getString(R.string.backtest_default_stock_code))
        etStartDate.setText(defaultStartDate.toString())
        etEndDate.setText(defaultEndDate.toString())

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("历史模拟参数")
            .setView(dialogView)
            .setPositiveButton("开始模拟", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val stockCode = etStockCode.text?.toString()?.trim().orEmpty()
                val startDate = etStartDate.text?.toString()?.trim().orEmpty()
                val endDate = etEndDate.text?.toString()?.trim().orEmpty()
                if (!stockCode.matches(Regex("\\d{6}"))) {
                    Toast.makeText(requireContext(), "请输入 6 位股票代码", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val error = validateBacktestDates(startDate, endDate)
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                viewModel.runBacktest(strategy.id, stockCode, startDate, endDate)
                Toast.makeText(requireContext(), "正在运行历史模拟...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validateBacktestDates(startDate: String, endDate: String): String? {
        return try {
            val start = LocalDate.parse(startDate)
            val end = LocalDate.parse(endDate)
            when {
                start.isAfter(end) -> "开始日期不能晚于结束日期"
                end.isAfter(LocalDate.now()) -> "结束日期不能晚于今天"
                start.isBefore(LocalDate.now().minusYears(10)) -> "回测周期暂支持近 10 年样本"
                else -> null
            }
        } catch (e: DateTimeParseException) {
            "请输入 yyyy-MM-dd 格式日期"
        }
    }

    private fun showBacktestResult(result: QuantViewModel.BacktestResult) {
        val analysis = BacktestAnalysisPolicy.evaluate(
            BacktestMetrics(
                totalReturn = result.totalReturn.toDouble(),
                annualizedReturn = result.annualizedReturn.toDouble(),
                maxDrawdown = result.maxDrawdown.toDouble(),
                sharpeRatio = result.sharpeRatio.toDouble(),
                winRate = result.winRate.toDouble(),
                totalTrades = result.totalTrades,
                profitTrades = result.profitTrades
            )
        )
        val message = buildString {
            append("标的代码：${result.stockCode}\n")
            append("回测周期：${result.startDate} ~ ${result.endDate}\n\n")
            append("数据口径：${result.dataSource}，样本交易日 ${result.sampleDays} 天\n")
            append("${result.dataStatus}\n\n")
            append("历史模拟指标：\n")
            append("• 样本总收益：${String.format(Locale.CHINA, "%.1f", result.totalReturn)}%\n")
            append("• 样本年化：${String.format(Locale.CHINA, "%.1f", result.annualizedReturn)}%\n")
            append("• 最大回撤：${String.format(Locale.CHINA, "%.1f", result.maxDrawdown)}%\n\n")
            append("风险指标：\n")
            append("• 夏普比率：${String.format(Locale.CHINA, "%.2f", result.sharpeRatio)}\n\n")
            append("交易统计：\n")
            append("• 总交易次数：${result.totalTrades}\n")
            append("• 盈利交易：${result.profitTrades}\n")
            append("• 胜率：${String.format(Locale.CHINA, "%.1f", result.winRate)}%")
            append("\n\n")
            append(buildBacktestAnalysisText(analysis))
            append("\n\n历史模拟不代表未来结果，不构成投资建议。")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${result.strategyName} 回测结果")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> viewModel.clearBacktestResult() }
            .show()
    }

    private fun buildBacktestAnalysisText(report: BacktestAnalysisReport): String {
        val risks = report.riskItems.joinToString("\n") { "• $it" }
        val actions = report.researchActions.joinToString("\n") { "• $it" }
        return "VIP风控解读：\n" +
            "• 综合评分：${report.score}/100（${report.grade}）\n" +
            "• ${report.returnText}\n" +
            "• ${report.riskText}\n" +
            "• ${report.reliabilityText}\n\n" +
            "风险提示：\n$risks\n\n" +
            "后续研究动作：\n$actions"
    }

    private fun showDiagnosticReport() {
        if (viewModel.isVipActive.value != true) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.quant_diagnostic_locked_title))
                .setMessage(getString(R.string.quant_diagnostic_locked_message))
                .setPositiveButton(getString(R.string.quant_diagnostic_open_vip)) { _, _ ->
                    openQuantSubscription()
                }
                .setNegativeButton(getString(R.string.dialog_close), null)
                .show()
            return
        }

        val report = currentDiagnosticReport
        if (report == null) {
            Toast.makeText(requireContext(), getString(R.string.quant_diagnostic_loading), Toast.LENGTH_LONG).show()
            viewModel.loadStrategies()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.quant_diagnostic_title))
            .setMessage(buildDiagnosticText(report))
            .setPositiveButton(getString(R.string.quant_diagnostic_refresh)) { _, _ ->
                viewModel.loadStrategies()
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun buildDiagnosticText(report: QuantDiagnosticReport): String {
        val risks = report.riskItems.joinToString("\n") { "· $it" }
        val highlights = report.highlightStrategies.joinToString("\n") {
            "· ${it.name}：胜率 ${String.format(Locale.CHINA, "%.1f", it.winRate)}%，回撤 ${String.format(Locale.CHINA, "%.1f", it.maxDrawdown)}%，夏普 ${String.format(Locale.CHINA, "%.2f", it.sharpeRatio)}"
        }.ifBlank { "· 暂无可展示模型。" }
        val actions = report.researchActions.joinToString("\n") { "· $it" }

        return "诊断评分：${report.score}/100（${report.grade}）\n" +
            "${report.strategyCoverageText}\n" +
            "${report.signalCoverageText}\n\n" +
            "风险雷达：\n$risks\n\n" +
            "重点模型：\n$highlights\n\n" +
            "建议研究动作：\n$actions\n\n" +
            "说明：模型诊断只做历史样本研究和风险识别，不构成投资建议或交易指令。"
    }

    private fun showCreateStrategyDialog() {
        if (viewModel.isVipActive.value != true) {
            AlertDialog.Builder(requireContext())
                .setTitle("开通量化 VIP")
                .setMessage("自定义公式和自定义策略为量化 VIP 功能。")
                .setPositiveButton("去订阅") { _, _ ->
                    openQuantSubscription()
                }
                .setNegativeButton("稍后再说", null)
                .show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_new_strategy, null)

        val etName = dialogView.findViewById<TextInputEditText>(R.id.etStrategyName)
        val etDesc = dialogView.findViewById<TextInputEditText>(R.id.etStrategyDesc)
        val etFormula = dialogView.findViewById<TextInputEditText>(R.id.etStrategyFormula)
        etFormula.setText(getString(R.string.strategy_formula_default))

        AlertDialog.Builder(requireContext())
            .setTitle("创建自定义策略")
            .setView(dialogView)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val desc = etDesc.text?.toString()?.trim() ?: ""
                val formula = etFormula.text?.toString()?.trim() ?: ""

                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入策略名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!viewModel.isFormulaAllowed(formula)) {
                    Toast.makeText(requireContext(), "请输入 160 字以内的公式，仅支持变量、数字和常用运算符", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                scrollToCreatedStrategy = true
                val strategy = viewModel.createCustomStrategy(
                    name = name,
                    description = desc.ifEmpty { "自定义量化研究模型" },
                    formula = formula
                )
                binding.recyclerView.post {
                    binding.recyclerView.smoothScrollToPosition(0)
                }
                Toast.makeText(requireContext(), "策略创建成功：${strategy.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openQuantSubscription() {
        startActivity(VipActivity.createIntent(requireContext(), finishOnSuccess = true))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVipState()
    }
}
