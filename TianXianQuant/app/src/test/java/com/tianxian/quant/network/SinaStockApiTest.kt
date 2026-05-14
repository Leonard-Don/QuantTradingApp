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
}
