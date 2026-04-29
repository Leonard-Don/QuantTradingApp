package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioHoldingPolicyTest {
    @Test
    fun returnsNullForEmptyHoldings() {
        assertNull(PortfolioHoldingPolicy.evaluate(emptyList(), emptyList()))
    }

    @Test
    fun calculatesProfitLossWeightsAndRiskTags() {
        val report = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                holding("600519", "贵州茅台", cost = 100.0, quantity = 100.0),
                holding("300750", "宁德时代", cost = 200.0, quantity = 50.0),
                holding("000858", "五粮液", cost = 160.0, quantity = 30.0)
            ),
            quotes = listOf(
                stock("600519", "贵州茅台", price = 90.0, change = -4.2, pe = 50.0, pb = 9.0),
                stock("300750", "宁德时代", price = 220.0, change = 1.2, pe = 22.0, pb = 4.2),
                stock("000858", "五粮液", price = 158.0, change = -1.0, pe = 26.0, pb = 4.8)
            )
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score in 0..100)
        assertTrue(report.profitLossPercent > -10.0)
        assertEquals(3, report.positions.size)
        assertTrue(report.positions.first().weightPercent > 30.0)
        assertTrue(report.positions.any { "浮亏压力" in it.riskTags })
        assertTrue(report.riskItems.any { it.contains("估值") })
    }

    @Test
    fun flagsMissingQuotesAndSmallPortfolio() {
        val report = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                holding("600519", "贵州茅台", cost = 100.0, quantity = 100.0),
                holding("000001", "平安银行", cost = 10.0, quantity = 500.0)
            ),
            quotes = listOf(stock("600519", "贵州茅台", price = 110.0, change = 1.0, pe = 28.0, pb = 6.0))
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.quoteCoverageText.contains("1/2"))
        assertTrue(report.riskItems.any { it.contains("行情覆盖") })
        assertTrue(report.riskItems.any { it.contains("少于 3") })
        assertTrue(report.positions.any { "行情缺失" in it.riskTags })
    }

    @Test
    fun balancedProfitablePortfolioScoresHigherThanConcentratedLosingPortfolio() {
        val strong = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                holding("600036", "招商银行", cost = 30.0, quantity = 1000.0),
                holding("300750", "宁德时代", cost = 180.0, quantity = 120.0),
                holding("300059", "东方财富", cost = 14.0, quantity = 1500.0)
            ),
            quotes = listOf(
                stock("600036", "招商银行", price = 34.0, change = 1.4, pe = 6.0, pb = 0.9),
                stock("300750", "宁德时代", price = 205.0, change = 2.0, pe = 22.0, pb = 4.0),
                stock("300059", "东方财富", price = 15.2, change = 0.8, pe = 30.0, pb = 4.0)
            )
        )
        val weak = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                holding("600519", "贵州茅台", cost = 120.0, quantity = 500.0),
                holding("000858", "五粮液", cost = 160.0, quantity = 40.0)
            ),
            quotes = listOf(
                stock("600519", "贵州茅台", price = 95.0, change = -5.0, pe = 50.0, pb = 9.0),
                stock("000858", "五粮液", price = 145.0, change = -4.0, pe = 46.0, pb = 8.5)
            )
        )

        assertNotNull(strong)
        assertNotNull(weak)
        checkNotNull(strong)
        checkNotNull(weak)
        assertTrue(strong.score > weak.score)
        assertTrue(strong.profitLoss > 0.0)
        assertTrue(weak.riskItems.any { it.contains("集中") || it.contains("权重") })
    }

    private fun holding(code: String, name: String, cost: Double, quantity: Double): PortfolioHolding {
        return PortfolioHolding(code = code, name = name, costPrice = cost, quantity = quantity)
    }

    private fun stock(
        code: String,
        name: String,
        price: Double,
        change: Double,
        pe: Double,
        pb: Double
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
            industry = "测试行业",
            turnover = 20.0,
            ma5 = 104.0,
            ma10 = 102.0,
            ma20 = 100.0
        )
    }
}
