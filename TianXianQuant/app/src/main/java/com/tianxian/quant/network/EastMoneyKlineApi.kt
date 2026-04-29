package com.tianxian.quant.network

import com.tianxian.quant.BuildConfig
import com.tianxian.quant.model.MovingAverageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object EastMoneyKlineApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getMovingAveragesResult(codes: List<String>): MarketDataResult<Map<String, MovingAverageInfo>> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext MarketDataResult.Success(emptyMap(), SOURCE_NAME)
            val data = codes.distinct()
                .map { code -> async { getMovingAverage(code) } }
                .awaitAll()
                .filterNotNull()
                .associateBy { it.code }

            MarketDataResult.Success(data, SOURCE_NAME)
        }

    private fun getMovingAverage(code: String): MovingAverageInfo? {
        val request = Request.Builder()
            .url(buildUrl(code))
            .header("Referer", "https://quote.eastmoney.com/")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseMovingAverage(code, body)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    internal fun parseMovingAverage(code: String, body: String): MovingAverageInfo? {
        val rows = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONArray("klines")
            ?: return null

        val closes = mutableListOf<Double>()
        for (index in 0 until rows.length()) {
            val fields = rows.optString(index).split(",")
            val close = fields.getOrNull(2)?.toDoubleOrNull() ?: continue
            closes += close
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

    private fun buildUrl(code: String): String {
        val today = LocalDate.now()
        val begin = today.minusMonths(7).format(DateTimeFormatter.BASIC_ISO_DATE)
        val end = today.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        return BuildConfig.EASTMONEY_KLINE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("secid", "${marketPrefix(code)}.$code")
            .addQueryParameter("fields1", "f1,f2,f3")
            .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56")
            .addQueryParameter("klt", "101")
            .addQueryParameter("fqt", "1")
            .addQueryParameter("beg", begin)
            .addQueryParameter("end", end)
            .build()
            .toString()
    }

    private fun marketPrefix(code: String): String {
        return if (code.startsWith("6")) "1" else "0"
    }

    private const val SOURCE_NAME = "东方财富 K 线"
}
