package io.github.leonarddon.quanttrading.model

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

    @Test
    fun marketAnalysisReportEnrichesBriefPulseRisksAndActions() {
        val watchlist = listOf(
            stock("300750", "宁德时代", "新能源", change = 2.2, pe = 22.0, pb = 4.1),
            stock("300059", "东方财富", "科技", change = -1.2, pe = 28.0, pb = 3.9)
        )
        val marketReport = marketReport()

        val report = DailyResearchBriefPolicy.evaluate(
            date = "2026-04-29",
            upCount = 22,
            downCount = 18,
            totalAmount = 360.0,
            hotSectors = listOf(sector("新能源", 2.0, "宁德时代")),
            strongStocks = watchlist,
            watchlistStocks = watchlist,
            watchlistHealthReport = WatchlistHealthPolicy.evaluate(watchlist),
            portfolioStressReport = PortfolioStressPolicy.evaluate(watchlist, marketUpCount = 22, marketDownCount = 18),
            marketAnalysisReport = marketReport
        )

        assertTrue(report.headline.contains("市场温度 76/100"))
        assertTrue(report.marketPulse.contains("成交额方向"))
        assertTrue(report.marketPulse.contains("数据质量"))
        assertTrue(report.marketPulse.contains("指数联动"))
        assertTrue(report.focusItems.any { it.contains("市场温度") })
        assertTrue(report.focusItems.any { it.contains("指数联动") })
        assertTrue(report.riskItems.any { it.contains("板块分化") })
        assertTrue(report.actionItems.any { it.contains("确认市场宽度") })
    }

    private fun sector(name: String, change: Double, leader: String): SectorInfo {
        return SectorInfo(
            name = name,
            changePercent = change,
            leadingStock = leader,
            capitalFlow = 100.0
        )
    }

    private fun marketReport(): MarketAnalysisReport {
        return MarketAnalysisReport(
            score = 76,
            grade = "偏暖",
            regime = "结构偏暖",
            breadthText = "市场宽度：上涨 22 只、下跌 18 只。",
            turnoverText = "成交额方向：上涨样本 210.0亿、下跌样本 150.0亿。",
            sectorText = "板块结构：新能源领涨，板块分化仍需观察。",
            watchlistText = "自选一致性：接近行情池平均。",
            sourceText = "行情来源：腾讯 quote（实时 quote 口径）。",
            qualityText = "数据质量：行情覆盖 100.0%（40/40），覆盖较完整。",
            indexAlignmentText = "指数联动：上证指数 +0.5%；指数平均涨跌 +0.5%，行情池平均 +0.8%，行情池与指数方向基本一致。",
            riskItems = listOf("板块分化仍需观察。"),
            focusSectors = listOf(sector("新能源", 2.0, "宁德时代")),
            focusStocks = emptyList(),
            researchActions = listOf("先确认市场宽度、成交额方向和板块扩散是否同向。")
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
