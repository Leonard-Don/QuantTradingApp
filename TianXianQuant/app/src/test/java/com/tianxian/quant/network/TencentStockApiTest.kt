package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TencentStockApiTest {

    @Test
    fun parseAmountYiOrNull_returnsNullForNullInput() {
        assertNull(TencentStockApi.parseAmountYiOrNull(null))
    }

    @Test
    fun parseAmountYiOrNull_returnsNullForBlankInput() {
        assertNull(TencentStockApi.parseAmountYiOrNull(""))
        assertNull(TencentStockApi.parseAmountYiOrNull("   "))
    }

    @Test
    fun parseAmountYiOrNull_returnsNullForMalformedInput() {
        assertNull(TencentStockApi.parseAmountYiOrNull("abc"))
        assertNull(TencentStockApi.parseAmountYiOrNull("--"))
    }

    @Test
    fun parseAmountYiOrNull_returnsZeroForTrueZero() {
        val parsed = TencentStockApi.parseAmountYiOrNull("0")
        assertNotNull("true \"0\" must be distinguishable from missing", parsed)
        assertEquals(0.0, parsed!!, 0.0)
    }

    @Test
    fun parseAmountYiOrNull_convertsWanToYi() {
        // 50000 万 = 5.0 亿
        val parsed = TencentStockApi.parseAmountYiOrNull("50000")
        assertNotNull(parsed)
        assertEquals(5.0, parsed!!, 1e-9)
    }

    @Test
    fun parseAmountYi_defaultsToZeroForMissingOrMalformed() {
        // Blank/malformed amounts must NOT inflate turnover; they collapse to 0.0
        // (model requires non-null Double — use parseAmountYiOrNull to disambiguate).
        assertEquals(0.0, TencentStockApi.parseAmountYi(null), 0.0)
        assertEquals(0.0, TencentStockApi.parseAmountYi(""), 0.0)
        assertEquals(0.0, TencentStockApi.parseAmountYi("   "), 0.0)
        assertEquals(0.0, TencentStockApi.parseAmountYi("abc"), 0.0)
    }

    @Test
    fun parseAmountYi_returnsZeroForTrueZero() {
        assertEquals(0.0, TencentStockApi.parseAmountYi("0"), 0.0)
    }

    @Test
    fun parseAmountYi_convertsWanToYi() {
        assertEquals(5.0, TencentStockApi.parseAmountYi("50000"), 1e-9)
    }
}
