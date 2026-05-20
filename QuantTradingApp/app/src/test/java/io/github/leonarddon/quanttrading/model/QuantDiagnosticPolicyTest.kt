package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantDiagnosticPolicyTest {
    @Test
    fun returnsNullWhenStrategiesAndSignalsAreEmpty() {
        assertNull(QuantDiagnosticPolicy.evaluate(emptyList(), emptyList()))
    }

    @Test
    fun reportsRisksForConcentratedWeakStrategySetWithoutSignals() {
        val report = QuantDiagnosticPolicy.evaluate(
            listOf(
                strategy("1", "高波动动量", listOf("动量"), winRate = 51.0, drawdown = 22.0, trades = 40),
                strategy("2", "短样本动量", listOf("动量"), winRate = 52.0, drawdown = 18.0, trades = 35),
                strategy("3", "趋势动量", listOf("动量"), winRate = 54.0, drawdown = 12.0, trades = 120)
            ),
            emptyList()
        )

        assertNotNull(report)
        checkNotNull(report)
        assertTrue(report.score in 0..100)
        assertTrue(report.riskItems.any { it.contains("无可用模型信号") })
        assertTrue(report.riskItems.any { it.contains("动量") && it.contains("单一研究假设") })
        assertTrue(report.riskItems.any { it.contains("最大回撤") })
        assertTrue(report.riskItems.any { it.contains("样本次数") })
    }

    @Test
    fun scoresBalancedStrategiesWithSignalsHigherThanWeakSet() {
        val strong = QuantDiagnosticPolicy.evaluate(
            listOf(
                strategy("1", "趋势观察", listOf("趋势"), winRate = 58.0, drawdown = 10.0, sharpe = 1.2),
                strategy("2", "价值观察", listOf("价值"), winRate = 57.0, drawdown = 11.0, sharpe = 1.16),
                strategy("3", "波动观察", listOf("波动"), winRate = 56.0, drawdown = 9.0, sharpe = 1.1)
            ),
            listOf(signal("600519", 78), signal("300750", 72))
        )
        val weak = QuantDiagnosticPolicy.evaluate(
            listOf(
                strategy("4", "高回撤一", listOf("动量"), winRate = 50.0, drawdown = 24.0, sharpe = 0.78),
                strategy("5", "高回撤二", listOf("动量"), winRate = 51.0, drawdown = 20.0, sharpe = 0.82)
            ),
            emptyList()
        )

        assertNotNull(strong)
        assertNotNull(weak)
        checkNotNull(strong)
        checkNotNull(weak)
        assertTrue(strong.score > weak.score)
        assertEquals("1", strong.highlightStrategies.first().id)
    }

    private fun strategy(
        id: String,
        name: String,
        tags: List<String>,
        winRate: Double,
        drawdown: Double,
        sharpe: Double = 1.0,
        trades: Int = 150
    ): Strategy {
        return Strategy(
            id = id,
            name = name,
            description = "$name 历史样本研究",
            winRate = winRate,
            maxDrawdown = drawdown,
            sharpeRatio = sharpe,
            annualReturn = 10.0,
            totalTrades = trades,
            profitFactor = 1.12,
            isVip = true,
            formula = "close > ma20 && turnover > avg_turnover_20",
            tags = tags
        )
    }

    private fun signal(code: String, strength: Int): QuantSignal {
        return QuantSignal(
            code = code,
            name = code,
            modelName = "测试模型",
            strength = strength,
            state = "测试观察",
            factors = listOf("MA5 > MA20")
        )
    }
}
