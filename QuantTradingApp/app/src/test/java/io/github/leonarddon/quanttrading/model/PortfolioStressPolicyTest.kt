package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioStressPolicyTest {
    @Test
    fun returnsNullForEmptyWatchlist() {
        assertNull(PortfolioStressPolicy.evaluate(emptyList(), marketUpCount = 10, marketDownCount = 8))
    }

    @Test
    fun reportsConcentrationTrendAndValuationRisks() {
        val report = PortfolioStressPolicy.evaluate(
            stocks = listOf(
                stock("600519", "贵州茅台", "消费", change = -4.2, pe = 52.0, pb = 9.2, turnover = 5.0, price = 92.0, ma20 = 100.0),
                stock("000858", "五粮液", "消费", change = -2.8, pe = 35.0, pb = 5.4, turnover = 8.0, price = 95.0, ma20 = 100.0),
                stock("600887", "伊利股份", "消费", change = 0.2, pe = 20.0, pb = 3.0, turnover = 24.0, price = 101.0, ma20 = 100.0),
                stock("300750", "宁德时代", "新能源", change = 1.2, pe = 28.0, pb = 5.8, turnover = 82.0, price = 107.0, ma20 = 100.0)
            ),
            marketUpCount = 12,
            marketDownCount = 36
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score in 0..100)
        assertEquals(3, report.scenarios.size)
        assertTrue(report.riskItems.any { it.contains("下跌样本占比") })
        assertTrue(report.riskItems.any { it.contains("消费") && it.contains("行业") })
        assertTrue(report.riskItems.any { it.contains("估值") })
        assertEquals("600519", report.scenarios.first().impactedStocks.first().code)
    }

    @Test
    fun balancedWatchlistScoresHigherThanConcentratedWeakWatchlist() {
        val balanced = PortfolioStressPolicy.evaluate(
            stocks = listOf(
                stock("600036", "招商银行", "金融", change = 1.4, pe = 6.0, pb = 0.9, turnover = 81.0, price = 110.0, ma20 = 100.0),
                stock("300750", "宁德时代", "新能源", change = 2.0, pe = 22.0, pb = 4.0, turnover = 82.0, price = 112.0, ma20 = 100.0),
                stock("300059", "东方财富", "科技", change = 0.7, pe = 30.0, pb = 4.0, turnover = 44.0, price = 106.0, ma20 = 100.0),
                stock("300760", "迈瑞医疗", "医药", change = 0.3, pe = 29.0, pb = 7.0, turnover = 38.0, price = 105.0, ma20 = 100.0)
            ),
            marketUpCount = 30,
            marketDownCount = 18
        )
        val weak = PortfolioStressPolicy.evaluate(
            stocks = listOf(
                stock("600519", "贵州茅台", "消费", change = -5.0, pe = 50.0, pb = 9.0, turnover = 5.0, price = 90.0, ma20 = 100.0),
                stock("000858", "五粮液", "消费", change = -4.0, pe = 46.0, pb = 8.5, turnover = 8.0, price = 92.0, ma20 = 100.0),
                stock("600887", "伊利股份", "消费", change = -2.0, pe = 20.0, pb = 3.0, turnover = 12.0, price = 94.0, ma20 = 100.0)
            ),
            marketUpCount = 12,
            marketDownCount = 36
        )

        assertNotNull(balanced)
        assertNotNull(weak)
        checkNotNull(balanced)
        checkNotNull(weak)
        assertTrue(balanced.score > weak.score)
        assertTrue(
            balanced.scenarios.first().estimatedDrawdownPercent <
                weak.scenarios.first().estimatedDrawdownPercent
        )
    }

    private fun stock(
        code: String,
        name: String,
        industry: String,
        change: Double,
        pe: Double,
        pb: Double,
        turnover: Double,
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
            turnover = turnover,
            ma5 = ma20 + 5.0,
            ma10 = ma20 + 2.0,
            ma20 = ma20
        )
    }
}
