package io.github.leonarddon.quanttrading.model

data class WatchlistHealthReport(
    val score: Int,
    val grade: String,
    val breadthText: String,
    val concentrationText: String,
    val valuationText: String,
    val trendText: String,
    val riskItems: List<String>,
    val focusStocks: List<StockInfo>,
    val researchActions: List<String>
)

object WatchlistHealthPolicy {
    fun evaluate(stocks: List<StockInfo>): WatchlistHealthReport? {
        if (stocks.isEmpty()) return null

        val upRatio = stocks.count { it.changePercent > 0 } * 1.0 / stocks.size
        val avgChange = stocks.map { it.changePercent }.average()
        val topIndustry = stocks.groupBy { it.industry.ifBlank { "未分类" } }
            .maxByOrNull { it.value.size }
        val topIndustryRatio = (topIndustry?.value?.size ?: 0) * 1.0 / stocks.size
        val trendCoverage = stocks.count { it.hasMovingAverageData() } * 1.0 / stocks.size
        val strongTrendRatio = stocks.count {
            it.hasMovingAverageData() && it.price >= it.ma5 && it.ma5 >= it.ma10 && it.ma10 >= it.ma20
        } * 1.0 / stocks.size
        val pressureCount = stocks.count { it.changePercent <= -3.0 || (it.hasMovingAverageData() && it.price < it.ma20) }
        val highValuationCount = stocks.count { it.pe > 45.0 || it.pb > 8.0 }
        val score = (72 +
            ((upRatio - 0.5) * 24).toInt() +
            (strongTrendRatio * 14).toInt() -
            concentrationPenalty(topIndustryRatio) -
            pressureCount * 4 -
            highValuationCount * 3
            ).coerceIn(0, 100)

        val riskItems = buildList {
            if (topIndustryRatio >= 0.6) {
                add("${topIndustry?.key ?: "单一行业"}占自选池 ${formatRatio(topIndustryRatio)}，需要留意行业集中暴露。")
            }
            if (pressureCount > 0) {
                add("$pressureCount 只样本处于短期压力区，可优先复盘价格相对 MA20 与成交额变化。")
            }
            if (highValuationCount > 0) {
                add("$highValuationCount 只样本估值字段偏高，建议补充财报质量和景气度验证。")
            }
            if (trendCoverage < 0.5) {
                add("均线覆盖不足 ${formatRatio(trendCoverage)}，当前体检对趋势判断置信度有限。")
            }
            if (isEmpty()) {
                add("未发现明显集中度、估值或短期趋势异常，后续重点观察样本强弱是否持续。")
            }
        }

        val focusStocks = stocks
            .sortedWith(
                compareByDescending<StockInfo> { focusPriority(it) }
                    .thenByDescending { kotlin.math.abs(it.changePercent) }
                    .thenByDescending { it.turnover }
            )
            .take(5)

        val actions = buildList {
            add("把体检结果与复盘页历史快照一起记录，观察评分和风险项是否连续改善或恶化。")
            if (topIndustryRatio >= 0.6) {
                add("补充至少 2 个非${topIndustry?.key ?: "集中行业"}样本做对照观察，降低单一行业叙事误差。")
            }
            if (pressureCount > 0) {
                add("对压力样本建立单独观察笔记，记录 MA20、成交额和所属板块强弱变化。")
            }
            if (stocks.size < 5) {
                add("自选池样本少于 5 只，建议先扩展观察池再判断组合结构。")
            }
        }.distinct().take(4)

        return WatchlistHealthReport(
            score = score,
            grade = gradeFor(score),
            breadthText = "上涨占比 ${formatRatio(upRatio)}，平均涨跌 ${formatPercent(avgChange)}。",
            concentrationText = topIndustry?.let {
                "${it.key} ${it.value.size}/${stocks.size} 只，占比 ${formatRatio(topIndustryRatio)}。"
            } ?: "暂无行业集中度数据。",
            valuationText = buildValuationText(stocks, highValuationCount),
            trendText = "均线覆盖 ${formatRatio(trendCoverage)}，强趋势样本 ${formatRatio(strongTrendRatio)}。",
            riskItems = riskItems,
            focusStocks = focusStocks,
            researchActions = actions
        )
    }

    private fun focusPriority(stock: StockInfo): Int {
        var priority = 0
        if (stock.changePercent <= -3.0) priority += 3
        if (stock.hasMovingAverageData() && stock.price < stock.ma20) priority += 3
        if (stock.pe > 45.0 || stock.pb > 8.0) priority += 2
        if (stock.turnover > 50.0) priority += 1
        return priority
    }

    private fun concentrationPenalty(ratio: Double): Int {
        return when {
            ratio >= 0.8 -> 18
            ratio >= 0.6 -> 12
            ratio >= 0.45 -> 6
            else -> 0
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "稳健"
            score >= 70 -> "均衡"
            score >= 55 -> "观察"
            else -> "承压"
        }
    }

    private fun buildValuationText(stocks: List<StockInfo>, highValuationCount: Int): String {
        val peValues = stocks.mapNotNull { it.pe.takeIf { value -> value > 0 } }
        val pbValues = stocks.mapNotNull { it.pb.takeIf { value -> value > 0 } }
        if (peValues.isEmpty() && pbValues.isEmpty()) {
            return "估值字段覆盖不足，需结合财报或合规数据源补充验证。"
        }
        val peText = peValues.takeIf { it.isNotEmpty() }?.let { "PE中位 ${formatNumber(it.sorted()[it.size / 2])}" }
        val pbText = pbValues.takeIf { it.isNotEmpty() }?.let { "PB中位 ${formatNumber(it.sorted()[it.size / 2])}" }
        return listOfNotNull(peText, pbText, "高估值样本 $highValuationCount 只").joinToString("，") + "。"
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatNumber(value: Double): String = String.format(java.util.Locale.CHINA, "%.1f", value)
}
