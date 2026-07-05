package io.github.leonarddon.quanttrading.data

import androidx.room.withTransaction
import io.github.leonarddon.quanttrading.model.Post
import io.github.leonarddon.quanttrading.model.PostComment
import io.github.leonarddon.quanttrading.model.PortfolioHolding
import io.github.leonarddon.quanttrading.model.PriceAlert
import io.github.leonarddon.quanttrading.model.PriceAlertDirection
import io.github.leonarddon.quanttrading.model.PriceAlertTrigger
import io.github.leonarddon.quanttrading.model.Strategy
import io.github.leonarddon.quanttrading.model.ReviewData
import io.github.leonarddon.quanttrading.model.ReviewSnapshot
import io.github.leonarddon.quanttrading.model.StockFilterCriteria
import io.github.leonarddon.quanttrading.model.StockInfo
import io.github.leonarddon.quanttrading.model.StockPriceAlertPolicy
import java.util.Locale
import java.util.UUID

data class CachedStockQuoteSnapshot(
    val stocks: List<StockInfo>,
    val fetchedAt: Long,
    val originalSources: List<String>
)

data class AccountDeletionResult(
    val success: Boolean,
    val message: String
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

    suspend fun isLoggedIn(): Boolean {
        return getUserState().isLoggedIn
    }

    suspend fun register(displayName: String, phone: String, password: String): UserStateEntity {
        val now = System.currentTimeMillis()
        val current = getUserState()
        val updated = current.copy(
            displayName = displayName.ifBlank { "本机用户" },
            phone = phone,
            passwordHash = PasswordHasher.hash(password),
            isLoggedIn = true,
            accountStatus = "本机账号已保存",
            createdAt = if (current.createdAt == 0L) now else current.createdAt,
            lastLoginAt = now
        )
        db.userStateDao().save(updated)
        return updated
    }

    suspend fun login(phone: String, password: String): Boolean {
        val state = getUserState()
        val matched = state.phone == phone &&
            state.passwordHash?.let { PasswordHasher.verify(password, it) } == true
        if (matched) {
            db.userStateDao().save(
                state.copy(
                    isLoggedIn = true,
                    lastLoginAt = System.currentTimeMillis(),
                    accountStatus = "本机账号已登录"
                )
            )
        }
        return matched
    }

    suspend fun logout() {
        val state = getUserState()
        db.userStateDao().save(
            state.copy(
                isLoggedIn = false,
                accountStatus = "本机账号已退出"
            )
        )
    }

    suspend fun deleteAccount(): AccountDeletionResult {
        clearLocalUserData("本机账号和研究资料已删除")
        return AccountDeletionResult(
            success = true,
            message = "本机账号和研究资料已删除"
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): UserStateEntity {
        val state = getUserState().copy(notificationsEnabled = enabled)
        db.userStateDao().save(state)
        return state
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

    suspend fun addPost(title: String, content: String, category: String) {
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

    suspend fun getPriceAlert(code: String): PriceAlert? {
        return db.priceAlertDao().get(code)?.toPriceAlert()
    }

    suspend fun savePriceAlert(
        stock: StockInfo,
        targetPrice: Double,
        direction: PriceAlertDirection
    ) {
        val now = System.currentTimeMillis()
        val existing = db.priceAlertDao().get(stock.code)
        db.priceAlertDao().save(
            PriceAlertEntity(
                code = stock.code,
                name = stock.name,
                targetPrice = targetPrice,
                direction = direction.storageValue,
                enabled = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastTriggeredAt = existing?.lastTriggeredAt ?: 0L
            )
        )
    }

    suspend fun deletePriceAlert(code: String) {
        db.priceAlertDao().delete(code)
    }

    suspend fun consumeTriggeredPriceAlerts(
        stocks: List<StockInfo>,
        now: Long = System.currentTimeMillis()
    ): List<PriceAlertTrigger> {
        if (stocks.isEmpty()) return emptyList()
        val stocksByCode = stocks.associateBy { it.code }
        val alerts = db.priceAlertDao().getEnabledByCodes(stocksByCode.keys.toList())
        if (alerts.isEmpty()) return emptyList()

        return alerts.mapNotNull { entity ->
            val stock = stocksByCode[entity.code] ?: return@mapNotNull null
            val alert = entity.toPriceAlert().copy(name = stock.name)
            val evaluation = StockPriceAlertPolicy.evaluate(alert, stock.price)
            val recentlyTriggered = now - entity.lastTriggeredAt < PRICE_ALERT_COOLDOWN_MILLIS
            if (!evaluation.triggered || recentlyTriggered) {
                return@mapNotNull null
            }

            db.priceAlertDao().save(entity.copy(name = stock.name, updatedAt = now, lastTriggeredAt = now))
            PriceAlertTrigger(
                code = stock.code,
                name = stock.name,
                currentPrice = stock.price,
                targetPrice = alert.targetPrice,
                direction = alert.direction,
                statusText = evaluation.statusText
            )
        }
    }

    suspend fun getPortfolioHoldings(): List<PortfolioHolding> {
        return db.portfolioHoldingDao().getAll().map { it.toHolding() }
    }

    suspend fun getPortfolioHoldingCodes(): List<String> {
        return db.portfolioHoldingDao().getCodes()
    }

    suspend fun savePortfolioHolding(
        code: String,
        name: String,
        costPrice: Double,
        quantity: Double,
        note: String = ""
    ) {
        val now = System.currentTimeMillis()
        val existing = db.portfolioHoldingDao().getAll().firstOrNull { it.code == code }
        db.portfolioHoldingDao().save(
            PortfolioHoldingEntity(
                code = code,
                name = name.ifBlank { code },
                costPrice = costPrice,
                quantity = quantity,
                note = note,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun deletePortfolioHolding(code: String) {
        db.portfolioHoldingDao().delete(code)
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
            category = category
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

    private fun PortfolioHoldingEntity.toHolding(): PortfolioHolding {
        return PortfolioHolding(
            code = code,
            name = name,
            costPrice = costPrice,
            quantity = quantity,
            note = note,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun PriceAlertEntity.toPriceAlert(): PriceAlert {
        return PriceAlert(
            code = code,
            name = name,
            targetPrice = targetPrice,
            direction = PriceAlertDirection.fromStorage(direction),
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastTriggeredAt = lastTriggeredAt
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
            formula = formula,
            tags = tagsCsv.split(",").filter { it.isNotBlank() }
        )
    }

    private fun ReviewData.toSnapshotEntity(createdAt: Long): ReviewSnapshotEntity {
        val marketReport = marketAnalysisReport
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
            marketScore = marketReport?.score ?: 0,
            marketGrade = marketReport?.grade.orEmpty(),
            marketRegime = marketReport?.regime.orEmpty(),
            marketSummary = marketReport?.let {
                listOf(
                    it.breadthText,
                    it.turnoverText,
                    it.sectorText,
                    it.watchlistText,
                    it.indexAlignmentText,
                    it.qualityText
                )
                    .filter { text -> text.isNotBlank() }
                    .joinToString(" ")
            }.orEmpty(),
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
            marketScore = marketScore,
            marketGrade = marketGrade,
            marketRegime = marketRegime,
            marketSummary = marketSummary,
            createdAt = createdAt
        )
    }

    private suspend fun clearLocalUserData(statusMessage: String) {
        db.withTransaction {
            db.postCommentDao().clear()
            db.postDao().clear()
            db.stockFilterDao().clear()
            db.stockWatchlistDao().clear()
            db.priceAlertDao().clear()
            db.portfolioHoldingDao().clear()
            db.stockQuoteCacheDao().clear()
            db.strategyDao().clear()
            db.reviewSnapshotDao().clear()
            db.userStateDao().clear()
            db.userStateDao().save(
                UserStateEntity(
                    accountStatus = statusMessage,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun formatSignedPercent(value: Double): String {
        return "${if (value >= 0) "+" else ""}${String.format(Locale.CHINA, "%.2f", value)}%"
    }

    private const val QUOTE_CACHE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val PRICE_ALERT_COOLDOWN_MILLIS = 6L * 60L * 60L * 1000L
}
