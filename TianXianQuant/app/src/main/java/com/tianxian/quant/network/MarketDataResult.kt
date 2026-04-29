package com.tianxian.quant.network

sealed class MarketDataResult<out T> {
    data class Success<T>(
        val data: T,
        val source: String,
        val warnings: List<String> = emptyList()
    ) : MarketDataResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : MarketDataResult<Nothing>()

    fun getOrNull(): T? {
        return when (this) {
            is Success -> data
            is Failure -> null
        }
    }

    fun sourceOrNull(): String? {
        return when (this) {
            is Success -> source
            is Failure -> null
        }
    }
}
