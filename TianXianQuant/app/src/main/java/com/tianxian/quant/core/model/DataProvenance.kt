package com.tianxian.quant.core.model

import com.tianxian.quant.model.TimestampSource

/**
 * Typed envelope describing **where** a payload came from and **when** it was authored.
 *
 * Companion to [com.tianxian.quant.model.ProviderHealth] but kept thin: it carries
 * only the raw facts (origin, ages, clock skew, warnings). Display logic lives in
 * [ProviderHealthSnapshot] and existing `ProviderHealthPolicy` so that this
 * structure stays cheap to construct and stable across screens.
 *
 * The `clockSkewMillis` field captures *future-timestamped* payloads (i.e.
 * `lastUpdatedAt > now`). Skew below [ProviderHealthPolicy.DEFAULT_CLOCK_SKEW_TOLERANCE_MILLIS]
 * is recorded but not flagged; skew above tolerance flips `hasClockSkew` and
 * forces consumers to treat the payload as unavailable.
 */
data class DataProvenance(
    val origin: DataOrigin,
    val lastUpdatedAt: Long,
    val observedAt: Long,
    val ageMillis: Long,
    val clockSkewMillis: Long,
    val hasClockSkew: Boolean,
    val timestampSource: TimestampSource = TimestampSource.UNKNOWN,
    val sourceLabel: String? = null,
    val warnings: List<String> = emptyList(),
) {
    /** True when at least one warning was recorded by upstream layers. */
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    /** Convenience: distinguish "no timestamp available" from a real 0L epoch. */
    val hasTimestamp: Boolean get() = lastUpdatedAt > 0L

    companion object
}
