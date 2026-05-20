package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistHealthPolicyTest {
    @Test
    fun returnsNullForEmptyWatchlist() {
        assertNull(WatchlistHealthPolicy.evaluate(emptyList()))
    }

    @Test
    fun reportsConcentrationAndPressureRisks() {
        val report = WatchlistHealthPolicy.evaluate(
            listOf(
                stock("600519", "贵州茅台", "消费", change = -3.6, pe = 48.0, pb = 9.0, price = 95.0, ma20 = 100.0),
                stock("000858", "五粮液", "消费", change = -1.2, pe = 26.0, pb = 5.0, price = 101.0, ma20 = 100.0),
                stock("600887", "伊利股份", "消费", change = 0.4, pe = 18.0, pb = 3.0, price = 104.0, ma20 = 100.0),
                stock("300750", "宁德时代", "新能源", change = 2.2, pe = 20.0, pb = 4.0, price = 108.0, ma20 = 100.0)
            )
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score in 0..100)
        assertTrue(report.riskItems.any { it.contains("消费") && it.contains("集中") })
        assertTrue(report.riskItems.any { it.contains("短期压力") })
        assertTrue(report.riskItems.any { it.contains("估值") })
        assertEquals("600519", report.focusStocks.first().code)
    }

    @Test
    fun givesHigherScoreToBalancedStrongWatchlist() {
        val strong = WatchlistHealthPolicy.evaluate(
            listOf(
                stock("600036", "招商银行", "金融", change = 1.4, pe = 6.0, pb = 0.9, price = 110.0, ma20 = 100.0),
                stock("300750", "宁德时代", "新能源", change = 2.1, pe = 21.0, pb = 4.0, price = 112.0, ma20 = 100.0),
                stock("300059", "东方财富", "科技", change = 0.8, pe = 30.0, pb = 4.2, price = 108.0, ma20 = 100.0),
                stock("300760", "迈瑞医疗", "医药", change = 0.5, pe = 28.0, pb = 7.0, price = 106.0, ma20 = 100.0)
            )
        )
        val weak = WatchlistHealthPolicy.evaluate(
            listOf(
                stock("600519", "贵州茅台", "消费", change = -5.0, pe = 50.0, pb = 9.0, price = 90.0, ma20 = 100.0),
                stock("000858", "五粮液", "消费", change = -4.0, pe = 46.0, pb = 8.5, price = 92.0, ma20 = 100.0),
                stock("600887", "伊利股份", "消费", change = -2.0, pe = 20.0, pb = 3.0, price = 94.0, ma20 = 100.0)
            )
        )

        assertNotNull(strong)
        assertNotNull(weak)
        checkNotNull(strong)
        checkNotNull(weak)
        assertTrue(strong.score > weak.score)
        assertTrue(strong.researchActions.isNotEmpty())
    }

    private fun stock(
        code: String,
        name: String,
        industry: String,
        change: Double,
        pe: Double,
        pb: Double,
        price: Double,
        ma20: Double
    ): StockInfo {
        return StockInfo(
            code = code,
            name = name,
            price = price,
            changePercent = change,
            volume = 100_000,
            marketCap = 1_000.0,
            pe = pe,
            pb = pb,
            industry = industry,
            turnover = 20.0,
            ma5 = ma20 + 6.0,
            ma10 = ma20 + 3.0,
            ma20 = ma20
        )
    }
}
