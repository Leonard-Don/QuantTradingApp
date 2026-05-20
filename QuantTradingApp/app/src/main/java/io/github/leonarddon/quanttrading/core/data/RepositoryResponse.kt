package io.github.leonarddon.quanttrading.core.data

import io.github.leonarddon.quanttrading.core.model.DataOrigin
import io.github.leonarddon.quanttrading.core.model.DataProvenance

/**
 * Typed response from a repository load: either a payload plus its
 * [DataProvenance], or a typed failure that still carries provenance so the UI
 * can decide whether to show stale-cache fallback messaging instead of "unknown
 * error".
 *
 * Designed to coexist with [io.github.leonarddon.quanttrading.network.MarketDataResult]; this
 * is the abstraction that newer repositories should implement directly,
 * whereas legacy code paths can continue to return `MarketDataResult` and be
 * mapped at the edge via `toRepositoryResponse(...)`.
 */
sealed class RepositoryResponse<out T> {
    abstract val provenance: DataProvenance

    val isLoaded: Boolean get() = this is Loaded
    val isFailed: Boolean get() = this is Failed

    fun dataOrNull(): T? = (this as? Loaded<T>)?.data

    data class Loaded<T>(
        val data: T,
        override val provenance: DataProvenance,
    ) : RepositoryResponse<T>()

    data class Failed(
        val message: String,
        override val provenance: DataProvenance,
        val cause: Throwable? = null,
    ) : RepositoryResponse<Nothing>() {
        val origin: DataOrigin get() = provenance.origin
    }
}
