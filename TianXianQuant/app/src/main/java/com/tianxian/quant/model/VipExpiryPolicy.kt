package com.tianxian.quant.model

data class VipExpiryState(
    val vipExpireTime: Long,
    val stockVipExpireTime: Long,
    val quantVipExpireTime: Long
)

object VipExpiryPolicy {
    fun extend(tier: VipTier, days: Int, now: Long, current: VipExpiryState): VipExpiryState {
        val duration = days * DAY_MILLIS

        fun extendOne(currentExpireTime: Long): Long {
            return maxOf(currentExpireTime, now) + duration
        }

        val stockExpiresAt = when (tier) {
            VipTier.STOCK, VipTier.FULL -> extendOne(current.stockVipExpireTime)
            VipTier.QUANT -> current.stockVipExpireTime
        }
        val quantExpiresAt = when (tier) {
            VipTier.QUANT, VipTier.FULL -> extendOne(current.quantVipExpireTime)
            VipTier.STOCK -> current.quantVipExpireTime
        }
        val anyExpiresAt = maxOf(stockExpiresAt, quantExpiresAt, current.vipExpireTime)

        return VipExpiryState(
            vipExpireTime = anyExpiresAt,
            stockVipExpireTime = stockExpiresAt,
            quantVipExpireTime = quantExpiresAt
        )
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}
