package com.tianxian.quant.core.data

import com.tianxian.quant.core.model.DataOrigin
import com.tianxian.quant.core.model.DataProvenance
import com.tianxian.quant.model.ProviderHealthPolicy
import com.tianxian.quant.model.TimestampSource

/**
 * Contract for repositories that prefer live network data but transparently
 * fall back to cached payloads when every upstream is unavailable, expressing
 * cache age and clock-skew as first-class state.
 *
 * Implementations are free to plug in their own network/cache plumbing; the
 * generic [OfflineFirstLoader] below is provided as a reusable template that
 * encodes the standard live → fallback → cache decision flow.
 */
interface OfflineFirstRepository<in K, T> {
    /**
     * Load [key], honoring [policy]. The returned response always carries
     * provenance so callers can render banners without re-deriving origin from
     * a free-text source string.
     */
    suspend fun load(key: K, policy: CachePolicy = CachePolicy.Default): RepositoryResponse<T>
}

/**
 * Configuration that drives the offline-first decision tree. Defaults are
 * conservative (`maxCacheAgeMillis` matches `FreshnessWindow.QUOTE.expiredAfterMillis`),
 * so a cache that is older than 4 hours will be served as `Failed`, never as
 * stale-but-usable.
 */
data class CachePolicy(
    val maxCacheAgeMillis: Long,
    val clockSkewToleranceMillis: Long = ProviderHealthPolicy.DEFAULT_CLOCK_SKEW_TOLERANCE_MILLIS,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Sensible default: 4 hour cache horizon, mirrors quote freshness window. */
        val Default = CachePolicy(maxCacheAgeMillis = 4L * 60 * 60 * 1000L)
    }
}

/**
 * Outcome of attempting to refresh from upstream. Modeled as a sealed class so
 * implementations can express "primary provider succeeded", "primary failed but
 * fallback succeeded" and "everything failed" without lossy boolean flags.
 */
sealed class NetworkFetchResult<out T> {
    data class Live<T>(
        val data: T,
        val timestampMillis: Long,
        val sourceLabel: String,
        val warnings: List<String> = emptyList(),
        val timestampSource: TimestampSource = TimestampSource.QUOTE,
    ) : NetworkFetchResult<T>()

    data class LiveFallback<T>(
        val data: T,
        val timestampMillis: Long,
        val sourceLabel: String,
        val primaryFailureReason: String?,
        val warnings: List<String> = emptyList(),
        val timestampSource: TimestampSource = TimestampSource.FALLBACK_PROVIDER,
    ) : NetworkFetchResult<T>()

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : NetworkFetchResult<Nothing>()
}

/**
 * Reusable template for offline-first repositories. Subclasses (or callers
 * using the lambda overload) plug in network and cache implementations; the
 * loader handles the decision logic and produces a [RepositoryResponse] with
 * fully-populated provenance, including clock-skew detection.
 *
 * Decision flow:
 *  1. Compute `now` once from [CachePolicy.now]; reused for skew calculations.
 *  2. Attempt the network fetch.
 *  3. If the network succeeds:
 *     - Persist the payload via [writeCache].
 *     - Return [RepositoryResponse.Loaded] with origin = `Live` or `LiveFallback`.
 *     - If the upstream timestamp is in the future beyond the configured
 *       tolerance, return a `Failed` response with provenance describing the
 *       clock-skew so the UI can show a "校时后重试" banner instead of stale data.
 *  4. If the network fails, attempt [readCache]. A cache entry within
 *     `maxCacheAgeMillis` becomes a `Loaded` response with `Cache` origin and
 *     the network failure recorded as a warning; otherwise return `Failed`
 *     with `Empty` origin.
 *
 * The class is `open` so feature repositories can override `fetchFromNetwork`,
 * `readCache`, or `writeCache` without subclassing the entire decision tree.
 */
open class OfflineFirstLoader<K, T>(
    private val fetchFromNetwork: suspend (K) -> NetworkFetchResult<T>,
    private val readCache: suspend (K) -> CacheEntry<T>?,
    private val writeCache: suspend (K, T, String, Long) -> Unit = { _, _, _, _ -> },
) : OfflineFirstRepository<K, T> {

    override suspend fun load(key: K, policy: CachePolicy): RepositoryResponse<T> {
        val now = policy.now()
        val networkOutcome = runCatching { fetchFromNetwork(key) }
            .getOrElse { error -> NetworkFetchResult.Failure(error.message ?: "网络异常", error) }

        when (networkOutcome) {
            is NetworkFetchResult.Live -> return acceptLive(
                key = key,
                data = networkOutcome.data,
                origin = DataOrigin.Live,
                lastUpdatedAt = networkOutcome.timestampMillis,
                sourceLabel = networkOutcome.sourceLabel,
                warnings = networkOutcome.warnings,
                timestampSource = networkOutcome.timestampSource,
                policy = policy,
                now = now,
            )

            is NetworkFetchResult.LiveFallback -> return acceptLive(
                key = key,
                data = networkOutcome.data,
                origin = DataOrigin.LiveFallback(
                    providerLabel = networkOutcome.sourceLabel,
                    primaryFailureReason = networkOutcome.primaryFailureReason,
                ),
                lastUpdatedAt = networkOutcome.timestampMillis,
                sourceLabel = networkOutcome.sourceLabel,
                warnings = buildList {
                    networkOutcome.primaryFailureReason?.takeIf { it.isNotBlank() }?.let { add(it) }
                    addAll(networkOutcome.warnings)
                },
                timestampSource = networkOutcome.timestampSource,
                policy = policy,
                now = now,
            )

            is NetworkFetchResult.Failure -> return fallbackToCache(
                key = key,
                policy = policy,
                now = now,
                failure = networkOutcome,
            )
        }
    }

    private suspend fun acceptLive(
        key: K,
        data: T,
        origin: DataOrigin,
        lastUpdatedAt: Long,
        sourceLabel: String,
        warnings: List<String>,
        timestampSource: TimestampSource,
        policy: CachePolicy,
        now: Long,
    ): RepositoryResponse<T> {
        val skew = (lastUpdatedAt - now).coerceAtLeast(0L)
        val skewExceedsTolerance = skew > policy.clockSkewToleranceMillis.coerceAtLeast(0L)

        if (skewExceedsTolerance) {
            // Refuse to serve future-timestamped payloads: provenance encodes
            // the skew so the UI can show "校时后重试" and the cached fallback
            // path can still be attempted by callers if desired.
            val provenance = DataProvenance(
                origin = origin,
                lastUpdatedAt = lastUpdatedAt,
                observedAt = now,
                ageMillis = 0L,
                clockSkewMillis = skew,
                hasClockSkew = true,
                timestampSource = timestampSource,
                sourceLabel = sourceLabel,
                warnings = warnings,
            )
            return RepositoryResponse.Failed(
                message = "数据时间戳异常：超前 ${formatMillis(skew)}",
                provenance = provenance,
            )
        }

        runCatching { writeCache(key, data, sourceLabel, lastUpdatedAt) }

        val age = if (lastUpdatedAt <= 0L) 0L else (now - lastUpdatedAt).coerceAtLeast(0L)
        return RepositoryResponse.Loaded(
            data = data,
            provenance = DataProvenance(
                origin = origin,
                lastUpdatedAt = lastUpdatedAt,
                observedAt = now,
                ageMillis = age,
                clockSkewMillis = skew,
                hasClockSkew = false,
                timestampSource = timestampSource,
                sourceLabel = sourceLabel,
                warnings = warnings,
            ),
        )
    }

    private suspend fun fallbackToCache(
        key: K,
        policy: CachePolicy,
        now: Long,
        failure: NetworkFetchResult.Failure,
    ): RepositoryResponse<T> {
        val cache = runCatching { readCache(key) }.getOrNull()
            ?: return RepositoryResponse.Failed(
                message = failure.message,
                provenance = DataProvenance(
                    origin = DataOrigin.Empty,
                    lastUpdatedAt = 0L,
                    observedAt = now,
                    ageMillis = 0L,
                    clockSkewMillis = 0L,
                    hasClockSkew = false,
                    timestampSource = TimestampSource.UNKNOWN,
                    sourceLabel = null,
                    warnings = listOf(failure.message),
                ),
                cause = failure.cause,
            )

        val age = (now - cache.fetchedAt).coerceAtLeast(0L)
        if (age > policy.maxCacheAgeMillis.coerceAtLeast(0L)) {
            return RepositoryResponse.Failed(
                message = "${failure.message}；本机缓存已过期",
                provenance = DataProvenance(
                    origin = DataOrigin.Empty,
                    lastUpdatedAt = cache.fetchedAt,
                    observedAt = now,
                    ageMillis = age,
                    clockSkewMillis = 0L,
                    hasClockSkew = false,
                    timestampSource = TimestampSource.LOCAL_CACHE,
                    sourceLabel = cache.originalSources.joinToString(" + ").ifBlank { null },
                    warnings = listOf(failure.message, "本机缓存已过期"),
                ),
                cause = failure.cause,
            )
        }

        val origin = DataOrigin.Cache(
            fetchedAt = cache.fetchedAt,
            originalSources = cache.originalSources,
        )
        return RepositoryResponse.Loaded(
            data = cache.data,
            provenance = DataProvenance(
                origin = origin,
                lastUpdatedAt = cache.fetchedAt,
                observedAt = now,
                ageMillis = age,
                clockSkewMillis = 0L,
                hasClockSkew = false,
                timestampSource = TimestampSource.LOCAL_CACHE,
                sourceLabel = cache.originalSources.joinToString(" + ").ifBlank { null },
                warnings = listOf(failure.message),
            ),
        )
    }

    private fun formatMillis(millis: Long): String {
        val minutes = millis / 60_000L
        if (minutes < 1L) return "不到 1 分钟"
        if (minutes < 60L) return "$minutes 分钟"
        val hours = minutes / 60L
        val rem = minutes % 60L
        return if (rem == 0L) "$hours 小时" else "$hours 小时 $rem 分钟"
    }
}
