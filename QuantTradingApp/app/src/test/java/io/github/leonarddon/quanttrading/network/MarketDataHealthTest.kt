package io.github.leonarddon.quanttrading.network

import io.github.leonarddon.quanttrading.model.Freshness
import io.github.leonarddon.quanttrading.model.FreshnessWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataHealthTest {

    private val baseNow = 1_700_000_000_000L

    @Test
    fun primaryProviderSuccessIsTreatedAsFreshNonFallback() {
        val result = MarketDataResult.Success(
            data = listOf("600519"),
            source = "腾讯多源 quote",
        )

        val health = result.toProviderHealth(
            lastUpdatedAt = baseNow - 30_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertEquals(Freshness.FRESH, health.freshness)
        assertFalse(health.isFallback)
        assertNull(health.fallbackReason)
    }

    @Test
    fun cachedSuccessIsTreatedAsFallback() {
        val result = MarketDataResult.Success(
            data = listOf("600519"),
            source = "本机行情缓存",
            warnings = listOf("实时行情源不可用，展示 2026-05-13 14:30 的本机缓存；原始来源：腾讯多源 quote"),
        )

        val health = result.toProviderHealth(
            lastUpdatedAt = baseNow - 90 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertTrue(health.isFallback)
        assertEquals(Freshness.STALE, health.freshness)
        assertNotNull(health.fallbackReason)
        assertTrue(health.fallbackReason!!.contains("实时行情源不可用"))
        assertTrue(health.statusText.contains("备用源"))
    }

    @Test
    fun failureMapsToUnavailableHealth() {
        val result = MarketDataResult.Failure("行情源均不可用：腾讯 quote 请求失败：HTTP 503")

        val health = result.toProviderHealth(
            lastUpdatedAt = 0L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertEquals(Freshness.UNAVAILABLE, health.freshness)
        assertFalse(health.isUsable)
        assertEquals(
            "行情源均不可用：腾讯 quote 请求失败：HTTP 503",
            health.fallbackReason,
        )
    }

    @Test
    fun warningOnlySuccessIsStillTaggedAsFallback() {
        val result = MarketDataResult.Success(
            data = listOf("600519"),
            source = "新浪行情",
            warnings = listOf("腾讯 quote 请求失败：HTTP 503"),
        )

        val health = result.toProviderHealth(
            lastUpdatedAt = baseNow - 30_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertTrue(health.isFallback)
        assertEquals(Freshness.FRESH, health.freshness)
        assertEquals("腾讯 quote 请求失败：HTTP 503", health.fallbackReason)
    }

    @Test
    fun emptyPrimaryWarningIsTaggedAsFallback() {
        val result = MarketDataResult.Success(
            data = listOf("600519"),
            source = "新浪行情",
            warnings = listOf("腾讯 quote 返回空行情，已切换到新浪行情"),
        )

        val health = result.toProviderHealth(
            lastUpdatedAt = baseNow - 30_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertTrue(health.isFallback)
        assertEquals("腾讯 quote 返回空行情，已切换到新浪行情", health.fallbackReason)
    }
}
