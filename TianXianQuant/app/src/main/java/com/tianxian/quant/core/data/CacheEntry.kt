package com.tianxian.quant.core.data

/**
 * Generic snapshot of a payload that was previously persisted to local storage,
 * along with the metadata needed to reason about its freshness.
 *
 * `fetchedAt` is the wall-clock millisecond at which the payload was originally
 * written. `originalSources` carries the upstream provider labels so the UI can
 * tell users where the cached data came from (e.g. "原始来源：腾讯多源 quote").
 */
data class CacheEntry<out T>(
    val data: T,
    val fetchedAt: Long,
    val originalSources: List<String> = emptyList(),
)
