package io.github.leonarddon.quanttrading.model

data class StockSectorPulse(
    val name: String,
    val stockCount: Int,
    val averageChangePercent: Double,
    val leadingStockName: String,
    val leadingStockCode: String,
    val leadingChangePercent: Double,
    val turnover: Double
)

data class StockBoardSnapshot(
    val sampleCount: Int,
    val upCount: Int,
    val downCount: Int,
    val flatCount: Int,
    val totalTurnover: Double,
    val topGainers: List<StockInfo>,
    val topLosers: List<StockInfo>,
    val hotSectors: List<StockSectorPulse>
)

data class StockDetailInsight(
    val score: Int,
    val grade: String,
    val toneText: String,
    val priceActionText: String,
    val valuationText: String,
    val liquidityText: String,
    val riskItems: List<String>,
    val researchActions: List<String>
)

object StockBoardPolicy {
    fun summarize(stocks: List<StockInfo>): StockBoardSnapshot? {
        val validStocks = stocks.filter { it.price > 0 }
        if (validStocks.isEmpty()) return null

        val sectors = validStocks
            .filter { it.industry.isNotBlank() && it.industry != "未分类" }
            .groupBy { it.industry }
            .map { (industry, items) ->
                val leader = items.maxWithOrNull(compareBy<StockInfo> { it.changePercent }.thenBy { it.turnover })
                    ?: items.first()
                StockSectorPulse(
                    name = industry,
                    stockCount = items.size,
                    averageChangePercent = items.map { it.changePercent }.average(),
                    leadingStockName = leader.name,
                    leadingStockCode = leader.code,
                    leadingChangePercent = leader.changePercent,
                    turnover = items.sumOf { it.turnover }
                )
            }
            .sortedWith(
                compareByDescending<StockSectorPulse> { it.averageChangePercent }
                    .thenByDescending { it.turnover }
            )
            .take(3)

        return StockBoardSnapshot(
            sampleCount = validStocks.size,
            upCount = validStocks.count { it.changePercent > 0 },
            downCount = validStocks.count { it.changePercent < 0 },
            flatCount = validStocks.count { it.changePercent == 0.0 },
            totalTurnover = validStocks.sumOf { it.turnover },
            topGainers = validStocks.sortedByDescending { it.changePercent }.take(3),
            topLosers = validStocks.sortedBy { it.changePercent }.take(3),
            hotSectors = sectors
        )
    }

    fun evaluateDetail(stock: StockInfo): StockDetailInsight {
        val score = (
            50 +
                priceActionScore(stock) +
                movingAverageScore(stock) +
                valuationScore(stock) +
                liquidityScore(stock)
            ).coerceIn(0, 100)

        val riskItems = buildRiskItems(stock)
        val actions = buildResearchActions(stock)

        return StockDetailInsight(
            score = score,
            grade = grade(score),
            toneText = toneText(stock, score),
            priceActionText = priceActionText(stock),
            valuationText = valuationText(stock),
            liquidityText = liquidityText(stock),
            riskItems = riskItems,
            researchActions = actions
        )
    }

    private fun priceActionScore(stock: StockInfo): Int {
        return when {
            stock.changePercent >= 5.0 -> 22
            stock.changePercent >= 3.0 -> 16
            stock.changePercent >= 1.0 -> 10
            stock.changePercent > 0.0 -> 4
            stock.changePercent <= -5.0 -> -22
            stock.changePercent <= -3.0 -> -16
            stock.changePercent <= -1.0 -> -8
            else -> 0
        }
    }

    private fun movingAverageScore(stock: StockInfo): Int {
        if (!stock.hasMovingAverageData()) return 0
        return when {
            stock.price >= stock.ma5 && stock.ma5 >= stock.ma10 && stock.ma10 >= stock.ma20 -> 18
            stock.price >= stock.ma20 -> 6
            stock.price < stock.ma20 -> -12
            else -> 0
        }
    }

    private fun valuationScore(stock: StockInfo): Int {
        var score = 0
        if (stock.pe > 0) {
            score += when {
                stock.pe <= 15 -> 8
                stock.pe <= 35 -> 4
                stock.pe >= 80 -> -12
                stock.pe >= 55 -> -8
                else -> 0
            }
        }
        if (stock.pb > 0) {
            score += when {
                stock.pb <= 1.5 -> 5
                stock.pb <= 4 -> 2
                stock.pb >= 10 -> -8
                stock.pb >= 7 -> -4
                else -> 0
            }
        }
        return score
    }

    private fun liquidityScore(stock: StockInfo): Int {
        return when {
            stock.turnover >= 100 -> 8
            stock.turnover >= 30 -> 5
            stock.volume >= 100_000 -> 3
            stock.turnover > 0 || stock.volume > 0 -> 1
            else -> -4
        }
    }

    private fun grade(score: Int): String {
        return when {
            score >= 85 -> "强势观察"
            score >= 70 -> "积极"
            score >= 55 -> "中性"
            score >= 40 -> "偏弱"
            else -> "高风险"
        }
    }

    private fun toneText(stock: StockInfo, score: Int): String {
        val direction = when {
            stock.changePercent >= 3 -> "短线强势"
            stock.changePercent <= -3 -> "短线承压"
            stock.changePercent > 0 -> "温和走强"
            stock.changePercent < 0 -> "温和回落"
            else -> "横盘观察"
        }
        val maText = if (stock.hasMovingAverageData()) {
            when {
                stock.price >= stock.ma5 && stock.ma5 >= stock.ma10 && stock.ma10 >= stock.ma20 -> "均线结构顺向"
                stock.price < stock.ma20 -> "价格低于 MA20"
                else -> "均线结构仍需确认"
            }
        } else {
            "均线样本不足"
        }
        return "$direction，$maText，综合观察 $score 分。"
    }

    private fun priceActionText(stock: StockInfo): String {
        val rangeText = if (stock.high > 0 && stock.low > 0) {
            "日内区间 ${formatNumber(stock.low)}-${formatNumber(stock.high)}"
        } else {
            "日内区间暂缺"
        }
        val openText = if (stock.open > 0 && stock.yesterdayClose > 0) {
            "开盘 ${formatNumber(stock.open)}，昨收 ${formatNumber(stock.yesterdayClose)}"
        } else {
            "开盘/昨收暂缺"
        }
        return "$rangeText；$openText。"
    }

    private fun valuationText(stock: StockInfo): String {
        val peText = stock.pe.takeIf { it > 0 }?.let { "PE ${formatNumber(it)}" } ?: "PE 暂缺"
        val pbText = stock.pb.takeIf { it > 0 }?.let { "PB ${formatNumber(it)}" } ?: "PB 暂缺"
        val capText = stock.marketCap.takeIf { it > 0 }?.let { "市值 ${formatNumber(it)} 亿" } ?: "市值暂缺"
        return "$peText，$pbText，$capText。"
    }

    private fun liquidityText(stock: StockInfo): String {
        val turnoverText = stock.turnover.takeIf { it > 0 }?.let { "成交额 ${formatNumber(it)} 亿" } ?: "成交额暂缺"
        return "$turnoverText，成交量 ${stock.volume} 股。"
    }

    private fun buildRiskItems(stock: StockInfo): List<String> {
        val risks = mutableListOf<String>()
        if (stock.changePercent <= -3.0) {
            risks += "当日跌幅较大，需复查是否存在行业或个股事件驱动。"
        }
        if (stock.hasMovingAverageData() && stock.price < stock.ma20) {
            risks += "价格低于 MA20，趋势结构尚未恢复。"
        }
        if (stock.pe >= 55 || stock.pb >= 7) {
            risks += "估值字段偏高，需要补充财报质量和景气度验证。"
        }
        if (stock.turnover <= 0.0 && stock.volume <= 0) {
            risks += "流动性字段缺失，当前详情只适合做静态记录。"
        }
        if (risks.isEmpty()) {
            risks += "暂无明显单项风险，但仍需结合公告、财报和行业数据复核。"
        }
        return risks
    }

    private fun buildResearchActions(stock: StockInfo): List<String> {
        val actions = mutableListOf(
            "核对 ${stock.industry} 行业同类样本，确认涨跌是否具备板块共振。",
            "把今日价格、成交额和均线状态保存到复盘记录，观察连续两次变化。"
        )
        if (stock.pe <= 0 || stock.pb <= 0) {
            actions += "补充估值字段来源，避免只靠价格涨跌判断。"
        }
        if (stock.hasMovingAverageData()) {
            actions += "复查 MA5/MA10/MA20 的斜率变化，确认趋势是否延续。"
        }
        actions += "仅作研究动作清单，不构成买卖建议或交易指令。"
        return actions
    }

    private fun formatNumber(value: Double): String {
        return "%.2f".format(value)
    }
}
