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
    fun failureMessageTakesPrecedenceOverFutureTimestampSkew() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + 10 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            failureMessage = "行情源均不可用：HTTP 503",
        )

        assertEquals(Freshness.UNAVAILABLE, report.freshness)
        assertFalse(report.isUsable)
        assertFalse(report.hasClockSkew)
        assertEquals(10 * 60_000L, report.clockSkewMillis)
        assertTrue(report.statusText.contains("数据源不可用"))
        assertEquals("行情源均不可用：HTTP 503", report.fallbackReason)
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
    fun defaultProvenanceIsUnknownForBackwardCompatibleCalls() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow - 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE
        )

        assertEquals(TimestampSource.UNKNOWN, report.provenance.timestampSource)
        assertNull(report.provenance.sourceLabel)
        assertEquals(0L, report.clockSkewMillis)
        assertFalse(report.hasClockSkew)
    }

    @Test
    fun providedProvenanceIsCarriedThrough() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "本机行情缓存",
            lastUpdatedAt = baseNow - 30 * 60_000L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            timestampSource = TimestampSource.LOCAL_CACHE,
            sourceLabel = "原始来源：腾讯多源 quote",
            isFallback = true,
        )

        assertEquals(TimestampSource.LOCAL_CACHE, report.provenance.timestampSource)
        assertEquals("原始来源：腾讯多源 quote", report.provenance.sourceLabel)
    }

    @Test
    fun smallFutureSkewWithinToleranceStaysFreshButRecordsSkew() {
        val skew = 45_000L
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + skew,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertEquals(Freshness.FRESH, report.freshness)
        assertEquals(0L, report.ageMillis)
        assertEquals(skew, report.clockSkewMillis)
        assertFalse("tolerable skew must not be flagged", report.hasClockSkew)
        assertNull(report.bannerText)
    }

    @Test
    fun excessiveFutureTimestampIsTreatedAsUnavailableNotFresh() {
        val skew = 10L * 60 * 1000L
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + skew,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
        )

        assertEquals(Freshness.UNAVAILABLE, report.freshness)
        assertFalse(report.isUsable)
        assertTrue(report.hasClockSkew)
        assertEquals(skew, report.clockSkewMillis)
        assertTrue(
            "status should mention clock skew",
            report.statusText.contains("时钟")
        )
        assertNotNull(report.bannerText)
        assertTrue(report.bannerText!!.contains("时钟"))
        assertNotNull(report.fallbackReason)
    }

    @Test
    fun customClockSkewToleranceIsHonored() {
        val skew = 90_000L
        val report = ProviderHealthPolicy.evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + skew,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            clockSkewToleranceMillis = 120_000L,
        )

        assertEquals(Freshness.FRESH, report.freshness)
        assertEquals(skew, report.clockSkewMillis)
        assertFalse(report.hasClockSkew)
    }

    @Test
    fun fallbackWithoutTimestampSurfacesUnavailableNotFresh() {
        val report = ProviderHealthPolicy.evaluate(
            providerName = "新浪行情",
            lastUpdatedAt = 0L,
            now = baseNow,
            window = FreshnessWindow.QUOTE,
            isFallback = true,
            warnings = listOf("腾讯 quote 请求失败：HTTP 503"),
            timestampSource = TimestampSource.FALLBACK_PROVIDER,
        )

        assertEquals(Freshness.UNAVAILABLE, report.freshness)
        assertFalse(report.isUsable)
        assertEquals(TimestampSource.FALLBACK_PROVIDER, report.provenance.timestampSource)
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
