package com.tianxian.quant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataHealthSummaryTest {

    private val baseNow = 1_700_000_000_000L

    private fun evaluate(
        providerName: String = "腾讯多源 quote",
        ageMillis: Long = 60_000L,
        window: FreshnessWindow = FreshnessWindow.QUOTE,
        warnings: List<String> = emptyList(),
        failureMessage: String? = null,
        isFallback: Boolean = false,
        lastUpdatedAt: Long? = null,
    ): ProviderHealth {
        return ProviderHealthPolicy.evaluate(
            providerName = providerName,
            lastUpdatedAt = lastUpdatedAt ?: (baseNow - ageMillis),
            now = baseNow,
            window = window,
            warnings = warnings,
            failureMessage = failureMessage,
            isFallback = isFallback,
        )
    }

    @Test
    fun freshPrimarySourceProducesOkSummaryWithNoBanner() {
        val summary = DataHealthSummarizer.summarize(evaluate(ageMillis = 30_000L))

        assertEquals(DataHealthSeverity.OK, summary.severity)
        assertFalse(summary.shouldShowBanner)
        assertNull(summary.primaryReason)
        assertEquals("数据最新", summary.headline)
        assertTrue(summary.detailLines.any { it.contains("数据更新于") })
    }

    @Test
    fun freshFallbackSourceProducesInfoSummaryWithFallbackHeadline() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "新浪行情",
                ageMillis = 30_000L,
                warnings = listOf("腾讯 quote 请求失败：HTTP 503"),
                isFallback = true,
            )
        )

        assertEquals(DataHealthSeverity.INFO, summary.severity)
        assertTrue(summary.shouldShowBanner)
        assertEquals("已切换至备用数据源", summary.headline)
        assertEquals("腾讯 quote 请求失败：HTTP 503", summary.primaryReason)
    }

    @Test
    fun agingPrimarySourceProducesInfoSummaryWithDelayLine() {
        val summary = DataHealthSummarizer.summarize(evaluate(ageMillis = 12 * 60_000L))

        assertEquals(DataHealthSeverity.INFO, summary.severity)
        assertEquals("数据有轻微延迟", summary.headline)
        assertTrue(summary.detailLines.any { it.contains("延迟") })
    }

    @Test
    fun staleFallbackProducesWarningSummaryAndKeepsWarningText() {
        val warning = "实时行情源不可用，展示 2026-05-13 14:30 的本机缓存"
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "本机行情缓存",
                ageMillis = 90 * 60_000L,
                warnings = listOf(warning),
                isFallback = true,
            )
        )

        assertEquals(DataHealthSeverity.WARNING, summary.severity)
        assertTrue(summary.shouldShowBanner)
        assertEquals("数据已过期，请留意", summary.headline)
        assertEquals(warning, summary.primaryReason)
        assertTrue(summary.detailLines.any { it.contains("已过期") })
        assertTrue(summary.detailLines.any { it.contains(warning) })
    }

    @Test
    fun expiredFallbackProducesErrorSummary() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "本机行情缓存",
                ageMillis = 6 * 60 * 60 * 1000L,
                warnings = listOf("展示本机缓存"),
                isFallback = true,
            )
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("数据已严重过期，请刷新", summary.headline)
        assertTrue(summary.shouldShowBanner)
    }

    @Test
    fun failureMessageProducesErrorSummaryWithProviderReason() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "腾讯多源 quote",
                lastUpdatedAt = 0L,
                failureMessage = "行情源均不可用：腾讯 quote 请求失败：HTTP 503",
            )
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("数据源不可用", summary.headline)
        assertEquals(
            "行情源均不可用：腾讯 quote 请求失败：HTTP 503",
            summary.primaryReason,
        )
    }

    @Test
    fun missingLastUpdatedTreatedAsUnavailable() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(providerName = "未知源", lastUpdatedAt = 0L)
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("数据源不可用", summary.headline)
        assertTrue(summary.detailLines.any { it.contains("暂无可信刷新时间") })
        assertNotNull(summary.primaryReason)
    }

    @Test
    fun emptyPrimaryWarningOnFallbackIsSurfacedAsInfo() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "新浪行情",
                ageMillis = 30_000L,
                warnings = listOf("腾讯 quote 返回空行情，已切换到新浪行情"),
                isFallback = true,
            )
        )

        assertEquals(DataHealthSeverity.INFO, summary.severity)
        assertTrue(summary.shouldShowBanner)
        assertEquals(
            "腾讯 quote 返回空行情，已切换到新浪行情",
            summary.primaryReason,
        )
    }

    @Test
    fun multiChannelSummaryPicksWorstSeverityAndOrdersDetails() {
        val quoteFresh = evaluate(providerName = "腾讯多源 quote", ageMillis = 30_000L)
        val klineStale = evaluate(
            providerName = "东方财富日线",
            ageMillis = 6L * 60 * 60 * 1000L,
            window = FreshnessWindow.KLINE,
            warnings = listOf("东财日线请求失败，使用本机缓存"),
            isFallback = true,
        )
        val fundamentalsAging = evaluate(
            providerName = "财报指标",
            ageMillis = 3L * 24 * 60 * 60 * 1000L,
            window = FreshnessWindow.FUNDAMENTALS,
        )

        val summary = DataHealthSummarizer.summarize(
            listOf(
                DataHealthChannel("行情", quoteFresh),
                DataHealthChannel("K 线", klineStale),
                DataHealthChannel("基本面", fundamentalsAging),
            )
        )

        assertEquals(DataHealthSeverity.WARNING, summary.severity)
        assertEquals("K 线数据已过期或来自备用源", summary.headline)
        assertEquals(3, summary.detailLines.size)
        val first = summary.detailLines.first()
        assertTrue("worst channel should lead", first.contains("K 线"))
        assertTrue(first.contains("过期"))
        assertTrue(summary.detailLines.last().contains("行情"))
        assertNotNull(summary.primaryReason)
    }

    @Test
    fun multiChannelFreshFallbackUsesBackupCopyRatherThanDelayCopy() {
        val primaryFallback = evaluate(
            providerName = "新浪行情",
            ageMillis = 30_000L,
            warnings = listOf("腾讯 quote 返回空行情，已切换到新浪行情"),
            isFallback = true,
        )
        val klineFresh = evaluate(
            providerName = "东方财富日线",
            ageMillis = 5 * 60_000L,
            window = FreshnessWindow.KLINE,
        )

        val summary = DataHealthSummarizer.summarize(
            listOf(
                DataHealthChannel("行情", primaryFallback),
                DataHealthChannel("K 线", klineFresh),
            )
        )

        assertEquals(DataHealthSeverity.INFO, summary.severity)
        assertEquals("行情已切换至备用源，仍可参考", summary.headline)
        assertTrue(summary.detailLines.first().contains("行情·备用"))
        assertFalse(summary.detailLines.first().contains("行情·延迟"))
    }

    @Test
    fun multiChannelAllFreshProducesOkSummary() {
        val a = evaluate(providerName = "腾讯多源 quote", ageMillis = 30_000L)
        val b = evaluate(
            providerName = "东方财富日线",
            ageMillis = 5 * 60_000L,
            window = FreshnessWindow.KLINE,
        )

        val summary = DataHealthSummarizer.summarize(
            listOf(DataHealthChannel("行情", a), DataHealthChannel("K 线", b))
        )

        assertEquals(DataHealthSeverity.OK, summary.severity)
        assertEquals("全部数据源最新", summary.headline)
        assertFalse(summary.shouldShowBanner)
        assertEquals(2, summary.detailLines.size)
    }

    @Test
    fun emptyChannelListProducesErrorSummary() {
        val summary = DataHealthSummarizer.summarize(emptyList())

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("暂无可用数据源", summary.headline)
        assertTrue(summary.shouldShowBanner)
        assertTrue(summary.detailLines.first().contains("尚未加载"))
    }

    @Test
    fun excessiveFutureTimestampIsReportedAsError() {
        val summary = DataHealthSummarizer.summarize(
            evaluate(
                providerName = "腾讯多源 quote",
                lastUpdatedAt = baseNow + 10L * 60 * 1000L,
            )
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertTrue(summary.shouldShowBanner)
        assertEquals("数据时间戳异常", summary.headline)
        assertTrue(summary.detailLines.any { it.contains("时钟") })
        assertNotNull(summary.primaryReason)
    }

    @Test
    fun multiChannelClockSkewSurfacesAsTimestampAnomaly() {
        val skewed = evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = baseNow + 10L * 60 * 1000L,
        )
        val klineFresh = evaluate(
            providerName = "东方财富日线",
            ageMillis = 5 * 60_000L,
            window = FreshnessWindow.KLINE,
        )

        val summary = DataHealthSummarizer.summarize(
            listOf(
                DataHealthChannel("行情", skewed),
                DataHealthChannel("K 线", klineFresh),
            )
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("行情数据时间戳异常", summary.headline)
        assertTrue(summary.detailLines.first().contains("行情·时钟异常"))
    }

    @Test
    fun multiChannelUnavailableBeatsStale() {
        val staleChannel = evaluate(
            providerName = "东方财富日线",
            ageMillis = 6L * 60 * 60 * 1000L,
            window = FreshnessWindow.KLINE,
            warnings = listOf("使用本机缓存"),
            isFallback = true,
        )
        val unavailableChannel = evaluate(
            providerName = "腾讯多源 quote",
            lastUpdatedAt = 0L,
            failureMessage = "腾讯 quote 请求失败：HTTP 503",
        )

        val summary = DataHealthSummarizer.summarize(
            listOf(
                DataHealthChannel("行情", unavailableChannel),
                DataHealthChannel("K 线", staleChannel),
            )
        )

        assertEquals(DataHealthSeverity.ERROR, summary.severity)
        assertEquals("行情数据源不可用", summary.headline)
        assertTrue(summary.detailLines.first().contains("行情·不可用"))
    }
}
