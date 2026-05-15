package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EastMoneyKlineApiTest {
    @Test
    fun parsesMovingAveragesFromKlineRows() {
        val rows = (1..20).joinToString(",") { index ->
            val close = 100 + index
            "\"2026-04-${index.toString().padStart(2, '0')},$close,$close,$close,$close,1000\""
        }
        val body = "{\"data\":{\"klines\":[$rows]}}"

        val average = EastMoneyKlineApi.parseMovingAverage("600519", body)

        assertNotNull(average)
        checkNotNull(average)
        assertEquals("600519", average.code)
        assertEquals(120.0, average.close, 0.001)
        assertEquals(118.0, average.ma5, 0.001)
        assertEquals(115.5, average.ma10, 0.001)
        assertEquals(110.5, average.ma20, 0.001)
    }

    @Test
    fun parsesDailyKlinesForHistoricalBacktest() {
        val body = "{\"data\":{\"klines\":[" +
            "\"2026-04-01,101,102,103,100,1200\"," +
            "\"2026-04-02,102,104,105,101,1500\"" +
            "]}}"

        val rows = EastMoneyKlineApi.parseDailyKlines("600519", body)

        assertEquals(2, rows.size)
        assertEquals("600519", rows.first().code)
        assertEquals("2026-04-01", rows.first().date)
        assertEquals(101.0, rows.first().open, 0.001)
        assertEquals(104.0, rows.last().close, 0.001)
        assertEquals(1500L, rows.last().volume)
    }

    @Test
    fun parseDailyKlines_skipsRowsWithNonFiniteOhlcValues() {
        // Java/Kotlin Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity" as
        // valid doubles, so "NaN".toDoubleOrNull() returns Double.NaN — not null.
        // Without an isFinite() guard, the `?: continue` skip never fires and
        // NaN/Infinity propagate into DailyKline, then through .average() into
        // MovingAverageInfo as NaN, silently corrupting screening + health.
        val body = "{\"data\":{\"klines\":[" +
            "\"2026-04-01,NaN,102,103,100,1200\"," +
            "\"2026-04-02,101,Infinity,103,100,1200\"," +
            "\"2026-04-03,101,102,-Infinity,100,1200\"," +
            "\"2026-04-04,101,102,103,NaN,1200\"," +
            "\"2026-04-05,101,102,103,100,1200\"" +
            "]}}"

        val rows = EastMoneyKlineApi.parseDailyKlines("600519", body)

        assertEquals(1, rows.size)
        assertEquals("2026-04-05", rows.first().date)
    }

    @Test
    fun parseDailyKlines_collapsesNonFiniteVolumeToZero() {
        // Double.POSITIVE_INFINITY.toLong() returns Long.MAX_VALUE, which would
        // wildly inflate volume into downstream calculations. Non-finite volume
        // must collapse to 0L, matching the existing missing/malformed semantics.
        val body = "{\"data\":{\"klines\":[" +
            "\"2026-04-01,101,102,103,100,Infinity\"," +
            "\"2026-04-02,101,102,103,100,NaN\"" +
            "]}}"

        val rows = EastMoneyKlineApi.parseDailyKlines("600519", body)

        assertEquals(2, rows.size)
        assertEquals(0L, rows[0].volume)
        assertEquals(0L, rows[1].volume)
    }

    @Test
    fun parseDailyKlines_collapsesSaturatingFiniteVolumeToZero() {
        // Java/Kotlin Double.toLong() saturates at Long.MAX_VALUE / Long.MIN_VALUE
        // for any out-of-range finite value, so a literal like "1e30" survives the
        // isFinite() guard and silently injects a fabricated max-volume into the
        // DailyKline stream. Mirror the PR #14 quote-provider fix: out-of-range
        // finite values must also collapse to 0L instead of saturating.
        val body = "{\"data\":{\"klines\":[" +
            "\"2026-04-01,101,102,103,100,1e30\"," +
            "\"2026-04-02,101,102,103,100,-1e30\"," +
            "\"2026-04-03,101,102,103,100,9223372036854775808\"" +
            "]}}"

        val rows = EastMoneyKlineApi.parseDailyKlines("600519", body)

        assertEquals(3, rows.size)
        assertEquals(0L, rows[0].volume)
        assertEquals(0L, rows[1].volume)
        assertEquals(0L, rows[2].volume)
    }

    @Test
    fun parseMovingAverage_excludesRowsWithNonFiniteCloseFromAverages() {
        // 21 rows, one with NaN close. Without the isFinite() guard NaN
        // propagates through .average() and every ma value becomes NaN.
        val rows = (1..21).joinToString(",") { index ->
            val day = index.toString().padStart(2, '0')
            if (index == 10) {
                "\"2026-04-$day,100,NaN,100,100,1000\""
            } else {
                val close = 100 + index
                "\"2026-04-$day,$close,$close,$close,$close,1000\""
            }
        }
        val body = "{\"data\":{\"klines\":[$rows]}}"

        val average = EastMoneyKlineApi.parseMovingAverage("600519", body)

        assertNotNull(average)
        checkNotNull(average)
        assertTrue("ma5 must be finite, was ${average.ma5}", average.ma5.isFinite())
        assertTrue("ma10 must be finite, was ${average.ma10}", average.ma10.isFinite())
        assertTrue("ma20 must be finite, was ${average.ma20}", average.ma20.isFinite())
    }
}
