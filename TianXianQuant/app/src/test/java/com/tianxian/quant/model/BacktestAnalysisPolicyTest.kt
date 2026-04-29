package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestAnalysisPolicyTest {
    @Test
    fun flagsWeakBacktestWithHighDrawdownAndLowCoverage() {
        val report = BacktestAnalysisPolicy.evaluate(
            BacktestMetrics(
                totalReturn = -4.0,
                annualizedReturn = -3.0,
                maxDrawdown = 24.0,
                sharpeRatio = 0.72,
                winRate = 48.0,
                totalTrades = 18,
                profitTrades = 8
            )
        )

        assertTrue(report.score in 0..100)
        assertEquals("待优化", report.grade)
        assertTrue(report.riskItems.any { it.contains("总收益为负") })
        assertTrue(report.riskItems.any { it.contains("最大回撤") })
        assertTrue(report.riskItems.any { it.contains("样本交易次数") })
    }

    @Test
    fun scoresRobustBacktestHigherThanWeakBacktest() {
        val robust = BacktestAnalysisPolicy.evaluate(
            BacktestMetrics(
                totalReturn = 18.0,
                annualizedReturn = 16.0,
                maxDrawdown = 7.0,
                sharpeRatio = 1.42,
                winRate = 61.0,
                totalTrades = 138,
                profitTrades = 84
            )
        )
        val weak = BacktestAnalysisPolicy.evaluate(
            BacktestMetrics(
                totalReturn = 2.0,
                annualizedReturn = 3.0,
                maxDrawdown = 21.0,
                sharpeRatio = 0.86,
                winRate = 49.0,
                totalTrades = 22,
                profitTrades = 11
            )
        )

        assertTrue(robust.score > weak.score)
        assertEquals("优秀", robust.grade)
        assertTrue(robust.reliabilityText.contains("充分"))
        assertTrue(weak.riskItems.any { it.contains("夏普") })
    }

    @Test
    fun treatsMidRangeBacktestAsObservationInsteadOfFailure() {
        val report = BacktestAnalysisPolicy.evaluate(
            BacktestMetrics(
                totalReturn = 8.0,
                annualizedReturn = 7.5,
                maxDrawdown = 13.0,
                sharpeRatio = 1.05,
                winRate = 54.0,
                totalTrades = 66,
                profitTrades = 36
            )
        )

        assertTrue(report.score >= 58)
        assertTrue(report.grade == "观察" || report.grade == "稳健")
        assertTrue(report.researchActions.isNotEmpty())
    }
}
