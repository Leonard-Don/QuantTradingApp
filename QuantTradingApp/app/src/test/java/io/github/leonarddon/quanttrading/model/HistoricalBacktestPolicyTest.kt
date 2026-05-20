package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoricalBacktestPolicyTest {
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
}
