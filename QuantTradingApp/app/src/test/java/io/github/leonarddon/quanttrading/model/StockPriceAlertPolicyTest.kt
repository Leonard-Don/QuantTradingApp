package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockPriceAlertPolicyTest {
    @Test
    fun aboveAlertTriggersWhenCurrentPriceCrossesTarget() {
        val alert = sampleAlert(targetPrice = 10.0, direction = PriceAlertDirection.ABOVE)

        val pending = StockPriceAlertPolicy.evaluate(alert, currentPrice = 9.8)
        val triggered = StockPriceAlertPolicy.evaluate(alert, currentPrice = 10.1)

        assertFalse(pending.triggered)
        assertTrue(pending.statusText.contains("未触发"))
        assertTrue(triggered.triggered)
        assertTrue(triggered.statusText.contains("已触发"))
    }

    @Test
    fun belowAlertTriggersWhenCurrentPriceFallsThroughTarget() {
        val alert = sampleAlert(targetPrice = 10.0, direction = PriceAlertDirection.BELOW)

        val pending = StockPriceAlertPolicy.evaluate(alert, currentPrice = 10.2)
        val triggered = StockPriceAlertPolicy.evaluate(alert, currentPrice = 9.9)

        assertFalse(pending.triggered)
        assertTrue(triggered.triggered)
    }

    private fun sampleAlert(
        targetPrice: Double,
        direction: PriceAlertDirection
    ): PriceAlert {
        return PriceAlert(
            code = "600519",
            name = "贵州茅台",
            targetPrice = targetPrice,
            direction = direction
        )
    }
}
