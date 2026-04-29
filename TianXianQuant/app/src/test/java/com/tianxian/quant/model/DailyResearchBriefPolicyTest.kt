package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyResearchBriefPolicyTest {
    @Test
    fun buildsBriefWithMarketSectorAndWatchlistContext() {
        val watchlist = listOf(
            stock("600036", "招商银行", "金融", change = 1.5, pe = 6.0, pb = 0.9),
            stock("300750", "宁德时代", "新能源", change = 2.2, pe = 22.0, pb = 4.1)
        )
        val health = WatchlistHealthPolicy.evaluate(watchlist)
        val stress = PortfolioStressPolicy.evaluate(watchlist, marketUpCount = 36, marketDownCount = 12)
        val report = DailyResearchBriefPolicy.evaluate(
            date = "2026-04-29",
            upCount = 36,
            downCount = 12,
            totalAmount = 480.0,
            hotSectors = listOf(sector("新能源", 2.4, "宁德时代"), sector("金融", 1.2, "招商银行")),
            strongStocks = watchlist,
            watchlistStocks = watchlist,
            watchlistHealthReport = health,
            portfolioStressReport = stress
        )

        assertTrue(report.score in 0..100)
        assertTrue(report.headline.contains("2026-04-29"))
        assertTrue(report.marketPulse.contains("上涨 36 只"))
        assertTrue(report.sectorPulse.contains("新能源"))
        assertTrue(report.watchlistPulse.contains("自选池 2 只"))
        assertTrue(report.focusItems.any { it.contains("板块焦点") })
        assertTrue(report.actionItems.any { it.contains("研究记录") || it.contains("复盘") })
    }

    @Test
    fun emptyWatchlistBriefAsksUserToBuildPersonalTrackingPool() {
        val report = DailyResearchBriefPolicy.evaluate(
            date = "2026-04-29",
            upCount = 10,
            downCount = 28,
            totalAmount = 120.0,
            hotSectors = emptyList(),
            strongStocks = emptyList(),
            watchlistStocks = emptyList(),
            watchlistHealthReport = null,
            portfolioStressReport = null
        )

        assertEquals("谨慎", report.grade)
        assertTrue(report.watchlistPulse.contains("自选池暂无样本"))
        assertTrue(report.riskItems.any { it.contains("自选池为空") })
        assertTrue(report.actionItems.any { it.contains("加入至少 5 只") })
    }

    @Test
    fun stressedMarketIncludesRiskFirstLanguage() {
        val weakWatchlist = listOf(
            stock("600519", "贵州茅台", "消费", change = -4.5, pe = 52.0, pb = 9.0),
            stock("000858", "五粮液", "消费", change = -3.8, pe = 46.0, pb = 8.6)
        )
        val health = WatchlistHealthPolicy.evaluate(weakWatchlist)
        val stress = PortfolioStressPolicy.evaluate(weakWatchlist, marketUpCount = 8, marketDownCount = 40)
        val report = DailyResearchBriefPolicy.evaluate(
            date = "2026-04-29",
            upCount = 8,
            downCount = 40,
            totalAmount = 180.0,
            hotSectors = listOf(sector("消费", -2.0, "贵州茅台")),
            strongStocks = weakWatchlist,
            watchlistStocks = weakWatchlist,
            watchlistHealthReport = health,
            portfolioStressReport = stress
        )

        assertTrue(report.score < 72)
        assertTrue(report.marketPulse.contains("样本承压"))
        assertTrue(report.riskItems.any { it.contains("优先识别风险") })
        assertTrue(report.riskItems.any { it.contains("消费") || it.contains("短期压力") })
    }

    private fun sector(name: String, change: Double, leader: String): SectorInfo {
        return SectorInfo(
            name = name,
            changePercent = change,
            leadingStock = leader,
            capitalFlow = 100.0
        )
    }

    private fun stock(
        code: String,
        name: String,
        industry: String,
        change: Double,
        pe: Double,
        pb: Double
    ): StockInfo {
        return StockInfo(
            code = code,
            name = name,
            price = 108.0,
            changePercent = change,
            volume = 100_000,
            marketCap = 1_000.0,
            pe = pe,
            pb = pb,
            industry = industry,
            turnover = 30.0,
            ma5 = 106.0,
            ma10 = 104.0,
            ma20 = 100.0
        )
    }
}
