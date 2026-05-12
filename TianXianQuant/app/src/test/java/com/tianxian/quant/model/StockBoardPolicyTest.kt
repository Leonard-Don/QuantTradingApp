package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockBoardPolicyTest {
    @Test
    fun summarizesBreadthRankingsAndHotSectors() {
        val snapshot = StockBoardPolicy.summarize(sampleStocks())

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(4, snapshot.sampleCount)
        assertEquals(2, snapshot.upCount)
        assertEquals(1, snapshot.downCount)
        assertEquals(1, snapshot.flatCount)
        assertEquals(listOf("300750", "300059", "600036"), snapshot.topGainers.map { it.code })
        assertEquals(listOf("600519", "600036", "300059"), snapshot.topLosers.map { it.code })
        assertEquals("新能源", snapshot.hotSectors.first().name)
        assertEquals("宁德时代", snapshot.hotSectors.first().leadingStockName)
    }

    @Test
    fun evaluatesStrongTrendAboveWeakTrend() {
        val strong = StockBoardPolicy.evaluateDetail(sampleStocks()[0])
        val weak = StockBoardPolicy.evaluateDetail(sampleStocks()[2])

        assertTrue(strong.score > weak.score)
        assertTrue(strong.toneText.contains("均线结构顺向"))
        assertTrue(weak.riskItems.any { it.contains("跌幅较大") || it.contains("MA20") })
    }

    private fun sampleStocks(): List<StockInfo> {
        return listOf(
            StockInfo(
                code = "300750",
                name = "宁德时代",
                price = 185.0,
                changePercent = 4.2,
                volume = 89_000,
                marketCap = 8_200.0,
                pe = 18.6,
                pb = 3.8,
                industry = "新能源",
                turnover = 82.0,
                high = 188.0,
                low = 178.0,
                open = 180.0,
                yesterdayClose = 177.5,
                ma5 = 180.0,
                ma10 = 175.0,
                ma20 = 170.0
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
                turnover = 44.0
            ),
            StockInfo(
                code = "600519",
                name = "贵州茅台",
                price = 1680.0,
                changePercent = -4.1,
                volume = 20_000,
                marketCap = 21_000.0,
                pe = 28.5,
                pb = 8.2,
                industry = "消费",
                turnover = 210.0,
                ma5 = 1700.0,
                ma10 = 1720.0,
                ma20 = 1750.0
            ),
            StockInfo(
                code = "600036",
                name = "招商银行",
                price = 32.0,
                changePercent = 0.0,
                volume = 125_000,
                marketCap = 8_100.0,
                pe = 5.8,
                pb = 0.85,
                industry = "金融",
                turnover = 81.0
            )
        )
    }
}
