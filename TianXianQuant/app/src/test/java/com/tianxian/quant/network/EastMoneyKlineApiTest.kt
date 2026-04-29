package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
