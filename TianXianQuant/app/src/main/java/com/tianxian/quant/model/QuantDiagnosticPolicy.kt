package com.tianxian.quant.model

data class QuantDiagnosticReport(
    val score: Int,
    val grade: String,
    val strategyCoverageText: String,
    val signalCoverageText: String,
    val riskItems: List<String>,
    val highlightStrategies: List<Strategy>,
    val researchActions: List<String>
)

object QuantDiagnosticPolicy {
    fun evaluate(strategies: List<Strategy>, signals: List<QuantSignal>): QuantDiagnosticReport? {
        if (strategies.isEmpty() && signals.isEmpty()) return null

        val strategyCount = strategies.size
        val strategyDivisor = strategyCount.coerceAtLeast(1)
        val vipCount = strategies.count { it.isVip }
        val validFormulaCount = strategies.count {
            it.formula.isNotBlank() && QuantFormulaPolicy.isAllowed(it.formula)
        }
        val formulaCoverage = validFormulaCount * 1.0 / strategyDivisor
        val avgWinRate = strategies.map { it.winRate }.averageOrZero()
        val avgDrawdown = strategies.map { it.maxDrawdown }.averageOrZero()
        val avgSharpe = strategies.map { it.sharpeRatio }.averageOrZero()
        val avgSignalStrength = signals.map { it.strength.toDouble() }.averageOrZero()
        val topTag = strategies
            .flatMap { it.tags.ifEmpty { listOf("未分类") } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
        val topTagRatio = (topTag?.value ?: 0) * 1.0 / strategyDivisor
        val highDrawdownCount = strategies.count { it.maxDrawdown >= 16.0 }
        val lowWinRateCount = strategies.count { it.winRate < 53.0 }
        val thinTradeCount = strategies.count { it.totalTrades in 1..79 }
        val invalidFormulaCount = strategies.count {
            it.formula.isNotBlank() && !QuantFormulaPolicy.isAllowed(it.formula)
        }

        val score = (
            64 +
                (formulaCoverage * 10).toInt() +
                ((avgWinRate - 52.0) * 1.2).toInt() +
                ((avgSharpe - 1.0) * 12).toInt() +
                ((avgSignalStrength - 50.0) * 0.2).toInt() -
                ((avgDrawdown - 12.0).coerceAtLeast(0.0) * 0.8).toInt() -
                highDrawdownCount * 4 -
                lowWinRateCount * 3 -
                thinTradeCount * 2 -
                concentrationPenalty(topTagRatio) -
                invalidFormulaCount * 5
            ).coerceIn(0, 100)

        val risks = buildList {
            if (signals.isEmpty()) {
                add("当前无可用模型信号，需先确认 quote 与历史 K 线数据源是否稳定。")
            }
            if (topTagRatio >= 0.55) {
                add("${topTag?.key ?: "单一标签"}模型占比 ${formatRatio(topTagRatio)}，策略库可能过度依赖单一研究假设。")
            }
            if (highDrawdownCount > 0) {
                add("$highDrawdownCount 个模型历史最大回撤偏高，需要优先复核仓位和止损假设。")
            }
            if (lowWinRateCount > 0) {
                add("$lowWinRateCount 个模型样本胜率低于 53%，建议拆分市场阶段再复盘。")
            }
            if (thinTradeCount > 0) {
                add("$thinTradeCount 个模型样本次数偏少，统计稳定性有限。")
            }
            if (invalidFormulaCount > 0) {
                add("$invalidFormulaCount 个公式未通过本地安全校验，不应进入历史模拟。")
            }
            if (isEmpty()) {
                add("未发现明显公式、回撤、样本量或信号覆盖风险，可继续观察模型稳定性。")
            }
        }

        val highlights = strategies
            .sortedByDescending { qualityScore(it) }
            .take(3)

        val actions = buildList {
            add("每周固定保存一次诊断结果，观察评分、回撤风险和信号覆盖是否持续改善。")
            if (signals.isEmpty()) {
                add("先修复或切换行情/K 线数据源，再评估模型强弱。")
            }
            if (highDrawdownCount > 0) {
                add("对高回撤模型单独降低假设权重，重新检查极端行情样本。")
            }
            if (topTagRatio >= 0.55) {
                add("补充不同标签模型做对照，避免只围绕单一因子解释结果。")
            }
            if (avgSignalStrength >= 70.0) {
                add("强信号阶段重点记录触发因子，而不是直接推导交易动作。")
            }
        }.distinct().take(4)

        return QuantDiagnosticReport(
            score = score,
            grade = gradeFor(score),
            strategyCoverageText = "策略库 $strategyCount 个，VIP 模型 $vipCount 个，合规公式覆盖 ${formatRatio(formulaCoverage)}，平均回撤 ${formatRatio(avgDrawdown / 100.0)}。",
            signalCoverageText = if (signals.isEmpty()) {
                "当前无模型信号样本。"
            } else {
                "当前信号样本 ${signals.size} 个，平均强度 ${formatNumber(avgSignalStrength)}/100。"
            },
            riskItems = risks,
            highlightStrategies = highlights,
            researchActions = actions
        )
    }

    private fun qualityScore(strategy: Strategy): Double {
        return strategy.sharpeRatio * 18.0 +
            strategy.annualReturn * 0.9 +
            strategy.winRate * 0.25 +
            strategy.profitFactor * 8.0 +
            strategy.totalTrades.coerceAtMost(300) / 60.0 -
            strategy.maxDrawdown * 0.8
    }

    private fun concentrationPenalty(ratio: Double): Int {
        return when {
            ratio >= 0.75 -> 12
            ratio >= 0.55 -> 7
            else -> 0
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "稳健"
            score >= 70 -> "可用"
            score >= 55 -> "待观察"
            else -> "需复核"
        }
    }

    private fun List<Double>.averageOrZero(): Double {
        return if (isEmpty()) 0.0 else average()
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatNumber(value: Double): String = String.format(java.util.Locale.CHINA, "%.1f", value)
}
