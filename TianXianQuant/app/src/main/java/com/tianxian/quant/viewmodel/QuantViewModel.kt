package com.tianxian.quant.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.model.QuantSignal
import com.tianxian.quant.model.QuantFormulaPolicy
import com.tianxian.quant.model.Strategy
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.network.MarketDataResult
import com.tianxian.quant.network.MarketDataRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

class QuantViewModel : ViewModel() {

    private val _strategies = MutableLiveData<List<Strategy>>()
    val strategies: LiveData<List<Strategy>> = _strategies

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectedStrategy = MutableLiveData<Strategy>()
    val selectedStrategy: LiveData<Strategy> = _selectedStrategy

    private val _backtestResult = MutableLiveData<BacktestResult?>()
    val backtestResult: LiveData<BacktestResult?> = _backtestResult

    private val _isVipActive = MutableLiveData(false)
    val isVipActive: LiveData<Boolean> = _isVipActive

    private val _quantSignals = MutableLiveData<List<QuantSignal>>()
    val quantSignals: LiveData<List<QuantSignal>> = _quantSignals

    private val _signalStatus = MutableLiveData<String>()
    val signalStatus: LiveData<String> = _signalStatus

    private val allStrategies = mutableListOf<Strategy>()

    private val signalSampleCodes = listOf(
        "600519", "000858", "000333", "600036", "300750",
        "002594", "002475", "300059", "600030", "002415"
    )

    init {
        loadStrategies()
    }

    fun loadStrategies() {
        viewModelScope.launch {
            _isLoading.value = true
            refreshVipState()
            allStrategies.clear()
            allStrategies.addAll(LocalStateRepository.getCustomStrategies())
            allStrategies.addAll(getSeedStrategies())
            _strategies.value = allStrategies.toList()
            loadQuantSignals()
            _isLoading.value = false
        }
    }

    fun selectStrategy(strategy: Strategy) {
        _selectedStrategy.value = strategy
    }

    fun runBacktest(
        strategyId: String,
        startDate: String = LocalDate.now().minusYears(1).toString(),
        endDate: String = LocalDate.now().toString()
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            kotlinx.coroutines.delay(800)

            val strategy = allStrategies.find { it.id == strategyId }
            if (strategy == null) {
                _isLoading.value = false
                return@launch
            }
            val fallbackEnd = LocalDate.now()
            val parsedEnd = runCatching { LocalDate.parse(endDate) }.getOrDefault(fallbackEnd)
            val parsedStart = runCatching { LocalDate.parse(startDate) }.getOrDefault(parsedEnd.minusYears(1))
            val start = if (parsedStart.isAfter(parsedEnd)) parsedEnd.minusYears(1) else parsedStart
            val end = parsedEnd
            val periodDays = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1)
            val periodFactor = (periodDays / 365.0).coerceIn(0.05, 10.0)
            val stabilityFactor = when (strategy.id) {
                "1" -> 0.92
                "2" -> 0.88
                "3" -> 0.84
                "4" -> 0.76
                "5" -> 0.90
                else -> 0.72
            }
            val annualizedReturn = strategy.annualReturn * stabilityFactor
            val sampleTrades = (strategy.totalTrades * periodFactor).toInt().coerceAtLeast(1)

            _backtestResult.value = BacktestResult(
                strategyId = strategyId,
                strategyName = strategy.name,
                startDate = start.toString(),
                endDate = end.toString(),
                totalReturn = (annualizedReturn * periodFactor).toFloat(),
                maxDrawdown = strategy.maxDrawdown.toFloat(),
                sharpeRatio = strategy.sharpeRatio.toFloat(),
                winRate = strategy.winRate.toFloat(),
                totalTrades = sampleTrades,
                profitTrades = (sampleTrades * strategy.winRate / 100).toInt(),
                annualizedReturn = annualizedReturn.toFloat()
            )
            _isLoading.value = false
        }
    }

    fun clearBacktestResult() {
        _backtestResult.value = null
    }

    fun createCustomStrategy(name: String, description: String, formula: String): Strategy {
        val metrics = estimateCustomMetrics(formula)
        val strategy = Strategy(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            winRate = metrics.winRate,
            maxDrawdown = metrics.maxDrawdown,
            sharpeRatio = metrics.sharpeRatio,
            annualReturn = metrics.annualReturn,
            totalTrades = metrics.totalTrades,
            profitFactor = metrics.profitFactor,
            isVip = true,
            formula = formula,
            tags = listOf("自定义", "公式")
        )
        allStrategies.add(0, strategy)
        _strategies.value = allStrategies.toList()
        viewModelScope.launch {
            LocalStateRepository.saveCustomStrategy(strategy)
        }
        return strategy
    }

    fun isFormulaAllowed(formula: String): Boolean {
        return QuantFormulaPolicy.isAllowed(formula)
    }

    fun refreshVipState() {
        viewModelScope.launch {
            _isVipActive.value = LocalStateRepository.isQuantVipActive()
        }
    }

    private suspend fun loadQuantSignals() {
        val quoteResult = MarketDataRepository.getQuoteResult(signalSampleCodes)
        val quotes = quoteResult.getOrNull().orEmpty()
        if (quoteResult is MarketDataResult.Failure) {
            _quantSignals.value = emptyList()
            _signalStatus.value = "${quoteResult.message}，模型信号观察暂不展示离线模拟结果。"
            return
        }
        if (quotes.isEmpty()) {
            _quantSignals.value = emptyList()
            _signalStatus.value = "腾讯 quote 暂无可用行情，模型信号观察不展示离线模拟结果。"
            return
        }

        val averageResult = MarketDataRepository.getMovingAveragesResult(quotes.map { it.code })
        val averages = averageResult.getOrNull().orEmpty()
        val signals = quotes.mapNotNull { stock ->
            val average = averages[stock.code] ?: return@mapNotNull null
            buildQuantSignal(
                stock.copy(
                    ma5 = average.ma5,
                    ma10 = average.ma10,
                    ma20 = average.ma20
                )
            )
        }
            .sortedByDescending { it.strength }
            .take(3)

        _quantSignals.value = signals
        _signalStatus.value = if (signals.isEmpty()) {
            val reason = (averageResult as? MarketDataResult.Failure)?.message ?: "历史 K 线暂不可用"
            "$reason，当前不展示模型信号观察。"
        } else {
            val quoteSource = quoteResult.sourceOrNull() ?: "多源 quote"
            val averageSource = averageResult.sourceOrNull() ?: "多源 K 线"
            "基于当前样本 quote、成交额和复权日线均线生成，仅展示前三项用于模型状态观察。行情源：$quoteSource；均线源：$averageSource。"
        }
    }

    private fun buildQuantSignal(stock: StockInfo): QuantSignal {
        val trendScore = when {
            stock.price >= stock.ma5 && stock.ma5 >= stock.ma10 && stock.ma10 >= stock.ma20 -> 46
            stock.price >= stock.ma10 && stock.ma10 >= stock.ma20 -> 34
            stock.price >= stock.ma20 -> 24
            else -> 14
        }
        val amountScore = when {
            stock.turnover >= 80 -> 24
            stock.turnover >= 30 -> 18
            stock.turnover >= 10 -> 12
            else -> 6
        }
        val volatilityScore = when {
            kotlin.math.abs(stock.changePercent) <= 3 -> 18
            kotlin.math.abs(stock.changePercent) <= 7 -> 12
            else -> 6
        }
        val strength = (trendScore + amountScore + volatilityScore).coerceIn(0, 100)
        val modelState = when {
            trendScore >= 46 && amountScore >= 18 -> "趋势强度观察"
            trendScore >= 34 -> "均线修复观察"
            volatilityScore <= 6 -> "波动放大观察"
            else -> "震荡样本观察"
        }
        return QuantSignal(
            code = stock.code,
            name = stock.name,
            modelName = "均线-量能模型",
            strength = strength,
            state = modelState,
            factors = listOf(
                "MA5 ${String.format(Locale.CHINA, "%.2f", stock.ma5)} / MA20 ${String.format(Locale.CHINA, "%.2f", stock.ma20)}",
                "成交额 ${String.format(Locale.CHINA, "%.2f", stock.turnover)}亿",
                "涨跌幅 ${if (stock.changePercent >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", stock.changePercent)}%"
            )
        )
    }

    private fun getSeedStrategies(): List<Strategy> {
        return listOf(
            Strategy(
                id = "1",
                name = "趋势观察模型",
                description = "基于均线排列和动量变化记录趋势强弱，用于历史样本复盘，不输出具体交易指令。",
                winRate = 58.5,
                maxDrawdown = 15.2,
                sharpeRatio = 1.18,
                annualReturn = 12.5,
                totalTrades = 156,
                profitFactor = 1.22,
                isVip = false,
                formula = "close > ma20 && volume > avg_volume_5",
                tags = listOf("趋势", "均线")
            ),
            Strategy(
                id = "2",
                name = "均值回归观察模型",
                description = "记录价格相对均线和布林带的位置变化，适合做震荡样本的历史统计。",
                winRate = 55.3,
                maxDrawdown = 12.8,
                sharpeRatio = 1.05,
                annualReturn = 9.2,
                totalTrades = 238,
                profitFactor = 1.12,
                isVip = true,
                formula = "close < lower_band || close > upper_band",
                tags = listOf("震荡", "布林带")
            ),
            Strategy(
                id = "3",
                name = "动量强弱观察模型",
                description = "跟踪样本股票的相对强弱、成交额变化和波动区间，只用于研究参考。",
                winRate = 56.2,
                maxDrawdown = 18.5,
                sharpeRatio = 1.15,
                annualReturn = 13.8,
                totalTrades = 98,
                profitFactor = 1.20,
                isVip = true,
                formula = "close / ma60 > 1.08 && turnover > avg_turnover_20",
                tags = listOf("动量", "强弱")
            ),
            Strategy(
                id = "4",
                name = "网格记录模型",
                description = "在历史震荡样本中记录网格间距、波动和执行偏差，用于观察纪律和风险暴露。",
                winRate = 52.6,
                maxDrawdown = 8.5,
                sharpeRatio = 0.95,
                annualReturn = 6.2,
                totalTrades = 456,
                profitFactor = 1.04,
                isVip = false,
                formula = "abs(close - ma20) / ma20 < 0.08",
                tags = listOf("网格", "震荡")
            ),
            Strategy(
                id = "5",
                name = "多因子选股模型",
                description = "综合估值、成长、动量等因子做样本排序，用于观察因子稳定性和阶段适用性。",
                winRate = 57.8,
                maxDrawdown = 14.2,
                sharpeRatio = 1.10,
                annualReturn = 11.5,
                totalTrades = 120,
                profitFactor = 1.14,
                isVip = true,
                formula = "pe < 35 && pb < 6 && turnover > avg_turnover_20",
                tags = listOf("多因子", "价值")
            )
        )
    }

    private fun estimateCustomMetrics(formula: String): CustomMetrics {
        val normalized = formula.lowercase()
        val factorCount = listOf("ma", "volume", "turnover", "pe", "pb", "close")
            .count { normalized.contains(it) }
            .coerceAtLeast(1)
        val complexity = (formula.count { it == '&' || it == '|' || it == '<' || it == '>' } / 2)
            .coerceIn(1, 5)
        val base = 50.0 + factorCount * 1.4 + complexity * 0.8
        return CustomMetrics(
            winRate = base.coerceAtMost(59.0),
            maxDrawdown = (10.0 + complexity * 1.8 + factorCount * 0.6).coerceAtMost(22.0),
            sharpeRatio = (0.82 + factorCount * 0.06 + complexity * 0.03).coerceAtMost(1.28),
            annualReturn = (5.0 + factorCount * 1.1 + complexity * 0.9).coerceAtMost(14.5),
            totalTrades = 60 + factorCount * 18 + complexity * 12,
            profitFactor = (1.0 + factorCount * 0.035 + complexity * 0.025).coerceAtMost(1.28)
        )
    }

    private data class CustomMetrics(
        val winRate: Double,
        val maxDrawdown: Double,
        val sharpeRatio: Double,
        val annualReturn: Double,
        val totalTrades: Int,
        val profitFactor: Double
    )

    data class BacktestResult(
        val strategyId: String,
        val strategyName: String,
        val startDate: String,
        val endDate: String,
        val totalReturn: Float,
        val maxDrawdown: Float,
        val sharpeRatio: Float,
        val winRate: Float,
        val totalTrades: Int,
        val profitTrades: Int,
        val annualizedReturn: Float
    )
}
