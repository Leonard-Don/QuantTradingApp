package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketAnalysisPolicyTest {
    @Test
    fun returnsNullForEmptySamples() {
        assertNull(MarketAnalysisPolicy.evaluate(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun identifiesBroadPositiveMarketStructure() {
        val stocks = listOf(
            stock("300750", "宁德时代", "新能源", 3.4, 90.0),
            stock("002594", "比亚迪", "新能源", 2.8, 75.0),
            stock("300059", "东方财富", "科技", 1.6, 48.0),
            stock("002415", "海康威视", "科技", 1.1, 32.0),
            stock("600036", "招商银行", "金融", 0.8, 42.0),
            stock("601318", "中国平安", "金融", 0.4, 38.0),
            stock("600519", "贵州茅台", "消费", 0.2, 58.0),
            stock("000858", "五粮液", "消费", -0.3, 30.0),
            stock("300760", "迈瑞医疗", "医药", 1.0, 28.0),
            stock("000538", "云南白药", "医药", 0.6, 18.0),
            stock("688981", "中芯国际", "科技", 2.2, 55.0),
            stock("601012", "隆基绿能", "新能源", 1.9, 36.0),
            stock("600030", "中信证券", "金融", 0.7, 33.0),
            stock("600887", "伊利股份", "消费", 0.5, 21.0),
            stock("600276", "恒瑞医药", "医药", 1.3, 26.0),
        )

        val report = MarketAnalysisPolicy.evaluate(
            stocks = stocks,
            hotSectors = sectors(stocks),
            watchlistStocks = stocks.take(5),
            marketIndices = listOf(index("上证指数", 0.9), index("创业板指", 1.4)),
            source = "腾讯 quote",
            requestedSampleCount = stocks.size
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score >= 70)
        assertTrue(report.regime == "扩散偏强" || report.regime == "结构偏暖")
        assertTrue(report.breadthText.contains("上涨"))
        assertTrue(report.sectorText.contains("板块结构"))
        assertTrue(report.sourceText.contains("腾讯 quote"))
        assertTrue(report.qualityText.contains("100.0%"))
        assertTrue(report.indexAlignmentText.contains("指数联动"))
        assertTrue(report.focusSectors.first().name == "新能源" || report.focusSectors.first().name == "科技")
        assertTrue(report.researchActions.any { it.contains("市场宽度") })
    }

    @Test
    fun surfacesDefensiveRisksAndWatchlistLag() {
        val stocks = listOf(
            stock("600519", "贵州茅台", "消费", -4.2, 180.0),
            stock("000858", "五粮液", "消费", -3.4, 120.0),
            stock("600887", "伊利股份", "消费", -2.1, 80.0),
            stock("600036", "招商银行", "金融", -1.8, 75.0),
            stock("601318", "中国平安", "金融", -2.6, 68.0),
            stock("600030", "中信证券", "金融", -1.0, 54.0),
            stock("300750", "宁德时代", "新能源", 1.2, 45.0),
            stock("002594", "比亚迪", "新能源", 0.8, 34.0),
            stock("300059", "东方财富", "科技", -0.9, 38.0),
            stock("002415", "海康威视", "科技", -1.5, 29.0),
            stock("300760", "迈瑞医疗", "医药", 0.2, 24.0),
            stock("000538", "云南白药", "医药", -0.6, 20.0),
            stock("601012", "隆基绿能", "新能源", 0.4, 27.0),
            stock("688981", "中芯国际", "科技", -2.3, 47.0),
            stock("600276", "恒瑞医药", "医药", -0.4, 22.0),
        )
        val watchlist = stocks.take(5)

        val report = MarketAnalysisPolicy.evaluate(stocks, sectors(stocks), watchlist)

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score < 70)
        assertEquals("防守观察", report.regime)
        assertTrue(report.riskItems.any { it.contains("上涨样本占比") })
        assertTrue(report.riskItems.any { it.contains("下跌样本成交额") })
        assertTrue(report.riskItems.any { it.contains("自选池平均涨跌") })
        assertTrue(report.watchlistText.contains("弱于"))
        assertEquals("600519", report.focusStocks.first().code)
    }

    @Test
    fun warnsWhenSampleCoverageIsThin() {
        val stocks = listOf(
            stock("300750", "宁德时代", "新能源", 2.4, 40.0),
            stock("300059", "东方财富", "科技", -1.2, 20.0),
            stock("600036", "招商银行", "金融", 0.3, 30.0),
        )

        val report = MarketAnalysisPolicy.evaluate(stocks, sectors(stocks), emptyList())

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.riskItems.any { it.contains("样本仅 3 只") })
        assertTrue(report.watchlistText.contains("暂无自选池样本"))
    }

    @Test
    fun surfacesDataQualityAndIndexDivergence() {
        val stocks = listOf(
            stock("300750", "宁德时代", "新能源", 3.2, 40.0),
            stock("002594", "比亚迪", "新能源", 2.8, 35.0),
            stock("300059", "东方财富", "科技", 2.4, 22.0)
        )

        val report = MarketAnalysisPolicy.evaluate(
            stocks = stocks,
            hotSectors = sectors(stocks),
            watchlistStocks = emptyList(),
            marketIndices = listOf(index("上证指数", -0.5), index("创业板指", -0.8)),
            source = "本机行情缓存",
            warnings = listOf("腾讯 quote 超时"),
            usingFallback = true,
            requestedSampleCount = 12
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.sourceText.contains("本机行情缓存"))
        assertTrue(report.qualityText.contains("25.0%"))
        assertTrue(report.indexAlignmentText.contains("明显强于指数"))
        assertTrue(report.riskItems.any { it.contains("行情覆盖 25.0%") })
        assertTrue(report.riskItems.any { it.contains("数据源提示") })
        assertTrue(report.researchActions.any { it.contains("行情源") })
    }

    private fun sectors(stocks: List<StockInfo>): List<SectorInfo> {
        return stocks.groupBy { it.industry }
            .map { (industry, items) ->
                val leader = items.maxByOrNull { it.changePercent } ?: items.first()
                SectorInfo(
                    name = industry,
                    code = industry,
                    changePercent = items.map { it.changePercent }.average(),
                    leadingStock = leader.name,
                    leadingStockCode = leader.code,
                    capitalFlow = items.sumOf { it.turnover },
                    stocks = items
                )
            }
    }

    private fun stock(
        code: String,
        name: String,
        industry: String,
        change: Double,
        turnover: Double
    ): StockInfo {
        return StockInfo(
            code = code,
            name = name,
            price = 100.0,
            changePercent = change,
            volume = 100_000,
            marketCap = 1_000.0,
            pe = 20.0,
            pb = 3.0,
            industry = industry,
            turnover = turnover
        )
    }

    private fun index(name: String, change: Double): MarketOverview {
        return MarketOverview(
            indexCode = name,
            indexName = name,
            price = 3_000.0,
            changePercent = change,
            changePoint = 0.0,
            volume = 100_000,
            amount = 1_000.0
        )
    }
}
