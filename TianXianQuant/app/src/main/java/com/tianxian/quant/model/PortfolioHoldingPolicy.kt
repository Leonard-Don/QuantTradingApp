package com.tianxian.quant.model

import java.util.Locale
import kotlin.math.abs

data class PortfolioPositionReport(
    val holding: PortfolioHolding,
    val quote: StockInfo?,
    val costValue: Double,
    val marketValue: Double,
    val profitLoss: Double,
    val profitLossPercent: Double,
    val weightPercent: Double,
    val riskTags: List<String>
)

data class PortfolioHoldingReport(
    val score: Int,
    val grade: String,
    val totalCost: Double,
    val marketValue: Double,
    val profitLoss: Double,
    val profitLossPercent: Double,
    val exposureText: String,
    val concentrationText: String,
    val quoteCoverageText: String,
    val positions: List<PortfolioPositionReport>,
    val riskItems: List<String>,
    val researchActions: List<String>
)

object PortfolioHoldingPolicy {
    fun evaluate(
        holdings: List<PortfolioHolding>,
        quotes: List<StockInfo>
    ): PortfolioHoldingReport? {
        val validHoldings = holdings
            .filter { it.code.isNotBlank() && it.costPrice > 0.0 && it.quantity > 0.0 }
            .distinctBy { it.code }
        if (validHoldings.isEmpty()) return null

        val quotesByCode = quotes.associateBy { it.code }
        val rawPositions = validHoldings.map { holding ->
            val quote = quotesByCode[holding.code]
            val price = quote?.price?.takeIf { it > 0.0 } ?: holding.costPrice
            val costValue = holding.costPrice * holding.quantity
            val marketValue = price * holding.quantity
            val profitLoss = marketValue - costValue
            val profitLossPercent = profitLoss / costValue * 100.0
            RawPosition(
                holding = holding,
                quote = quote,
                costValue = costValue,
                marketValue = marketValue,
                profitLoss = profitLoss,
                profitLossPercent = profitLossPercent
            )
        }
        val totalCost = rawPositions.sumOf { it.costValue }.coerceAtLeast(0.0001)
        val marketValue = rawPositions.sumOf { it.marketValue }.coerceAtLeast(0.0001)
        val profitLoss = marketValue - totalCost
        val profitLossPercent = profitLoss / totalCost * 100.0
        val positions = rawPositions.map { raw ->
            val weight = raw.marketValue / marketValue * 100.0
            PortfolioPositionReport(
                holding = raw.holding,
                quote = raw.quote,
                costValue = raw.costValue,
                marketValue = raw.marketValue,
                profitLoss = raw.profitLoss,
                profitLossPercent = raw.profitLossPercent,
                weightPercent = weight,
                riskTags = riskTags(raw, weight)
            )
        }.sortedByDescending { it.marketValue }

        val quoteCoverage = validHoldings.count { it.code in quotesByCode } * 1.0 / validHoldings.size
        val topWeight = positions.firstOrNull()?.weightPercent ?: 0.0
        val lossCount = positions.count { it.profitLossPercent <= -8.0 }
        val highWeightCount = positions.count { it.weightPercent >= 35.0 }
        val highValuationCount = positions.count {
            val quote = it.quote
            quote != null && (quote.pe > 45.0 || quote.pb > 8.0)
        }
        val score = (76 +
            ((profitLossPercent / 2.0).coerceIn(-12.0, 12.0)).toInt() -
            concentrationPenalty(topWeight) -
            lossCount * 6 -
            highWeightCount * 5 -
            highValuationCount * 3 -
            (if (quoteCoverage < 0.7) 8 else 0)
            ).coerceIn(0, 100)

        val riskItems = buildList {
            if (quoteCoverage < 1.0) {
                add("行情覆盖 ${formatRatio(quoteCoverage)}，未覆盖样本按成本价估算，盈亏和风险评分置信度有限。")
            }
            if (topWeight >= 45.0) {
                add("单一持仓权重 ${formatNumber(topWeight)}%，组合集中度偏高。")
            } else if (topWeight >= 35.0) {
                add("单一持仓权重超过 35%，需要复盘仓位集中风险。")
            }
            if (lossCount > 0) {
                add("$lossCount 个持仓样本浮亏超过 8%，建议优先复盘成本区间、趋势和持仓理由。")
            }
            if (highValuationCount > 0) {
                add("$highValuationCount 个持仓样本估值字段偏高，需补充财报质量和景气度验证。")
            }
            if (positions.size < 3) {
                add("持仓样本少于 3 个，组合报告更适合做单票记录，不适合判断组合结构。")
            }
            if (isEmpty()) {
                add("未发现明显行情覆盖、集中度、亏损或估值异常，继续跟踪权重和风险标签变化。")
            }
        }.distinct().take(5)

        val researchActions = buildList {
            add("每日收盘后更新持仓样本，把浮盈亏、权重和风险标签与研究简报一起复盘。")
            if (topWeight >= 35.0) {
                add("为高权重样本单独记录仓位假设和风险边界，避免单票波动支配组合判断。")
            }
            if (lossCount > 0) {
                add("对浮亏样本检查 MA20、板块强弱和成交额变化，不把成本价当成决策依据。")
            }
            if (quoteCoverage < 1.0) {
                add("优先修正无行情样本的股票代码或名称，确保组合报告基于真实 quote。")
            }
            add("组合记录仅用于本机研究，不接券商、不下单、不提供交易指令。")
        }.distinct().take(5)

        return PortfolioHoldingReport(
            score = score,
            grade = gradeFor(score),
            totalCost = totalCost,
            marketValue = marketValue,
            profitLoss = profitLoss,
            profitLossPercent = profitLossPercent,
            exposureText = "成本 ${formatAmount(totalCost)}，市值 ${formatAmount(marketValue)}，浮盈亏 ${formatSignedAmount(profitLoss)}（${formatPercent(profitLossPercent)}）。",
            concentrationText = "持仓 ${positions.size} 个，最高权重 ${formatNumber(topWeight)}%，高权重样本 $highWeightCount 个。",
            quoteCoverageText = "行情覆盖 ${formatRatio(quoteCoverage)}，覆盖 ${validHoldings.count { it.code in quotesByCode }}/${validHoldings.size} 个。",
            positions = positions,
            riskItems = riskItems,
            researchActions = researchActions
        )
    }

    private fun riskTags(raw: RawPosition, weightPercent: Double): List<String> {
        val tags = mutableListOf<String>()
        if (raw.quote == null) tags += "行情缺失"
        if (weightPercent >= 35.0) tags += "高权重"
        if (raw.profitLossPercent <= -8.0) tags += "浮亏压力"
        if (raw.profitLossPercent >= 12.0) tags += "浮盈回撤观察"
        val quote = raw.quote
        if (quote != null && quote.hasMovingAverageData() && quote.price < quote.ma20) {
            tags += "MA20压力"
        }
        if (quote != null && (quote.pe > 45.0 || quote.pb > 8.0)) {
            tags += "估值偏高"
        }
        return tags.ifEmpty { listOf("跟踪") }
    }

    private fun concentrationPenalty(topWeight: Double): Int {
        return when {
            topWeight >= 60.0 -> 18
            topWeight >= 45.0 -> 12
            topWeight >= 35.0 -> 6
            else -> 0
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "稳健"
            score >= 72 -> "可控"
            score >= 58 -> "观察"
            else -> "承压"
        }
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatAmount(value: Double): String = "${formatNumber(value)}元"

    private fun formatSignedAmount(value: Double): String = "${if (value >= 0) "+" else ""}${formatAmount(value)}"

    private fun formatNumber(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private data class RawPosition(
        val holding: PortfolioHolding,
        val quote: StockInfo?,
        val costValue: Double,
        val marketValue: Double,
        val profitLoss: Double,
        val profitLossPercent: Double
    )
}
