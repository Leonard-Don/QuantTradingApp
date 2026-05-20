package io.github.leonarddon.quanttrading.payment

import io.github.leonarddon.quanttrading.BuildConfig
import io.github.leonarddon.quanttrading.model.VipTier

enum class PaymentChannel(val label: String) {
    WECHAT("微信支付"),
    ALIPAY("支付宝")
}

data class SubscriptionOrder(
    val tier: VipTier,
    val priceText: String,
    val durationDays: Int,
    val channel: PaymentChannel
)

sealed class PaymentResult {
    object DebugPaid : PaymentResult()
    data class NotConfigured(val channel: PaymentChannel) : PaymentResult()
}

object PaymentGateway {
    suspend fun startSubscription(order: SubscriptionOrder): PaymentResult {
        // V1 keeps the app-side payment contract ready. Real collection needs merchant AppId,
        // SDK callbacks, signature verification, and server-side order creation.
        return if (BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION) {
            PaymentResult.DebugPaid
        } else {
            PaymentResult.NotConfigured(order.channel)
        }
    }
}
