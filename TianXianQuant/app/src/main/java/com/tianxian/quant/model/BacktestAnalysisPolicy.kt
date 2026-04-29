package com.tianxian.quant.model

import java.util.Locale

data class BacktestMetrics(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val maxDrawdown: Double,
    val sharpeRatio: Double,
    val winRate: Double,
    val totalTrades: Int,
    val profitTrades: Int
)

data class BacktestAnalysisReport(
    val score: Int,
    val grade: String,
    val returnText: String,
    val riskText: String,
    val reliabilityText: String,
    val riskItems: List<String>,
    val researchActions: List<String>
)

object BacktestAnalysisPolicy {
    fun evaluate(metrics: BacktestMetrics): BacktestAnalysisReport {
        val tradeCoverageScore = when {
            metrics.totalTrades >= 120 -> 10
            metrics.totalTrades >= 60 -> 6
            metrics.totalTrades >= 30 -> 2
            else -> -6
        }
        val score = (62 +
            returnScore(metrics.annualizedReturn) +
            sharpeScore(metrics.sharpeRatio) +
            drawdownScore(metrics.maxDrawdown) +
            winRateScore(metrics.winRate) +
            tradeCoverageScore
            ).coerceIn(0, 100)

        val riskItems = buildList {
            if (metrics.totalReturn < 0) {
                add("样本总收益为负，当前周期不支持该模型假设。")
            }
            if (metrics.maxDrawdown >= 20.0) {
                add("最大回撤超过 20%，需要额外复盘极端区间和仓位承压能力。")
            } else if (metrics.maxDrawdown >= 15.0) {
                add("最大回撤处于中高区间，模型需要配合更严格的风险预算观察。")
            }
            if (metrics.sharpeRatio < 1.0) {
                add("夏普比率低于 1.0，收益波动比仍需改善。")
            }
            if (metrics.winRate < 52.0) {
                add("胜率低于 52%，需要检查样本是否依赖少数大额收益。")
            }
            if (metrics.totalTrades < 30) {
                add("样本交易次数少于 30，统计置信度有限。")
            }
            if (isEmpty()) {
                add("未发现明显回撤、夏普、胜率或样本量异常，仍需跨周期复核。")
            }
        }

        val actions = buildList {
            add("把该模型放入至少两个不同市场阶段复测，观察评分是否稳定。")
            if (metrics.maxDrawdown >= 15.0) {
                add("优先定位最大回撤发生区间，复盘当时的市场宽度、成交额和趋势破位情况。")
            }
            if (metrics.sharpeRatio < 1.0) {
                add("尝试增加波动过滤或成交额过滤，降低无效信号频率。")
            }
            if (metrics.totalTrades < 60) {
                add("扩大样本周期或样本股票池，避免用小样本结论包装模型。")
            }
            add("历史模拟只用于研究模型假设，不作为交易指令或收益承诺。")
        }.distinct().take(5)

        return BacktestAnalysisReport(
            score = score,
            grade = gradeFor(score),
            returnText = "收益质量：总收益 ${formatPercent(metrics.totalReturn)}，年化 ${formatPercent(metrics.annualizedReturn)}。",
            riskText = "风险质量：最大回撤 ${formatPercent(metrics.maxDrawdown)}，夏普 ${formatNumber(metrics.sharpeRatio)}，胜率 ${formatPercent(metrics.winRate)}。",
            reliabilityText = "样本可靠性：交易 ${metrics.totalTrades} 次，盈利 ${metrics.profitTrades} 次，覆盖度 ${coverageText(metrics.totalTrades)}。",
            riskItems = riskItems,
            researchActions = actions
        )
    }

    private fun returnScore(annualizedReturn: Double): Int {
        return when {
            annualizedReturn >= 15.0 -> 10
            annualizedReturn >= 10.0 -> 7
            annualizedReturn >= 5.0 -> 3
            annualizedReturn >= 0.0 -> 0
            else -> -10
        }
    }

    private fun sharpeScore(sharpe: Double): Int {
        return when {
            sharpe >= 1.35 -> 10
            sharpe >= 1.15 -> 7
            sharpe >= 1.0 -> 3
            sharpe >= 0.8 -> -3
            else -> -8
        }
    }

    private fun drawdownScore(drawdown: Double): Int {
        return when {
            drawdown <= 8.0 -> 10
            drawdown <= 12.0 -> 7
            drawdown <= 16.0 -> 2
            drawdown <= 22.0 -> -6
            else -> -12
        }
    }

    private fun winRateScore(winRate: Double): Int {
        return when {
            winRate >= 60.0 -> 8
            winRate >= 56.0 -> 5
            winRate >= 52.0 -> 2
            winRate >= 48.0 -> -4
            else -> -8
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "优秀"
            score >= 72 -> "稳健"
            score >= 58 -> "观察"
            else -> "待优化"
        }
    }

    private fun coverageText(totalTrades: Int): String {
        return when {
            totalTrades >= 120 -> "充分"
            totalTrades >= 60 -> "可用"
            totalTrades >= 30 -> "偏少"
            else -> "不足"
        }
    }

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatNumber(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
}
