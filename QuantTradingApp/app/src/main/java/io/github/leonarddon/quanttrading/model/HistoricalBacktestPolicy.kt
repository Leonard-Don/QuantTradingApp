package io.github.leonarddon.quanttrading.model

import io.github.leonarddon.quanttrading.model.StrategyFormulaPolicy.FormulaContext
import io.github.leonarddon.quanttrading.model.StrategyFormulaPolicy.FormulaNode
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

data class HistoricalBacktestTrade(
    val entryDate: String,
    val exitDate: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val returnPercent: Double,
    val code: String = ""
)

data class HistoricalBacktestResult(
    val metrics: BacktestMetrics,
    val trades: List<HistoricalBacktestTrade>,
    val sampleDays: Int
)

/**
 * A single symbol fed into [HistoricalBacktestPolicy.runPortfolio], with the capital
 * weight it should receive. Weights are normalised by the policy, so callers may pass
 * raw numbers (e.g. 2.0 / 1.0 / 1.0) and the policy turns them into fractions.
 */
data class BacktestSymbol(
    val code: String,
    val klines: List<DailyKline>,
    val weight: Double = 1.0
)

/**
 * Per-symbol contribution inside a portfolio backtest, for attribution/debugging.
 */
data class PortfolioLegResult(
    val code: String,
    val weight: Double,
    val contributionReturn: Double,
    val trades: List<HistoricalBacktestTrade>
)

/**
 * Benchmark side of a portfolio backtest: an equal-bar buy-and-hold of the benchmark
 * series over the same window, so the strategy return can be read as excess return.
 */
data class BenchmarkComparison(
    val benchmarkTotalReturn: Double,
    val benchmarkAnnualizedReturn: Double,
    val benchmarkMaxDrawdown: Double,
    val excessReturn: Double,
    val outperformed: Boolean
)

/**
 * Result of a multi-symbol, weighted portfolio backtest.
 */
data class PortfolioBacktestResult(
    val metrics: BacktestMetrics,
    val legs: List<PortfolioLegResult>,
    val benchmark: BenchmarkComparison?,
    val sampleDays: Int,
    val symbolCount: Int
)

/**
 * Historical simulation policy.
 *
 * Two entry points, both pure (no side effects, deterministic for fixed inputs):
 *  - [run]: single-symbol, full-capital simulation kept for existing callers.
 *  - [runPortfolio]: multi-symbol, weighted simulation with an optional benchmark.
 *
 * Entry/exit signals are produced by [StrategyFormulaPolicy]: the strategy formula is
 * parsed once into a [FormulaNode] AST and evaluated per bar against indicator values
 * (close, ma5/ma20/ma60, volume, avg_volume_*, turnover, etc.). The previous keyword
 * pattern-matching ("ma60" in formula -> momentum branch) has been removed.
 */
object HistoricalBacktestPolicy {

    fun run(
        strategy: Strategy,
        klines: List<DailyKline>,
        startDate: LocalDate,
        endDate: LocalDate
    ): HistoricalBacktestResult? {
        val ordered = sanitize(klines)
        if (ordered.size < MIN_SAMPLE_DAYS) return null

        val signalNode = StrategyFormulaPolicy.parse(strategy.formula)
        val sim = simulateLeg(ordered, signalNode, startDate, endDate, code = ordered.firstOrNull()?.code.orEmpty())
            ?: return null

        return HistoricalBacktestResult(
            metrics = sim.toMetrics(),
            trades = sim.trades,
            sampleDays = sim.sampleDays
        )
    }

    /**
     * Multi-symbol weighted backtest.
     *
     * Each [BacktestSymbol] is simulated independently with the strategy formula, then
     * combined by its (normalised) capital weight. A symbol whose K-line history is too
     * short is skipped; the run still succeeds as long as at least one symbol qualifies.
     * When [benchmarkKlines] is supplied, a buy-and-hold benchmark over the same window
     * is computed and the strategy's excess return is reported.
     */
    fun runPortfolio(
        strategy: Strategy,
        symbols: List<BacktestSymbol>,
        startDate: LocalDate,
        endDate: LocalDate,
        benchmarkKlines: List<DailyKline> = emptyList()
    ): PortfolioBacktestResult? {
        if (symbols.isEmpty()) return null
        val signalNode = StrategyFormulaPolicy.parse(strategy.formula)

        // Simulate each leg; drop symbols with insufficient data.
        val rawLegs = symbols.mapNotNull { symbol ->
            val ordered = sanitize(symbol.klines)
            if (ordered.size < MIN_SAMPLE_DAYS) return@mapNotNull null
            val sim = simulateLeg(ordered, signalNode, startDate, endDate, symbol.code)
                ?: return@mapNotNull null
            (symbol.weight.coerceAtLeast(0.0)) to sim
        }
        if (rawLegs.isEmpty()) return null

        val totalWeight = rawLegs.sumOf { it.first }
        if (totalWeight <= 0.0) return null

        // Normalised weights; per-leg equity curves aligned by trading-day index.
        val normalized = rawLegs.map { (weight, sim) -> (weight / totalWeight) to sim }
        val horizon = normalized.maxOf { it.second.equityCurve.size }
        if (horizon < MIN_RESULT_DAYS) return null

        // Portfolio equity per bar = weighted sum of each leg's equity (legs that have
        // already ended hold their final equity, i.e. capital sits in cash).
        val portfolioEquity = DoubleArray(horizon)
        for (bar in 0 until horizon) {
            var equity = 0.0
            for ((weight, sim) in normalized) {
                val legEquity = sim.equityCurve.getOrElse(bar) { sim.equityCurve.last() }
                equity += weight * legEquity
            }
            portfolioEquity[bar] = equity
        }

        val curve = portfolioEquity.toList()
        val finalEquity = curve.last()
        val sampleDays = horizon
        val totalReturn = (finalEquity - 1.0) * 100.0
        val annualizedReturn = annualize(finalEquity, sampleDays)
        val maxDrawdown = maxDrawdownOf(curve) * 100.0
        val dailyReturns = dailyReturnsOf(curve)

        val allTrades = normalized.flatMap { it.second.trades }
        val profitableTrades = allTrades.count { it.returnPercent > 0.0 }
        val winRate = if (allTrades.isEmpty()) 0.0 else profitableTrades * 100.0 / allTrades.size

        val legResults = normalized.map { (weight, sim) ->
            PortfolioLegResult(
                code = sim.code,
                weight = weight,
                contributionReturn = weight * (sim.equityCurve.last() - 1.0) * 100.0,
                trades = sim.trades
            )
        }

        val benchmark = buildBenchmark(benchmarkKlines, startDate, endDate, totalReturn)

        return PortfolioBacktestResult(
            metrics = BacktestMetrics(
                totalReturn = totalReturn,
                annualizedReturn = annualizedReturn,
                maxDrawdown = maxDrawdown,
                sharpeRatio = sharpeRatio(dailyReturns),
                winRate = winRate,
                totalTrades = allTrades.size,
                profitTrades = profitableTrades
            ),
            legs = legResults,
            benchmark = benchmark,
            sampleDays = sampleDays,
            symbolCount = legResults.size
        )
    }

    // ---------------------------------------------------------------------
    // Per-symbol simulation
    // ---------------------------------------------------------------------

    /** Internal per-symbol simulation output, normalised to a starting equity of 1.0. */
    private class LegSimulation(
        val code: String,
        val equityCurve: List<Double>,
        val dailyReturns: List<Double>,
        val maxDrawdown: Double,
        val trades: List<HistoricalBacktestTrade>,
        val sampleDays: Int
    ) {
        fun toMetrics(): BacktestMetrics {
            val finalEquity = equityCurve.lastOrNull() ?: 1.0
            val profitableTrades = trades.count { it.returnPercent > 0.0 }
            return BacktestMetrics(
                totalReturn = (finalEquity - 1.0) * 100.0,
                annualizedReturn = annualize(finalEquity, sampleDays),
                maxDrawdown = maxDrawdown * 100.0,
                sharpeRatio = sharpeRatio(dailyReturns),
                winRate = if (trades.isEmpty()) 0.0 else profitableTrades * 100.0 / trades.size,
                totalTrades = trades.size,
                profitTrades = profitableTrades
            )
        }
    }

    /**
     * Simulate one symbol over [startDate, endDate]. Returns null when the windowed
     * sample is too small. The leg starts fully in cash with equity 1.0.
     */
    private fun simulateLeg(
        ordered: List<DailyKline>,
        signalNode: FormulaNode?,
        startDate: LocalDate,
        endDate: LocalDate,
        code: String
    ): LegSimulation? {
        var cash = 1.0
        var positionShares = 0.0
        var entryPrice = 0.0
        var entryDate = ""
        var peakEquity = 1.0
        var maxDrawdown = 0.0
        var previousEquity = 1.0
        val dailyReturns = mutableListOf<Double>()
        val equityCurve = mutableListOf<Double>()
        val trades = mutableListOf<HistoricalBacktestTrade>()
        var sampleDays = 0

        ordered.forEachIndexed { index, kline ->
            val date = LocalDate.parse(kline.date)
            if (date.isBefore(startDate) || date.isAfter(endDate)) return@forEachIndexed
            if (index < WARMUP_DAYS) return@forEachIndexed

            sampleDays += 1
            val entrySignal = shouldEnter(signalNode, ordered, index)
            val exitSignal = shouldExit(signalNode, ordered, index, entryPrice)

            if (positionShares > 0.0 && exitSignal) {
                val exitPrice = kline.close * (1.0 - TRADE_COST_RATE)
                cash = positionShares * exitPrice
                trades += HistoricalBacktestTrade(
                    entryDate = entryDate,
                    exitDate = kline.date,
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    returnPercent = (exitPrice - entryPrice) / entryPrice * 100.0,
                    code = code
                )
                positionShares = 0.0
                entryPrice = 0.0
                entryDate = ""
            } else if (positionShares == 0.0 && entrySignal) {
                entryPrice = kline.close * (1.0 + TRADE_COST_RATE)
                if (entryPrice > 0.0) {
                    positionShares = cash / entryPrice
                    cash = 0.0
                    entryDate = kline.date
                }
            }

            val equity = cash + positionShares * kline.close
            peakEquity = maxOf(peakEquity, equity)
            maxDrawdown = maxOf(maxDrawdown, (peakEquity - equity) / peakEquity)
            if (sampleDays > 1 && previousEquity > 0.0) {
                dailyReturns += (equity - previousEquity) / previousEquity
            }
            previousEquity = equity
            equityCurve += equity
        }

        if (sampleDays < MIN_RESULT_DAYS) return null

        // Close any open position at the last in-window bar.
        if (positionShares > 0.0) {
            val last = ordered.lastOrNull {
                val date = LocalDate.parse(it.date)
                !date.isBefore(startDate) && !date.isAfter(endDate)
            }
            if (last != null) {
                val exitPrice = last.close * (1.0 - TRADE_COST_RATE)
                cash = positionShares * exitPrice
                trades += HistoricalBacktestTrade(
                    entryDate = entryDate,
                    exitDate = last.date,
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    returnPercent = (exitPrice - entryPrice) / entryPrice * 100.0,
                    code = code
                )
                positionShares = 0.0
                // The final settled equity replaces the last marked-to-market point.
                if (equityCurve.isNotEmpty()) {
                    equityCurve[equityCurve.lastIndex] = cash
                }
            }
        }

        return LegSimulation(
            code = code,
            equityCurve = equityCurve,
            dailyReturns = dailyReturns,
            maxDrawdown = maxDrawdown,
            trades = trades,
            sampleDays = sampleDays
        )
    }

    // ---------------------------------------------------------------------
    // Signal evaluation (formula-driven)
    // ---------------------------------------------------------------------

    /**
     * Entry signal. When the strategy carries a parseable formula it is evaluated
     * against the bar's indicator values; otherwise a default trend-following rule
     * (close above MA20 with a rising MA5 and volume expansion) is used.
     */
    private fun shouldEnter(signalNode: FormulaNode?, rows: List<DailyKline>, index: Int): Boolean {
        if (signalNode != null) {
            return StrategyFormulaPolicy.matchesNode(
                signalNode,
                indicatorContext(rows, index),
                default = false
            )
        }
        val close = rows[index].close
        val ma5 = averageClose(rows, index, 5)
        val ma20 = averageClose(rows, index, 20)
        return close > ma20 && ma5 >= ma20 && rows[index].volume > averageVolume(rows, index, 5)
    }

    /**
     * Exit signal. A hard stop (close below 92% of entry price) always applies. With a
     * formula the position is closed once the entry condition no longer holds; without
     * one the default rule (close below MA20 or MA5 rolling under MA20) is used.
     */
    private fun shouldExit(
        signalNode: FormulaNode?,
        rows: List<DailyKline>,
        index: Int,
        entryPrice: Double
    ): Boolean {
        val close = rows[index].close
        if (entryPrice > 0.0 && close < entryPrice * STOP_LOSS_RATIO) return true
        if (signalNode != null) {
            // Exit when the entry condition is no longer satisfied.
            return !StrategyFormulaPolicy.matchesNode(
                signalNode,
                indicatorContext(rows, index),
                default = true
            )
        }
        val ma5 = averageClose(rows, index, 5)
        val ma20 = averageClose(rows, index, 20)
        return close < ma20 || ma5 < ma20 * 0.99
    }

    /**
     * Build the per-bar indicator map the formula evaluator reads from. Indicator names
     * mirror those used in seed strategy formulas. Approximated fields (turnover,
     * fundamentals) are derived from available K-line data so common formulas resolve.
     */
    private fun indicatorContext(rows: List<DailyKline>, index: Int): FormulaContext {
        val bar = rows[index]
        val indicators = HashMap<String, Double>()
        indicators["close"] = bar.close
        indicators["open"] = bar.open
        indicators["high"] = bar.high
        indicators["low"] = bar.low
        indicators["volume"] = bar.volume.toDouble()
        if (index >= 1) indicators["prev_close"] = rows[index - 1].close

        for (period in MA_PERIODS) {
            if (index >= period - 1) {
                indicators["ma$period"] = averageClose(rows, index, period)
            }
        }
        for (period in VOLUME_PERIODS) {
            if (index >= period - 1) {
                indicators["avg_volume_$period"] = averageVolume(rows, index, period)
            }
        }
        // Turnover is not in the daily K-line schema; approximate it with the bar's
        // traded value (close * volume) so turnover-based formulas remain evaluable.
        val turnoverProxy = bar.close * bar.volume
        indicators["turnover"] = turnoverProxy
        for (period in VOLUME_PERIODS) {
            if (index >= period - 1) {
                indicators["avg_turnover_$period"] = averageTurnover(rows, index, period)
            }
        }
        // Bollinger-style bands around MA20 (2 standard deviations) when warmed up.
        if (index >= 19) {
            val ma20 = averageClose(rows, index, 20)
            val sd = closeStdDev(rows, index, 20)
            indicators["lower_band"] = ma20 - 2.0 * sd
            indicators["upper_band"] = ma20 + 2.0 * sd
        }
        return FormulaContext(indicators)
    }

    // ---------------------------------------------------------------------
    // Benchmark
    // ---------------------------------------------------------------------

    private fun buildBenchmark(
        benchmarkKlines: List<DailyKline>,
        startDate: LocalDate,
        endDate: LocalDate,
        strategyTotalReturn: Double
    ): BenchmarkComparison? {
        val ordered = sanitize(benchmarkKlines).filter {
            val date = LocalDate.parse(it.date)
            !date.isBefore(startDate) && !date.isAfter(endDate)
        }
        if (ordered.size < MIN_RESULT_DAYS) return null

        val first = ordered.first().close
        if (first <= 0.0) return null
        // Buy-and-hold equity curve normalised to 1.0 at the first in-window bar.
        val curve = ordered.map { it.close / first }
        val finalEquity = curve.last()
        val benchmarkTotalReturn = (finalEquity - 1.0) * 100.0
        val benchmarkAnnualized = annualize(finalEquity, ordered.size)
        val benchmarkMaxDrawdown = maxDrawdownOf(curve) * 100.0
        val excess = strategyTotalReturn - benchmarkTotalReturn

        return BenchmarkComparison(
            benchmarkTotalReturn = benchmarkTotalReturn,
            benchmarkAnnualizedReturn = benchmarkAnnualized,
            benchmarkMaxDrawdown = benchmarkMaxDrawdown,
            excessReturn = excess,
            outperformed = excess > 0.0
        )
    }

    // ---------------------------------------------------------------------
    // Shared math helpers
    // ---------------------------------------------------------------------

    private fun sanitize(klines: List<DailyKline>): List<DailyKline> {
        return klines
            .filter { it.close > 0.0 && runCatching { LocalDate.parse(it.date) }.isSuccess }
            .sortedBy { it.date }
    }

    private fun annualize(finalEquity: Double, sampleDays: Int): Double {
        if (sampleDays <= 0) return 0.0
        return (finalEquity.coerceAtLeast(0.0001).pow(252.0 / sampleDays) - 1.0) * 100.0
    }

    private fun maxDrawdownOf(equityCurve: List<Double>): Double {
        var peak = equityCurve.firstOrNull() ?: return 0.0
        var maxDrawdown = 0.0
        for (equity in equityCurve) {
            peak = maxOf(peak, equity)
            if (peak > 0.0) {
                maxDrawdown = maxOf(maxDrawdown, (peak - equity) / peak)
            }
        }
        return maxDrawdown
    }

    private fun dailyReturnsOf(equityCurve: List<Double>): List<Double> {
        if (equityCurve.size < 2) return emptyList()
        val returns = mutableListOf<Double>()
        for (i in 1 until equityCurve.size) {
            val previous = equityCurve[i - 1]
            if (previous > 0.0) {
                returns += (equityCurve[i] - previous) / previous
            }
        }
        return returns
    }

    private fun averageClose(rows: List<DailyKline>, index: Int, period: Int): Double {
        return rows.subList(index - period + 1, index + 1).map { it.close }.average()
    }

    private fun averageVolume(rows: List<DailyKline>, index: Int, period: Int): Double {
        return rows.subList(index - period + 1, index + 1).map { it.volume.toDouble() }.average()
    }

    private fun averageTurnover(rows: List<DailyKline>, index: Int, period: Int): Double {
        return rows.subList(index - period + 1, index + 1)
            .map { it.close * it.volume }
            .average()
    }

    private fun closeStdDev(rows: List<DailyKline>, index: Int, period: Int): Double {
        val window = rows.subList(index - period + 1, index + 1).map { it.close }
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
        return sqrt(variance)
    }

    private fun sharpeRatio(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0
        val average = returns.average()
        val variance = returns.sumOf { (it - average) * (it - average) } / (returns.size - 1)
        val stdev = sqrt(variance)
        if (stdev == 0.0) return 0.0
        return average / stdev * sqrt(252.0)
    }

    private val MA_PERIODS = listOf(5, 10, 20, 30, 60)
    private val VOLUME_PERIODS = listOf(5, 10, 20)
    private const val WARMUP_DAYS = 60
    private const val MIN_SAMPLE_DAYS = 80
    private const val MIN_RESULT_DAYS = 20
    private const val TRADE_COST_RATE = 0.0015
    private const val STOP_LOSS_RATIO = 0.92
}
