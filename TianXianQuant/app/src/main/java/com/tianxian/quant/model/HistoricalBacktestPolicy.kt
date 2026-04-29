package com.tianxian.quant.model

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

data class HistoricalBacktestTrade(
    val entryDate: String,
    val exitDate: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val returnPercent: Double
)

data class HistoricalBacktestResult(
    val metrics: BacktestMetrics,
    val trades: List<HistoricalBacktestTrade>,
    val sampleDays: Int
)

object HistoricalBacktestPolicy {
    fun run(
        strategy: Strategy,
        klines: List<DailyKline>,
        startDate: LocalDate,
        endDate: LocalDate
    ): HistoricalBacktestResult? {
        val ordered = klines
            .filter { it.close > 0.0 && runCatching { LocalDate.parse(it.date) }.isSuccess }
            .sortedBy { it.date }
        if (ordered.size < MIN_SAMPLE_DAYS) return null

        var cash = 1.0
        var positionShares = 0.0
        var entryPrice = 0.0
        var entryDate = ""
        var peakEquity = 1.0
        var maxDrawdown = 0.0
        var previousEquity = 1.0
        val dailyReturns = mutableListOf<Double>()
        val trades = mutableListOf<HistoricalBacktestTrade>()
        var sampleDays = 0

        ordered.forEachIndexed { index, kline ->
            val date = LocalDate.parse(kline.date)
            if (date.isBefore(startDate) || date.isAfter(endDate)) return@forEachIndexed
            if (index < WARMUP_DAYS) return@forEachIndexed

            sampleDays += 1
            val equityBeforeTrade = cash + positionShares * kline.close
            val entrySignal = shouldEnter(strategy, ordered, index)
            val exitSignal = shouldExit(strategy, ordered, index, entryPrice)

            if (positionShares > 0.0 && exitSignal) {
                val exitPrice = kline.close * (1.0 - TRADE_COST_RATE)
                cash = positionShares * exitPrice
                val returnPercent = (exitPrice - entryPrice) / entryPrice * 100.0
                trades += HistoricalBacktestTrade(
                    entryDate = entryDate,
                    exitDate = kline.date,
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    returnPercent = returnPercent
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
            if (sampleDays > 1 && equityBeforeTrade > 0.0) {
                dailyReturns += (equity - previousEquity) / previousEquity
            }
            previousEquity = equity
        }

        if (sampleDays < MIN_RESULT_DAYS) return null

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
                    returnPercent = (exitPrice - entryPrice) / entryPrice * 100.0
                )
                positionShares = 0.0
                entryPrice = 0.0
                entryDate = ""
            }
        }

        val finalEquity = cash + positionShares * ordered.last().close
        val totalReturn = (finalEquity - 1.0) * 100.0
        val annualizedReturn = if (sampleDays > 0) {
            ((finalEquity.coerceAtLeast(0.0001)).pow(252.0 / sampleDays) - 1.0) * 100.0
        } else {
            0.0
        }
        val profitableTrades = trades.count { it.returnPercent > 0.0 }
        val winRate = if (trades.isEmpty()) 0.0 else profitableTrades * 100.0 / trades.size

        return HistoricalBacktestResult(
            metrics = BacktestMetrics(
                totalReturn = totalReturn,
                annualizedReturn = annualizedReturn,
                maxDrawdown = maxDrawdown * 100.0,
                sharpeRatio = sharpeRatio(dailyReturns),
                winRate = winRate,
                totalTrades = trades.size,
                profitTrades = profitableTrades
            ),
            trades = trades,
            sampleDays = sampleDays
        )
    }

    private fun shouldEnter(strategy: Strategy, rows: List<DailyKline>, index: Int): Boolean {
        val close = rows[index].close
        val ma5 = averageClose(rows, index, 5)
        val ma20 = averageClose(rows, index, 20)
        val ma60 = averageClose(rows, index, 60)
        val volume20 = averageVolume(rows, index, 20)
        val normalized = "${strategy.id} ${strategy.formula} ${strategy.tags.joinToString(" ")}".lowercase()
        return when {
            "lower_band" in normalized || "回归" in strategy.name -> close < ma20 * 0.96
            "grid" in normalized || "网格" in strategy.name -> close in (ma20 * 0.94)..(ma20 * 1.02) && ma5 >= ma20 * 0.98
            "ma60" in normalized || "动量" in strategy.name -> close > ma60 * 1.03 && rows[index].volume > volume20
            "pe" in normalized || "多因子" in strategy.name -> close > ma20 && rows[index].volume >= volume20 * 0.9
            else -> close > ma20 && ma5 >= ma20 && rows[index].volume > averageVolume(rows, index, 5)
        }
    }

    private fun shouldExit(strategy: Strategy, rows: List<DailyKline>, index: Int, entryPrice: Double): Boolean {
        val close = rows[index].close
        val ma5 = averageClose(rows, index, 5)
        val ma20 = averageClose(rows, index, 20)
        val normalized = "${strategy.id} ${strategy.formula} ${strategy.tags.joinToString(" ")}".lowercase()
        if (entryPrice > 0.0 && close < entryPrice * 0.92) return true
        return when {
            "lower_band" in normalized || "回归" in strategy.name -> close >= ma20 || close < entryPrice * 0.94
            "grid" in normalized || "网格" in strategy.name -> close >= ma20 * 1.06 || close < ma20 * 0.90
            else -> close < ma20 || ma5 < ma20 * 0.99
        }
    }

    private fun averageClose(rows: List<DailyKline>, index: Int, period: Int): Double {
        return rows.subList(index - period + 1, index + 1).map { it.close }.average()
    }

    private fun averageVolume(rows: List<DailyKline>, index: Int, period: Int): Double {
        return rows.subList(index - period + 1, index + 1).map { it.volume.toDouble() }.average()
    }

    private fun sharpeRatio(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0
        val average = returns.average()
        val variance = returns.sumOf { (it - average) * (it - average) } / (returns.size - 1)
        val stdev = sqrt(variance)
        if (stdev == 0.0) return 0.0
        return average / stdev * sqrt(252.0)
    }

    private const val WARMUP_DAYS = 60
    private const val MIN_SAMPLE_DAYS = 80
    private const val MIN_RESULT_DAYS = 20
    private const val TRADE_COST_RATE = 0.0015
}
