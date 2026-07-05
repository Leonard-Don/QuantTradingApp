package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertTrue
import org.junit.Test

class StockResearchPolicyTest {
    @Test
    fun strongTrendReasonableValuationGetsHigherScore() {
        val strong = StockResearchPolicy.evaluate(
            stock(
                change = 1.2,
                price = 110.0,
                ma5 = 108.0,
                ma10 = 104.0,
                ma20 = 100.0,
                pe = 18.0,
                pb = 2.4,
                turnover = 82.0
            )
        )
        val weak = StockResearchPolicy.evaluate(
            stock(
                change = -6.5,
                price = 88.0,
                ma5 = 94.0,
                ma10 = 97.0,
                ma20 = 100.0,
                pe = 68.0,
                pb = 9.2,
                turnover = 1.4
            )
        )

        assertTrue(strong.score > weak.score)
        assertTrue(strong.trendText.contains("强趋势"))
        assertTrue(weak.riskItems.any { it.contains("MA20") })
        assertTrue(weak.riskItems.any { it.contains("估值") })
        assertTrue(weak.riskItems.any { it.contains("成交额") })
    }

    @Test
    fun missingMovingAverageIsReportedAsConfidenceRisk() {
        val report = StockResearchPolicy.evaluate(
            stock(
                change = 0.5,
                price = 20.0,
                ma5 = 0.0,
                ma10 = 0.0,
                ma20 = 0.0,
                pe = 20.0,
                pb = 2.0,
                turnover = 20.0
            )
        )

        assertTrue(report.riskItems.any { it.contains("均线字段不足") })
        assertTrue(report.researchActions.any { it.contains("历史 K 线") })
    }

    @Test
    fun wideIntradayRangeAddsVolatilityRisk() {
        val report = StockResearchPolicy.evaluate(
            stock(
                change = 2.0,
                price = 100.0,
                ma5 = 99.0,
                ma10 = 98.0,
                ma20 = 96.0,
                pe = 22.0,
                pb = 3.0,
                turnover = 35.0,
                high = 108.0,
                low = 99.0,
                yesterdayClose = 100.0
            )
        )

        assertTrue(report.riskItems.any { it.contains("振幅") })
    }

    private fun stock(
        change: Double,
        price: Double,
        ma5: Double,
        ma10: Double,
        ma20: Double,
        pe: Double,
        pb: Double,
        turnover: Double,
        high: Double = 0.0,
        low: Double = 0.0,
        yesterdayClose: Double = 0.0
    ): StockInfo {
        return StockInfo(
            code = "600000",
            name = "测试股票",
            price = price,
            changePercent = change,
            volume = 100_000,
            marketCap = 1_000.0,
            pe = pe,
            pb = pb,
            industry = "测试",
            turnover = turnover,
            high = high,
            low = low,
            yesterdayClose = yesterdayClose,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20
        )
    }
}
