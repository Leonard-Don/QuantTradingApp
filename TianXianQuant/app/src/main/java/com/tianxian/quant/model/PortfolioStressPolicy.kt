package com.tianxian.quant.model

import java.util.Locale
import kotlin.math.abs

data class PortfolioStressScenario(
    val name: String,
    val marketShockPercent: Double,
    val estimatedDrawdownPercent: Double,
    val impactedStocks: List<StockInfo>,
    val explanation: String
)

data class PortfolioStressReport(
    val score: Int,
    val grade: String,
    val exposureText: String,
    val marketBreadthText: String,
    val concentrationText: String,
    val liquidityText: String,
    val scenarios: List<PortfolioStressScenario>,
    val riskItems: List<String>,
    val researchActions: List<String>
)

object PortfolioStressPolicy {
    fun evaluate(
        stocks: List<StockInfo>,
        marketUpCount: Int,
        marketDownCount: Int
    ): PortfolioStressReport? {
        val watchlist = stocks.distinctBy { it.code }
        if (watchlist.isEmpty()) return null

        val marketBreadthTotal = (marketUpCount + marketDownCount).coerceAtLeast(1)
        val marketDownRatio = marketDownCount * 1.0 / marketBreadthTotal
        val avgChange = watchlist.map { it.changePercent }.average()
        val topIndustry = watchlist.groupBy { it.industry.ifBlank { "未分类" } }
            .maxByOrNull { it.value.size }
        val topIndustryRatio = (topIndustry?.value?.size ?: 0) * 1.0 / watchlist.size
        val trendPressureCount = watchlist.count {
            it.changePercent <= -3.0 || (it.hasMovingAverageData() && it.price < it.ma20)
        }
        val highValuationCount = watchlist.count { it.pe > 45.0 || it.pb > 8.0 }
        val lowLiquidityCount = watchlist.count { it.turnover in 0.0001..10.0 }
        val avgSensitivity = watchlist.map { stressSensitivity(it) }.average().coerceIn(0.75, 1.85)
        val concentrationAmplifier = when {
            topIndustryRatio >= 0.75 -> 1.18
            topIndustryRatio >= 0.60 -> 1.12
            topIndustryRatio >= 0.45 -> 1.06
            else -> 1.0
        }
        val marketAmplifier = (1.0 + (marketDownRatio - 0.5).coerceAtLeast(0.0) * 0.35)
            .coerceIn(1.0, 1.22)

        val score = (84 +
            ((avgChange / 2.0).coerceIn(-6.0, 6.0)).toInt() -
            concentrationPenalty(topIndustryRatio) -
            (trendPressureCount * 5) -
            (highValuationCount * 3) -
            (lowLiquidityCount * 3) -
            (if (marketDownRatio >= 0.65) 8 else 0)
            ).coerceIn(0, 100)

        val impactedStocks = watchlist
            .sortedWith(
                compareByDescending<StockInfo> { stressSensitivity(it) }
                    .thenByDescending { abs(it.changePercent) }
                    .thenByDescending { it.turnover }
            )
            .take(3)

        val scenarios = listOf(
            buildScenario(
                name = "温和回撤",
                shock = -3.0,
                avgSensitivity = avgSensitivity,
                concentrationAmplifier = concentrationAmplifier,
                marketAmplifier = marketAmplifier,
                impactedStocks = impactedStocks
            ),
            buildScenario(
                name = "系统压力",
                shock = -6.0,
                avgSensitivity = avgSensitivity,
                concentrationAmplifier = concentrationAmplifier,
                marketAmplifier = marketAmplifier,
                impactedStocks = impactedStocks
            ),
            buildScenario(
                name = "极端压力",
                shock = -9.0,
                avgSensitivity = avgSensitivity,
                concentrationAmplifier = concentrationAmplifier,
                marketAmplifier = marketAmplifier,
                impactedStocks = impactedStocks
            )
        )

        val riskItems = buildList {
            if (marketDownRatio >= 0.65) {
                add("当前行情池下跌样本占比 ${formatRatio(marketDownRatio)}，压力测试采用偏谨慎放大系数。")
            }
            if (topIndustryRatio >= 0.60) {
                add("${topIndustry?.key ?: "单一行业"}占自选池 ${formatRatio(topIndustryRatio)}，组合冲击可能被行业同涨同跌放大。")
            }
            if (trendPressureCount > 0) {
                add("$trendPressureCount 只样本处于跌幅或 MA20 压力区，情景回撤中会优先承压。")
            }
            if (highValuationCount > 0) {
                add("$highValuationCount 只样本估值字段偏高，极端压力下估值收缩敏感度更高。")
            }
            if (lowLiquidityCount > 0) {
                add("$lowLiquidityCount 只样本成交额低于 10 亿，压力情景下流动性观察置信度较低。")
            }
            if (isEmpty()) {
                add("未发现明显集中度、趋势、估值或流动性压力，后续重点观察市场宽度变化。")
            }
        }

        val actions = buildList {
            add("把压力测试与自选池体检、历史回溯一起复盘，观察评分是否连续恶化。")
            if (topIndustryRatio >= 0.60) {
                add("补充非${topIndustry?.key ?: "集中行业"}样本做对照，降低单一行业假设误差。")
            }
            if (trendPressureCount > 0) {
                add("优先复盘承压样本的 MA20、成交额和板块强弱变化，不把单日波动当成结论。")
            }
            if (watchlist.size < 5) {
                add("自选池少于 5 只时，压力测试只适合作为样本提醒，建议先扩展观察池。")
            }
            add("压力情景为等权估算，不替代真实持仓、仓位和成本价测算。")
        }.distinct().take(5)

        return PortfolioStressReport(
            score = score,
            grade = gradeFor(score),
            exposureText = "等权样本 ${watchlist.size} 只，平均涨跌 ${formatPercent(avgChange)}，平均压力敏感度 ${formatNumber(avgSensitivity)}。",
            marketBreadthText = "行情池宽度：上涨 $marketUpCount 只，下跌 $marketDownCount 只，下跌占比 ${formatRatio(marketDownRatio)}。",
            concentrationText = topIndustry?.let {
                "${it.key} ${it.value.size}/${watchlist.size} 只，占比 ${formatRatio(topIndustryRatio)}。"
            } ?: "暂无行业集中度数据。",
            liquidityText = "成交额低于 10 亿样本 $lowLiquidityCount 只，趋势压力样本 $trendPressureCount 只。",
            scenarios = scenarios,
            riskItems = riskItems,
            researchActions = actions
        )
    }

    private fun buildScenario(
        name: String,
        shock: Double,
        avgSensitivity: Double,
        concentrationAmplifier: Double,
        marketAmplifier: Double,
        impactedStocks: List<StockInfo>
    ): PortfolioStressScenario {
        val estimatedDrawdown = abs(shock) * avgSensitivity * concentrationAmplifier * marketAmplifier
        val explanation = "按等权自选池、样本波动敏感度、行业集中度和当前市场宽度估算；不使用真实仓位。"
        return PortfolioStressScenario(
            name = name,
            marketShockPercent = shock,
            estimatedDrawdownPercent = estimatedDrawdown.coerceIn(0.0, 35.0),
            impactedStocks = impactedStocks,
            explanation = explanation
        )
    }

    private fun stressSensitivity(stock: StockInfo): Double {
        var sensitivity = 1.0
        if (stock.changePercent <= -3.0) sensitivity += 0.18
        if (abs(stock.changePercent) >= 6.0) sensitivity += 0.16
        if (stock.hasMovingAverageData() && stock.price < stock.ma20) sensitivity += 0.18
        if (stock.pe > 45.0 || stock.pb > 8.0) sensitivity += 0.14
        if (stock.turnover in 0.0001..10.0) sensitivity += 0.12
        if (stock.turnover >= 80.0) sensitivity += 0.06
        return sensitivity.coerceIn(0.75, 1.85)
    }

    private fun concentrationPenalty(ratio: Double): Int {
        return when {
            ratio >= 0.75 -> 16
            ratio >= 0.60 -> 11
            ratio >= 0.45 -> 6
            else -> 0
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "抗压"
            score >= 70 -> "可控"
            score >= 55 -> "警戒"
            else -> "高压"
        }
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatNumber(value: Double): String = String.format(Locale.CHINA, "%.1f", value)
}
