package io.github.leonarddon.quanttrading.model

import kotlin.math.abs
import kotlin.math.roundToInt

data class MarketAnalysisReport(
    val score: Int,
    val grade: String,
    val regime: String,
    val breadthText: String,
    val turnoverText: String,
    val sectorText: String,
    val watchlistText: String,
    val sourceText: String = "",
    val qualityText: String = "",
    val indexAlignmentText: String = "",
    val riskItems: List<String>,
    val focusSectors: List<SectorInfo>,
    val focusStocks: List<StockInfo>,
    val researchActions: List<String>
)

object MarketAnalysisPolicy {
    fun evaluate(
        stocks: List<StockInfo>,
        hotSectors: List<SectorInfo>,
        watchlistStocks: List<StockInfo>,
        marketIndices: List<MarketOverview> = emptyList(),
        source: String = "",
        warnings: List<String> = emptyList(),
        usingFallback: Boolean = false,
        requestedSampleCount: Int = stocks.size
    ): MarketAnalysisReport? {
        if (stocks.isEmpty()) return null

        val total = stocks.size.coerceAtLeast(1)
        val requestedCount = requestedSampleCount.coerceAtLeast(total)
        val coverageRatio = total * 1.0 / requestedCount
        val uniqueWarnings = warnings.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val upCount = stocks.count { it.changePercent > 0 }
        val downCount = stocks.count { it.changePercent < 0 }
        val upRatio = upCount * 1.0 / total
        val avgChange = stocks.map { it.changePercent }.average()
        val strongRatio = stocks.count { it.changePercent >= 2.0 } * 1.0 / total
        val weakRatio = stocks.count { it.changePercent <= -2.0 } * 1.0 / total

        val totalTurnover = stocks.sumOf { it.turnover }.coerceAtLeast(0.0)
        val upTurnover = stocks.filter { it.changePercent > 0 }.sumOf { it.turnover }.coerceAtLeast(0.0)
        val downTurnover = stocks.filter { it.changePercent < 0 }.sumOf { it.turnover }.coerceAtLeast(0.0)
        val activeTurnover = stocks.filter { abs(it.changePercent) >= 1.0 }.sumOf { it.turnover }.coerceAtLeast(0.0)
        val directionalTurnover = (upTurnover + downTurnover).coerceAtLeast(0.0001)
        val upTurnoverRatio = upTurnover / directionalTurnover

        val sectors = hotSectors.ifEmpty { deriveSectors(stocks) }
        val positiveSectorRatio = sectors.count { it.changePercent > 0 } * 1.0 / sectors.size.coerceAtLeast(1)
        val leadingSector = sectors.maxByOrNull { it.changePercent }
        val laggingSector = sectors.minByOrNull { it.changePercent }
        val topTurnoverSector = sectors.maxByOrNull { it.capitalFlow }
        val topSectorTurnoverShare = if (totalTurnover > 0) {
            (topTurnoverSector?.capitalFlow ?: 0.0) / totalTurnover
        } else {
            0.0
        }
        val sectorDispersion = if (leadingSector != null && laggingSector != null && sectors.size > 1) {
            leadingSector.changePercent - laggingSector.changePercent
        } else {
            0.0
        }

        val watchlistAvgChange = watchlistStocks.takeIf { it.isNotEmpty() }?.map { it.changePercent }?.average()
        val watchlistUpRatio = watchlistStocks.takeIf { it.isNotEmpty() }
            ?.let { list -> list.count { it.changePercent > 0 } * 1.0 / list.size }
        val indexAvgChange = marketIndices.takeIf { it.isNotEmpty() }
            ?.map { it.changePercent }
            ?.average()

        val score = (
            62 +
                breadthScore(upRatio) +
                (avgChange * 4).roundToInt().coerceIn(-14, 14) +
                sectorScore(positiveSectorRatio) +
                turnoverScore(upTurnoverRatio, upTurnover + downTurnover) +
                watchlistScore(watchlistAvgChange, avgChange) -
                coveragePenalty(stocks.size) -
                concentrationPenalty(topSectorTurnoverShare) -
                dispersionPenalty(sectorDispersion) -
                dataQualityPenalty(coverageRatio, usingFallback, uniqueWarnings.size) -
                indexAlignmentPenalty(avgChange, indexAvgChange)
            ).coerceIn(0, 100)

        val focusSectors = sectors
            .sortedWith(
                compareByDescending<SectorInfo> { sectorFocusScore(it, totalTurnover) }
                    .thenByDescending { abs(it.changePercent) }
            )
            .take(4)
        val focusStocks = stocks
            .sortedWith(
                compareByDescending<StockInfo> { stockFocusScore(it, totalTurnover) }
                    .thenByDescending { abs(it.changePercent) }
                    .thenByDescending { it.turnover }
            )
            .take(6)

        val sourceText = buildSourceText(source, usingFallback, uniqueWarnings)
        val qualityText = buildQualityText(total, requestedCount, coverageRatio, usingFallback)
        val indexAlignmentText = buildIndexAlignmentText(marketIndices, avgChange, indexAvgChange)

        val risks = buildList {
            if (usingFallback) {
                add("当前行情使用缓存或离线降级口径，市场温度只能作为样本观察，不能外推为实时全市场结论。")
            }
            if (coverageRatio < 0.7) {
                add("行情覆盖 ${formatRatio(coverageRatio)}（$total/$requestedCount），样本覆盖偏窄，需补充更多行业样本后再强化结论。")
            }
            if (uniqueWarnings.isNotEmpty()) {
                add("数据源提示：${formatWarnings(uniqueWarnings)}。")
            }
            if (indexAvgChange != null && avgChange >= indexAvgChange + 1.8) {
                add("行情池平均涨跌 ${formatPercent(avgChange)} 明显强于指数平均 ${formatPercent(indexAvgChange)}，可能是局部热点而非全市场扩散。")
            }
            if (indexAvgChange != null && avgChange <= indexAvgChange - 1.8) {
                add("行情池平均涨跌 ${formatPercent(avgChange)} 明显弱于指数平均 ${formatPercent(indexAvgChange)}，需检查样本行业是否过度偏向承压方向。")
            }
            if (stocks.size < 15) {
                add("行情池样本仅 ${stocks.size} 只，当前市场判断更适合做观察清单，不适合代表全市场。")
            }
            if (upRatio < 0.4) {
                add("上涨样本占比 ${formatRatio(upRatio)}，市场宽度偏弱，应先识别承压来源。")
            }
            if (downTurnover > upTurnover * 1.25 && downTurnover > 0.0) {
                add("下跌样本成交额明显高于上涨样本，资金方向观察偏防守。")
            }
            if (positiveSectorRatio < 0.4 && sectors.isNotEmpty()) {
                add("板块正扩散率 ${formatRatio(positiveSectorRatio)}，热点延展不足。")
            }
            if (sectorDispersion >= 6.0) {
                add("板块强弱差 ${formatPercent(sectorDispersion)}，结构分化较大，需避免把单一热点当成整体市场。")
            }
            if (topSectorTurnoverShare >= 0.55) {
                add("${topTurnoverSector?.name ?: "单一板块"}占样本成交额 ${formatRatio(topSectorTurnoverShare)}，成交额集中度偏高。")
            }
            if (watchlistAvgChange != null && watchlistAvgChange < avgChange - 1.0) {
                add("自选池平均涨跌 ${formatPercent(watchlistAvgChange)}，弱于行情池平均 ${formatPercent(avgChange)}。")
            }
            if (isEmpty()) {
                add("未发现明显宽度、成交额、板块扩散或自选池背离风险，继续观察是否能连续保持。")
            }
        }.distinct().take(6)

        val actions = buildList {
            if (usingFallback || coverageRatio < 0.7 || uniqueWarnings.isNotEmpty()) {
                add("先核对行情源、样本覆盖和指数 quote 是否恢复，再把温度变化写入研究结论。")
            }
            if (indexAvgChange != null && abs(avgChange - indexAvgChange) >= 1.8) {
                add("单独记录指数与行情池的背离，区分指数权重影响、样本行业偏差和真实扩散。")
            }
            add("先确认市场宽度、成交额方向和板块扩散是否同向，再记录今日主线。")
            leadingSector?.let {
                add("复盘${it.name}是否只是样本领涨，还是带动相邻板块一起改善。")
            }
            laggingSector?.takeIf { sectorDispersion >= 4.0 }?.let {
                add("把${it.name}列入承压对照，观察弱势板块是否拖累自选池。")
            }
            if (watchlistStocks.isEmpty()) {
                add("在选股页加入自选池样本，让市场分析能比较个人观察池与行情池方向。")
            } else if (watchlistAvgChange != null && watchlistAvgChange < avgChange - 1.0) {
                add("优先复盘自选池落后样本，区分个股问题、行业问题和市场宽度问题。")
            }
            if (downTurnover > upTurnover * 1.25 && downTurnover > 0.0) {
                add("成交额偏向下跌样本时，先减少结论外推，等待下一次快照确认。")
            }
            add("所有结论只写成研究假设和观察动作，不写成买卖指令。")
        }.distinct().take(6)

        return MarketAnalysisReport(
            score = score,
            grade = gradeFor(score),
            regime = regimeFor(score, upRatio),
            breadthText = "市场宽度：上涨 $upCount 只、下跌 $downCount 只，上涨占比 ${formatRatio(upRatio)}，平均涨跌 ${formatPercent(avgChange)}；强势样本 ${formatRatio(strongRatio)}，承压样本 ${formatRatio(weakRatio)}。",
            turnoverText = if (totalTurnover > 0) {
                "成交额方向：上涨样本 ${formatAmount(upTurnover)}、下跌样本 ${formatAmount(downTurnover)}，活跃样本成交额占比 ${formatRatio(activeTurnover / totalTurnover)}。"
            } else {
                "成交额方向：当前样本缺少有效成交额字段，成交额判断置信度有限。"
            },
            sectorText = leadingSector?.let {
                val laggingText = laggingSector?.let { sector -> "，承压板块 ${sector.name} ${formatPercent(sector.changePercent)}" }.orEmpty()
                "板块结构：正扩散率 ${formatRatio(positiveSectorRatio)}，领涨板块 ${it.name} ${formatPercent(it.changePercent)}$laggingText，强弱差 ${formatPercent(sectorDispersion)}。"
            } ?: "板块结构：暂无有效行业样本。",
            watchlistText = buildWatchlistText(watchlistStocks, watchlistAvgChange, watchlistUpRatio, avgChange),
            sourceText = sourceText,
            qualityText = qualityText,
            indexAlignmentText = indexAlignmentText,
            riskItems = risks,
            focusSectors = focusSectors,
            focusStocks = focusStocks,
            researchActions = actions
        )
    }

    private fun deriveSectors(stocks: List<StockInfo>): List<SectorInfo> {
        return stocks.groupBy { it.industry.ifBlank { "未分类" } }
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

    private fun breadthScore(upRatio: Double): Int {
        return when {
            upRatio >= 0.7 -> 18
            upRatio >= 0.55 -> 10
            upRatio >= 0.45 -> 3
            upRatio >= 0.35 -> -6
            else -> -14
        }
    }

    private fun sectorScore(positiveSectorRatio: Double): Int {
        return when {
            positiveSectorRatio >= 0.7 -> 10
            positiveSectorRatio >= 0.5 -> 5
            positiveSectorRatio >= 0.35 -> -2
            else -> -8
        }
    }

    private fun turnoverScore(upTurnoverRatio: Double, directionalTurnover: Double): Int {
        if (directionalTurnover <= 0.0) return 0
        return when {
            upTurnoverRatio >= 0.65 -> 10
            upTurnoverRatio >= 0.5 -> 4
            upTurnoverRatio >= 0.35 -> -4
            else -> -10
        }
    }

    private fun watchlistScore(watchlistAvgChange: Double?, marketAvgChange: Double): Int {
        if (watchlistAvgChange == null) return 0
        return when {
            watchlistAvgChange >= marketAvgChange + 1.0 -> 5
            watchlistAvgChange >= marketAvgChange - 0.5 -> 1
            else -> -5
        }
    }

    private fun coveragePenalty(sampleSize: Int): Int = if (sampleSize < 15) 8 else 0

    private fun dataQualityPenalty(coverageRatio: Double, usingFallback: Boolean, warningCount: Int): Int {
        val fallbackPenalty = if (usingFallback) 10 else 0
        val coveragePenalty = when {
            coverageRatio < 0.4 -> 8
            coverageRatio < 0.7 -> 5
            coverageRatio < 0.9 -> 2
            else -> 0
        }
        val warningPenalty = warningCount.coerceAtMost(2) * 2
        return fallbackPenalty + coveragePenalty + warningPenalty
    }

    private fun indexAlignmentPenalty(sampleAvgChange: Double, indexAvgChange: Double?): Int {
        if (indexAvgChange == null) return 0
        return if (abs(sampleAvgChange - indexAvgChange) >= 2.5) 3 else 0
    }

    private fun concentrationPenalty(topSectorTurnoverShare: Double): Int {
        return when {
            topSectorTurnoverShare >= 0.7 -> 10
            topSectorTurnoverShare >= 0.55 -> 6
            else -> 0
        }
    }

    private fun dispersionPenalty(sectorDispersion: Double): Int = if (sectorDispersion >= 6.0) 4 else 0

    private fun sectorFocusScore(sector: SectorInfo, totalTurnover: Double): Double {
        val turnoverShare = if (totalTurnover > 0) sector.capitalFlow / totalTurnover else 0.0
        return abs(sector.changePercent) * 2.0 + turnoverShare * 10.0
    }

    private fun stockFocusScore(stock: StockInfo, totalTurnover: Double): Double {
        val turnoverShare = if (totalTurnover > 0) stock.turnover / totalTurnover else 0.0
        val pressureBonus = if (stock.changePercent < 0) 1.0 else 0.0
        return abs(stock.changePercent) * 2.0 + turnoverShare * 10.0 + pressureBonus
    }

    private fun buildWatchlistText(
        watchlistStocks: List<StockInfo>,
        watchlistAvgChange: Double?,
        watchlistUpRatio: Double?,
        marketAvgChange: Double
    ): String {
        if (watchlistStocks.isEmpty()) {
            return "自选一致性：暂无自选池样本，无法比较个人观察池与行情池方向。"
        }
        val avgChange = watchlistAvgChange ?: 0.0
        val relation = when {
            avgChange >= marketAvgChange + 1.0 -> "强于"
            avgChange >= marketAvgChange - 0.5 -> "接近"
            else -> "弱于"
        }
        return "自选一致性：${watchlistStocks.size} 只自选样本，上涨占比 ${formatRatio(watchlistUpRatio ?: 0.0)}，平均涨跌 ${formatPercent(avgChange)}，$relation 行情池平均 ${formatPercent(marketAvgChange)}。"
    }

    private fun buildSourceText(
        source: String,
        usingFallback: Boolean,
        warnings: List<String>
    ): String {
        val sourceName = source.ifBlank { if (usingFallback) "本地缓存/离线样本" else "多源 quote" }
        val mode = if (usingFallback) "降级口径" else "实时 quote 口径"
        val warningText = warnings.takeIf { it.isNotEmpty() }
            ?.let { "；提示：${formatWarnings(it)}" }
            .orEmpty()
        return "行情来源：$sourceName（$mode）$warningText。"
    }

    private fun buildQualityText(
        sampleSize: Int,
        requestedCount: Int,
        coverageRatio: Double,
        usingFallback: Boolean
    ): String {
        val quality = when {
            usingFallback -> "降级观察"
            coverageRatio >= 0.9 -> "覆盖较完整"
            coverageRatio >= 0.7 -> "覆盖可用"
            coverageRatio >= 0.4 -> "覆盖偏窄"
            else -> "覆盖不足"
        }
        return "数据质量：行情覆盖 ${formatRatio(coverageRatio)}（$sampleSize/$requestedCount），$quality；当前结论仍以本机样本研究记录为边界。"
    }

    private fun buildIndexAlignmentText(
        marketIndices: List<MarketOverview>,
        sampleAvgChange: Double,
        indexAvgChange: Double?
    ): String {
        if (marketIndices.isEmpty() || indexAvgChange == null) {
            return "指数联动：指数 quote 暂不可用，本次只能比较行情池内部结构。"
        }
        val indexSummary = marketIndices
            .sortedByDescending { abs(it.changePercent) }
            .take(3)
            .joinToString("、") { "${it.indexName} ${formatPercent(it.changePercent)}" }
        val relation = when {
            sampleAvgChange >= indexAvgChange + 1.8 -> "行情池明显强于指数，需确认是否只是局部热点。"
            sampleAvgChange <= indexAvgChange - 1.8 -> "行情池明显弱于指数，需检查样本行业偏差。"
            sampleAvgChange >= indexAvgChange + 0.6 -> "行情池略强于指数，结构相对占优。"
            sampleAvgChange <= indexAvgChange - 0.6 -> "行情池略弱于指数，结构相对承压。"
            else -> "行情池与指数方向基本一致。"
        }
        return "指数联动：$indexSummary；指数平均涨跌 ${formatPercent(indexAvgChange)}，行情池平均 ${formatPercent(sampleAvgChange)}，$relation"
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 82 -> "积极"
            score >= 70 -> "偏暖"
            score >= 56 -> "中性"
            else -> "谨慎"
        }
    }

    private fun regimeFor(score: Int, upRatio: Double): String {
        return when {
            score >= 82 && upRatio >= 0.62 -> "扩散偏强"
            score >= 70 -> "结构偏暖"
            score >= 56 -> "震荡分化"
            else -> "防守观察"
        }
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatAmount(value: Double): String = "${formatNumber(value)}亿"

    private fun formatWarnings(warnings: List<String>): String {
        return warnings
            .take(2)
            .joinToString("；") { it.trim().trimEnd('。') }
            .take(140)
    }

    private fun formatNumber(value: Double): String = String.format(java.util.Locale.CHINA, "%.1f", value)
}
