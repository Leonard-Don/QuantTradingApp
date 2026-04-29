package com.tianxian.quant.network

import com.tianxian.quant.data.CachedStockQuoteSnapshot
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.model.DailyKline
import com.tianxian.quant.model.MarketOverview
import com.tianxian.quant.model.MovingAverageInfo
import com.tianxian.quant.model.StockInfo
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MarketDataRepository {
    suspend fun getQuoteResult(codes: List<String>): MarketDataResult<List<StockInfo>> {
        val requestedCodes = codes.distinct()
        if (requestedCodes.isEmpty()) return MarketDataResult.Success(emptyList(), "多源 quote")
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
                        return successAndCache(requestedCodes, merged.values.toList(), source, warnings)
                    }
                    is MarketDataResult.Failure -> {
                        warnings += primary.warnings
                        warnings += "新浪补全失败：${secondary.message}"
                        return successAndCache(requestedCodes, primary.data, primary.source, warnings)
                    }
                }
            }
            return successAndCache(requestedCodes, primary.data, primary.source, primary.warnings)
        }

        warnings += when (primary) {
            is MarketDataResult.Success -> "${primary.source} 返回空行情"
            is MarketDataResult.Failure -> primary.message
        }

        return when (val secondary = SinaStockApi.getQuoteResult(requestedCodes)) {
            is MarketDataResult.Success -> {
                if (secondary.data.isEmpty()) {
                    cachedQuoteResult(
                        requestedCodes,
                        warnings + "${secondary.source} 返回空行情",
                        "行情源均无可用 quote"
                    )
                } else {
                    successAndCache(requestedCodes, secondary.data, secondary.source, warnings + secondary.warnings)
                }
            }
            is MarketDataResult.Failure -> {
                cachedQuoteResult(requestedCodes, warnings + secondary.message, "行情源均不可用")
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

    suspend fun getDailyKlinesResult(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): MarketDataResult<List<DailyKline>> {
        if (!code.matches(Regex("\\d{6}"))) {
            return MarketDataResult.Failure("股票代码格式无效")
        }
        return EastMoneyKlineApi.getDailyKlinesResult(code, startDate, endDate)
    }

    private suspend fun successAndCache(
        requestedCodes: List<String>,
        stocks: List<StockInfo>,
        source: String,
        warnings: List<String>
    ): MarketDataResult.Success<List<StockInfo>> {
        val orderedStocks = orderByRequestedCodes(requestedCodes, stocks)
        LocalStateRepository.saveStockQuoteCache(orderedStocks, source)
        return MarketDataResult.Success(orderedStocks, source, warnings.distinct())
    }

    private suspend fun cachedQuoteResult(
        requestedCodes: List<String>,
        warnings: List<String>,
        failurePrefix: String
    ): MarketDataResult<List<StockInfo>> {
        val cached = LocalStateRepository.getCachedStockQuotes(requestedCodes)
            ?: return MarketDataResult.Failure("$failurePrefix：${warnings.joinToString("；")}")
        return MarketDataResult.Success(
            data = cached.stocks,
            source = CACHE_SOURCE_NAME,
            warnings = (warnings + cached.warningText()).distinct()
        )
    }

    private fun orderByRequestedCodes(requestedCodes: List<String>, stocks: List<StockInfo>): List<StockInfo> {
        val byCode = stocks.associateBy { it.code }
        return requestedCodes.mapNotNull { byCode[it] } + stocks.filterNot { it.code in requestedCodes.toSet() }
    }

    private fun CachedStockQuoteSnapshot.warningText(): String {
        val originalSourceText = originalSources.joinToString(" + ").ifBlank { "未知来源" }
        return "实时行情源不可用，展示 ${formatCacheTime(fetchedAt)} 的本机缓存；原始来源：$originalSourceText"
    }

    private fun formatCacheTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }

    private const val CACHE_SOURCE_NAME = "本机行情缓存"
}
