package com.tianxian.quant.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.model.StockFilterCriteria
import com.tianxian.quant.model.StockFilterPolicy
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.model.StockSearchIndex
import com.tianxian.quant.model.hasMovingAverageData
import com.tianxian.quant.network.MarketDataResult
import com.tianxian.quant.network.MarketDataRepository
import kotlinx.coroutines.launch

class StockSelectViewModel : ViewModel() {

    private val _stocks = MutableLiveData<List<StockInfo>>()
    val stocks: LiveData<List<StockInfo>> = _stocks

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _dataStatus = MutableLiveData<String?>()
    val dataStatus: LiveData<String?> = _dataStatus

    private val _criteriaState = MutableLiveData(StockFilterCriteria())
    val criteriaState: LiveData<StockFilterCriteria> = _criteriaState

    private val _watchlistCodes = MutableLiveData<Set<String>>(emptySet())
    val watchlistCodes: LiveData<Set<String>> = _watchlistCodes

    private val _isVipActive = MutableLiveData(false)
    val isVipActive: LiveData<Boolean> = _isVipActive

    private var allStocks: List<StockInfo> = emptyList()
    private var keyword: String = ""
    private var criteria = StockFilterCriteria()
    private var baseDataStatus: String = ""
    private var watchlistCodeSet: Set<String> = emptySet()

    private val hotStockCodes = StockSearchIndex.defaultCodes()

    private val industryFilters = setOf("科技", "消费", "金融", "新能源", "医药")

    init {
        loadStocks()
    }

    fun getFilterOptions(): List<String> {
        return listOf(
            "全部", StockFilterPolicy.WATCHLIST_FILTER, "涨幅榜", "跌幅榜", "成交额", "放量", "均线强势", "低市盈率", "低市净率", "大市值",
            "科技", "消费", "金融", "新能源", "医药",
            "高级多因子(VIP)", "主力资金(VIP)", "龙虎榜(VIP)", "每日精选(VIP)"
        )
    }

    fun loadStocks() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            refreshVipState()
            criteria = LocalStateRepository.getStockFilter()
            _criteriaState.value = criteria
            refreshWatchlistState()
            val quoteCodes = (hotStockCodes + watchlistCodeSet.toList()).distinct()

            try {
                when (val quoteResult = MarketDataRepository.getQuoteResult(quoteCodes)) {
                    is MarketDataResult.Success -> {
                        val result = quoteResult.data
                        if (result.isEmpty()) {
                            allStocks = getFallbackData()
                            baseDataStatus = "多源 quote 暂无可用行情，当前展示离线样本数据。"
                        } else {
                            val enriched = attachMovingAverages(result)
                            allStocks = enriched.stocks
                            val maCount = allStocks.count { it.hasMovingAverageData() }
                            baseDataStatus = buildDataSourceStatus(
                                quoteSource = quoteResult.source,
                                maSource = enriched.source,
                                maCount = maCount,
                                total = allStocks.size,
                                warnings = quoteResult.warnings + enriched.warnings
                            )
                        }
                    }
                    is MarketDataResult.Failure -> {
                        allStocks = getFallbackData()
                        baseDataStatus = "${quoteResult.message}，当前展示离线样本数据。"
                        _error.value = "行情请求失败，已切换离线样本。"
                    }
                }
                applyCurrentFilter()
            } catch (e: Exception) {
                allStocks = getFallbackData()
                baseDataStatus = "实时行情暂不可用，当前展示离线样本数据。"
                _error.value = "网络请求失败，已切换离线样本。"
                applyCurrentFilter()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchStocks(input: String) {
        keyword = input.trim()
        if (keyword.isBlank()) {
            applyCurrentFilter()
            return
        }

        val searchCodes = if (keyword.matches(Regex("\\d{6}"))) {
            listOf(keyword)
        } else {
            StockSearchIndex.searchCodes(keyword)
        }

        if (searchCodes.isNotEmpty()) {
            viewModelScope.launch {
                _isLoading.value = true
                when (val quoteResult = MarketDataRepository.getQuoteResult(searchCodes)) {
                    is MarketDataResult.Success -> {
                        val quotes = quoteResult.data
                        if (quotes.isNotEmpty()) {
                            val enriched = attachMovingAverages(quotes)
                            val enrichedQuotes = enriched.stocks
                            mergeSearchResults(enrichedQuotes)
                            val maCount = enrichedQuotes.count { it.hasMovingAverageData() }
                            val sourceStatus = "行情源：${quoteResult.source}；均线源：${enriched.source ?: "暂无可用 K 线"} $maCount/${enrichedQuotes.size}。"
                            baseDataStatus = if (keyword.matches(Regex("\\d{6}"))) {
                                "已按股票代码查询多源 quote；$sourceStatus"
                            } else {
                                "已按关键词“$keyword”在本机股票索引匹配 ${searchCodes.size} 只，并查询多源 quote；$sourceStatus"
                            }
                        } else {
                            baseDataStatus = if (keyword.matches(Regex("\\d{6}"))) {
                                "未能查询到代码 $keyword 的多源 quote，请确认代码或稍后刷新。"
                            } else {
                                "本机索引匹配到 ${searchCodes.size} 只样本，但多源 quote 暂无可用结果，当前不展示离线搜索结果。"
                            }
                        }
                    }
                    is MarketDataResult.Failure -> {
                        baseDataStatus = "${quoteResult.message}，当前不展示离线搜索结果。"
                        _error.value = "搜索行情请求失败，请稍后重试。"
                    }
                }
                applyCurrentFilter()
                _isLoading.value = false
            }
        } else {
            baseDataStatus = "未在本机股票索引中匹配“$keyword”。可输入 6 位股票代码，或搜索名称、拼音简写。"
            applyCurrentFilter()
        }
    }

    fun filterByCategory(category: String): Boolean {
        if (StockFilterPolicy.isVipFilter(category) && _isVipActive.value != true) {
            _error.value = "$category 需要选股 VIP 权限，当前仅展示基础筛选。"
            return false
        }

        criteria = criteria.copy(
            sortMode = category,
            industry = category.takeIf { it in industryFilters }
        )
        viewModelScope.launch {
            LocalStateRepository.saveStockFilter(criteria)
        }
        _criteriaState.value = criteria
        applyCurrentFilter()
        return true
    }

    fun applyAdvancedFilter(
        minChangePercent: Double?,
        minVolume: Long?,
        minTurnover: Double?,
        maxPe: Double?,
        maxPb: Double?,
        minMarketCap: Double?
    ) {
        criteria = criteria.copy(
            minChangePercent = minChangePercent,
            minVolume = minVolume,
            minTurnover = minTurnover,
            maxPe = maxPe,
            maxPb = maxPb,
            minMarketCap = minMarketCap
        )
        viewModelScope.launch {
            LocalStateRepository.saveStockFilter(criteria)
        }
        _criteriaState.value = criteria
        applyCurrentFilter()
    }

    fun clearFilters() {
        criteria = StockFilterCriteria()
        viewModelScope.launch {
            LocalStateRepository.saveStockFilter(criteria)
        }
        _criteriaState.value = criteria
        applyCurrentFilter()
    }

    fun getCurrentCriteria(): StockFilterCriteria {
        return criteria
    }

    fun refresh() {
        loadStocks()
    }

    fun toggleWatchlist(stock: StockInfo) {
        viewModelScope.launch {
            val isWatched = stock.code in watchlistCodeSet
            if (isWatched) {
                LocalStateRepository.removeWatchlistStock(stock.code)
                watchlistCodeSet = watchlistCodeSet - stock.code
                _error.value = "${stock.name} 已移出自选池"
            } else {
                LocalStateRepository.addWatchlistStock(stock)
                watchlistCodeSet = watchlistCodeSet + stock.code
                _error.value = "${stock.name} 已加入自选池"
            }
            _watchlistCodes.value = watchlistCodeSet
            applyCurrentFilter()
        }
    }

    fun refreshVipState() {
        viewModelScope.launch {
            _isVipActive.value = LocalStateRepository.isStockVipActive()
        }
    }

    fun applyVipFilterAfterReturn(category: String) {
        viewModelScope.launch {
            _isVipActive.value = LocalStateRepository.isStockVipActive()
            if (_isVipActive.value == true) {
                filterByCategory(category)
            } else {
                _error.value = "$category 需要选股 VIP 权限，当前仅展示基础筛选。"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun refreshWatchlistState() {
        watchlistCodeSet = LocalStateRepository.getWatchlistCodes().toSet()
        _watchlistCodes.value = watchlistCodeSet
    }

    private fun applyCurrentFilter() {
        val result = StockFilterPolicy.filter(
            stocks = allStocks,
            keyword = keyword,
            criteria = criteria,
            watchlistCodes = watchlistCodeSet,
            hasStockVip = _isVipActive.value == true
        )
        val filtered = result.stocks
        val selectedSortMode = criteria.sortMode
        val effectiveSortMode = result.effectiveSortMode

        _dataStatus.value = when {
            result.watchlistEmpty -> {
                "自选池为空。可在股票卡片右上角点星标加入，自选记录会保存在本机。"
            }
            effectiveSortMode == StockFilterPolicy.WATCHLIST_FILTER -> {
                "$baseDataStatus\n自选池已保存 ${watchlistCodeSet.size} 只，当前条件可展示 ${filtered.size} 只；仅作本机跟踪和研究参考。"
            }
            result.vipBlocked -> {
                "$selectedSortMode 需要选股 VIP 权限，当前仅展示基础筛选。"
            }
            result.sourceRequired -> {
                "$effectiveSortMode 需要接入专门授权数据源，当前不展示模拟名单。"
            }
            effectiveSortMode == "高级多因子(VIP)" -> {
                "$baseDataStatus\n当前条件：PE 1-35、PB 0.1-6，并按涨跌幅和估值排序；仅基于当前行情池可用字段。"
            }
            effectiveSortMode == "每日精选(VIP)" -> {
                "$baseDataStatus\n当前条件：按样本成交额、涨跌幅和估值生成本机观察池，不代表全市场精选。"
            }
            result.movingAverageUnavailable -> {
                "均线筛选需要历史 K 线数据源；当前历史 K 线暂不可用，不展示模拟均线结果。"
            }
            effectiveSortMode == "均线强势" -> {
                "$baseDataStatus\n当前条件：现价 >= MA5 >= MA10 >= MA20，仅作数据观察。"
            }
            else -> baseDataStatus
        }
        _stocks.value = filtered
    }

    private suspend fun attachMovingAverages(stocks: List<StockInfo>): MovingAverageAttachResult {
        val averageResult = MarketDataRepository.getMovingAveragesResult(stocks.map { it.code })
        val movingAverages = averageResult.getOrNull().orEmpty()
        val enrichedStocks = stocks.map { stock ->
            movingAverages[stock.code]?.let { averages ->
                stock.copy(
                    ma5 = averages.ma5,
                    ma10 = averages.ma10,
                    ma20 = averages.ma20
                )
            } ?: stock
        }
        return MovingAverageAttachResult(
            stocks = enrichedStocks,
            source = averageResult.sourceOrNull(),
            warnings = when (averageResult) {
                is MarketDataResult.Success -> averageResult.warnings
                is MarketDataResult.Failure -> listOf(averageResult.message)
            }
        )
    }

    private fun mergeSearchResults(stocks: List<StockInfo>) {
        val searchCodes = stocks.map { it.code }.toSet()
        allStocks = stocks + allStocks.filterNot { it.code in searchCodes }
    }

    private fun getFallbackData(): List<StockInfo> {
        return listOf(
            StockInfo("600519", "贵州茅台", 1680.50, 1.25, 28500, 21000.0, 28.5, 8.2, industry = "消费", turnover = 210.0),
            StockInfo("000858", "五粮液", 145.80, -0.85, 52000, 5600.0, 22.3, 4.5, industry = "消费", turnover = 56.0),
            StockInfo("300750", "宁德时代", 185.60, 2.15, 89000, 8200.0, 18.6, 3.8, industry = "新能源", turnover = 82.0),
            StockInfo("002594", "比亚迪", 245.30, 0.95, 67000, 7100.0, 25.4, 5.2, industry = "新能源", turnover = 71.0),
            StockInfo("600036", "招商银行", 32.15, -1.20, 125000, 8100.0, 5.8, 0.85, industry = "金融", turnover = 81.0),
            StockInfo("300059", "东方财富", 15.20, 1.10, 180000, 2600.0, 30.2, 4.0, industry = "科技", turnover = 44.0),
            StockInfo("300760", "迈瑞医疗", 285.00, 0.35, 24000, 3500.0, 29.5, 7.1, industry = "医药", turnover = 38.0)
        )
    }

    private fun buildDataSourceStatus(
        quoteSource: String,
        maSource: String?,
        maCount: Int,
        total: Int,
        warnings: List<String>
    ): String {
        val warningText = warnings.distinct().take(2).joinToString("；")
        val base = "行情来自 $quoteSource，筛选基于当前股票池；均线来自 ${maSource ?: "暂无可用 K 线"} $maCount/$total。"
        return if (warningText.isBlank()) base else "$base\n数据源提示：$warningText。"
    }

    private data class MovingAverageAttachResult(
        val stocks: List<StockInfo>,
        val source: String?,
        val warnings: List<String>
    )
}
