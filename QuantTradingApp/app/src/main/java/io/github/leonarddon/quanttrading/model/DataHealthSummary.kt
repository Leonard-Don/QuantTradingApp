package io.github.leonarddon.quanttrading.model

enum class DataHealthSeverity(val rank: Int) {
    OK(0),
    INFO(1),
    WARNING(2),
    ERROR(3);

    companion object {
        fun max(a: DataHealthSeverity, b: DataHealthSeverity): DataHealthSeverity {
            return if (a.rank >= b.rank) a else b
        }
    }
}

data class DataHealthSummary(
    val severity: DataHealthSeverity,
    val headline: String,
    val detailLines: List<String>,
    val shouldShowBanner: Boolean,
    val primaryReason: String?,
)

data class DataHealthChannel(
    val label: String,
    val health: ProviderHealth,
)

object DataHealthSummarizer {

    fun summarize(health: ProviderHealth): DataHealthSummary {
        val severity = severityOf(health)
        val headline = headlineOf(health, severity)
        val details = mutableListOf<String>()
        details += health.statusText
        health.bannerText?.let { details += it }
        health.fallbackReason
            ?.takeIf { it.isNotBlank() && health.bannerText?.contains(it) != true }
            ?.let { details += "原因：$it" }
        return DataHealthSummary(
            severity = severity,
            headline = headline,
            detailLines = details.distinct(),
            shouldShowBanner = severity != DataHealthSeverity.OK,
            primaryReason = health.fallbackReason,
        )
    }

    fun summarize(channels: List<DataHealthChannel>): DataHealthSummary {
        if (channels.isEmpty()) {
            return DataHealthSummary(
                severity = DataHealthSeverity.ERROR,
                headline = "暂无可用数据源",
                detailLines = listOf("尚未加载任何行情或基本面数据，请下拉刷新或检查网络。"),
                shouldShowBanner = true,
                primaryReason = "尚未加载任何数据源",
            )
        }

        val perChannel = channels.map { it to severityOf(it.health) }
        val worst = perChannel.maxByOrNull { it.second.rank }!!
        val worstSeverity = worst.second

        val headline = when (worstSeverity) {
            DataHealthSeverity.OK -> "全部数据源最新"
            DataHealthSeverity.INFO -> if (worst.first.health.isFallback) {
                "${worst.first.label}已切换至备用源，仍可参考"
            } else {
                "${worst.first.label}有延迟，仍可参考"
            }
            DataHealthSeverity.WARNING -> "${worst.first.label}数据已过期或来自备用源"
            DataHealthSeverity.ERROR -> if (worst.first.health.hasClockSkew) {
                "${worst.first.label}数据时间戳异常"
            } else {
                "${worst.first.label}数据源不可用"
            }
        }

        val details = perChannel
            .sortedWith(compareByDescending<Pair<DataHealthChannel, DataHealthSeverity>> { it.second.rank }
                .thenBy { it.first.label })
            .map { (channel, sev) -> formatChannelLine(channel, sev) }

        val primaryReason = worst.first.health.fallbackReason
            ?: worst.first.health.bannerText

        return DataHealthSummary(
            severity = worstSeverity,
            headline = headline,
            detailLines = details,
            shouldShowBanner = worstSeverity != DataHealthSeverity.OK,
            primaryReason = primaryReason,
        )
    }

    private fun severityOf(health: ProviderHealth): DataHealthSeverity {
        return when (health.freshness) {
            Freshness.FRESH -> if (health.isFallback) DataHealthSeverity.INFO else DataHealthSeverity.OK
            Freshness.AGING -> DataHealthSeverity.INFO
            Freshness.STALE -> DataHealthSeverity.WARNING
            Freshness.EXPIRED -> DataHealthSeverity.ERROR
            Freshness.UNAVAILABLE -> DataHealthSeverity.ERROR
        }
    }

    private fun headlineOf(health: ProviderHealth, severity: DataHealthSeverity): String {
        return when (severity) {
            DataHealthSeverity.OK -> "数据最新"
            DataHealthSeverity.INFO -> if (health.isFallback) "已切换至备用数据源" else "数据有轻微延迟"
            DataHealthSeverity.WARNING -> "数据已过期，请留意"
            DataHealthSeverity.ERROR -> when {
                health.hasClockSkew -> "数据时间戳异常"
                health.freshness == Freshness.UNAVAILABLE -> "数据源不可用"
                else -> "数据已严重过期，请刷新"
            }
        }
    }

    private fun formatChannelLine(channel: DataHealthChannel, severity: DataHealthSeverity): String {
        val tag = when (severity) {
            DataHealthSeverity.OK -> "正常"
            DataHealthSeverity.INFO -> if (channel.health.isFallback) "备用" else "延迟"
            DataHealthSeverity.WARNING -> if (channel.health.isFallback) "备用过期" else "过期"
            DataHealthSeverity.ERROR -> when {
                channel.health.hasClockSkew -> "时钟异常"
                channel.health.freshness == Freshness.UNAVAILABLE -> "不可用"
                else -> "严重过期"
            }
        }
        return "[${channel.label}·$tag] ${channel.health.statusText}"
    }
}
