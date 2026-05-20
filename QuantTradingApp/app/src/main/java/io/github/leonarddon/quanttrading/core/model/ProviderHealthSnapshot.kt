package io.github.leonarddon.quanttrading.core.model

import io.github.leonarddon.quanttrading.model.DataHealthSeverity
import io.github.leonarddon.quanttrading.model.Freshness
import io.github.leonarddon.quanttrading.model.FreshnessWindow
import io.github.leonarddon.quanttrading.model.ProviderHealth
import io.github.leonarddon.quanttrading.model.ProviderHealthPolicy
import io.github.leonarddon.quanttrading.model.TimestampSource

/**
 * Immutable, Compose-ready UI state describing a single provider channel.
 *
 * The fields here are stable, primitives-only (plus the small enums declared in
 * `model`). Snapshots compare by value, are trivially diffable by Compose, and
 * can also be rendered by the existing XML screens without translation — the
 * goal is to let UI code adopt this without rewriting screens to Compose first.
 *
 * Use [ProviderHealthSnapshot.from] to derive one from [DataProvenance] +
 * [FreshnessWindow]; this routes through `ProviderHealthPolicy` so display
 * strings stay consistent with the existing health banner.
 */
data class ProviderHealthSnapshot(
    val providerName: String,
    val provenance: DataProvenance,
    val freshness: Freshness,
    val severity: DataHealthSeverity,
    val isUsable: Boolean,
    val statusText: String,
    val bannerText: String?,
    val fallbackReason: String?,
) {
    val shouldShowBanner: Boolean get() = severity != DataHealthSeverity.OK
    val isFallback: Boolean get() = provenance.origin.isFallback
    val isFromCache: Boolean get() = provenance.origin.isCache
    val hasClockSkew: Boolean get() = provenance.hasClockSkew
    val clockSkewMillis: Long get() = provenance.clockSkewMillis
    val lastUpdatedAt: Long get() = provenance.lastUpdatedAt
    val ageMillis: Long get() = provenance.ageMillis

    /** Underlying [ProviderHealth] for callers that still consume the legacy type. */
    fun toProviderHealth(): ProviderHealth = ProviderHealth(
        providerName = providerName,
        lastUpdatedAt = provenance.lastUpdatedAt,
        ageMillis = provenance.ageMillis,
        freshness = freshness,
        isUsable = isUsable,
        isFallback = isFallback,
        fallbackReason = fallbackReason,
        statusText = statusText,
        bannerText = bannerText,
        provenance = io.github.leonarddon.quanttrading.model.ProviderProvenance(
            timestampSource = provenance.timestampSource,
            sourceLabel = provenance.sourceLabel,
        ),
        clockSkewMillis = provenance.clockSkewMillis,
        hasClockSkew = provenance.hasClockSkew,
    )

    companion object {
        /**
         * Build a snapshot from a typed provenance plus a freshness window. The
         * existing [ProviderHealthPolicy] is the single source of truth for the
         * display strings, so feature screens that adopt the snapshot get the
         * same status / banner copy as XML screens that read [ProviderHealth].
         */
        fun from(
            providerName: String,
            provenance: DataProvenance,
            window: FreshnessWindow,
            failureMessage: String? = null,
            clockSkewToleranceMillis: Long = ProviderHealthPolicy.DEFAULT_CLOCK_SKEW_TOLERANCE_MILLIS,
        ): ProviderHealthSnapshot {
            val health = ProviderHealthPolicy.evaluate(
                providerName = providerName,
                lastUpdatedAt = provenance.lastUpdatedAt,
                now = provenance.observedAt,
                window = window,
                warnings = provenance.warnings,
                failureMessage = failureMessage,
                isFallback = provenance.origin.isFallback,
                timestampSource = provenance.timestampSource,
                sourceLabel = provenance.sourceLabel,
                clockSkewToleranceMillis = clockSkewToleranceMillis,
            )
            return ProviderHealthSnapshot(
                providerName = providerName,
                provenance = provenance.copy(
                    ageMillis = health.ageMillis,
                    clockSkewMillis = health.clockSkewMillis,
                    hasClockSkew = health.hasClockSkew,
                    timestampSource = health.provenance.timestampSource,
                    sourceLabel = health.provenance.sourceLabel,
                ),
                freshness = health.freshness,
                severity = severityFor(health.freshness, provenance.origin.isFallback),
                isUsable = health.isUsable,
                statusText = health.statusText,
                bannerText = health.bannerText,
                fallbackReason = health.fallbackReason,
            )
        }

        private fun severityFor(freshness: Freshness, isFallback: Boolean): DataHealthSeverity {
            return when (freshness) {
                Freshness.FRESH -> if (isFallback) DataHealthSeverity.INFO else DataHealthSeverity.OK
                Freshness.AGING -> DataHealthSeverity.INFO
                Freshness.STALE -> DataHealthSeverity.WARNING
                Freshness.EXPIRED -> DataHealthSeverity.ERROR
                Freshness.UNAVAILABLE -> DataHealthSeverity.ERROR
            }
        }
    }
}

/**
 * Minimal "unknown timestamp" provenance — useful when callers want to surface
 * an error state but have no upstream metadata to attach yet.
 */
fun DataProvenance.Companion.unavailable(
    observedAt: Long,
    warnings: List<String> = emptyList(),
): DataProvenance = DataProvenance(
    origin = DataOrigin.Empty,
    lastUpdatedAt = 0L,
    observedAt = observedAt,
    ageMillis = 0L,
    clockSkewMillis = 0L,
    hasClockSkew = false,
    timestampSource = TimestampSource.UNKNOWN,
    sourceLabel = null,
    warnings = warnings,
)
