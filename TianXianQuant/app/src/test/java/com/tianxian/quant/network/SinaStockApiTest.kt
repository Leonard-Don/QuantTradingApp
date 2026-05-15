package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SinaStockApiTest {

    @Test
    fun parseAmountYiOrNull_returnsNullForNullInput() {
        assertNull(SinaStockApi.parseAmountYiOrNull(null))
    }

    @Test
    fun parseAmountYiOrNull_returnsNullForBlankInput() {
        assertNull(SinaStockApi.parseAmountYiOrNull(""))
        assertNull(SinaStockApi.parseAmountYiOrNull("   "))
    }

    @Test
    fun parseAmountYiOrNull_returnsNullForMalformedInput() {
        assertNull(SinaStockApi.parseAmountYiOrNull("abc"))
        assertNull(SinaStockApi.parseAmountYiOrNull("--"))
        assertNull(SinaStockApi.parseAmountYiOrNull("NaN"))
        assertNull(SinaStockApi.parseAmountYiOrNull("Infinity"))
        assertNull(SinaStockApi.parseAmountYiOrNull("-Infinity"))
    }

    @Test
    fun parseAmountYiOrNull_returnsZeroForTrueZero() {
        val parsed = SinaStockApi.parseAmountYiOrNull("0")
        assertNotNull("true \"0\" must be distinguishable from missing", parsed)
        assertEquals(0.0, parsed!!, 0.0)
    }

    @Test
    fun parseAmountYiOrNull_convertsYuanToYi() {
        // 5 亿元 = 500000000 元
        val parsed = SinaStockApi.parseAmountYiOrNull("500000000")
        assertNotNull(parsed)
        assertEquals(5.0, parsed!!, 1e-9)
    }

    @Test
    fun parseAmountYi_defaultsToZeroForMissingOrMalformed() {
        // Blank/malformed amounts must NOT inflate turnover; they collapse to 0.0
        // (StockInfo requires non-null Double — use parseAmountYiOrNull to disambiguate).
        assertEquals(0.0, SinaStockApi.parseAmountYi(null), 0.0)
        assertEquals(0.0, SinaStockApi.parseAmountYi(""), 0.0)
        assertEquals(0.0, SinaStockApi.parseAmountYi("   "), 0.0)
        assertEquals(0.0, SinaStockApi.parseAmountYi("abc"), 0.0)
    }

    @Test
    fun parseAmountYi_returnsZeroForTrueZero() {
        assertEquals(0.0, SinaStockApi.parseAmountYi("0"), 0.0)
    }

    @Test
    fun parseAmountYi_convertsYuanToYi() {
        assertEquals(9.79940605, SinaStockApi.parseAmountYi("979940605.000"), 1e-8)
    }

    @Test
    fun parsesStockQuoteRows() {
        val stocks = SinaStockApi.parseStockList(
            """
            var hq_str_sh600519="贵州茅台,1405.000,1405.000,1403.010,1409.750,1401.090,1403.010,1403.020,697172,979940605.000,2026-04-29,09:57:51,00,";
            var hq_str_sz000858="五 粮 液,97.880,100.200,97.520,98.580,97.040,97.510,97.520,16217836,1585636183.000,2026-04-29,09:57:51,00";
            """.trimIndent()
        )

        assertEquals(listOf("600519", "000858"), stocks.map { it.code })
        assertEquals("贵州茅台", stocks.first().name)
        assertEquals(1403.01, stocks.first().price, 0.001)
        assertTrue(stocks.first().turnover > 9.0)
    }

    @Test
    fun parsesIndexQuoteRows() {
        val indices = SinaStockApi.parseMarketOverview(
            """
            var hq_str_sh000001="上证指数,4061.8221,4078.6374,4082.2266,4087.2103,4061.8221,0,0,189196450,338007629595,2026-04-29,09:57:50,00,";
            var hq_str_sz399001="深证成指,14751.418,14830.455,14890.282,14938.478,14751.418,0,0,23556753888,424312399934.441,2026-04-29,09:57:48,00";
            """.trimIndent()
        )

        assertEquals(listOf("000001", "399001"), indices.map { it.indexCode })
        assertEquals("上证指数", indices.first().indexName)
        assertEquals(4082.2266, indices.first().price, 0.0001)
    }

    @Test
    fun parseStockList_collapsesNonFiniteNumericFieldsToZero() {
        // Java/Kotlin Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity",
        // so a raw "Infinity" survives toDoubleOrNull() and bleeds into price /
        // open / high / low / yesterdayClose. Volume is worse: Infinity.toLong()
        // returns Long.MAX_VALUE. changePercent is computed from price &
        // yesterdayClose so a non-finite price would propagate NaN downstream.
        // All must collapse to 0.0 / 0L the same way blank fields already do.
        val body = """
            var hq_str_sh600519="测试股票,NaN,Infinity,-Infinity,NaN,Infinity,0,0,Infinity,NaN,2026-04-29,09:57:51,00,";
        """.trimIndent()

        val stocks = SinaStockApi.parseStockList(body)

        assertEquals(1, stocks.size)
        val stock = stocks.first()
        assertEquals(0.0, stock.price, 0.0)
        assertEquals(0.0, stock.open, 0.0)
        assertEquals(0.0, stock.high, 0.0)
        assertEquals(0.0, stock.low, 0.0)
        assertEquals(0.0, stock.yesterdayClose, 0.0)
        assertTrue(
            "changePercent must be finite, was ${stock.changePercent}",
            stock.changePercent.isFinite()
        )
        assertEquals(0.0, stock.changePercent, 0.0)
        // Infinity.toLong() == Long.MAX_VALUE; must collapse to 0L.
        assertEquals(0L, stock.volume)
        assertEquals(0.0, stock.turnover, 0.0)
    }

    @Test
    fun parseStockList_collapsesHugeFiniteVolumeToZero() {
        // Java/Kotlin Double.parseDouble accepts very large but FINITE literals
        // such as "1e30" -- they slip past an isFinite() guard. Double.toLong()
        // then saturates to Long.MAX_VALUE (9223372036854775807) for any value
        // beyond Long range, silently injecting a fabricated max-volume into
        // StockInfo. Bounded conversion must collapse those to 0L while keeping
        // ordinary finite volumes intact.
        val body = """
            var hq_str_sh600519="测试股票,10,10,10,10,10,10,10,1e30,500000000,2026-04-29,09:57:51,00,";
            var hq_str_sz000858="对照股票,10,10,10,10,10,10,10,123,500000000,2026-04-29,09:57:51,00";
        """.trimIndent()

        val stocks = SinaStockApi.parseStockList(body)

        assertEquals(2, stocks.size)
        val huge = stocks.first { it.code == "600519" }
        assertEquals(
            "1e30 must not saturate to Long.MAX_VALUE",
            0L,
            huge.volume
        )
        val ordinary = stocks.first { it.code == "000858" }
        assertEquals("ordinary finite volume must round-trip", 123L, ordinary.volume)
    }

    @Test
    fun parseMarketOverview_collapsesHugeFiniteVolumeToZero() {
        // Same saturation footgun applies to the index parser: 1e30 is finite
        // but its .toLong() returns Long.MAX_VALUE, polluting MarketOverview.volume.
        val body = """
            var hq_str_sh000001="上证指数,10,10,10,10,10,0,0,1e30,338007629595,2026-04-29,09:57:50,00,";
            var hq_str_sz399001="深证成指,10,10,10,10,10,0,0,123,424312399934.441,2026-04-29,09:57:48,00";
        """.trimIndent()

        val overviews = SinaStockApi.parseMarketOverview(body)

        assertEquals(2, overviews.size)
        assertEquals(0L, overviews.first { it.indexCode == "000001" }.volume)
        assertEquals(123L, overviews.first { it.indexCode == "399001" }.volume)
    }

    @Test
    fun parseMarketOverview_collapsesNonFiniteNumericFieldsToZero() {
        // Same Double.parseDouble("Infinity") footgun applies to the index
        // parser: price/changePoint/changePercent/volume/amount must reject
        // non-finite doubles instead of letting them bleed into MarketOverview.
        val body = """
            var hq_str_sh000001="上证指数,NaN,Infinity,-Infinity,NaN,Infinity,0,0,Infinity,NaN,2026-04-29,09:57:50,00,";
        """.trimIndent()

        val overviews = SinaStockApi.parseMarketOverview(body)

        assertEquals(1, overviews.size)
        val overview = overviews.first()
        assertEquals(0.0, overview.price, 0.0)
        assertTrue(
            "changePoint must be finite, was ${overview.changePoint}",
            overview.changePoint.isFinite()
        )
        assertEquals(0.0, overview.changePoint, 0.0)
        assertTrue(
            "changePercent must be finite, was ${overview.changePercent}",
            overview.changePercent.isFinite()
        )
        assertEquals(0.0, overview.changePercent, 0.0)
        assertEquals(0L, overview.volume)
        assertEquals(0.0, overview.amount, 0.0)
    }
}
