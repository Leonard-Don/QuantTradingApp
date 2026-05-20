package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoricalBacktestPolicyTest {

    private fun risingSeries(
        code: String,
        count: Int = 150,
        slope: Double = 0.3,
        startClose: Double = 100.0,
        startDate: LocalDate = LocalDate.parse("2025-01-01")
    ): List<DailyKline> = (0 until count).map { offset ->
        val close = startClose + offset * slope
        DailyKline(
            code = code,
            date = startDate.plusDays(offset.toLong()).toString(),
            open = close - 0.2,
            close = close,
            high = close + 0.5,
            low = close - 0.8,
            volume = 1_000L + offset
        )
    }

    private fun trendStrategy(formula: String = "close > ma20 && volume > avg_volume_5") = Strategy(
        id = "trend",
        name = "趋势观察模型",
        description = "test",
        winRate = 0.0,
        maxDrawdown = 0.0,
        sharpeRatio = 0.0,
        formula = formula
    )

    @Test
    fun buildsRealMetricsFromDailyKlineSeries() {
        val strategy = Strategy(
            id = "trend",
            name = "趋势观察模型",
            description = "test",
            winRate = 0.0,
            maxDrawdown = 0.0,
            sharpeRatio = 0.0,
            formula = "close > ma20 && volume > avg_volume_5"
        )
        val start = LocalDate.parse("2025-01-01")
        val rows = (0 until 150).map { offset ->
            val date = start.plusDays(offset.toLong())
            val close = 100.0 + offset * 0.3
            DailyKline(
                code = "600519",
                date = date.toString(),
                open = close - 0.2,
                close = close,
                high = close + 0.5,
                low = close - 0.8,
                volume = 1_000L + offset
            )
        }

        val result = HistoricalBacktestPolicy.run(
            strategy = strategy,
            klines = rows,
            startDate = start.plusDays(70),
            endDate = start.plusDays(149)
        )

        assertNotNull(result)
        checkNotNull(result)
        assertTrue(result.sampleDays >= 70)
        assertTrue(result.metrics.totalTrades >= 1)
        assertTrue(result.metrics.totalReturn > 0.0)
        assertEquals(result.metrics.totalTrades, result.trades.size)
    }

    @Test
    fun returnsNullWhenKlineSampleIsTooShort() {
        val strategy = Strategy(
            id = "trend",
            name = "趋势观察模型",
            description = "test",
            winRate = 0.0,
            maxDrawdown = 0.0,
            sharpeRatio = 0.0
        )
        val rows = (0 until 30).map { offset ->
            DailyKline(
                code = "600519",
                date = LocalDate.parse("2025-01-01").plusDays(offset.toLong()).toString(),
                open = 10.0,
                close = 10.0 + offset,
                high = 11.0 + offset,
                low = 9.5 + offset,
                volume = 1_000L
            )
        }

        val result = HistoricalBacktestPolicy.run(
            strategy = strategy,
            klines = rows,
            startDate = LocalDate.parse("2025-01-01"),
            endDate = LocalDate.parse("2025-02-01")
        )

        assertEquals(null, result)
    }

    @Test
    fun formulaDrivenSignalKeepsRisingSeriesInvested() {
        // A strict formula and the loose default rule both stay long on a rising
        // series, so the single end-of-window settle trade must be profitable.
        val result = HistoricalBacktestPolicy.run(
            strategy = trendStrategy("close > ma20"),
            klines = risingSeries("600519"),
            startDate = LocalDate.parse("2025-01-01").plusDays(70),
            endDate = LocalDate.parse("2025-01-01").plusDays(149)
        )
        assertNotNull(result)
        checkNotNull(result)
        assertTrue(result.metrics.totalTrades >= 1)
        assertTrue(result.metrics.totalReturn > 0.0)
    }

    @Test
    fun malformedFormulaFallsBackToDefaultRuleAndStillRuns() {
        // A broken formula must not crash the backtest; the default rule takes over.
        val result = HistoricalBacktestPolicy.run(
            strategy = trendStrategy("close >"),
            klines = risingSeries("600519"),
            startDate = LocalDate.parse("2025-01-01").plusDays(70),
            endDate = LocalDate.parse("2025-01-01").plusDays(149)
        )
        assertNotNull(result)
    }

    // --- multi-symbol portfolio ------------------------------------------

    @Test
    fun runsWeightedMultiSymbolPortfolio() {
        val symbols = listOf(
            BacktestSymbol("600519", risingSeries("600519", slope = 0.4), weight = 2.0),
            BacktestSymbol("000858", risingSeries("000858", slope = 0.2), weight = 1.0)
        )
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = symbols,
            startDate = LocalDate.parse("2025-01-01").plusDays(70),
            endDate = LocalDate.parse("2025-01-01").plusDays(149)
        )
        assertNotNull(result)
        checkNotNull(result)
        assertEquals(2, result.symbolCount)
        assertEquals(2, result.legs.size)
        // Weights are normalised to sum to 1.0 (2:1 -> 0.667 / 0.333).
        assertEquals(1.0, result.legs.sumOf { it.weight }, 1e-9)
        val heavy = result.legs.first { it.code == "600519" }
        assertEquals(2.0 / 3.0, heavy.weight, 1e-9)
        assertTrue(result.metrics.totalReturn > 0.0)
        assertTrue(result.metrics.totalTrades >= 2)
    }

    @Test
    fun portfolioReturnSitsBetweenItsLegReturns() {
        // A weighted blend of two symbols must land between the two leg returns.
        val fast = risingSeries("AAA", slope = 0.6)
        val slow = risingSeries("BBB", slope = 0.1)
        val start = LocalDate.parse("2025-01-01").plusDays(70)
        val end = LocalDate.parse("2025-01-01").plusDays(149)
        val strategy = trendStrategy("close > ma20")

        val fastOnly = HistoricalBacktestPolicy.run(strategy, fast, start, end)!!
        val slowOnly = HistoricalBacktestPolicy.run(strategy, slow, start, end)!!
        val portfolio = HistoricalBacktestPolicy.runPortfolio(
            strategy = strategy,
            symbols = listOf(
                BacktestSymbol("AAA", fast, weight = 1.0),
                BacktestSymbol("BBB", slow, weight = 1.0)
            ),
            startDate = start,
            endDate = end
        )!!

        val low = minOf(fastOnly.metrics.totalReturn, slowOnly.metrics.totalReturn)
        val high = maxOf(fastOnly.metrics.totalReturn, slowOnly.metrics.totalReturn)
        assertTrue(portfolio.metrics.totalReturn in (low - 1e-6)..(high + 1e-6))
    }

    @Test
    fun portfolioSkipsSymbolsWithInsufficientHistory() {
        val good = BacktestSymbol("600519", risingSeries("600519"), weight = 1.0)
        val tooShort = BacktestSymbol(
            "000001",
            risingSeries("000001", count = 30),
            weight = 1.0
        )
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = listOf(good, tooShort),
            startDate = LocalDate.parse("2025-01-01").plusDays(70),
            endDate = LocalDate.parse("2025-01-01").plusDays(149)
        )
        assertNotNull(result)
        checkNotNull(result)
        // Only the qualifying symbol contributes.
        assertEquals(1, result.symbolCount)
        assertEquals("600519", result.legs.single().code)
    }

    @Test
    fun portfolioReturnsNullWhenNoSymbolQualifies() {
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = listOf(
                BacktestSymbol("000001", risingSeries("000001", count = 25), weight = 1.0)
            ),
            startDate = LocalDate.parse("2025-01-01"),
            endDate = LocalDate.parse("2025-06-01")
        )
        assertNull(result)
    }

    @Test
    fun portfolioReturnsNullForEmptySymbolList() {
        assertNull(
            HistoricalBacktestPolicy.runPortfolio(
                strategy = trendStrategy(),
                symbols = emptyList(),
                startDate = LocalDate.parse("2025-01-01"),
                endDate = LocalDate.parse("2025-06-01")
            )
        )
    }

    // --- benchmark comparison --------------------------------------------

    @Test
    fun benchmarkComparisonReportsExcessReturn() {
        val start = LocalDate.parse("2025-01-01").plusDays(70)
        val end = LocalDate.parse("2025-01-01").plusDays(149)
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = listOf(
                BacktestSymbol("600519", risingSeries("600519", slope = 0.5), weight = 1.0)
            ),
            startDate = start,
            endDate = end,
            // A flat benchmark: strategy on a rising series should outperform it.
            benchmarkKlines = risingSeries("000300", slope = 0.0, startClose = 4000.0)
        )
        assertNotNull(result)
        checkNotNull(result)
        val benchmark = result.benchmark
        assertNotNull(benchmark)
        checkNotNull(benchmark)
        assertEquals(0.0, benchmark.benchmarkTotalReturn, 1e-6)
        assertEquals(
            result.metrics.totalReturn - benchmark.benchmarkTotalReturn,
            benchmark.excessReturn,
            1e-6
        )
        assertTrue(benchmark.outperformed)
    }

    @Test
    fun benchmarkIsNullWhenNotSupplied() {
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = listOf(
                BacktestSymbol("600519", risingSeries("600519"), weight = 1.0)
            ),
            startDate = LocalDate.parse("2025-01-01").plusDays(70),
            endDate = LocalDate.parse("2025-01-01").plusDays(149)
        )
        assertNotNull(result)
        assertNull(result!!.benchmark)
    }

    @Test
    fun benchmarkUnderperformanceIsFlagged() {
        val start = LocalDate.parse("2025-01-01").plusDays(70)
        val end = LocalDate.parse("2025-01-01").plusDays(149)
        // Strategy on a flat series (no profitable entries) versus a fast-rising
        // benchmark: the strategy should not outperform.
        val result = HistoricalBacktestPolicy.runPortfolio(
            strategy = trendStrategy("close > ma20"),
            symbols = listOf(
                BacktestSymbol("600519", risingSeries("600519", slope = 0.02), weight = 1.0)
            ),
            startDate = start,
            endDate = end,
            benchmarkKlines = risingSeries("000300", slope = 5.0, startClose = 4000.0)
        )
        assertNotNull(result)
        checkNotNull(result)
        val benchmark = result.benchmark
        assertNotNull(benchmark)
        checkNotNull(benchmark)
        assertTrue(benchmark.benchmarkTotalReturn > 0.0)
        assertFalse(benchmark.outperformed)
    }
}
