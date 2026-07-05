package io.github.leonarddon.quanttrading.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leonarddon.quanttrading.data.LocalStateRepository
import io.github.leonarddon.quanttrading.model.Post
import io.github.leonarddon.quanttrading.model.PostComment
import kotlinx.coroutines.launch

class CommunityViewModel : ViewModel() {

    private val _posts = MutableLiveData<List<Post>>()
    val posts: LiveData<List<Post>> = _posts

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectedCategory = MutableLiveData<String>()
    val selectedCategory: LiveData<String> = _selectedCategory

    private val _communityStats = MutableLiveData(CommunityStats())
    val communityStats: LiveData<CommunityStats> = _communityStats

    private val categories = listOf("全部", FEATURED_CATEGORY, "投资逻辑", "技术方法", "量化研究", "复盘笔记", "新手交流")

    private var allPosts = emptyList<Post>()

    init {
        loadPosts()
    }

    fun loadPosts() {
        viewModelScope.launch {
            _isLoading.value = true
            allPosts = LocalStateRepository.getPosts(getSeedPosts())
            applyCategoryFilter()
            _isLoading.value = false
        }
    }

    fun filterByCategory(category: String) {
        _selectedCategory.value = category
        applyCategoryFilter()
    }

    fun getCategories(): List<String> = categories

    fun likePost(postId: String) {
        viewModelScope.launch {
            LocalStateRepository.likePost(postId)
            loadPosts()
        }
    }

    fun addPost(title: String, content: String, category: String) {
        viewModelScope.launch {
            LocalStateRepository.addPost(title, content, category)
            loadPosts()
        }
    }

    suspend fun getComments(postId: String): List<PostComment> {
        return LocalStateRepository.getComments(postId)
    }

    fun addComment(postId: String, content: String) {
        viewModelScope.launch {
            LocalStateRepository.addComment(postId, content)
            loadPosts()
        }
    }

    private fun applyCategoryFilter() {
        val category = _selectedCategory.value ?: "全部"
        val filtered = if (category == "全部") {
            allPosts
        } else {
            allPosts.filter { it.category == category }
        }
        _posts.value = filtered
        updateCommunityStats(filtered)
    }

    private fun updateCommunityStats(visiblePosts: List<Post>) {
        val category = _selectedCategory.value ?: "全部"
        val featuredCount = allPosts.count { it.category == FEATURED_CATEGORY }
        _communityStats.value = CommunityStats(
            selectedCategory = category,
            totalCount = allPosts.size,
            visibleCount = visiblePosts.size,
            featuredCount = featuredCount
        )
    }

    private fun getSeedPosts(): List<Post> {
        return listOf(
            Post(
                "1",
                "趋势观察者",
                "",
                "专题样本池复盘框架",
                "记录科技板块成交额和指数强弱变化的拆解模板。内容只用于复盘训练，重点是观察量能、波动和行业扩散度，不提供具体交易指令。",
                "2小时前",
                128,
                32,
                FEATURED_CATEGORY
            ),
            Post(
                "2",
                "价值研究员",
                "",
                "消费板块估值分位笔记",
                "整理了消费板块若干样本的估值分位和现金流指标。结论只用于研究讨论，后续还需要结合财报、行业景气度和风险因素持续观察。",
                "3小时前",
                89,
                18,
                "投资逻辑"
            ),
            Post(
                "3",
                "量化练习生",
                "",
                "网格模型历史模拟模板",
                "分享一个震荡区间观察模板：固定网格间距、记录波动、记录执行偏差。历史模拟不代表未来结果，适合用来训练纪律和复盘方法。",
                "5小时前",
                256,
                45,
                "量化研究"
            ),
            Post(
                "4",
                "复盘达人",
                "",
                "市场复盘：情绪修复观察",
                "今天样本股强弱分布较前一交易日改善。复盘时可以同时记录成交额、宽基指数和行业扩散度，先记录事实，再形成研究假设。",
                "6小时前",
                167,
                28,
                "复盘笔记"
            ),
            Post(
                "5",
                "新股民",
                "",
                "如何建立自己的选股记录表",
                "刚开始学习股票研究，想请教大家如何记录行业、市值、估值和成交量指标，方便后续复盘自己的观察框架。",
                "8小时前",
                45,
                56,
                "新手交流"
            )
        )
    }

    companion object {
        const val FEATURED_CATEGORY = "专题研究"
    }

    data class CommunityStats(
        val selectedCategory: String = "全部",
        val totalCount: Int = 0,
        val visibleCount: Int = 0,
        val featuredCount: Int = 0
    )
}
