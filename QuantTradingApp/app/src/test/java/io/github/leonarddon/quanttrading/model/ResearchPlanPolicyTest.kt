package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPlanPolicyTest {
    @Test
    fun buildsPlanFromBriefWatchlistStressAndHoldings() {
        val watchlist = listOf(
            stock("600036", "招商银行", "金融", change = 1.5, pe = 6.0, pb = 0.9),
            stock("300750", "宁德时代", "新能源", change = 2.2, pe = 22.0, pb = 4.1)
        )
        val health = WatchlistHealthPolicy.evaluate(watchlist)
        val stress = PortfolioStressPolicy.evaluate(watchlist, marketUpCount = 36, marketDownCount = 12)
        val holding = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                PortfolioHolding("600036", "招商银行", costPrice = 30.0, quantity = 1000.0),
                PortfolioHolding("300750", "宁德时代", costPrice = 180.0, quantity = 120.0)
            ),
            quotes = watchlist
        )
        val brief = DailyResearchBriefPolicy.evaluate(
            date = "2026-04-29",
            upCount = 36,
            downCount = 12,
            totalAmount = 480.0,
            hotSectors = listOf(sector("新能源", 2.4, "宁德时代")),
            strongStocks = watchlist,
            watchlistStocks = watchlist,
            watchlistHealthReport = health,
            portfolioStressReport = stress,
            portfolioHoldingReport = holding
        )

        val report = ResearchPlanPolicy.evaluate(
            date = "2026-04-29",
            upCount = 36,
            downCount = 12,
            totalAmount = 480.0,
            hotSectors = listOf(sector("新能源", 2.4, "宁德时代")),
            strongStocks = watchlist,
            watchlistStocks = watchlist,
            watchlistHealthReport = health,
            portfolioStressReport = stress,
            portfolioHoldingReport = holding,
            dailyBriefReport = brief
        )

        assertTrue(report.score in 0..100)
        assertTrue(report.headline.contains("2026-04-29"))
        assertTrue(report.dailyFocus.contains("持仓组合"))
        assertTrue(report.planItems.any { it.title.contains("持仓组合") })
        assertTrue(report.planItems.any { it.source == "简报" })
        assertTrue(report.exportText.contains("研究任务"))
        assertTrue(report.exportText.contains("不构成投资建议"))
    }

    @Test
    fun emptyPersonalDataCreatesHighPrioritySetupTasks() {
        val report = ResearchPlanPolicy.evaluate(
            date = "2026-04-29",
            upCount = 8,
            downCount = 40,
            totalAmount = 120.0,
            hotSectors = emptyList(),
            strongStocks = emptyList(),
            watchlistStocks = emptyList(),
            watchlistHealthReport = null,
            portfolioStressReport = null,
            portfolioHoldingReport = null,
            dailyBriefReport = null
        )

        assertEquals("高", report.planItems.first().priority)
        assertTrue(report.planItems.any { it.title.contains("建立自选") })
        assertTrue(report.planItems.any { it.title.contains("补全持仓") })
        assertTrue(report.trackingChecklist.any { it.contains("补录") })
        assertTrue(report.dailyFocus.contains("持仓组合待补全"))
    }

    @Test
    fun pressuredReportsLiftRiskItemsAndPriority() {
        val weakWatchlist = listOf(
            stock("600519", "贵州茅台", "消费", change = -4.5, pe = 52.0, pb = 9.0),
            stock("000858", "五粮液", "消费", change = -3.8, pe = 46.0, pb = 8.6)
        )
        val health = WatchlistHealthPolicy.evaluate(weakWatchlist)
        val stress = PortfolioStressPolicy.evaluate(weakWatchlist, marketUpCount = 8, marketDownCount = 40)
        val holding = PortfolioHoldingPolicy.evaluate(
            holdings = listOf(
                PortfolioHolding("600519", "贵州茅台", costPrice = 120.0, quantity = 500.0),
                PortfolioHolding("000858", "五粮液", costPrice = 160.0, quantity = 40.0)
            ),
            quotes = weakWatchlist.map {
                it.copy(price = if (it.code == "600519") 95.0 else 145.0)
            }
        )

        val report = ResearchPlanPolicy.evaluate(
            date = "2026-04-29",
            upCount = 8,
            downCount = 40,
            totalAmount = 120.0,
            hotSectors = listOf(sector("消费", -2.0, "贵州茅台")),
            strongStocks = weakWatchlist,
            watchlistStocks = weakWatchlist,
            watchlistHealthReport = health,
            portfolioStressReport = stress,
            portfolioHoldingReport = holding,
            dailyBriefReport = null
        )

        assertTrue(report.score < 72)
        assertTrue(report.planItems.count { it.priority == "高" } >= 2)
        assertTrue(report.riskReviewItems.any { it.contains("消费") || it.contains("浮亏") || it.contains("压力") })
    }

    @Test
    fun marketAnalysisReportAddsTemperaturePlanAndChecklist() {
        val watchlist = listOf(
            stock("300750", "宁德时代", "新能源", change = 1.8, pe = 22.0, pb = 4.1),
            stock("300059", "东方财富", "科技", change = -1.0, pe = 28.0, pb = 3.9)
        )
        val marketReport = marketReport()

        val report = ResearchPlanPolicy.evaluate(
            date = "2026-04-29",
            upCount = 20,
            downCount = 20,
            totalAmount = 360.0,
            hotSectors = listOf(sector("新能源", 2.0, "宁德时代")),
            strongStocks = watchlist,
            watchlistStocks = watchlist,
            watchlistHealthReport = WatchlistHealthPolicy.evaluate(watchlist),
            portfolioStressReport = PortfolioStressPolicy.evaluate(watchlist, marketUpCount = 20, marketDownCount = 20),
            portfolioHoldingReport = null,
            dailyBriefReport = null,
            marketAnalysisReport = marketReport
        )

        assertTrue(report.dailyFocus.contains("市场温度 52/100"))
        assertTrue(report.planItems.any { it.title == "市场温度复盘" && it.priority == "高" })
        assertTrue(report.planItems.any { it.title == "行情质量核对" && it.source == "数据" })
        assertTrue(report.planItems.any { it.title.contains("新能源结构跟踪") })
        assertTrue(report.riskReviewItems.any { it.contains("上涨样本占比") })
        assertTrue(report.trackingChecklist.any { it.contains("行情来源") || it.contains("覆盖率") })
        assertTrue(report.trackingChecklist.any { it.contains("市场温度 52/100") })
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
            score = 52,
            grade = "谨慎",
            regime = "防守观察",
            breadthText = "市场宽度：上涨样本占比 36.0%。",
            turnoverText = "成交额方向：下跌样本成交额偏高。",
            sectorText = "板块结构：新能源领涨但扩散不足。",
            watchlistText = "自选一致性：弱于行情池平均。",
            sourceText = "行情来源：本机行情缓存（降级口径）。",
            qualityText = "数据质量：行情覆盖 60.0%（18/30），覆盖偏窄。",
            indexAlignmentText = "指数联动：上证指数 +0.2%；指数平均涨跌 +0.2%，行情池平均 -0.8%，行情池略弱于指数，结构相对承压。",
            riskItems = listOf("上涨样本占比偏低。", "下跌样本成交额偏高。"),
            focusSectors = listOf(sector("新能源", 2.0, "宁德时代")),
            focusStocks = emptyList(),
            researchActions = listOf("成交额偏向下跌样本时，先减少结论外推。")
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
