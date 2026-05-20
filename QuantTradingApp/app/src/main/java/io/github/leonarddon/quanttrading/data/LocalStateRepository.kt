package io.github.leonarddon.quanttrading.data

import androidx.room.withTransaction
import io.github.leonarddon.quanttrading.BuildConfig
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
import io.github.leonarddon.quanttrading.model.VipExpiryPolicy
import io.github.leonarddon.quanttrading.model.VipExpiryState
import io.github.leonarddon.quanttrading.model.VipTier
import io.github.leonarddon.quanttrading.network.BackendAccountSync
import io.github.leonarddon.quanttrading.network.BackendEntitlementResponse
import io.github.leonarddon.quanttrading.network.BackendOrderStatusResponse
import io.github.leonarddon.quanttrading.network.QuantTradingBackendRepository
import io.github.leonarddon.quanttrading.payment.PaymentChannel
import java.security.MessageDigest
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
        if (BuildConfig.DEMO_VIP_ENABLED) {
            return ensureDemoVipState(existing)
        }
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
        return isActive(state.stockVipExpireTime) ||
            isWithinBackendGrace(state, state.stockVipExpireTime) ||
            isLegacyVipActive(state)
    }

    suspend fun isQuantVipActive(): Boolean {
        val state = getUserState()
        return isActive(state.quantVipExpireTime) ||
            isWithinBackendGrace(state, state.quantVipExpireTime) ||
            isLegacyVipActive(state)
    }

    suspend fun isLoggedIn(): Boolean {
        return getUserState().isLoggedIn
    }

    private suspend fun ensureDemoVipState(existing: UserStateEntity?): UserStateEntity {
        if (existing != null && existing.isLoggedIn && hasAnyVip(existing) && existing.isDemoVipUser()) {
            seedDemoSubscriptionOrder(existing)
            return existing
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + DEMO_VIP_DURATION_MILLIS
        val updated = (existing ?: UserStateEntity()).copy(
            displayName = "演示VIP用户",
            phone = "13800000000",
            passwordHash = null,
            isLoggedIn = true,
            isVip = true,
            vipExpireTime = expiresAt,
            stockVipExpireTime = expiresAt,
            quantVipExpireTime = expiresAt,
            backendAccessToken = null,
            backendRefreshToken = null,
            backendTokenExpiresAt = 0L,
            backendGraceUntil = 0L,
            backendSyncStatus = DEMO_VIP_STATUS,
            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
            lastLoginAt = now
        )
        db.userStateDao().save(updated)
        seedDemoSubscriptionOrder(updated)
        return updated
    }

    private suspend fun seedDemoSubscriptionOrder(state: UserStateEntity) {
        if (db.subscriptionOrderDao().getRecent(1).isNotEmpty()) return
        val now = System.currentTimeMillis()
        db.subscriptionOrderDao().save(
            localPaidOrderEntity(
                tier = VipTier.FULL,
                days = DEMO_VIP_DURATION_DAYS,
                channel = PaymentChannel.WECHAT,
                state = state,
                paidAt = now
            ).copy(
                source = "local_demo",
                note = "本地演示支付完成：全功能 VIP 已生效"
            )
        )
    }

    suspend fun register(displayName: String, phone: String, password: String): UserStateEntity {
        val now = System.currentTimeMillis()
        val current = getUserState()
        val backendSync = QuantTradingBackendRepository.register(displayName, phone, password)
        val localState = current.copy(
            displayName = displayName.ifBlank { "本机用户" },
            phone = phone,
            passwordHash = if (backendSync.success) null else hashPassword(phone, password),
            isLoggedIn = true,
            createdAt = if (current.createdAt == 0L) now else current.createdAt,
            lastLoginAt = now
        )
        val updated = applyBackendSync(localState, backendSync)
        db.userStateDao().save(updated)
        return updated
    }

    suspend fun login(phone: String, password: String): Boolean {
        val state = getUserState()
        val backendSync = QuantTradingBackendRepository.login(phone, password)
        if (backendSync.success) {
            val updated = applyBackendSync(
                state.copy(
                    phone = phone,
                    passwordHash = null,
                    isLoggedIn = true,
                    lastLoginAt = System.currentTimeMillis()
                ),
                backendSync
            )
            db.userStateDao().save(updated)
            return true
        }

        val matched = state.phone == phone && state.passwordHash == hashPassword(phone, password)
        if (matched) {
            db.userStateDao().save(
                state.copy(
                    isLoggedIn = true,
                    lastLoginAt = System.currentTimeMillis(),
                    backendSyncStatus = backendSync.message
                )
            )
        } else if (backendSync.enabled) {
            db.userStateDao().save(state.copy(backendSyncStatus = backendSync.message))
        }
        return matched
    }

    suspend fun logout() {
        val state = getUserState()
        db.userStateDao().save(
            state.copy(
                isLoggedIn = false,
                backendAccessToken = null,
                backendRefreshToken = null,
                backendTokenExpiresAt = 0L,
                backendGraceUntil = 0L,
                backendSyncStatus = if (QuantTradingBackendRepository.isEnabled) {
                    "已退出服务端登录"
                } else {
                    state.backendSyncStatus
                }
            )
        )
    }

    suspend fun deleteAccount(): AccountDeletionResult {
        val state = refreshBackendSessionIfNeeded(getUserState())
        val backendSync = if (QuantTradingBackendRepository.isEnabled && !state.backendAccessToken.isNullOrBlank()) {
            QuantTradingBackendRepository.deleteAccount(state.backendAccessToken)
        } else {
            BackendAccountSync.disabled()
        }
        if (backendSync.enabled && !backendSync.success) {
            db.userStateDao().save(state.copy(backendSyncStatus = backendSync.message))
            return AccountDeletionResult(false, backendSync.message)
        }

        clearLocalUserData(
            backendSync.message.takeIf { backendSync.enabled }
                ?: "本机账号和研究资料已删除"
        )
        return AccountDeletionResult(
            success = true,
            message = if (backendSync.enabled) {
                "服务端账号和本机资料已删除"
            } else {
                "本机账号和研究资料已删除"
            }
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): UserStateEntity {
        val state = getUserState().copy(notificationsEnabled = enabled)
        db.userStateDao().save(state)
        return state
    }

    suspend fun refreshBackendEntitlements(): UserStateEntity {
        val state = refreshBackendSessionIfNeeded(getUserState())
        if (!QuantTradingBackendRepository.isEnabled || !state.isLoggedIn || state.backendAccessToken.isNullOrBlank()) {
            return state
        }
        val backendSync = QuantTradingBackendRepository.fetchEntitlements(state.backendAccessToken)
        val updated = applyBackendSync(state, backendSync)
        db.userStateDao().save(updated)
        return updated
    }

    suspend fun getSubscriptionOrders(limit: Int = 10): List<SubscriptionOrderEntity> {
        return db.subscriptionOrderDao().getRecent(limit)
    }

    suspend fun refreshSubscriptionOrders(limit: Int = 10): List<SubscriptionOrderEntity> {
        val state = refreshBackendSessionIfNeeded(getUserState())
        if (!QuantTradingBackendRepository.isEnabled || !state.isLoggedIn || state.backendAccessToken.isNullOrBlank()) {
            return getSubscriptionOrders(limit)
        }
        val sync = QuantTradingBackendRepository.fetchOrders(state.backendAccessToken)
        if (sync.success) {
            db.subscriptionOrderDao().saveAll(sync.orders.map { it.toEntity(source = "backend") })
            return getSubscriptionOrders(limit)
        }
        if (sync.enabled) {
            db.userStateDao().save(state.copy(backendSyncStatus = sync.message))
        }
        return getSubscriptionOrders(limit)
    }

    suspend fun activateVip(tier: VipTier, days: Int, channel: PaymentChannel? = null): UserStateEntity {
        val state = refreshBackendSessionIfNeeded(getUserState())
        val backendSync = if (channel != null) {
            QuantTradingBackendRepository.activateSandboxSubscription(
                accessToken = state.backendAccessToken,
                tier = tier,
                durationDays = days,
                channel = channel
            )
        } else {
            BackendAccountSync.disabled()
        }
        if (backendSync.success) {
            backendSync.order?.let { db.subscriptionOrderDao().save(it.toEntity(source = "backend")) }
            val updated = applyBackendSync(state, backendSync)
            db.userStateDao().save(updated)
            return updated
        }
        backendSync.order?.let { db.subscriptionOrderDao().save(it.toEntity(source = "backend")) }
        if (BuildConfig.REQUIRE_BACKEND_PAYMENT_SYNC && channel != null && QuantTradingBackendRepository.isEnabled) {
            val updated = state.copy(backendSyncStatus = backendSync.message)
            db.userStateDao().save(updated)
            return updated
        }
        if (!BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION) {
            val updated = state.copy(backendSyncStatus = backendSync.message)
            db.userStateDao().save(updated)
            return updated
        }

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
            quantVipExpireTime = expiryState.quantVipExpireTime,
            backendSyncStatus = if (backendSync.enabled) backendSync.message else state.backendSyncStatus
        )
        db.userStateDao().save(updated)
        if (channel != null) {
            db.subscriptionOrderDao().save(
                localPaidOrderEntity(
                    tier = tier,
                    days = days,
                    channel = channel,
                    state = updated,
                    paidAt = now
                )
            )
        }
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

    private suspend fun refreshBackendSessionIfNeeded(state: UserStateEntity): UserStateEntity {
        if (!QuantTradingBackendRepository.isEnabled || !state.isLoggedIn) return state
        if (state.backendAccessToken.isNullOrBlank()) return state
        if (state.backendTokenExpiresAt > System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MILLIS) return state

        val sync = QuantTradingBackendRepository.refreshSession(state.backendRefreshToken)
        val updated = applyBackendSync(state, sync)
        db.userStateDao().save(updated)
        return updated
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
            db.subscriptionOrderDao().clear()
            db.strategyDao().clear()
            db.reviewSnapshotDao().clear()
            db.userStateDao().clear()
            db.userStateDao().save(
                UserStateEntity(
                    backendSyncStatus = statusMessage,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun applyBackendSync(state: UserStateEntity, sync: BackendAccountSync): UserStateEntity {
        var updated = state.copy(backendSyncStatus = sync.message)
        val auth = sync.auth
        if (sync.success && auth != null) {
            updated = updated.copy(
                serverUserId = auth.userId,
                backendAccessToken = auth.accessToken,
                backendRefreshToken = auth.refreshToken,
                backendTokenExpiresAt = auth.expiresAt
            )
        }
        val entitlement = sync.entitlement
        if (sync.success && entitlement != null) {
            updated = applyBackendEntitlement(updated, entitlement)
        }
        return updated
    }

    private fun applyBackendEntitlement(
        state: UserStateEntity,
        entitlement: BackendEntitlementResponse
    ): UserStateEntity {
        val latestExpiry = maxOf(entitlement.stockVipExpireTime, entitlement.quantVipExpireTime)
        return state.copy(
            isVip = maxOf(latestExpiry, entitlement.graceUntil) > System.currentTimeMillis(),
            vipExpireTime = latestExpiry,
            stockVipExpireTime = entitlement.stockVipExpireTime,
            quantVipExpireTime = entitlement.quantVipExpireTime,
            backendGraceUntil = entitlement.graceUntil
        )
    }

    private fun BackendOrderStatusResponse.toEntity(source: String): SubscriptionOrderEntity {
        return SubscriptionOrderEntity(
            orderId = orderId,
            tier = tier,
            durationDays = durationDays,
            amountCents = amountCents,
            currency = currency,
            channel = channel,
            status = status,
            createdAt = createdAt,
            paidAt = paidAt,
            stockVipExpireTime = entitlement?.stockVipExpireTime ?: 0L,
            quantVipExpireTime = entitlement?.quantVipExpireTime ?: 0L,
            source = source,
            note = "服务端订单状态：$status",
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun localPaidOrderEntity(
        tier: VipTier,
        days: Int,
        channel: PaymentChannel,
        state: UserStateEntity,
        paidAt: Long
    ): SubscriptionOrderEntity {
        return SubscriptionOrderEntity(
            orderId = "local-${UUID.randomUUID()}",
            tier = tier.name,
            durationDays = days,
            amountCents = priceCents(tier, days),
            currency = "CNY",
            channel = channel.name,
            status = "PAID",
            createdAt = paidAt,
            paidAt = paidAt,
            stockVipExpireTime = state.stockVipExpireTime,
            quantVipExpireTime = state.quantVipExpireTime,
            source = "local_debug",
            note = "Debug 本机模拟支付记录",
            updatedAt = paidAt
        )
    }

    private fun priceCents(tier: VipTier, days: Int): Int {
        return when (tier) {
            VipTier.STOCK -> when {
                days >= 360 -> 58_800
                days >= 90 -> 16_800
                else -> 6_800
            }
            VipTier.QUANT -> 16_800
            VipTier.FULL -> 58_800
        }
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
            isWithinBackendGrace(state, maxOf(state.stockVipExpireTime, state.quantVipExpireTime)) ||
            isLegacyVipActive(state)
    }

    private fun isLegacyVipActive(state: UserStateEntity): Boolean {
        val hasTieredState = state.stockVipExpireTime > 0L || state.quantVipExpireTime > 0L
        return !hasTieredState && state.isVip && isActive(state.vipExpireTime)
    }

    private fun isActive(expireTime: Long): Boolean {
        return expireTime > System.currentTimeMillis()
    }

    private fun UserStateEntity.isDemoVipUser(): Boolean {
        return displayName == "演示VIP用户" && backendSyncStatus == DEMO_VIP_STATUS
    }

    private fun isWithinBackendGrace(state: UserStateEntity, tierExpireTime: Long): Boolean {
        return tierExpireTime > 0L && isActive(state.backendGraceUntil)
    }

    private const val DEMO_VIP_DURATION_DAYS = 366
    private const val DEMO_VIP_DURATION_MILLIS = DEMO_VIP_DURATION_DAYS * 24L * 60L * 60L * 1000L
    private const val DEMO_VIP_STATUS = "本机演示账号：全功能 VIP 已开通"
    private const val QUOTE_CACHE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val PRICE_ALERT_COOLDOWN_MILLIS = 6L * 60L * 60L * 1000L
    private const val TOKEN_REFRESH_SKEW_MILLIS = 60L * 1000L
}
