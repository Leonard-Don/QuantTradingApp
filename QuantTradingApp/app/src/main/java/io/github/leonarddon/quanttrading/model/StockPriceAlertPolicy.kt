package io.github.leonarddon.quanttrading.model

import java.util.Locale
import kotlin.math.abs

data class PriceAlertEvaluation(
    val triggered: Boolean,
    val statusText: String,
    val distancePercent: Double
)

object StockPriceAlertPolicy {
    fun evaluate(alert: PriceAlert, currentPrice: Double): PriceAlertEvaluation {
        if (alert.targetPrice <= 0.0 || currentPrice <= 0.0) {
            return PriceAlertEvaluation(
                triggered = false,
                statusText = "目标价或现价暂不可用，等待下一次行情刷新。",
                distancePercent = 0.0
            )
        }

        val triggered = when (alert.direction) {
            PriceAlertDirection.ABOVE -> currentPrice >= alert.targetPrice
            PriceAlertDirection.BELOW -> currentPrice <= alert.targetPrice
        }
        val distancePercent = abs(currentPrice - alert.targetPrice) / alert.targetPrice * 100.0
        val currentText = formatPrice(currentPrice)
        val targetText = formatPrice(alert.targetPrice)
        val directionText = alert.direction.displayName
        val distanceText = formatPercent(distancePercent)
        val statusText = if (triggered) {
            "已触发：现价 $currentText 已达到$directionText $targetText。"
        } else {
            "未触发：现价 $currentText，距$directionText $targetText 还差 $distanceText。"
        }

        return PriceAlertEvaluation(
            triggered = triggered,
            statusText = statusText,
            distancePercent = distancePercent
        )
    }

    fun triggerSummary(trigger: PriceAlertTrigger): String {
        return "${trigger.name}(${trigger.code}) ${trigger.statusText} 仅作本机研究观察。"
    }

    private fun formatPrice(value: Double): String {
        return String.format(Locale.CHINA, "%.2f", value)
    }

    private fun formatPercent(value: Double): String {
        return String.format(Locale.CHINA, "%.2f%%", value)
    }
}
