package com.tianxian.quant.model

data class StockInfo(
    val code: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val volume: Long,
    val marketCap: Double = 0.0,
    val pe: Double = 0.0,
    val pb: Double = 0.0,
    val isVip: Boolean = false,
    val industry: String = "未分类",
    val turnover: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val open: Double = 0.0,
    val yesterdayClose: Double = 0.0,
    val ma5: Double = 0.0,
    val ma10: Double = 0.0,
    val ma20: Double = 0.0
)

data class MovingAverageInfo(
    val code: String,
    val close: Double,
    val ma5: Double,
    val ma10: Double,
    val ma20: Double
)

data class SectorInfo(
    val name: String,
    val code: String = "",
    val changePercent: Double,
    val leadingStock: String,
    val leadingStockCode: String = "",
    val capitalFlow: Double,
    val stocks: List<StockInfo> = emptyList()
)

data class ReviewData(
    val date: String,
    val upCount: Int,
    val downCount: Int,
    val limitUpCount: Int,
    val limitDownCount: Int,
    val totalAmount: Double,
    val hotSectors: List<SectorInfo>,
    val limitUpStocks: List<StockInfo> = emptyList(),
    val limitDownStocks: List<StockInfo> = emptyList(),
    val strongStocks: List<StockInfo> = emptyList(),
    val sampleStocks: List<StockInfo> = emptyList(),
    val watchlistStocks: List<StockInfo> = emptyList(),
    val watchlistHealthReport: WatchlistHealthReport? = null,
    val portfolioStressReport: PortfolioStressReport? = null
)

data class ReviewSnapshot(
    val date: String,
    val upCount: Int,
    val downCount: Int,
    val limitUpCount: Int,
    val limitDownCount: Int,
    val totalAmount: Double,
    val sectorSummary: String,
    val strongStockSummary: String,
    val createdAt: Long
)

data class Post(
    val id: String,
    val author: String,
    val avatar: String,
    val title: String,
    val content: String,
    val time: String,
    val likes: Int,
    val comments: Int,
    val category: String,
    val isVip: Boolean = false,
    val images: List<String> = emptyList()
)

data class PostComment(
    val id: String,
    val postId: String,
    val author: String,
    val content: String,
    val time: String,
    val createdAt: Long
)

data class Strategy(
    val id: String,
    val name: String,
    val description: String,
    val winRate: Double,
    val maxDrawdown: Double,
    val sharpeRatio: Double,
    val annualReturn: Double = 0.0,
    val totalTrades: Int = 0,
    val profitFactor: Double = 0.0,
    val isVip: Boolean = false,
    val formula: String = "",
    val tags: List<String> = emptyList()
)

data class QuantSignal(
    val code: String,
    val name: String,
    val modelName: String,
    val strength: Int,
    val state: String,
    val factors: List<String>
)

data class VipPlan(
    val id: String,
    val name: String,
    val price: Double,
    val duration: String,
    val features: List<String>
)

enum class VipTier(val displayName: String) {
    STOCK("选股 VIP"),
    QUANT("量化 VIP"),
    FULL("全功能 VIP")
}

data class MarketOverview(
    val indexCode: String,
    val indexName: String,
    val price: Double,
    val changePercent: Double,
    val changePoint: Double,
    val volume: Long,
    val amount: Double
)

data class CapitalFlow(
    val code: String,
    val name: String,
    val mainInflow: Double,
    val mainOutflow: Double,
    val retailInflow: Double,
    val retailOutflow: Double,
    val netInflow: Double
)

data class TechSignal(
    val code: String,
    val name: String,
    val signal: String,
    val strength: Int,
    val price: Double,
    val changePercent: Double,
    val time: String
)

data class StockFilterCriteria(
    val sortMode: String = "全部",
    val industry: String? = null,
    val minChangePercent: Double? = null,
    val minVolume: Long? = null,
    val minTurnover: Double? = null,
    val maxPe: Double? = null,
    val maxPb: Double? = null,
    val minMarketCap: Double? = null
)
