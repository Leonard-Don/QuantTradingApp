package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthPolicyTest {

    private val baseNow = 1_700_000_000_000L

    @Test
    fun freshQuoteWithinFreshWindowIsMarkedFresh() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow - 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE
        )

        assertEquals(Freshness.FRESH, report.freshness)
        assertTrue(report.isUsable)
        assertNull(report.bannerText)
        assertNull(report.fallbackReason)
        assertTrue(report.statusText.contains("腾讯多源 quote"))
        assertTrue(report.statusText.contains("数据更新于"))
    }

    @Test
    fun delayedQuoteWithinAgingWindowReportsDelay() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow - 12 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE
        )

        assertEquals(Freshness.AGING, report.freshness)
        assertTrue(report.isUsable)
        val banner = report.bannerText
        assertNotNull(banner)
        assertTrue("banner should mention delay", banner!!.contains("延迟"))
        assertTrue(report.statusText.contains("12 分钟"))
    }

    @Test
    fun cacheFallbackBeyondStaleWindowSurfacesFallbackReason() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "本机行情缓存",
            lastUpdatedAt = baseNow - 90 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            warnings = listOf("实时行情源不可用，展示 2026-05-13 14:30 的本机缓存"),
            isFallback = true
        )

        assertEquals(Freshness.STALE, report.freshness)
        assertTrue(report.isUsable)
        assertEquals(
            "实时行情源不可用，展示 2026-05-13 14:30 的本机缓存",
            report.fallbackReason
        )
        val banner = report.bannerText
        assertNotNull(banner)
        assertTrue(banner!!.contains("已过期"))
        assertTrue(banner.contains("实时行情源不可用"))
        assertTrue(report.statusText.contains("备用源"))
    }

    @Test
    fun dataPastExpiredWindowIsNotUsable() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "本机行情缓存",
            lastUpdatedAt = baseNow - 6 * 60 * 60 * 1000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            warnings = listOf("展示本机缓存"),
            isFallback = true
        )

        assertEquals(Freshness.EXPIRED, report.freshness)
        assertFalse(report.isUsable)
        assertTrue(report.bannerText!!.contains("严重过期"))
    }

    @Test
    fun failureMessageProducesUnavailableHealth() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = 0L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            failureMessage = "行情源均不可用：腾讯 quote 请求失败：HTTP 503"
        )

        assertEquals(Freshness.UNAVAILABLE, report.freshness)
        assertFalse(report.isUsable)
        assertTrue(report.statusText.contains("数据源不可用"))
        assertEquals(
            "行情源均不可用：腾讯 quote 请求失败：HTTP 503",
            report.fallbackReason
        )
        assertNotNull(report.bannerText)
    }

    @Test
    fun missingLastUpdatedReportsUnavailableEvenWithoutFailure() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = 0L,
            now = baseNow,
            window = FreshnessWindow.QUOTE
        )

        assertEquals(Freshness.UNAVAILABLE, report.freshness)
        assertFalse(report.isUsable)
        assertTrue(report.statusText.contains("暂无可信刷新时间"))
    }

    @Test
    fun fallbackAnnotatedEvenWhenDataIsFresh() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "新浪行情",
            lastUpdatedAt = baseNow - 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            warnings = listOf("腾讯 quote 请求失败：HTTP 503"),
            isFallback = true
        )

        assertEquals(Freshness.FRESH, report.freshness)
        assertTrue(report.isUsable)
        assertEquals("腾讯 quote 请求失败：HTTP 503", report.fallbackReason)
        assertTrue(report.statusText.contains("备用源"))
    }

    @Test
    fun negativeAgeIsClampedToZero() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE
        )

        assertEquals(Freshness.FRESH, report.freshness)
        assertEquals(0L, report.ageMillis)
    }

    @Test
    fun klineWindowKeepsHourOldDataFresh() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "东方财富日线",
            lastUpdatedAt = baseNow - 20 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.KLINE
        )

        assertEquals(Freshness.FRESH, report.freshness)
    }

    @Test
    fun fundamentalsWindowTreatsWeekOldDataAsAging() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "财报指标",
            lastUpdatedAt = baseNow - 3L * 24 * 60 * 60 * 1000L,
            now = baseNow,
            window = FreshnessWindow.FUNDAMENTALS
        )

        assertEquals(Freshness.AGING, report.freshness)
        assertTrue(report.isUsable)
    }
}
