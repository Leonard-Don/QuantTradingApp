package com.tianxian.quant.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.model.DailyResearchBriefPolicy
import com.tianxian.quant.model.MarketOverview
import com.tianxian.quant.model.PortfolioStressPolicy
import com.tianxian.quant.model.ReviewData
import com.tianxian.quant.model.ReviewSnapshot
import com.tianxian.quant.model.SectorInfo
import com.tianxian.quant.model.StockSearchIndex
import com.tianxian.quant.model.WatchlistHealthPolicy
import com.tianxian.quant.network.MarketDataResult
import com.tianxian.quant.network.MarketDataRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReviewViewModel : ViewModel() {

    private val _reviewData = MutableLiveData<ReviewData>()
    val reviewData: LiveData<ReviewData> = _reviewData

    private val _marketOverview = MutableLiveData<List<MarketOverview>>()
    val marketOverview: LiveData<List<MarketOverview>> = _marketOverview

    private val _reviewStatus = MutableLiveData<String>()
    val reviewStatus: LiveData<String> = _reviewStatus

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectedTab = MutableLiveData<Int>()
    val selectedTab: LiveData<Int> = _selectedTab

    private val _reviewHistory = MutableLiveData<List<ReviewSnapshot>>()
    val reviewHistory: LiveData<List<ReviewSnapshot>> = _reviewHistory

    private val _isVipActive = MutableLiveData(false)
    val isVipActive: LiveData<Boolean> = _isVipActive

    init {
        _selectedTab.value = 0
        loadAllData()
    }

    fun loadAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            refreshVipState()

            // 加载大盘指数
            try {
                _marketOverview.value = when (val result = MarketDataRepository.getMarketOverviewResult()) {
                    is MarketDataResult.Success -> result.data
                    is MarketDataResult.Failure -> emptyList()
                }
            } catch (e: Exception) {
                _marketOverview.value = emptyList()
            }

            loadReviewData()
            loadReviewHistory()

            _isLoading.value = false
        }
    }

    private suspend fun loadReviewData() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val watchlistCodes = LocalStateRepository.getWatchlistCodes()
        val sampleCodes = (StockSearchIndex.defaultCodes(REVIEW_SAMPLE_LIMIT) + watchlistCodes).distinct()
        val quoteResult = MarketDataRepository.getQuoteResult(sampleCodes)
        val liveStocks = quoteResult.getOrNull().orEmpty()
        val usingFallback = quoteResult is MarketDataResult.Failure || liveStocks.isEmpty()
        val stocks = if (usingFallback) getFallbackStocks() else liveStocks
        val watchlistCodeSet = watchlistCodes.toSet()
        val watchlistStocks = stocks.filter { it.code in watchlistCodeSet }
        val sectors = stocks
            .groupBy { it.industry }
            .filterKeys { it.isNotBlank() }
            .map { (industry, items) ->
                val leader = items.maxByOrNull { it.changePercent } ?: items.first()
                SectorInfo(
                    name = industry,
                    code = industry,
                    changePercent = items.map { it.changePercent }.average(),
                    leadingStock = leader.name,
                    leadingStockCode = leader.code,
                    capitalFlow = items.sumOf { it.turnover }
                )
            }
            .sortedByDescending { it.changePercent }

        val upCount = stocks.count { it.changePercent > 0 }
        val downCount = stocks.count { it.changePercent < 0 }
        val totalAmount = stocks.sumOf { it.turnover }
        val watchlistHealthReport = WatchlistHealthPolicy.evaluate(watchlistStocks)
        val portfolioStressReport = PortfolioStressPolicy.evaluate(
            stocks = watchlistStocks,
            marketUpCount = upCount,
            marketDownCount = downCount
        )
        val dailyBriefReport = DailyResearchBriefPolicy.evaluate(
            date = today,
            upCount = upCount,
            downCount = downCount,
            totalAmount = totalAmount,
            hotSectors = sectors,
            strongStocks = stocks.sortedByDescending { it.changePercent }.take(5),
            watchlistStocks = watchlistStocks,
            watchlistHealthReport = watchlistHealthReport,
            portfolioStressReport = portfolioStressReport
        )

        val reviewData = ReviewData(
            date = today,
            upCount = upCount,
            downCount = downCount,
            limitUpCount = stocks.count { it.changePercent >= 9.8 },
            limitDownCount = stocks.count { it.changePercent <= -9.8 },
            totalAmount = totalAmount,
            hotSectors = sectors,
            strongStocks = stocks.sortedByDescending { it.changePercent }.take(5),
            sampleStocks = stocks,
            watchlistStocks = watchlistStocks,
            watchlistHealthReport = watchlistHealthReport,
            portfolioStressReport = portfolioStressReport,
            dailyResearchBriefReport = dailyBriefReport
        )
        _reviewData.value = reviewData
        _reviewStatus.value = if (usingFallback) {
            val reason = (quoteResult as? MarketDataResult.Failure)?.message ?: "多源 quote 暂无可用行情"
            "离线降级：$reason，当前复盘使用 ${stocks.size} 只本地样本占位，仅用于界面查看；不会写入历史回溯。"
        } else {
            val source = quoteResult.sourceOrNull() ?: "多源 quote"
            val warnings = (quoteResult as? MarketDataResult.Success)?.warnings.orEmpty().distinct().take(2)
            val warningText = warnings.joinToString("；").takeIf { it.isNotBlank() }?.let { "\n数据源提示：$it。" }.orEmpty()
            "行情来自 $source；复盘统计基于当前 ${stocks.size} 只行情池样本，自选池跟踪 ${watchlistStocks.size}/${watchlistCodes.size} 只，不代表全市场覆盖。已保存为本机历史快照。$warningText"
        }
        if (!usingFallback) {
            LocalStateRepository.saveReviewSnapshot(reviewData)
        }
        loadReviewHistory()
    }

    fun selectTab(position: Int) {
        _selectedTab.value = position
    }

    fun refresh() {
        loadAllData()
    }

    fun refreshVipState() {
        viewModelScope.launch {
            _isVipActive.value = LocalStateRepository.isVipActive()
        }
    }

    private suspend fun loadReviewHistory() {
        _reviewHistory.value = LocalStateRepository.getReviewSnapshots()
    }

    private fun getFallbackStocks(): List<com.tianxian.quant.model.StockInfo> {
        return listOf(
            com.tianxian.quant.model.StockInfo("600519", "贵州茅台", 1680.50, 1.25, 28500, 21000.0, 28.5, 8.2, industry = "消费", turnover = 210.0),
            com.tianxian.quant.model.StockInfo("000858", "五粮液", 145.80, -0.85, 52000, 5600.0, 22.3, 4.5, industry = "消费", turnover = 56.0),
            com.tianxian.quant.model.StockInfo("300750", "宁德时代", 185.60, 2.15, 89000, 8200.0, 18.6, 3.8, industry = "新能源", turnover = 82.0),
            com.tianxian.quant.model.StockInfo("300059", "东方财富", 15.20, 1.10, 180000, 2600.0, 30.2, 4.0, industry = "科技", turnover = 44.0),
            com.tianxian.quant.model.StockInfo("600036", "招商银行", 32.15, -1.20, 125000, 8100.0, 5.8, 0.85, industry = "金融", turnover = 81.0)
        )
    }

    private companion object {
        const val REVIEW_SAMPLE_LIMIT = 48
    }
}
