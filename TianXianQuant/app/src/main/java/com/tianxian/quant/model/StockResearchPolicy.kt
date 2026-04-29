package com.tianxian.quant.model

import kotlin.math.abs

data class StockResearchReport(
    val score: Int,
    val grade: String,
    val trendText: String,
    val valuationText: String,
    val liquidityText: String,
    val riskItems: List<String>,
    val researchActions: List<String>
)

object StockResearchPolicy {
    fun evaluate(stock: StockInfo): StockResearchReport {
        val trendScore = trendScore(stock)
        val valuationScore = valuationScore(stock)
        val liquidityScore = liquidityScore(stock)
        val volatilityPenalty = volatilityPenalty(stock)
        val score = (64 + trendScore + valuationScore + liquidityScore - volatilityPenalty)
            .coerceIn(0, 100)

        val risks = buildList {
            if (!stock.hasMovingAverageData()) {
                add("均线字段不足，趋势诊断置信度有限。")
            } else if (stock.price < stock.ma20) {
                add("现价低于 MA20，短期趋势处于压力观察区。")
            }
            if (stock.changePercent <= -5.0) {
                add("当日跌幅超过 5%，需复核是否存在事件驱动或流动性冲击。")
            }
            if (stock.pe > 45.0 || stock.pb > 8.0) {
                add("估值字段偏高，需要结合盈利质量和行业景气度验证。")
            }
            if (stock.turnover in 0.0..3.0) {
                add("样本成交额偏低，价格变化可能受流动性影响较大。")
            }
            if (dayRangePercent(stock) >= 7.0) {
                add("日内振幅偏大，适合先做波动归因记录。")
            }
            if (isEmpty()) {
                add("未发现明显趋势、估值或流动性异常，适合继续做样本跟踪。")
            }
        }

        val actions = buildList {
            add("加入自选池后连续观察 3 个交易日，记录价格、成交额和行业相对强弱。")
            if (stock.hasMovingAverageData()) {
                add("复核现价与 MA5/MA10/MA20 的相对位置，确认趋势是否持续。")
            } else {
                add("刷新历史 K 线数据源后再补充均线诊断。")
            }
            if (stock.pe > 45.0 || stock.pb > 8.0) {
                add("补充财报质量、利润增速和估值分位，避免只看行情强弱。")
            }
            if (stock.changePercent <= -3.0 || stock.price < stock.ma20) {
                add("建立压力样本笔记，跟踪 MA20、成交额和同板块表现。")
            }
        }.distinct().take(4)

        return StockResearchReport(
            score = score,
            grade = gradeFor(score),
            trendText = trendText(stock),
            valuationText = valuationText(stock),
            liquidityText = liquidityText(stock),
            riskItems = risks,
            researchActions = actions
        )
    }

    private fun trendScore(stock: StockInfo): Int {
        if (!stock.hasMovingAverageData()) return -4
        return when {
            stock.price >= stock.ma5 && stock.ma5 >= stock.ma10 && stock.ma10 >= stock.ma20 -> 15
            stock.price >= stock.ma10 && stock.ma10 >= stock.ma20 -> 9
            stock.price >= stock.ma20 -> 4
            else -> -12
        }
    }

    private fun valuationScore(stock: StockInfo): Int {
        val peScore = when {
            stock.pe <= 0.0 -> -2
            stock.pe <= 15.0 -> 7
            stock.pe <= 35.0 -> 5
            stock.pe <= 60.0 -> -4
            else -> -8
        }
        val pbScore = when {
            stock.pb <= 0.0 -> -1
            stock.pb <= 2.0 -> 5
            stock.pb <= 6.0 -> 2
            stock.pb <= 8.0 -> 0
            else -> -5
        }
        return peScore + pbScore
    }

    private fun liquidityScore(stock: StockInfo): Int {
        return when {
            stock.turnover >= 80.0 -> 8
            stock.turnover >= 20.0 -> 5
            stock.turnover > 0.0 -> 2
            stock.volume >= 100_000_000 -> 2
            else -> -3
        }
    }

    private fun volatilityPenalty(stock: StockInfo): Int {
        return when {
            abs(stock.changePercent) <= 3.0 -> 0
            abs(stock.changePercent) <= 7.0 -> 4
            else -> 9
        }
    }

    private fun trendText(stock: StockInfo): String {
        if (!stock.hasMovingAverageData()) return "均线暂缺，趋势诊断需要补充历史 K 线。"
        return when {
            stock.price >= stock.ma5 && stock.ma5 >= stock.ma10 && stock.ma10 >= stock.ma20 ->
                "现价位于 MA5/MA10/MA20 上方，均线呈强趋势观察形态。"
            stock.price >= stock.ma20 ->
                "现价仍在 MA20 上方，但短期均线排列未完全确认。"
            else -> "现价低于 MA20，短期处于趋势压力观察区。"
        }
    }

    private fun valuationText(stock: StockInfo): String {
        val peText = stock.pe.takeIf { it > 0 }?.let { "PE ${formatNumber(it)}" } ?: "PE 暂缺"
        val pbText = stock.pb.takeIf { it > 0 }?.let { "PB ${formatNumber(it)}" } ?: "PB 暂缺"
        val state = when {
            stock.pe > 45.0 || stock.pb > 8.0 -> "估值字段偏高"
            stock.pe in 1.0..35.0 && stock.pb in 0.1..6.0 -> "估值字段处于可跟踪区间"
            else -> "估值字段覆盖或区间需要补充验证"
        }
        return "$peText，$pbText，$state。"
    }

    private fun liquidityText(stock: StockInfo): String {
        val amount = if (stock.turnover > 0) "${formatNumber(stock.turnover)}亿" else "暂无成交额"
        val range = dayRangePercent(stock)
        val rangeText = if (range > 0) "，日内振幅 ${formatNumber(range)}%" else ""
        return "成交额 $amount$rangeText。"
    }

    private fun dayRangePercent(stock: StockInfo): Double {
        val reference = stock.yesterdayClose.takeIf { it > 0 } ?: stock.price.takeIf { it > 0 } ?: return 0.0
        if (stock.high <= 0 || stock.low <= 0 || stock.high < stock.low) return 0.0
        return (stock.high - stock.low) / reference * 100.0
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "稳健跟踪"
            score >= 70 -> "均衡观察"
            score >= 55 -> "谨慎观察"
            else -> "风险复核"
        }
    }

    private fun formatNumber(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f", value)
}
