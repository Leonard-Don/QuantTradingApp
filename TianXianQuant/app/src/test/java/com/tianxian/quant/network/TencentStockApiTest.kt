package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun parseAmountYiOrNull_returnsNullForNonFiniteInput() {
        // Java/Kotlin Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity" as
        // valid doubles. Without an isFinite() guard, a malformed Tencent amount
        // field bypasses the null-vs-zero contract and propagates Infinity/NaN
        // into turnover/provider-health surfaces. Mirrors SinaStockApi behavior.
        assertNull(TencentStockApi.parseAmountYiOrNull("NaN"))
        assertNull(TencentStockApi.parseAmountYiOrNull("Infinity"))
        assertNull(TencentStockApi.parseAmountYiOrNull("-Infinity"))
    }

    @Test
    fun parseAmountYi_returnsZeroForNonFiniteInput() {
        assertEquals(0.0, TencentStockApi.parseAmountYi("NaN"), 0.0)
        assertEquals(0.0, TencentStockApi.parseAmountYi("Infinity"), 0.0)
        assertEquals(0.0, TencentStockApi.parseAmountYi("-Infinity"), 0.0)
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

    @Test
    fun parseStockList_collapsesNonFiniteNumericFieldsToZero() {
        // Java/Kotlin Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity",
        // so without an isFinite() filter these slip past `?: 0.0` and propagate
        // into price/change/high/low/open/yesterdayClose/marketCap/pe/pb. Volume
        // is even more dangerous: Double.POSITIVE_INFINITY.toLong() == Long.MAX_VALUE,
        // which then overflows when multiplied by 100. All of these must collapse
        // to 0.0 / 0L the same way blank/malformed fields already do.
        val fields = MutableList(48) { "0" }
        fields[1] = "测试股票"
        fields[3] = "NaN"           // price
        fields[4] = "Infinity"      // yesterdayClose
        fields[5] = "-Infinity"     // open
        fields[6] = "Infinity"      // volume (gets toLong() * 100)
        fields[32] = "NaN"          // changePercent
        fields[33] = "Infinity"     // high
        fields[34] = "-Infinity"    // low
        fields[37] = "NaN"          // amount in wan (turnover)
        fields[39] = "Infinity"     // pe
        fields[45] = "-Infinity"    // marketCap
        fields[46] = "NaN"          // pb
        val body = "v_sh600519=\"${fields.joinToString("~")}\";"

        val stocks = TencentStockApi.parseStockList(body)

        assertEquals(1, stocks.size)
        val stock = stocks.first()
        assertTrue("price must be finite, was ${stock.price}", stock.price.isFinite())
        assertEquals(0.0, stock.price, 0.0)
        assertEquals(0.0, stock.yesterdayClose, 0.0)
        assertEquals(0.0, stock.open, 0.0)
        // Infinity.toLong() == Long.MAX_VALUE; * 100 overflows. Must collapse to 0L.
        assertEquals(0L, stock.volume)
        assertEquals(0.0, stock.changePercent, 0.0)
        assertEquals(0.0, stock.high, 0.0)
        assertEquals(0.0, stock.low, 0.0)
        assertEquals(0.0, stock.turnover, 0.0)
        assertEquals(0.0, stock.pe, 0.0)
        assertEquals(0.0, stock.marketCap, 0.0)
        assertEquals(0.0, stock.pb, 0.0)
    }

    @Test
    fun parseStockList_collapsesHugeFiniteVolumeToZero() {
        // toFiniteDoubleOrNull already rejects NaN/±Infinity, but a finite literal
        // like "1e30" survives the isFinite() guard. (1e30).toLong() then saturates
        // to Long.MAX_VALUE (9223372036854775807), and Tencent immediately *100 it,
        // wrapping into a large negative Long. Bound the conversion BEFORE the *100.
        val fields = MutableList(48) { "0" }
        fields[1] = "测试股票"
        fields[6] = "1e30"
        val body = "v_sh600519=\"${fields.joinToString("~")}\";"

        val stocks = TencentStockApi.parseStockList(body)

        assertEquals(1, stocks.size)
        val stock = stocks.first()
        assertEquals(
            "1e30 must not saturate-then-overflow into a negative volume",
            0L,
            stock.volume
        )
        assertTrue(
            "huge finite volume must collapse to non-negative",
            stock.volume >= 0L
        )
    }

    @Test
    fun parseStockList_collapsesUnsafeScaledVolumeToZero() {
        // A value comfortably inside Long range (e.g. 1e17 < Long.MAX_VALUE ≈ 9.22e18)
        // still detonates Tencent's ×100 scaling: 1e17.toLong() * 100 = 1e19 overflows
        // Long and wraps to a negative number. The fix must check the scaled magnitude,
        // not just the raw double.
        val fields = MutableList(48) { "0" }
        fields[1] = "测试股票"
        fields[6] = "1e17"
        val body = "v_sh600519=\"${fields.joinToString("~")}\";"

        val stocks = TencentStockApi.parseStockList(body)

        assertEquals(1, stocks.size)
        assertEquals(0L, stocks.first().volume)
    }

    @Test
    fun parseStockList_preservesOrdinaryVolumeAfterScaling() {
        // Regression guard: bounding must not break the legacy ×100 path for
        // realistic lot counts.
        val fields = MutableList(48) { "0" }
        fields[1] = "对照股票"
        fields[6] = "123"
        val body = "v_sh600519=\"${fields.joinToString("~")}\";"

        val stocks = TencentStockApi.parseStockList(body)

        assertEquals(1, stocks.size)
        assertEquals(12300L, stocks.first().volume)
    }

    @Test
    fun parseMarketOverview_collapsesHugeFiniteVolumeToZero() {
        // Index parser does not ×100 but still saturates on (1e30).toLong() →
        // Long.MAX_VALUE. Bounded conversion must collapse those to 0L while
        // preserving ordinary values.
        val hugeFields = MutableList(48) { "0" }
        hugeFields[1] = "上证指数"
        hugeFields[6] = "1e30"
        val ordinaryFields = MutableList(48) { "0" }
        ordinaryFields[1] = "深证成指"
        ordinaryFields[6] = "123"
        val body = buildString {
            append("v_sh000001=\"${hugeFields.joinToString("~")}\";")
            append("v_sz399001=\"${ordinaryFields.joinToString("~")}\";")
        }

        val overviews = TencentStockApi.parseMarketOverview(body)

        assertEquals(2, overviews.size)
        assertEquals(0L, overviews.first { it.indexCode == "000001" }.volume)
        assertEquals(123L, overviews.first { it.indexCode == "399001" }.volume)
    }

    @Test
    fun parseMarketOverview_collapsesNonFiniteNumericFieldsToZero() {
        // Same Double.parseDouble("Infinity") footgun applies to the index parser:
        // price/changePoint/changePercent/volume/amount must reject non-finite
        // doubles instead of letting them bleed into MarketOverview.
        val fields = MutableList(48) { "0" }
        fields[1] = "上证指数"
        fields[3] = "Infinity"      // price
        fields[6] = "Infinity"      // volume
        fields[31] = "NaN"          // changePoint
        fields[32] = "-Infinity"    // changePercent
        fields[37] = "Infinity"     // amount
        val body = "v_sh000001=\"${fields.joinToString("~")}\";"

        val overviews = TencentStockApi.parseMarketOverview(body)

        assertEquals(1, overviews.size)
        val overview = overviews.first()
        assertEquals(0.0, overview.price, 0.0)
        assertEquals(0.0, overview.changePoint, 0.0)
        assertEquals(0.0, overview.changePercent, 0.0)
        assertEquals(0L, overview.volume)
        assertEquals(0.0, overview.amount, 0.0)
    }

    @Test
    fun parseMovingAverage_excludesRowsWithNonFiniteCloseFromAverages() {
        // Tencent kline rows reach parseMovingAverage as JSON arrays whose close
        // value is read via optString(2).toDoubleOrNull(). Java/Kotlin
        // Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity", so a single
        // poisoned row slips past `?: continue` and propagates through
        // .average() into MovingAverageInfo as NaN — corrupting screening and
        // provider-health surfaces. Mirrors the EastMoney fix (#12). A NaN row
        // near the tail also poisons closes.last() (the `close` field).
        val rows = (1..21).joinToString(",") { index ->
            val day = index.toString().padStart(2, '0')
            val close = if (index == 10) "NaN" else (100 + index).toString()
            "[\"2026-04-$day\",\"100\",\"$close\",\"100\",\"100\",\"1000\"]"
        }
        val body = "{\"data\":{\"sh600519\":{\"qfqday\":[$rows]}}}"

        val average = TencentStockApi.parseMovingAverage("sh600519", "600519", body)

        assertNotNull(average)
        checkNotNull(average)
        assertTrue("close must be finite, was ${average.close}", average.close.isFinite())
        assertTrue("ma5 must be finite, was ${average.ma5}", average.ma5.isFinite())
        assertTrue("ma10 must be finite, was ${average.ma10}", average.ma10.isFinite())
        assertTrue("ma20 must be finite, was ${average.ma20}", average.ma20.isFinite())
    }

    @Test
    fun parseMovingAverage_excludesRowsWithInfinityCloseFromAverages() {
        // Companion to the NaN case: Infinity/-Infinity must also be rejected,
        // since Double.parseDouble accepts both. With the bug, ma20 inherits
        // Infinity from row 5 and closes.last() inherits -Infinity from row 22.
        // 22 rows leave exactly 20 valid closes after skipping the two poisoned
        // rows, so we cross the `closes.size < 20` floor either way.
        val rows = (1..22).joinToString(",") { index ->
            val day = index.toString().padStart(2, '0')
            val close = when (index) {
                5 -> "Infinity"
                22 -> "-Infinity"
                else -> (100 + index).toString()
            }
            "[\"2026-04-$day\",\"100\",\"$close\",\"100\",\"100\",\"1000\"]"
        }
        val body = "{\"data\":{\"sh600519\":{\"qfqday\":[$rows]}}}"

        val average = TencentStockApi.parseMovingAverage("sh600519", "600519", body)

        assertNotNull(average)
        checkNotNull(average)
        assertTrue("close must be finite, was ${average.close}", average.close.isFinite())
        assertTrue("ma5 must be finite, was ${average.ma5}", average.ma5.isFinite())
        assertTrue("ma10 must be finite, was ${average.ma10}", average.ma10.isFinite())
        assertTrue("ma20 must be finite, was ${average.ma20}", average.ma20.isFinite())
    }
}
