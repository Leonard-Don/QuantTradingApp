package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VipExpiryPolicyTest {
    @Test
    fun extendsStockVipOnly() {
        val result = VipExpiryPolicy.extend(VipTier.STOCK, 31, NOW, emptyState())

        assertEquals(NOW + days(31), result.vipExpireTime)
        assertEquals(NOW + days(31), result.stockVipExpireTime)
        assertEquals(0L, result.quantVipExpireTime)
    }

    @Test
    fun extendsQuantVipOnly() {
        val result = VipExpiryPolicy.extend(VipTier.QUANT, 93, NOW, emptyState())

        assertEquals(NOW + days(93), result.vipExpireTime)
        assertEquals(0L, result.stockVipExpireTime)
        assertEquals(NOW + days(93), result.quantVipExpireTime)
    }

    @Test
    fun extendsFullVipAcrossBothTiers() {
        val result = VipExpiryPolicy.extend(VipTier.FULL, 366, NOW, emptyState())

        assertEquals(NOW + days(366), result.vipExpireTime)
        assertEquals(NOW + days(366), result.stockVipExpireTime)
        assertEquals(NOW + days(366), result.quantVipExpireTime)
    }

    @Test
    fun extendsUnexpiredTierFromExistingExpiry() {
        val result = VipExpiryPolicy.extend(
            tier = VipTier.STOCK,
            days = 31,
            now = NOW,
            current = VipExpiryState(
                vipExpireTime = NOW + days(10),
                stockVipExpireTime = NOW + days(10),
                quantVipExpireTime = 0L
            )
        )

        assertEquals(NOW + days(41), result.vipExpireTime)
        assertEquals(NOW + days(41), result.stockVipExpireTime)
        assertEquals(0L, result.quantVipExpireTime)
    }

    @Test
    fun extendsExpiredTierFromNow() {
        val result = VipExpiryPolicy.extend(
            tier = VipTier.QUANT,
            days = 93,
            now = NOW,
            current = VipExpiryState(
                vipExpireTime = NOW - days(5),
                stockVipExpireTime = 0L,
                quantVipExpireTime = NOW - days(5)
            )
        )

        assertEquals(NOW + days(93), result.vipExpireTime)
        assertEquals(0L, result.stockVipExpireTime)
        assertEquals(NOW + days(93), result.quantVipExpireTime)
    }

    private fun emptyState(): VipExpiryState {
        return VipExpiryState(
            vipExpireTime = 0L,
            stockVipExpireTime = 0L,
            quantVipExpireTime = 0L
        )
    }

    private fun days(value: Int): Long = value * DAY_MILLIS

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
