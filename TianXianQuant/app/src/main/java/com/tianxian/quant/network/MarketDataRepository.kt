package com.tianxian.quant.network

import com.tianxian.quant.model.MarketOverview
import com.tianxian.quant.model.MovingAverageInfo
import com.tianxian.quant.model.StockInfo

object MarketDataRepository {
    suspend fun getQuoteResult(codes: List<String>): MarketDataResult<List<StockInfo>> {
        val requestedCodes = codes.distinct()
        val primary = TencentStockApi.getQuoteResult(requestedCodes)
        val warnings = mutableListOf<String>()

        if (primary is MarketDataResult.Success && primary.data.isNotEmpty()) {
            val merged = primary.data.associateBy { it.code }.toMutableMap()
            val missingCodes = requestedCodes.filterNot { it in merged }
            if (missingCodes.isNotEmpty()) {
                when (val secondary = SinaStockApi.getQuoteResult(missingCodes)) {
                    is MarketDataResult.Success -> {
                        secondary.data.forEach { merged[it.code] = it }
                        warnings += primary.warnings
                        warnings += secondary.warnings
                        val source = if (secondary.data.isEmpty()) {
                            primary.source
                        } else {
                            "${primary.source} + ${secondary.source}"
                        }
                        return MarketDataResult.Success(merged.values.toList(), source, warnings)
                    }
                    is MarketDataResult.Failure -> {
                        warnings += primary.warnings
                        warnings += "新浪补全失败：${secondary.message}"
                        return MarketDataResult.Success(primary.data, primary.source, warnings)
                    }
                }
            }
            return primary
        }

        warnings += when (primary) {
            is MarketDataResult.Success -> "${primary.source} 返回空行情"
            is MarketDataResult.Failure -> primary.message
        }

        return when (val secondary = SinaStockApi.getQuoteResult(requestedCodes)) {
            is MarketDataResult.Success -> {
                if (secondary.data.isEmpty()) {
                    MarketDataResult.Failure("行情源均无可用 quote：${(warnings + "${secondary.source} 返回空行情").joinToString("；")}")
                } else {
                    MarketDataResult.Success(secondary.data, secondary.source, warnings + secondary.warnings)
                }
            }
            is MarketDataResult.Failure -> {
                MarketDataResult.Failure("行情源均不可用：${(warnings + secondary.message).joinToString("；")}")
            }
        }
    }

    suspend fun getMarketOverviewResult(): MarketDataResult<List<MarketOverview>> {
        val primary = TencentStockApi.getMarketOverviewResult()
        if (primary is MarketDataResult.Success && primary.data.isNotEmpty()) return primary

        val warning = when (primary) {
            is MarketDataResult.Success -> "${primary.source} 返回空指数"
            is MarketDataResult.Failure -> primary.message
        }

        return when (val secondary = SinaStockApi.getMarketOverviewResult()) {
            is MarketDataResult.Success -> {
                if (secondary.data.isEmpty()) {
                    MarketDataResult.Failure("指数源均无可用 quote：$warning；${secondary.source} 返回空指数")
                } else {
                    MarketDataResult.Success(secondary.data, secondary.source, listOf(warning) + secondary.warnings)
                }
            }
            is MarketDataResult.Failure -> {
                MarketDataResult.Failure("指数源均不可用：$warning；${secondary.message}")
            }
        }
    }

    suspend fun getMovingAveragesResult(codes: List<String>): MarketDataResult<Map<String, MovingAverageInfo>> {
        val requestedCodes = codes.distinct()
        if (requestedCodes.isEmpty()) return MarketDataResult.Success(emptyMap(), "K 线源")

        val tencentData = TencentStockApi.getMovingAverages(requestedCodes)
        val merged = tencentData.toMutableMap()
        val missingCodes = requestedCodes.filterNot { it in merged }

        if (missingCodes.isNotEmpty()) {
            when (val eastMoney = EastMoneyKlineApi.getMovingAveragesResult(missingCodes)) {
                is MarketDataResult.Success -> {
                    merged += eastMoney.data
                    val source = when {
                        tencentData.isNotEmpty() && eastMoney.data.isNotEmpty() -> "腾讯复权日线 + ${eastMoney.source}"
                        tencentData.isNotEmpty() -> "腾讯复权日线"
                        eastMoney.data.isNotEmpty() -> eastMoney.source
                        else -> "K 线源"
                    }
                    return MarketDataResult.Success(merged, source, eastMoney.warnings)
                }
                is MarketDataResult.Failure -> {
                    if (tencentData.isNotEmpty()) {
                        return MarketDataResult.Success(
                            tencentData,
                            "腾讯复权日线",
                            listOf("东方财富 K 线补全失败：${eastMoney.message}")
                        )
                    }
                    return MarketDataResult.Failure("K 线源均不可用：${eastMoney.message}")
                }
            }
        }

        return if (merged.isEmpty()) {
            MarketDataResult.Failure("K 线源均无可用数据")
        } else {
            MarketDataResult.Success(merged, "腾讯复权日线")
        }
    }

    suspend fun getMovingAverages(codes: List<String>): Map<String, MovingAverageInfo> {
        return getMovingAveragesResult(codes).getOrNull().orEmpty()
    }
}
