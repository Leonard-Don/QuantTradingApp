package com.tianxian.quant.data

import com.tianxian.quant.model.Post
import com.tianxian.quant.model.PostComment
import com.tianxian.quant.model.Strategy
import com.tianxian.quant.model.ReviewData
import com.tianxian.quant.model.ReviewSnapshot
import com.tianxian.quant.model.StockFilterCriteria
import com.tianxian.quant.model.StockInfo
import com.tianxian.quant.model.VipExpiryPolicy
import com.tianxian.quant.model.VipExpiryState
import com.tianxian.quant.model.VipTier
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class CachedStockQuoteSnapshot(
    val stocks: List<StockInfo>,
    val fetchedAt: Long,
    val originalSources: List<String>
)

object LocalStateRepository {
    private val db get() = AppDatabase.instance

    suspend fun getUserState(): UserStateEntity {
        val existing = db.userStateDao().get()
        if (existing != null) return existing
        val created = UserStateEntity()
        db.userStateDao().save(created)
        return created
    }

    suspend fun isVipActive(): Boolean {
        val state = getUserState()
        return hasAnyVip(state)
    }

    suspend fun isStockVipActive(): Boolean {
        val state = getUserState()
        return isActive(state.stockVipExpireTime) || isLegacyVipActive(state)
    }

    suspend fun isQuantVipActive(): Boolean {
        val state = getUserState()
        return isActive(state.quantVipExpireTime) || isLegacyVipActive(state)
    }

    suspend fun isLoggedIn(): Boolean {
        return getUserState().isLoggedIn
    }

    suspend fun register(displayName: String, phone: String, password: String): UserStateEntity {
        val now = System.currentTimeMillis()
        val current = getUserState()
        val updated = current.copy(
            displayName = displayName.ifBlank { "本机用户" },
            phone = phone,
            passwordHash = hashPassword(phone, password),
            isLoggedIn = true,
            createdAt = if (current.createdAt == 0L) now else current.createdAt,
            lastLoginAt = now
        )
        db.userStateDao().save(updated)
        return updated
    }

    suspend fun login(phone: String, password: String): Boolean {
        val state = getUserState()
        val matched = state.phone == phone && state.passwordHash == hashPassword(phone, password)
        if (matched) {
            db.userStateDao().save(state.copy(isLoggedIn = true, lastLoginAt = System.currentTimeMillis()))
        }
        return matched
    }

    suspend fun logout() {
        val state = getUserState()
        db.userStateDao().save(state.copy(isLoggedIn = false))
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): UserStateEntity {
        val state = getUserState().copy(notificationsEnabled = enabled)
        db.userStateDao().save(state)
        return state
    }

    suspend fun activateVip(tier: VipTier, days: Int): UserStateEntity {
        val state = getUserState()
        val now = System.currentTimeMillis()
        val expiryState = VipExpiryPolicy.extend(
            tier = tier,
            days = days,
            now = now,
            current = VipExpiryState(
                vipExpireTime = state.vipExpireTime,
                stockVipExpireTime = state.stockVipExpireTime,
                quantVipExpireTime = state.quantVipExpireTime
            )
        )

        val updated = state.copy(
            isVip = expiryState.vipExpireTime > now,
            vipExpireTime = expiryState.vipExpireTime,
            stockVipExpireTime = expiryState.stockVipExpireTime,
            quantVipExpireTime = expiryState.quantVipExpireTime
        )
        db.userStateDao().save(updated)
        return updated
    }

    suspend fun getPosts(seedPosts: List<Post>): List<Post> {
        val dao = db.postDao()
        if (dao.count() == 0) {
            dao.saveAll(seedPosts.mapIndexed { index, post ->
                post.toEntity(System.currentTimeMillis() - index * 60_000L)
            })
        }
        return dao.getAll().map { it.toPost() }
    }

    suspend fun addPost(title: String, content: String, category: String, isVip: Boolean = false) {
        val authorName = getUserState().displayName.takeIf { it.isNotBlank() } ?: "我"
        db.postDao().save(
            PostEntity(
                id = UUID.randomUUID().toString(),
                author = authorName,
                title = title,
                content = content,
                timeLabel = "刚刚",
                likes = 0,
                comments = 0,
                category = category,
                isVip = isVip,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun likePost(postId: String) {
        db.postDao().like(postId)
    }

    suspend fun getComments(postId: String): List<PostComment> {
        return db.postCommentDao().getByPost(postId).map { it.toComment() }
    }

    suspend fun addComment(postId: String, content: String) {
        val authorName = getUserState().displayName.takeIf { it.isNotBlank() } ?: "我"
        db.postCommentDao().save(
            PostCommentEntity(
                id = UUID.randomUUID().toString(),
                postId = postId,
                author = authorName,
                content = content,
                timeLabel = "刚刚",
                createdAt = System.currentTimeMillis()
            )
        )
        db.postDao().incrementComments(postId)
    }

    suspend fun getStockFilter(): StockFilterCriteria {
        return db.stockFilterDao().get()?.toCriteria() ?: StockFilterCriteria()
    }

    suspend fun saveStockFilter(criteria: StockFilterCriteria) {
        db.stockFilterDao().save(criteria.toEntity())
    }

    suspend fun getWatchlistCodes(): List<String> {
        return db.stockWatchlistDao().getCodes()
    }

    suspend fun addWatchlistStock(stock: StockInfo) {
        db.stockWatchlistDao().save(
            StockWatchlistEntity(
                code = stock.code,
                name = stock.name,
                industry = stock.industry,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeWatchlistStock(code: String) {
        db.stockWatchlistDao().delete(code)
    }

    suspend fun saveStockQuoteCache(
        stocks: List<StockInfo>,
        source: String,
        fetchedAt: Long = System.currentTimeMillis()
    ) {
        if (stocks.isEmpty()) return
        db.stockQuoteCacheDao().saveAll(stocks.map { it.toQuoteCacheEntity(source, fetchedAt) })
        db.stockQuoteCacheDao().deleteOlderThan(fetchedAt - QUOTE_CACHE_MAX_AGE_MILLIS)
    }

    suspend fun getCachedStockQuotes(
        codes: List<String>,
        now: Long = System.currentTimeMillis()
    ): CachedStockQuoteSnapshot? {
        val requestedCodes = codes.distinct()
        if (requestedCodes.isEmpty()) return null
        val minFetchedAt = now - QUOTE_CACHE_MAX_AGE_MILLIS
        val rowsByCode = db.stockQuoteCacheDao()
            .getByCodes(requestedCodes)
            .filter { it.fetchedAt >= minFetchedAt }
            .associateBy { it.code }
        if (rowsByCode.isEmpty()) return null

        val rows = requestedCodes.mapNotNull { rowsByCode[it] }
        return CachedStockQuoteSnapshot(
            stocks = rows.map { it.toStockInfo() },
            fetchedAt = rows.minOf { it.fetchedAt },
            originalSources = rows.map { it.source }.filter { it.isNotBlank() }.distinct()
        )
    }

    suspend fun getCustomStrategies(): List<Strategy> {
        return db.strategyDao().getCustomStrategies().map { it.toStrategy() }
    }

    suspend fun saveCustomStrategy(strategy: Strategy) {
        db.strategyDao().save(strategy.toEntity(System.currentTimeMillis()))
    }

    suspend fun saveReviewSnapshot(reviewData: ReviewData) {
        db.reviewSnapshotDao().save(reviewData.toSnapshotEntity(System.currentTimeMillis()))
    }

    suspend fun getReviewSnapshots(limit: Int = 20): List<ReviewSnapshot> {
        return db.reviewSnapshotDao().getRecent(limit).map { it.toSnapshot() }
    }

    private fun Post.toEntity(createdAt: Long): PostEntity {
        return PostEntity(
            id = id,
            author = author,
            title = title,
            content = content,
            timeLabel = time,
            likes = likes,
            comments = comments,
            category = category,
            isVip = isVip,
            createdAt = createdAt
        )
    }

    private fun PostEntity.toPost(): Post {
        return Post(
            id = id,
            author = author,
            avatar = "",
            title = title,
            content = content,
            time = timeLabel,
            likes = likes,
            comments = comments,
            category = category,
            isVip = isVip
        )
    }

    private fun PostCommentEntity.toComment(): PostComment {
        return PostComment(
            id = id,
            postId = postId,
            author = author,
            content = content,
            time = timeLabel,
            createdAt = createdAt
        )
    }

    private fun StockFilterEntity.toCriteria(): StockFilterCriteria {
        return StockFilterCriteria(
            sortMode = sortMode,
            industry = industry,
            minChangePercent = minChangePercent,
            minVolume = minVolume,
            minTurnover = minTurnover,
            maxPe = maxPe,
            maxPb = maxPb,
            minMarketCap = minMarketCap
        )
    }

    private fun StockFilterCriteria.toEntity(): StockFilterEntity {
        return StockFilterEntity(
            sortMode = sortMode,
            industry = industry,
            minChangePercent = minChangePercent,
            minVolume = minVolume,
            minTurnover = minTurnover,
            maxPe = maxPe,
            maxPb = maxPb,
            minMarketCap = minMarketCap
        )
    }

    private fun StockInfo.toQuoteCacheEntity(source: String, fetchedAt: Long): StockQuoteCacheEntity {
        return StockQuoteCacheEntity(
            code = code,
            name = name,
            price = price,
            changePercent = changePercent,
            volume = volume,
            marketCap = marketCap,
            pe = pe,
            pb = pb,
            industry = industry,
            turnover = turnover,
            high = high,
            low = low,
            open = open,
            yesterdayClose = yesterdayClose,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            source = source,
            fetchedAt = fetchedAt
        )
    }

    private fun StockQuoteCacheEntity.toStockInfo(): StockInfo {
        return StockInfo(
            code = code,
            name = name,
            price = price,
            changePercent = changePercent,
            volume = volume,
            marketCap = marketCap,
            pe = pe,
            pb = pb,
            industry = industry,
            turnover = turnover,
            high = high,
            low = low,
            open = open,
            yesterdayClose = yesterdayClose,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20
        )
    }

    private fun Strategy.toEntity(createdAt: Long): StrategyEntity {
        return StrategyEntity(
            id = id,
            name = name,
            description = description,
            winRate = winRate,
            maxDrawdown = maxDrawdown,
            sharpeRatio = sharpeRatio,
            annualReturn = annualReturn,
            totalTrades = totalTrades,
            profitFactor = profitFactor,
            isVip = isVip,
            formula = formula,
            tagsCsv = tags.joinToString(","),
            createdAt = createdAt
        )
    }

    private fun StrategyEntity.toStrategy(): Strategy {
        return Strategy(
            id = id,
            name = name,
            description = description,
            winRate = winRate,
            maxDrawdown = maxDrawdown,
            sharpeRatio = sharpeRatio,
            annualReturn = annualReturn,
            totalTrades = totalTrades,
            profitFactor = profitFactor,
            isVip = isVip,
            formula = formula,
            tags = tagsCsv.split(",").filter { it.isNotBlank() }
        )
    }

    private fun ReviewData.toSnapshotEntity(createdAt: Long): ReviewSnapshotEntity {
        return ReviewSnapshotEntity(
            date = date,
            upCount = upCount,
            downCount = downCount,
            limitUpCount = limitUpCount,
            limitDownCount = limitDownCount,
            totalAmount = totalAmount,
            sectorSummary = hotSectors.take(5).joinToString("；") {
                "${it.name} ${formatSignedPercent(it.changePercent)} 样本代表 ${it.leadingStock}"
            },
            strongStockSummary = strongStocks.take(5).joinToString("；") {
                "${it.name} ${formatSignedPercent(it.changePercent)}"
            },
            createdAt = createdAt
        )
    }

    private fun ReviewSnapshotEntity.toSnapshot(): ReviewSnapshot {
        return ReviewSnapshot(
            date = date,
            upCount = upCount,
            downCount = downCount,
            limitUpCount = limitUpCount,
            limitDownCount = limitDownCount,
            totalAmount = totalAmount,
            sectorSummary = sectorSummary,
            strongStockSummary = strongStockSummary,
            createdAt = createdAt
        )
    }

    private fun formatSignedPercent(value: Double): String {
        return "${if (value >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", value)}%"
    }

    private fun hashPassword(phone: String, password: String): String {
        val input = "$phone:$password".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }

    private fun hasAnyVip(state: UserStateEntity): Boolean {
        return isActive(state.stockVipExpireTime) ||
            isActive(state.quantVipExpireTime) ||
            isLegacyVipActive(state)
    }

    private fun isLegacyVipActive(state: UserStateEntity): Boolean {
        val hasTieredState = state.stockVipExpireTime > 0L || state.quantVipExpireTime > 0L
        return !hasTieredState && state.isVip && isActive(state.vipExpireTime)
    }

    private fun isActive(expireTime: Long): Boolean {
        return expireTime > System.currentTimeMillis()
    }

    private const val QUOTE_CACHE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
}
