package io.github.leonarddon.quanttrading.model

import java.util.Locale

data class DailyResearchBriefReport(
    val score: Int,
    val grade: String,
    val headline: String,
    val marketPulse: String,
    val sectorPulse: String,
    val watchlistPulse: String,
    val focusItems: List<String>,
    val riskItems: List<String>,
    val actionItems: List<String>
)

object DailyResearchBriefPolicy {
    fun evaluate(
        date: String,
        upCount: Int,
        downCount: Int,
        totalAmount: Double,
        hotSectors: List<SectorInfo>,
        strongStocks: List<StockInfo>,
        watchlistStocks: List<StockInfo>,
        watchlistHealthReport: WatchlistHealthReport?,
        portfolioStressReport: PortfolioStressReport?,
        portfolioHoldingReport: PortfolioHoldingReport? = null,
        marketAnalysisReport: MarketAnalysisReport? = null
    ): DailyResearchBriefReport {
        val totalBreadth = (upCount + downCount).coerceAtLeast(1)
        val upRatio = upCount * 1.0 / totalBreadth
        val marketSignalScore = marketAnalysisReport?.score?.let { (it - 62) / 3 } ?: 0
        val marketScore = when {
            upRatio >= 0.68 -> 18
            upRatio >= 0.54 -> 12
            upRatio >= 0.42 -> 6
            else -> -4
        }
        val healthScore = watchlistHealthReport?.score?.let { (it - 65) / 2 } ?: -4
        val stressScore = portfolioStressReport?.score?.let { (it - 65) / 3 } ?: -3
        val sectorScore = hotSectors.take(3).count { it.changePercent > 0 } * 3
        val score = (64 + marketScore + marketSignalScore + healthScore + stressScore + sectorScore).coerceIn(0, 100)

        val marketTone = when {
            upRatio >= 0.68 -> "样本扩散偏强"
            upRatio >= 0.54 -> "样本结构偏暖"
            upRatio >= 0.42 -> "样本分化"
            else -> "样本承压"
        }
        val topSector = hotSectors.firstOrNull()
        val topStrongStocks = strongStocks.take(3).joinToString("、") { it.name }.ifBlank { "暂无" }
        val watchlistAvgChange = watchlistStocks.takeIf { it.isNotEmpty() }
            ?.map { it.changePercent }
            ?.average()
        val holdingPulse = portfolioHoldingReport?.let {
            " 持仓组合 ${it.positions.size} 个，浮盈亏 ${formatPercent(it.profitLossPercent)}，组合 ${it.score} 分。"
        }.orEmpty()
        val watchlistPulse = if (watchlistStocks.isEmpty()) {
            "自选池暂无样本，今日简报只能覆盖行情池和板块样本。"
        } else {
            "自选池 ${watchlistStocks.size} 只，平均涨跌 ${formatPercent(watchlistAvgChange ?: 0.0)}；" +
                "健康 ${watchlistHealthReport?.score?.toString() ?: "暂无"} 分，压力 ${portfolioStressReport?.score?.toString() ?: "暂无"} 分。"
        } + holdingPulse

        val focusItems = buildList {
            marketAnalysisReport?.let {
                add("市场温度：${it.score}/100（${it.grade}｜${it.regime}）。")
            }
            add("市场宽度：上涨 $upCount 只、下跌 $downCount 只，上涨占比 ${formatRatio(upRatio)}。")
            topSector?.let {
                add("板块焦点：${it.name} 样本平均涨跌 ${formatPercent(it.changePercent)}，代表样本 ${it.leadingStock}。")
            }
            marketAnalysisReport?.focusSectors?.firstOrNull()?.let {
                add("结构焦点：${it.name} 样本成交额 ${formatAmount(it.capitalFlow)}，代表样本 ${it.leadingStock}。")
            }
            marketAnalysisReport?.indexAlignmentText?.takeIf { it.isNotBlank() }?.let {
                add(it)
            }
            if (topStrongStocks != "暂无") {
                add("强势样本：$topStrongStocks。")
            }
            watchlistHealthReport?.focusStocks?.take(2)?.joinToString("、") { "${it.name}(${it.code})" }
                ?.takeIf { it.isNotBlank() }
                ?.let { add("自选重点复盘：$it。") }
        }.take(5)

        val riskItems = buildList {
            if (upRatio < 0.42) {
                add("行情池下跌样本占优，今日研究应优先识别风险而非扩展结论。")
            }
            marketAnalysisReport?.riskItems?.take(2)?.forEach { add(it) }
            watchlistHealthReport?.riskItems?.take(2)?.forEach { add(it) }
            portfolioStressReport?.riskItems?.take(2)?.forEach { add(it) }
            portfolioHoldingReport?.riskItems?.take(2)?.forEach { add(it) }
            if (watchlistStocks.isEmpty()) {
                add("自选池为空，无法生成个性化组合体检和压力测试。")
            }
            if (isEmpty()) {
                add("未发现明显市场宽度、自选池或压力测试风险，仍需等待更多样本确认。")
            }
        }.distinct().take(5)

        val actionItems = buildList {
            marketAnalysisReport?.researchActions?.take(2)?.forEach { add(it) }
            add("先复盘市场宽度和前三板块，再看自选池是否与市场方向一致。")
            watchlistHealthReport?.researchActions?.firstOrNull()?.let { add(it) }
            portfolioStressReport?.researchActions?.firstOrNull()?.let { add(it) }
            portfolioHoldingReport?.researchActions?.firstOrNull()?.let { add(it) }
            if (watchlistStocks.isEmpty()) {
                add("在选股页加入至少 5 只自选样本，让后续简报具备个性化跟踪口径。")
            }
            add("把简报作为研究记录，不把单日样本写成买卖指令或收益承诺。")
        }.distinct().take(5)

        return DailyResearchBriefReport(
            score = score,
            grade = gradeFor(score),
            headline = marketAnalysisReport?.let {
                "$date 研究简报：市场温度 ${it.score}/100（${it.regime}），成交额样本 ${formatAmount(totalAmount)}。"
            } ?: "$date 研究简报：$marketTone，成交额样本 ${formatAmount(totalAmount)}。",
            marketPulse = marketAnalysisReport?.let {
                listOf(
                    it.breadthText,
                    it.turnoverText,
                    it.indexAlignmentText,
                    it.sourceText,
                    it.qualityText,
                    it.watchlistText
                ).filter { text -> text.isNotBlank() }.joinToString("\n")
            } ?: "行情池口径：上涨 $upCount 只、下跌 $downCount 只，上涨占比 ${formatRatio(upRatio)}，状态为$marketTone。",
            sectorPulse = topSector?.let {
                "板块焦点：${it.name} 位列样本前列，平均涨跌 ${formatPercent(it.changePercent)}，代表样本 ${it.leadingStock}。"
            } ?: "板块焦点：暂无可用板块样本。",
            watchlistPulse = watchlistPulse,
            focusItems = focusItems,
            riskItems = riskItems,
            actionItems = actionItems
        )
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "积极"
            score >= 72 -> "偏暖"
            score >= 58 -> "中性"
            else -> "谨慎"
        }
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatAmount(value: Double): String = "${formatNumber(value)}亿"

    private fun formatNumber(value: Double): String = String.format(Locale.CHINA, "%.1f", value)
}
