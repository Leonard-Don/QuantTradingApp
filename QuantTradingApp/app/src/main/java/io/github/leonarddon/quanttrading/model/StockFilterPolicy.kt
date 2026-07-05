package io.github.leonarddon.quanttrading.model

data class StockFilterPolicyResult(
    val stocks: List<StockInfo>,
    val effectiveSortMode: String,
    val watchlistEmpty: Boolean,
    val sourceRequired: Boolean,
    val movingAverageUnavailable: Boolean
)

fun StockInfo.hasMovingAverageData(): Boolean {
    return ma5 > 0 && ma10 > 0 && ma20 > 0
}

object StockFilterPolicy {
    const val WATCHLIST_FILTER = "自选池"

    private val sourceRequiredFilters = setOf("主力资金", "龙虎榜")

    fun isSourceRequiredFilter(sortMode: String): Boolean = sortMode in sourceRequiredFilters

    fun filter(
        stocks: List<StockInfo>,
        keyword: String,
        criteria: StockFilterCriteria,
        watchlistCodes: Set<String>
    ): StockFilterPolicyResult {
        var filtered = stocks
        val normalizedKeyword = keyword.trim()

        if (normalizedKeyword.isNotBlank()) {
            filtered = filtered.filter {
                it.code.contains(normalizedKeyword) ||
                    it.name.contains(normalizedKeyword, ignoreCase = true) ||
                    StockSearchIndex.matches(it.code, normalizedKeyword)
            }
        }

        if (criteria.sortMode == WATCHLIST_FILTER) {
            filtered = filtered.filter { it.code in watchlistCodes }
        }

        criteria.industry?.let { industry ->
            filtered = filtered.filter { it.industry == industry }
        }
        criteria.minChangePercent?.let { value ->
            filtered = filtered.filter { it.changePercent >= value }
        }
        criteria.minVolume?.let { value ->
            filtered = filtered.filter { it.volume >= value }
        }
        criteria.minTurnover?.let { value ->
            filtered = filtered.filter { it.turnover >= value }
        }
        criteria.maxPe?.let { value ->
            filtered = filtered.filter { it.pe > 0 && it.pe <= value }
        }
        criteria.maxPb?.let { value ->
            filtered = filtered.filter { it.pb > 0 && it.pb <= value }
        }
        criteria.minMarketCap?.let { value ->
            filtered = filtered.filter { it.marketCap >= value }
        }

        val selectedSortMode = criteria.sortMode
        val effectiveSortMode = selectedSortMode

        filtered = when (effectiveSortMode) {
            "涨幅榜" -> filtered.sortedByDescending { it.changePercent }
            "跌幅榜" -> filtered.sortedBy { it.changePercent }
            "成交额" -> filtered.sortedByDescending { it.turnover }
            "放量" -> filtered.sortedByDescending { it.volume }
            "均线强势" -> filtered
                .filter { it.hasMovingAverageData() && it.price >= it.ma5 && it.ma5 >= it.ma10 && it.ma10 >= it.ma20 }
                .sortedByDescending { it.changePercent }
            "低市盈率" -> filtered.filter { it.pe > 0 }.sortedBy { it.pe }
            "低市净率" -> filtered.filter { it.pb > 0 }.sortedBy { it.pb }
            "大市值" -> filtered.sortedByDescending { it.marketCap }
            "多因子" -> filtered.filter { it.pe in 1.0..35.0 && it.pb in 0.1..6.0 }
                .sortedWith(compareByDescending<StockInfo> { it.changePercent }.thenBy { it.pe })
            "主力资金" -> emptyList()
            "龙虎榜" -> emptyList()
            "每日样本池" -> filtered.sortedWith(
                compareByDescending<StockInfo> { it.turnover }
                    .thenByDescending { it.changePercent }
                    .thenBy { it.pe.takeIf { value -> value > 0 } ?: Double.MAX_VALUE }
            ).take(8)
            else -> filtered
        }

        return StockFilterPolicyResult(
            stocks = filtered,
            effectiveSortMode = effectiveSortMode,
            watchlistEmpty = effectiveSortMode == WATCHLIST_FILTER && watchlistCodes.isEmpty(),
            sourceRequired = isSourceRequiredFilter(effectiveSortMode),
            movingAverageUnavailable = effectiveSortMode == "均线强势" && stocks.none { it.hasMovingAverageData() }
        )
    }

}
