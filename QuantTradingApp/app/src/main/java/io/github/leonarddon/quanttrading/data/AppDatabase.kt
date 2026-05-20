package io.github.leonarddon.quanttrading.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.leonarddon.quanttrading.MyApp

@Entity(tableName = "user_state")
data class UserStateEntity(
    @PrimaryKey val id: String = LOCAL_USER_ID,
    val displayName: String = "本机用户",
    val phone: String? = null,
    val passwordHash: String? = null,
    val isLoggedIn: Boolean = false,
    val isVip: Boolean = false,
    val vipExpireTime: Long = 0L,
    val stockVipExpireTime: Long = 0L,
    val quantVipExpireTime: Long = 0L,
    val notificationsEnabled: Boolean = false,
    val serverUserId: String? = null,
    val backendAccessToken: String? = null,
    val backendRefreshToken: String? = null,
    val backendTokenExpiresAt: Long = 0L,
    val backendGraceUntil: Long = 0L,
    val backendSyncStatus: String = "服务端同步未启用，当前使用本机演示账号",
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = 0L
)

@Entity(tableName = "subscription_orders")
data class SubscriptionOrderEntity(
    @PrimaryKey val orderId: String,
    val tier: String,
    val durationDays: Int,
    val amountCents: Int,
    val currency: String,
    val channel: String,
    val status: String,
    val createdAt: Long,
    val paidAt: Long?,
    val stockVipExpireTime: Long,
    val quantVipExpireTime: Long,
    val source: String,
    val note: String,
    val updatedAt: Long
)

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val id: String,
    val author: String,
    val title: String,
    val content: String,
    val timeLabel: String,
    val likes: Int,
    val comments: Int,
    val category: String,
    val isVip: Boolean,
    val createdAt: Long
)

@Entity(tableName = "post_comments")
data class PostCommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val author: String,
    val content: String,
    val timeLabel: String,
    val createdAt: Long
)

@Entity(tableName = "stock_filter")
data class StockFilterEntity(
    @PrimaryKey val id: String = DEFAULT_FILTER_ID,
    val sortMode: String = "全部",
    val industry: String? = null,
    val minChangePercent: Double? = null,
    val minVolume: Long? = null,
    val minTurnover: Double? = null,
    val maxPe: Double? = null,
    val maxPb: Double? = null,
    val minMarketCap: Double? = null
)

@Entity(tableName = "stock_watchlist")
data class StockWatchlistEntity(
    @PrimaryKey val code: String,
    val name: String,
    val industry: String,
    val createdAt: Long
)

@Entity(tableName = "price_alerts")
data class PriceAlertEntity(
    @PrimaryKey val code: String,
    val name: String,
    val targetPrice: Double,
    val direction: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastTriggeredAt: Long
)

@Entity(tableName = "portfolio_holdings")
data class PortfolioHoldingEntity(
    @PrimaryKey val code: String,
    val name: String,
    val costPrice: Double,
    val quantity: Double,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "stock_quote_cache")
data class StockQuoteCacheEntity(
    @PrimaryKey val code: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val volume: Long,
    val marketCap: Double,
    val pe: Double,
    val pb: Double,
    val industry: String,
    val turnover: Double,
    val high: Double,
    val low: Double,
    val open: Double,
    val yesterdayClose: Double,
    val ma5: Double,
    val ma10: Double,
    val ma20: Double,
    val source: String,
    val fetchedAt: Long
)

@Entity(tableName = "custom_strategies")
data class StrategyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val winRate: Double,
    val maxDrawdown: Double,
    val sharpeRatio: Double,
    val annualReturn: Double,
    val totalTrades: Int,
    val profitFactor: Double,
    val isVip: Boolean,
    val formula: String,
    val tagsCsv: String,
    val createdAt: Long
)

@Entity(tableName = "review_snapshots")
data class ReviewSnapshotEntity(
    @PrimaryKey val date: String,
    val upCount: Int,
    val downCount: Int,
    val limitUpCount: Int,
    val limitDownCount: Int,
    val totalAmount: Double,
    val sectorSummary: String,
    val strongStockSummary: String,
    val marketScore: Int,
    val marketGrade: String,
    val marketRegime: String,
    val marketSummary: String,
    val createdAt: Long
)

@Dao
interface UserStateDao {
    @Query("SELECT * FROM user_state WHERE id = :id LIMIT 1")
    suspend fun get(id: String = LOCAL_USER_ID): UserStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: UserStateEntity)

    @Query("DELETE FROM user_state")
    suspend fun clear()
}

@Dao
interface SubscriptionOrderDao {
    @Query("SELECT * FROM subscription_orders ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 10): List<SubscriptionOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(order: SubscriptionOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(orders: List<SubscriptionOrderEntity>)

    @Query("DELETE FROM subscription_orders")
    suspend fun clear()
}

@Dao
interface PostDao {
    @Query("SELECT * FROM posts ORDER BY createdAt DESC")
    suspend fun getAll(): List<PostEntity>

    @Query("SELECT COUNT(*) FROM posts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(posts: List<PostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(post: PostEntity)

    @Query("UPDATE posts SET likes = likes + 1 WHERE id = :postId")
    suspend fun like(postId: String)

    @Query("UPDATE posts SET comments = comments + 1 WHERE id = :postId")
    suspend fun incrementComments(postId: String)

    @Query("DELETE FROM posts")
    suspend fun clear()
}

@Dao
interface PostCommentDao {
    @Query("SELECT * FROM post_comments WHERE postId = :postId ORDER BY createdAt DESC")
    suspend fun getByPost(postId: String): List<PostCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(comment: PostCommentEntity)

    @Query("DELETE FROM post_comments")
    suspend fun clear()
}

@Dao
interface StockFilterDao {
    @Query("SELECT * FROM stock_filter WHERE id = :id LIMIT 1")
    suspend fun get(id: String = DEFAULT_FILTER_ID): StockFilterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(filter: StockFilterEntity)

    @Query("DELETE FROM stock_filter")
    suspend fun clear()
}

@Dao
interface StockWatchlistDao {
    @Query("SELECT * FROM stock_watchlist ORDER BY createdAt DESC")
    suspend fun getAll(): List<StockWatchlistEntity>

    @Query("SELECT code FROM stock_watchlist ORDER BY createdAt DESC")
    suspend fun getCodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(stock: StockWatchlistEntity)

    @Query("DELETE FROM stock_watchlist WHERE code = :code")
    suspend fun delete(code: String)

    @Query("DELETE FROM stock_watchlist")
    suspend fun clear()
}

@Dao
interface PriceAlertDao {
    @Query("SELECT * FROM price_alerts WHERE code = :code LIMIT 1")
    suspend fun get(code: String): PriceAlertEntity?

    @Query("SELECT * FROM price_alerts WHERE code IN (:codes) AND enabled = 1")
    suspend fun getEnabledByCodes(codes: List<String>): List<PriceAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(alert: PriceAlertEntity)

    @Query("DELETE FROM price_alerts WHERE code = :code")
    suspend fun delete(code: String)

    @Query("DELETE FROM price_alerts")
    suspend fun clear()
}

@Dao
interface PortfolioHoldingDao {
    @Query("SELECT * FROM portfolio_holdings ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PortfolioHoldingEntity>

    @Query("SELECT code FROM portfolio_holdings ORDER BY updatedAt DESC")
    suspend fun getCodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(holding: PortfolioHoldingEntity)

    @Query("DELETE FROM portfolio_holdings WHERE code = :code")
    suspend fun delete(code: String)

    @Query("DELETE FROM portfolio_holdings")
    suspend fun clear()
}

@Dao
interface StockQuoteCacheDao {
    @Query("SELECT * FROM stock_quote_cache WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<StockQuoteCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(stocks: List<StockQuoteCacheEntity>)

    @Query("DELETE FROM stock_quote_cache WHERE fetchedAt < :minFetchedAt")
    suspend fun deleteOlderThan(minFetchedAt: Long)

    @Query("DELETE FROM stock_quote_cache")
    suspend fun clear()
}

@Dao
interface StrategyDao {
    @Query("SELECT * FROM custom_strategies ORDER BY createdAt DESC")
    suspend fun getCustomStrategies(): List<StrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(strategy: StrategyEntity)

    @Query("DELETE FROM custom_strategies")
    suspend fun clear()
}

@Dao
interface ReviewSnapshotDao {
    @Query("SELECT * FROM review_snapshots ORDER BY date DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<ReviewSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(snapshot: ReviewSnapshotEntity)

    @Query("DELETE FROM review_snapshots")
    suspend fun clear()
}

@Database(
    entities = [
        UserStateEntity::class,
        SubscriptionOrderEntity::class,
        PostEntity::class,
        PostCommentEntity::class,
        StockFilterEntity::class,
        StockWatchlistEntity::class,
        PriceAlertEntity::class,
        PortfolioHoldingEntity::class,
        StockQuoteCacheEntity::class,
        StrategyEntity::class,
        ReviewSnapshotEntity::class
    ],
    version = 14,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userStateDao(): UserStateDao
    abstract fun subscriptionOrderDao(): SubscriptionOrderDao
    abstract fun postDao(): PostDao
    abstract fun postCommentDao(): PostCommentDao
    abstract fun stockFilterDao(): StockFilterDao
    abstract fun stockWatchlistDao(): StockWatchlistDao
    abstract fun priceAlertDao(): PriceAlertDao
    abstract fun portfolioHoldingDao(): PortfolioHoldingDao
    abstract fun stockQuoteCacheDao(): StockQuoteCacheDao
    abstract fun strategyDao(): StrategyDao
    abstract fun reviewSnapshotDao(): ReviewSnapshotDao

    companion object {
        val instance: AppDatabase by lazy {
            Room.databaseBuilder(
                MyApp.instance,
                AppDatabase::class.java,
                "quanttrading_quant.db"
            ).addMigrations(*APP_DATABASE_MIGRATIONS).build()
        }
    }
}

const val LOCAL_USER_ID = "local_user"
const val DEFAULT_FILTER_ID = "default"
private const val CURRENT_DATABASE_VERSION = 14

val APP_DATABASE_MIGRATIONS: Array<Migration> = (1 until CURRENT_DATABASE_VERSION)
    .map { startVersion ->
        object : Migration(startVersion, CURRENT_DATABASE_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateToCurrentVersion(db)
            }
        }
    }
    .toTypedArray()

private fun migrateToCurrentVersion(db: SupportSQLiteDatabase) {
    CURRENT_TABLES.forEach { table ->
        rebuildTableToCurrentSchema(db, table)
    }
}

private fun rebuildTableToCurrentSchema(db: SupportSQLiteDatabase, table: TableSpec) {
    if (!tableExists(db, table.name)) {
        db.execSQL(table.createSql(table.name))
        return
    }

    val existingColumns = columnNames(db, table.name)
    val tempName = "${table.name}_migration_new"
    db.execSQL("DROP TABLE IF EXISTS ${quoteIdentifier(tempName)}")
    db.execSQL(table.createSql(tempName))

    val insertColumns = table.columns.joinToString(", ") { quoteIdentifier(it.name) }
    val selectColumns = table.columns.joinToString(", ") { it.selectExpression(existingColumns) }
    db.execSQL(
        "INSERT OR REPLACE INTO ${quoteIdentifier(tempName)} ($insertColumns) " +
            "SELECT $selectColumns FROM ${quoteIdentifier(table.name)}"
    )

    db.execSQL("DROP TABLE ${quoteIdentifier(table.name)}")
    db.execSQL("ALTER TABLE ${quoteIdentifier(tempName)} RENAME TO ${quoteIdentifier(table.name)}")
}

private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
    db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(tableName)
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun columnNames(db: SupportSQLiteDatabase, tableName: String): Set<String> {
    val columns = mutableSetOf<String>()
    db.query("PRAGMA table_info(${quoteIdentifier(tableName)})").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            columns += cursor.getString(nameIndex)
        }
    }
    return columns
}

private data class TableSpec(
    val name: String,
    val columnsSql: String,
    val columns: List<ColumnSpec>
) {
    fun createSql(tableName: String): String =
        "CREATE TABLE IF NOT EXISTS ${quoteIdentifier(tableName)} ($columnsSql)"
}

private data class ColumnSpec(
    val name: String,
    val fallbackExpression: (Set<String>) -> String,
    val nullable: Boolean = false
) {
    fun selectExpression(existingColumns: Set<String>): String {
        if (name !in existingColumns) return fallbackExpression(existingColumns)

        val currentColumn = quoteIdentifier(name)
        return if (nullable) {
            currentColumn
        } else {
            "COALESCE($currentColumn, ${fallbackExpression(existingColumns)})"
        }
    }
}

private fun textColumn(name: String, fallback: String): ColumnSpec =
    ColumnSpec(name, { sqlString(fallback) })

private fun nullableColumn(name: String): ColumnSpec =
    ColumnSpec(name, { "NULL" }, nullable = true)

private fun intColumn(name: String, fallback: String = "0"): ColumnSpec =
    ColumnSpec(name, { fallback })

private fun realColumn(name: String, fallback: String = "0.0"): ColumnSpec =
    ColumnSpec(name, { fallback })

private fun vipExpiryColumn(name: String): ColumnSpec =
    ColumnSpec(name, { existingColumns ->
        if ("vipExpireTime" in existingColumns) {
            "COALESCE(${quoteIdentifier("vipExpireTime")}, 0)"
        } else {
            "0"
        }
    })

private fun quoteIdentifier(identifier: String): String =
    "`" + identifier.replace("`", "``") + "`"

private fun sqlString(value: String): String =
    "'" + value.replace("'", "''") + "'"

private val CURRENT_TABLES = listOf(
    TableSpec(
        name = "user_state",
        columnsSql = "`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `phone` TEXT, " +
            "`passwordHash` TEXT, `isLoggedIn` INTEGER NOT NULL, `isVip` INTEGER NOT NULL, " +
            "`vipExpireTime` INTEGER NOT NULL, `stockVipExpireTime` INTEGER NOT NULL, " +
            "`quantVipExpireTime` INTEGER NOT NULL, `notificationsEnabled` INTEGER NOT NULL, " +
            "`serverUserId` TEXT, `backendAccessToken` TEXT, `backendRefreshToken` TEXT, " +
            "`backendTokenExpiresAt` INTEGER NOT NULL, `backendGraceUntil` INTEGER NOT NULL, " +
            "`backendSyncStatus` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `lastLoginAt` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        columns = listOf(
            textColumn("id", LOCAL_USER_ID),
            textColumn("displayName", "本机用户"),
            nullableColumn("phone"),
            nullableColumn("passwordHash"),
            intColumn("isLoggedIn"),
            intColumn("isVip"),
            intColumn("vipExpireTime"),
            vipExpiryColumn("stockVipExpireTime"),
            vipExpiryColumn("quantVipExpireTime"),
            intColumn("notificationsEnabled"),
            nullableColumn("serverUserId"),
            nullableColumn("backendAccessToken"),
            nullableColumn("backendRefreshToken"),
            intColumn("backendTokenExpiresAt"),
            intColumn("backendGraceUntil"),
            textColumn("backendSyncStatus", "服务端同步未启用，当前使用本机演示账号"),
            intColumn("createdAt"),
            intColumn("lastLoginAt")
        )
    ),
    TableSpec(
        name = "subscription_orders",
        columnsSql = "`orderId` TEXT NOT NULL, `tier` TEXT NOT NULL, `durationDays` INTEGER NOT NULL, " +
            "`amountCents` INTEGER NOT NULL, `currency` TEXT NOT NULL, `channel` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `paidAt` INTEGER, " +
            "`stockVipExpireTime` INTEGER NOT NULL, `quantVipExpireTime` INTEGER NOT NULL, " +
            "`source` TEXT NOT NULL, `note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`orderId`)",
        columns = listOf(
            textColumn("orderId", ""),
            textColumn("tier", ""),
            intColumn("durationDays"),
            intColumn("amountCents"),
            textColumn("currency", "CNY"),
            textColumn("channel", ""),
            textColumn("status", "PENDING"),
            intColumn("createdAt"),
            nullableColumn("paidAt"),
            intColumn("stockVipExpireTime"),
            intColumn("quantVipExpireTime"),
            textColumn("source", "local"),
            textColumn("note", ""),
            intColumn("updatedAt")
        )
    ),
    TableSpec(
        name = "posts",
        columnsSql = "`id` TEXT NOT NULL, `author` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL, `timeLabel` TEXT NOT NULL, `likes` INTEGER NOT NULL, " +
            "`comments` INTEGER NOT NULL, `category` TEXT NOT NULL, `isVip` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        columns = listOf(
            textColumn("id", ""),
            textColumn("author", ""),
            textColumn("title", ""),
            textColumn("content", ""),
            textColumn("timeLabel", ""),
            intColumn("likes"),
            intColumn("comments"),
            textColumn("category", "全部"),
            intColumn("isVip"),
            intColumn("createdAt")
        )
    ),
    TableSpec(
        name = "post_comments",
        columnsSql = "`id` TEXT NOT NULL, `postId` TEXT NOT NULL, `author` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL, `timeLabel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`)",
        columns = listOf(
            textColumn("id", ""),
            textColumn("postId", ""),
            textColumn("author", ""),
            textColumn("content", ""),
            textColumn("timeLabel", ""),
            intColumn("createdAt")
        )
    ),
    TableSpec(
        name = "stock_filter",
        columnsSql = "`id` TEXT NOT NULL, `sortMode` TEXT NOT NULL, `industry` TEXT, " +
            "`minChangePercent` REAL, `minVolume` INTEGER, `minTurnover` REAL, `maxPe` REAL, " +
            "`maxPb` REAL, `minMarketCap` REAL, PRIMARY KEY(`id`)",
        columns = listOf(
            textColumn("id", DEFAULT_FILTER_ID),
            textColumn("sortMode", "全部"),
            nullableColumn("industry"),
            nullableColumn("minChangePercent"),
            nullableColumn("minVolume"),
            nullableColumn("minTurnover"),
            nullableColumn("maxPe"),
            nullableColumn("maxPb"),
            nullableColumn("minMarketCap")
        )
    ),
    TableSpec(
        name = "stock_watchlist",
        columnsSql = "`code` TEXT NOT NULL, `name` TEXT NOT NULL, `industry` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`code`)",
        columns = listOf(
            textColumn("code", ""),
            textColumn("name", ""),
            textColumn("industry", ""),
            intColumn("createdAt")
        )
    ),
    TableSpec(
        name = "price_alerts",
        columnsSql = "`code` TEXT NOT NULL, `name` TEXT NOT NULL, `targetPrice` REAL NOT NULL, " +
            "`direction` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER NOT NULL, PRIMARY KEY(`code`)",
        columns = listOf(
            textColumn("code", ""),
            textColumn("name", ""),
            realColumn("targetPrice"),
            textColumn("direction", "ABOVE"),
            intColumn("enabled", "1"),
            intColumn("createdAt"),
            intColumn("updatedAt"),
            intColumn("lastTriggeredAt")
        )
    ),
    TableSpec(
        name = "portfolio_holdings",
        columnsSql = "`code` TEXT NOT NULL, `name` TEXT NOT NULL, `costPrice` REAL NOT NULL, " +
            "`quantity` REAL NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`code`)",
        columns = listOf(
            textColumn("code", ""),
            textColumn("name", ""),
            realColumn("costPrice"),
            realColumn("quantity"),
            textColumn("note", ""),
            intColumn("createdAt"),
            intColumn("updatedAt")
        )
    ),
    TableSpec(
        name = "stock_quote_cache",
        columnsSql = "`code` TEXT NOT NULL, `name` TEXT NOT NULL, `price` REAL NOT NULL, " +
            "`changePercent` REAL NOT NULL, `volume` INTEGER NOT NULL, `marketCap` REAL NOT NULL, " +
            "`pe` REAL NOT NULL, `pb` REAL NOT NULL, `industry` TEXT NOT NULL, " +
            "`turnover` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, " +
            "`open` REAL NOT NULL, `yesterdayClose` REAL NOT NULL, `ma5` REAL NOT NULL, " +
            "`ma10` REAL NOT NULL, `ma20` REAL NOT NULL, `source` TEXT NOT NULL, " +
            "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`code`)",
        columns = listOf(
            textColumn("code", ""),
            textColumn("name", ""),
            realColumn("price"),
            realColumn("changePercent"),
            intColumn("volume"),
            realColumn("marketCap"),
            realColumn("pe"),
            realColumn("pb"),
            textColumn("industry", "未分类"),
            realColumn("turnover"),
            realColumn("high"),
            realColumn("low"),
            realColumn("open"),
            realColumn("yesterdayClose"),
            realColumn("ma5"),
            realColumn("ma10"),
            realColumn("ma20"),
            textColumn("source", ""),
            intColumn("fetchedAt")
        )
    ),
    TableSpec(
        name = "custom_strategies",
        columnsSql = "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
            "`winRate` REAL NOT NULL, `maxDrawdown` REAL NOT NULL, `sharpeRatio` REAL NOT NULL, " +
            "`annualReturn` REAL NOT NULL, `totalTrades` INTEGER NOT NULL, " +
            "`profitFactor` REAL NOT NULL, `isVip` INTEGER NOT NULL, `formula` TEXT NOT NULL, " +
            "`tagsCsv` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        columns = listOf(
            textColumn("id", ""),
            textColumn("name", ""),
            textColumn("description", ""),
            realColumn("winRate"),
            realColumn("maxDrawdown"),
            realColumn("sharpeRatio"),
            realColumn("annualReturn"),
            intColumn("totalTrades"),
            realColumn("profitFactor"),
            intColumn("isVip"),
            textColumn("formula", ""),
            textColumn("tagsCsv", ""),
            intColumn("createdAt")
        )
    ),
    TableSpec(
        name = "review_snapshots",
        columnsSql = "`date` TEXT NOT NULL, `upCount` INTEGER NOT NULL, `downCount` INTEGER NOT NULL, " +
            "`limitUpCount` INTEGER NOT NULL, `limitDownCount` INTEGER NOT NULL, " +
            "`totalAmount` REAL NOT NULL, `sectorSummary` TEXT NOT NULL, " +
            "`strongStockSummary` TEXT NOT NULL, `marketScore` INTEGER NOT NULL, " +
            "`marketGrade` TEXT NOT NULL, `marketRegime` TEXT NOT NULL, `marketSummary` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`date`)",
        columns = listOf(
            textColumn("date", ""),
            intColumn("upCount"),
            intColumn("downCount"),
            intColumn("limitUpCount"),
            intColumn("limitDownCount"),
            realColumn("totalAmount"),
            textColumn("sectorSummary", ""),
            textColumn("strongStockSummary", ""),
            intColumn("marketScore"),
            textColumn("marketGrade", ""),
            textColumn("marketRegime", ""),
            textColumn("marketSummary", ""),
            intColumn("createdAt")
        )
    )
)
