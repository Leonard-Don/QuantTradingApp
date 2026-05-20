package io.github.leonarddon.quanttrading.network

import io.github.leonarddon.quanttrading.BuildConfig
import io.github.leonarddon.quanttrading.model.StockInfo
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("api/stocks/list")
    suspend fun getStockList(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20
    ): List<StockInfo>

    @GET("api/stocks/search")
    suspend fun searchStocks(
        @Query("keyword") keyword: String
    ): List<StockInfo>

    companion object {
        val BASE_URL: String = BuildConfig.APP_API_BASE_URL
    }
}
