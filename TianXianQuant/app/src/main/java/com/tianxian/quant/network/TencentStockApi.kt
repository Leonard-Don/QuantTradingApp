package com.tianxian.quant.network

import com.tianxian.quant.BuildConfig
import com.tianxian.quant.model.*
import com.tianxian.quant.model.StockSearchIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object TencentStockApi {

    private val quoteBaseUrl = BuildConfig.TENCENT_QUOTE_BASE_URL
    private val klineUrl = BuildConfig.TENCENT_KLINE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val stockPattern = Pattern.compile("v_([a-z]{2})(\\d{6})=\"(.+?)\";")

    suspend fun getQuote(codes: List<String>): List<StockInfo> {
        return getQuoteResult(codes).getOrNull().orEmpty()
    }

    suspend fun getQuoteResult(codes: List<String>): MarketDataResult<List<StockInfo>> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext MarketDataResult.Success(emptyList(), SOURCE_NAME)

        val marketCodes = codes.map { code ->
            when {
                code.startsWith("6") -> "sh$code"
                code.startsWith("00") || code.startsWith("30") -> "sz$code"
                code.startsWith("68") -> "sh$code"
                else -> "sz$code"
            }
        }

        val url = "$quoteBaseUrl${marketCodes.joinToString(",")}"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext MarketDataResult.Failure("腾讯 quote 请求失败：HTTP ${response.code}")
                }

                val body = response.body?.string()
                    ?: return@withContext MarketDataResult.Failure("腾讯 quote 响应为空")
                MarketDataResult.Success(parseStockList(body), SOURCE_NAME)
            }
        } catch (e: Exception) {
            MarketDataResult.Failure("腾讯 quote 网络异常：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    suspend fun getMarketOverview(): List<MarketOverview> {
        return getMarketOverviewResult().getOrNull().orEmpty()
    }

    suspend fun getMarketOverviewResult(): MarketDataResult<List<MarketOverview>> = withContext(Dispatchers.IO) {
        val indices = listOf("sh000001", "sz399001", "sz399006")
        val url = "$quoteBaseUrl${indices.joinToString(",")}"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext MarketDataResult.Failure("腾讯指数 quote 请求失败：HTTP ${response.code}")
                }

                val body = response.body?.string()
                    ?: return@withContext MarketDataResult.Failure("腾讯指数 quote 响应为空")
                MarketDataResult.Success(parseMarketOverview(body), SOURCE_NAME)
            }
        } catch (e: Exception) {
            MarketDataResult.Failure("腾讯指数 quote 网络异常：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    suspend fun getMovingAverages(codes: List<String>): Map<String, MovingAverageInfo> = withContext(Dispatchers.IO) {
        codes.distinct().map { code ->
            async { getMovingAverage(code) }
        }.awaitAll()
            .filterNotNull()
            .associateBy { it.code }
    }

    suspend fun searchStocks(keyword: String): List<StockInfo> = withContext(Dispatchers.IO) {
        val codes = if (keyword.matches(Regex("\\d{6}"))) {
            listOf(keyword)
        } else {
            StockSearchIndex.searchCodes(keyword)
        }
        getQuote(codes)
    }

    internal fun parseStockList(response: String): List<StockInfo> {
        val stocks = mutableListOf<StockInfo>()
        val matcher = stockPattern.matcher(response)

        while (matcher.find()) {
            try {
                matcher.group(1) ?: continue
                val code = matcher.group(2) ?: continue
                val data = matcher.group(3) ?: continue

                val fields = data.split("~")
                if (fields.size < 35) continue

                val stock = StockInfo(
                    code = code,
                    name = fields.getOrNull(1) ?: "",
                    price = fields.getOrNull(3).toFiniteDoubleOrNull() ?: 0.0,
                    changePercent = fields.getOrNull(32).toFiniteDoubleOrNull() ?: 0.0,
                    volume = (fields.getOrNull(6).toFiniteDoubleOrNull() ?: 0.0).toBoundedScaledLong(100L),
                    industry = StockSearchIndex.industryFor(code)
                        ?: IndustryPolicy.infer(code, fields.getOrNull(1).orEmpty()),
                    turnover = parseAmountYi(fields.getOrNull(37)),
                    high = fields.getOrNull(33).toFiniteDoubleOrNull() ?: 0.0,
                    low = fields.getOrNull(34).toFiniteDoubleOrNull() ?: 0.0,
                    open = fields.getOrNull(5).toFiniteDoubleOrNull() ?: 0.0,
                    yesterdayClose = fields.getOrNull(4).toFiniteDoubleOrNull() ?: 0.0,
                    marketCap = fields.getOrNull(45).toFiniteDoubleOrNull() ?: 0.0,
                    pe = fields.getOrNull(39).toFiniteDoubleOrNull() ?: 0.0,
                    pb = fields.getOrNull(46).toFiniteDoubleOrNull() ?: 0.0
                )
                stocks.add(stock)
            } catch (ignored: Exception) {
                // Skip malformed quote rows and keep the rest of the response usable.
            }
        }

        return stocks
    }

    private fun getMovingAverage(code: String): MovingAverageInfo? {
        val marketCode = toMarketCode(code)
        val url = "$klineUrl$marketCode,day,,,40,qfq"
        val request = Request.Builder().url(url).build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            parseMovingAverage(marketCode, code, body)
        } catch (e: Exception) {
            null
        }
    }

    internal fun parseMovingAverage(marketCode: String, code: String, body: String): MovingAverageInfo? {
        val data = JSONObject(body).optJSONObject("data") ?: return null
        val stockData = data.optJSONObject(marketCode) ?: return null
        val rows = stockData.optJSONArray("qfqday")
            ?: stockData.optJSONArray("day")
            ?: return null

        val closes = mutableListOf<Double>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            // Double.parseDouble accepts "NaN"/"Infinity"/"-Infinity"; without
            // an isFinite() guard they slip past `?: continue` and propagate
            // through .average() into MovingAverageInfo as NaN/±Infinity,
            // corrupting downstream screening and provider-health surfaces.
            // Mirrors EastMoneyKlineApi.parseDailyKlines (PR #12).
            val close = row.optString(2).toFiniteDoubleOrNull() ?: continue
            closes.add(close)
        }
        if (closes.size < 20) return null

        return MovingAverageInfo(
            code = code,
            close = closes.last(),
            ma5 = closes.takeLast(5).average(),
            ma10 = closes.takeLast(10).average(),
            ma20 = closes.takeLast(20).average()
        )
    }

    private fun toMarketCode(code: String): String {
        return when {
            code.startsWith("6") -> "sh$code"
            code.startsWith("00") || code.startsWith("30") -> "sz$code"
            code.startsWith("68") -> "sh$code"
            else -> "sz$code"
        }
    }

    internal fun parseMarketOverview(response: String): List<MarketOverview> {
        val markets = mutableListOf<MarketOverview>()
        val matcher = stockPattern.matcher(response)

        val indexNames = mapOf(
            "000001" to "上证指数",
            "399001" to "深证成指",
            "399006" to "创业板指"
        )

        while (matcher.find()) {
            try {
                val code = matcher.group(2) ?: continue
                val data = matcher.group(3) ?: continue

                val fields = data.split("~")
                if (fields.size < 35) continue

                val overview = MarketOverview(
                    indexCode = code,
                    indexName = indexNames[code] ?: fields.getOrNull(1) ?: "",
                    price = fields.getOrNull(3).toFiniteDoubleOrNull() ?: 0.0,
                    changePercent = fields.getOrNull(32).toFiniteDoubleOrNull() ?: 0.0,
                    changePoint = fields.getOrNull(31).toFiniteDoubleOrNull() ?: 0.0,
                    volume = (fields.getOrNull(6).toFiniteDoubleOrNull() ?: 0.0).toBoundedScaledLong(),
                    amount = parseAmountYi(fields.getOrNull(37))
                )
                markets.add(overview)
            } catch (ignored: Exception) {
                // Skip malformed index rows and keep the rest of the response usable.
            }
        }

        return markets
    }

    suspend fun getLimitUpStocks(): List<StockInfo> = withContext(Dispatchers.IO) {
        // 涨停股列表需要专门的接口，这里返回空列表
        // 实际应用中可以使用涨停板数据源
        emptyList()
    }

    suspend fun getHotSectors(): List<SectorInfo> = withContext(Dispatchers.IO) {
        // 板块数据需要专门的接口
        emptyList()
    }

    internal fun parseAmountYi(rawAmountWan: String?): Double {
        return parseAmountYiOrNull(rawAmountWan) ?: 0.0
    }

    // Returns null when the raw amount is missing/blank/malformed so callers can
    // distinguish "no data" from a true numeric 0. parseAmountYi keeps the legacy
    // non-null contract for model fields that require Double.
    internal fun parseAmountYiOrNull(rawAmountWan: String?): Double? {
        val trimmed = rawAmountWan?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val wan = trimmed.toDoubleOrNull() ?: return null
        if (!wan.isFinite()) return null
        return wan / 10_000.0
    }

    private fun String?.toFiniteDoubleOrNull(): Double? {
        val parsed = this?.trim()?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() }
    }

    // Tencent reports stock volume in lots (1 lot = 100 shares) and indices
    // directly, so the conversion is Double → Long with an optional pre-scale.
    // Doing the multiplication on the Double side surfaces overflow as ±Infinity
    // (caught by isFinite), and bounding against Long.MAX_VALUE / Long.MIN_VALUE
    // rejects huge-but-finite literals like "1e30" that would otherwise saturate
    // Double.toLong() to Long.MAX_VALUE — and then wrap negative when multiplied.
    private fun Double.toBoundedScaledLong(scale: Long = 1L): Long {
        if (!this.isFinite()) return 0L
        val scaled = this * scale
        if (!scaled.isFinite()) return 0L
        if (scaled >= Long.MAX_VALUE.toDouble()) return 0L
        if (scaled <= Long.MIN_VALUE.toDouble()) return 0L
        return scaled.toLong()
    }

    private const val SOURCE_NAME = "腾讯公开 quote"
}
