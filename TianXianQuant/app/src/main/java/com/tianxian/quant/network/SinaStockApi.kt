package com.tianxian.quant.network

import com.tianxian.quant.BuildConfig
import com.tianxian.quant.model.IndustryPolicy
import com.tianxian.quant.model.MarketOverview
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.model.StockSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SinaStockApi {
    private val quoteBaseUrl = BuildConfig.SINA_QUOTE_BASE_URL
    private val responseCharset = Charset.forName("GB18030")
    private val linePattern = Pattern.compile("var hq_str_([a-z]{2})(\\d{6})=\"(.*?)\";")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getQuoteResult(codes: List<String>): MarketDataResult<List<StockInfo>> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext MarketDataResult.Success(emptyList(), SOURCE_NAME)
        request(buildSymbols(codes)) { body ->
            MarketDataResult.Success(parseStockList(body), SOURCE_NAME)
        }
    }

    suspend fun getMarketOverviewResult(): MarketDataResult<List<MarketOverview>> = withContext(Dispatchers.IO) {
        request(listOf("sh000001", "sz399001", "sz399006")) { body ->
            MarketDataResult.Success(parseMarketOverview(body), SOURCE_NAME)
        }
    }

    internal fun parseStockList(response: String): List<StockInfo> {
        val stocks = mutableListOf<StockInfo>()
        val matcher = linePattern.matcher(response)
        while (matcher.find()) {
            val code = matcher.group(2) ?: continue
            if (code in INDEX_CODES) continue
            val fields = matcher.group(3).orEmpty().split(",")
            if (fields.size < 10 || fields[0].isBlank()) continue

            val price = fields.getOrNull(3).toDoubleValue()
            val yesterdayClose = fields.getOrNull(2).toDoubleValue()
            val changePercent = if (yesterdayClose > 0) {
                (price - yesterdayClose) / yesterdayClose * 100.0
            } else {
                0.0
            }
            val amountYi = parseAmountYi(fields.getOrNull(9))
            stocks += StockInfo(
                code = code,
                name = fields[0].trim(),
                price = price,
                changePercent = changePercent,
                volume = fields.getOrNull(8).toLongValue(),
                industry = StockSearchIndex.industryFor(code) ?: inferIndustry(code, fields[0]),
                turnover = amountYi,
                high = fields.getOrNull(4).toDoubleValue(),
                low = fields.getOrNull(5).toDoubleValue(),
                open = fields.getOrNull(1).toDoubleValue(),
                yesterdayClose = yesterdayClose
            )
        }
        return stocks
    }

    internal fun parseMarketOverview(response: String): List<MarketOverview> {
        val markets = mutableListOf<MarketOverview>()
        val matcher = linePattern.matcher(response)
        while (matcher.find()) {
            val code = matcher.group(2) ?: continue
            if (code !in INDEX_CODES) continue
            val fields = matcher.group(3).orEmpty().split(",")
            if (fields.size < 10 || fields[0].isBlank()) continue

            val price = fields.getOrNull(3).toDoubleValue()
            val yesterdayClose = fields.getOrNull(2).toDoubleValue()
            val changePoint = price - yesterdayClose
            val changePercent = if (yesterdayClose > 0) changePoint / yesterdayClose * 100.0 else 0.0
            markets += MarketOverview(
                indexCode = code,
                indexName = INDEX_NAMES[code] ?: fields[0].trim(),
                price = price,
                changePercent = changePercent,
                changePoint = changePoint,
                volume = fields.getOrNull(8).toLongValue(),
                amount = parseAmountYi(fields.getOrNull(9))
            )
        }
        return markets
    }

    private fun <T> request(symbols: List<String>, parser: (String) -> MarketDataResult<T>): MarketDataResult<T> {
        val request = Request.Builder()
            .url("$quoteBaseUrl${symbols.joinToString(",")}")
            .header("Referer", "https://finance.sina.com.cn/")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return MarketDataResult.Failure("新浪 quote 请求失败：HTTP ${response.code}")
                }
                val bytes = response.body?.bytes()
                    ?: return MarketDataResult.Failure("新浪 quote 响应为空")
                parser(String(bytes, responseCharset))
            }
        } catch (e: Exception) {
            MarketDataResult.Failure("新浪 quote 网络异常：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun buildSymbols(codes: List<String>): List<String> {
        return codes.distinct().map { code ->
            when {
                code.startsWith("6") -> "sh$code"
                code.startsWith("00") || code.startsWith("30") -> "sz$code"
                code.startsWith("68") -> "sh$code"
                else -> "sz$code"
            }
        }
    }

    private fun String?.toDoubleValue(): Double = this?.trim()?.toDoubleOrNull() ?: 0.0

    private fun String?.toLongValue(): Long = this?.trim()?.toDoubleOrNull()?.toLong() ?: 0L

    internal fun parseAmountYi(rawAmountYuan: String?): Double {
        return parseAmountYiOrNull(rawAmountYuan) ?: 0.0
    }

    // Returns null when the raw amount is missing/blank/malformed so callers can
    // distinguish "no data" from a true numeric 0. parseAmountYi keeps the legacy
    // non-null contract for model fields that require Double.
    internal fun parseAmountYiOrNull(rawAmountYuan: String?): Double? {
        val trimmed = rawAmountYuan?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val yuan = trimmed.toDoubleOrNull() ?: return null
        if (!yuan.isFinite()) return null
        return yuan / 100_000_000.0
    }

    private fun inferIndustry(code: String, name: String): String {
        return IndustryPolicy.infer(code, name)
    }

    private val INDEX_CODES = setOf("000001", "399001", "399006")
    private val INDEX_NAMES = mapOf(
        "000001" to "上证指数",
        "399001" to "深证成指",
        "399006" to "创业板指"
    )
    private const val SOURCE_NAME = "新浪 quote"
}
