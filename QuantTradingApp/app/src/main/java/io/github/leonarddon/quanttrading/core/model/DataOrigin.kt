package io.github.leonarddon.quanttrading.core.model

/**
 * Where a payload came from at load time. Concrete cases let the UI distinguish
 * a fresh network response from a degraded fallback path without parsing free-text
 * source labels.
 *
 * Lives in `core.model` so that other modules can map their own results into
 * a typed provenance without depending on the network layer.
 */
sealed class DataOrigin {
    /** Live response from the primary/preferred upstream. */
    object Live : DataOrigin()

    /** Live response from a fallback upstream after the preferred provider failed or returned empty. */
    data class LiveFallback(
        val providerLabel: String,
        val primaryFailureReason: String?
    ) : DataOrigin()

    /** Cached payload served because every upstream was unavailable. */
    data class Cache(
        val fetchedAt: Long,
        val originalSources: List<String>
    ) : DataOrigin()

    /** No payload at all — caller must show an unavailable state. */
    object Empty : DataOrigin()
}

/** Convenience predicates — keep call sites declarative. */
val DataOrigin.isLive: Boolean
    get() = this is DataOrigin.Live

val DataOrigin.isFallback: Boolean
    get() = this is DataOrigin.LiveFallback || this is DataOrigin.Cache

val DataOrigin.isCache: Boolean
    get() = this is DataOrigin.Cache
