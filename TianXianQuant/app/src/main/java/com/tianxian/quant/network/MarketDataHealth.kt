package com.tianxian.quant.network

import com.tianxian.quant.model.FreshnessWindow
import com.tianxian.quant.model.ProviderHealth
import com.tianxian.quant.model.ProviderHealthPolicy

private const val LOCAL_CACHE_SOURCE_NAME = "本机行情缓存"

fun MarketDataResult<*>.toProviderHealth(
    lastUpdatedAt: Long,
    now: Long,
    window: FreshnessWindow,
    providerNameOverride: String? = null,
): ProviderHealth {
    return when (this) {
        is MarketDataResult.Success -> {
            val providerName = providerNameOverride ?: source
            val isFallback = providerName.contains(LOCAL_CACHE_SOURCE_NAME) ||
                warnings.any { warning ->
                    listOf("失败", "展示", "不可用", "空行情", "空指数", "空数据", "缓存")
                        .any { warning.contains(it) }
                }
            ProviderHealthPolicy.evaluate(
                providerName = providerName,
                lastUpdatedAt = lastUpdatedAt,
                now = now,
                window = window,
                warnings = warnings,
                isFallback = isFallback,
            )
        }
        is MarketDataResult.Failure -> {
            val providerName = providerNameOverride ?: "数据源"
            ProviderHealthPolicy.evaluate(
                providerName = providerName,
                lastUpdatedAt = lastUpdatedAt,
                now = now,
                window = window,
                failureMessage = message,
            )
        }
    }
}
