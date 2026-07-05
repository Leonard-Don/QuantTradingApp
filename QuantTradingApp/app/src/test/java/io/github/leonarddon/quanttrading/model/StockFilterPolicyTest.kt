package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockFilterPolicyTest {
    @Test
    fun appliesMultiFactorFilter() {
        val result = StockFilterPolicy.filter(
            stocks = sampleStocks(),
            keyword = "",
            criteria = StockFilterCriteria(sortMode = "多因子"),
            watchlistCodes = emptySet()
        )

        assertEquals("多因子", result.effectiveSortMode)
        assertEquals(listOf("300750", "300059", "600036"), result.stocks.map { it.code })
    }

    @Test
    fun filtersSavedWatchlistCodes() {
        val result = StockFilterPolicy.filter(
            stocks = sampleStocks(),
            keyword = "",
            criteria = StockFilterCriteria(sortMode = StockFilterPolicy.WATCHLIST_FILTER),
            watchlistCodes = setOf("600519", "300059")
        )

        assertFalse(result.watchlistEmpty)
        assertEquals(listOf("600519", "300059"), result.stocks.map { it.code })
    }

    @Test
    fun reportsEmptyWatchlist() {
        val result = StockFilterPolicy.filter(
            stocks = sampleStocks(),
            keyword = "",
            criteria = StockFilterCriteria(sortMode = StockFilterPolicy.WATCHLIST_FILTER),
            watchlistCodes = emptySet()
        )

        assertTrue(result.watchlistEmpty)
        assertEquals(emptyList<String>(), result.stocks.map { it.code })
    }

    @Test
    fun filtersMovingAverageStrength() {
        val result = StockFilterPolicy.filter(
            stocks = sampleStocks(),
            keyword = "",
            criteria = StockFilterCriteria(sortMode = "均线强势"),
            watchlistCodes = emptySet()
        )

        assertFalse(result.movingAverageUnavailable)
        assertEquals(listOf("300750", "600519"), result.stocks.map { it.code })
    }

    private fun sampleStocks(): List<StockInfo> {
        return listOf(
            StockInfo(
                code = "600519",
                name = "贵州茅台",
                price = 1680.0,
                changePercent = 1.2,
                volume = 20_000,
                marketCap = 21_000.0,
                pe = 28.5,
                pb = 8.2,
                industry = "消费",
                turnover = 210.0,
                ma5 = 1660.0,
                ma10 = 1600.0,
                ma20 = 1550.0
            ),
            StockInfo(
                code = "300750",
                name = "宁德时代",
                price = 185.0,
                changePercent = 2.1,
                volume = 89_000,
                marketCap = 8_200.0,
                pe = 18.6,
                pb = 3.8,
                industry = "新能源",
                turnover = 82.0,
                ma5 = 180.0,
                ma10 = 175.0,
                ma20 = 170.0
            ),
            StockInfo(
                code = "600036",
                name = "招商银行",
                price = 32.0,
                changePercent = -1.2,
                volume = 125_000,
                marketCap = 8_100.0,
                pe = 5.8,
                pb = 0.85,
                industry = "金融",
                turnover = 81.0
            ),
            StockInfo(
                code = "300059",
                name = "东方财富",
                price = 15.2,
                changePercent = 1.1,
                volume = 180_000,
                marketCap = 2_600.0,
                pe = 30.2,
                pb = 4.0,
                industry = "科技",
                turnover = 44.0,
                ma5 = 15.0,
                ma10 = 15.5,
                ma20 = 16.0
            )
        )
    }
}
