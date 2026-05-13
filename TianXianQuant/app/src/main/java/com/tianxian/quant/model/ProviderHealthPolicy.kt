package com.tianxian.quant.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Freshness(val displayName: String) {
    FRESH("最新"),
    AGING("略有延迟"),
    STALE("已过期"),
    EXPIRED("严重过期"),
    UNAVAILABLE("不可用")
}

data class FreshnessWindow(
    val freshWithinMillis: Long,
    val staleAfterMillis: Long,
    val expiredAfterMillis: Long,
) {
    init {
        require(freshWithinMillis in 0..staleAfterMillis) {
            "freshWithinMillis must be <= staleAfterMillis"
        }
        require(staleAfterMillis <= expiredAfterMillis) {
            "staleAfterMillis must be <= expiredAfterMillis"
        }
    }

    companion object {
        val QUOTE = FreshnessWindow(
            freshWithinMillis = 5L * 60 * 1000L,
            staleAfterMillis = 30L * 60 * 1000L,
            expiredAfterMillis = 4L * 60 * 60 * 1000L,
        )

        val KLINE = FreshnessWindow(
            freshWithinMillis = 30L * 60 * 1000L,
            staleAfterMillis = 4L * 60 * 60 * 1000L,
            expiredAfterMillis = 48L * 60 * 60 * 1000L,
        )

        val FUNDAMENTALS = FreshnessWindow(
            freshWithinMillis = 24L * 60 * 60 * 1000L,
            staleAfterMillis = 7L * 24 * 60 * 60 * 1000L,
            expiredAfterMillis = 30L * 24 * 60 * 60 * 1000L,
        )
    }
}

data class ProviderHealth(
    val providerName: String,
    val lastUpdatedAt: Long,
    val ageMillis: Long,
    val freshness: Freshness,
    val isUsable: Boolean,
    val isFallback: Boolean,
    val fallbackReason: String?,
    val statusText: String,
    val bannerText: String?,
)

object ProviderHealthPolicy {

    fun evaluate(
        providerName: String,
        lastUpdatedAt: Long,
        now: Long,
        window: FreshnessWindow,
        warnings: List<String> = emptyList(),
        failureMessage: String? = null,
        isFallback: Boolean = false,
    ): ProviderHealth {
        if (!failureMessage.isNullOrBlank()) {
            return ProviderHealth(
                providerName = providerName,
                lastUpdatedAt = lastUpdatedAt,
                ageMillis = ageBetween(lastUpdatedAt, now),
                freshness = Freshness.UNAVAILABLE,
                isUsable = false,
                isFallback = isFallback,
                fallbackReason = failureMessage,
                statusText = "$providerName · 数据源不可用：$failureMessage",
                bannerText = "数据源不可用，请检查网络或稍后重试。",
            )
        }
        if (lastUpdatedAt <= 0L) {
            return ProviderHealth(
                providerName = providerName,
                lastUpdatedAt = 0L,
                ageMillis = 0L,
                freshness = Freshness.UNAVAILABLE,
                isUsable = false,
                isFallback = isFallback,
                fallbackReason = warnings.firstOrNull() ?: "未获取到数据更新时间",
                statusText = "$providerName · 暂无可信刷新时间",
                bannerText = "未获取到数据更新时间，建议手动刷新。",
            )
        }

        val age = ageBetween(lastUpdatedAt, now)
        val freshness = when {
            age < window.freshWithinMillis -> Freshness.FRESH
            age < window.staleAfterMillis -> Freshness.AGING
            age < window.expiredAfterMillis -> Freshness.STALE
            else -> Freshness.EXPIRED
        }

        val fallbackReason: String? = when {
            warnings.isNotEmpty() && (isFallback || freshness >= Freshness.STALE) ->
                warnings.first()
            isFallback -> "已切换至备用数据源"
            else -> null
        }

        return ProviderHealth(
            providerName = providerName,
            lastUpdatedAt = lastUpdatedAt,
            ageMillis = age,
            freshness = freshness,
            isUsable = freshness != Freshness.EXPIRED,
            isFallback = isFallback,
            fallbackReason = fallbackReason,
            statusText = buildStatusText(providerName, lastUpdatedAt, age, freshness, isFallback),
            bannerText = buildBannerText(freshness, age, fallbackReason),
        )
    }

    private fun ageBetween(lastUpdatedAt: Long, now: Long): Long {
        if (lastUpdatedAt <= 0L) return 0L
        return (now - lastUpdatedAt).coerceAtLeast(0L)
    }

    private fun buildStatusText(
        providerName: String,
        lastUpdatedAt: Long,
        age: Long,
        freshness: Freshness,
        isFallback: Boolean,
    ): String {
        val nameWithFallback = if (isFallback) "$providerName（备用源）" else providerName
        val timeText = formatTime(lastUpdatedAt)
        return when (freshness) {
            Freshness.FRESH -> "$nameWithFallback · 数据更新于 $timeText"
            Freshness.AGING -> "$nameWithFallback · 数据更新于 $timeText，已延迟 ${formatDuration(age)}"
            Freshness.STALE -> "$nameWithFallback · 数据更新于 $timeText，已过期 ${formatDuration(age)}"
            Freshness.EXPIRED -> "$nameWithFallback · 数据更新于 $timeText，已严重过期"
            Freshness.UNAVAILABLE -> "$nameWithFallback · 暂无可信刷新时间"
        }
    }

    private fun buildBannerText(
        freshness: Freshness,
        age: Long,
        fallbackReason: String?,
    ): String? {
        return when (freshness) {
            Freshness.FRESH -> null
            Freshness.AGING -> "数据延迟 ${formatDuration(age)}，仅作观察参考。"
            Freshness.STALE -> {
                val reasonSuffix = fallbackReason?.let { "原因：$it" } ?: "建议手动刷新。"
                "数据已过期 ${formatDuration(age)}，$reasonSuffix"
            }
            Freshness.EXPIRED -> "数据已严重过期，请刷新或检查网络后再使用。"
            Freshness.UNAVAILABLE -> "数据源不可用，请检查网络后重试。"
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000L
        if (minutes < 1L) return "不到 1 分钟"
        if (minutes < 60L) return "$minutes 分钟"
        val hours = minutes / 60L
        if (hours < 24L) {
            val remainMin = minutes % 60L
            return if (remainMin == 0L) "$hours 小时" else "$hours 小时 $remainMin 分钟"
        }
        val days = hours / 24L
        return "$days 天"
    }
}
