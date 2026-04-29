package com.tianxian.quant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrationFromEmptyVersion1CreatesCurrentTables() {
        createLegacyDatabase(version = 1).close()

        val database = openMigratedDatabase()
        try {
            val tables = database.openHelper.readableDatabase.tableNames()

            assertTrue("user_state should exist", "user_state" in tables)
            assertTrue("post_comments should exist", "post_comments" in tables)
            assertTrue("review_snapshots should exist", "review_snapshots" in tables)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFromLegacyVersion7PreservesLocalStateAndBackfillsVipTiers() {
        createLegacyDatabase(version = 7).use { database ->
            database.execSQL(
                "CREATE TABLE `user_state` (" +
                    "`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `isLoggedIn` INTEGER NOT NULL, " +
                    "`isVip` INTEGER NOT NULL, `vipExpireTime` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `lastLoginAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            database.execSQL(
                "INSERT INTO `user_state` (`id`, `displayName`, `isLoggedIn`, `isVip`, " +
                    "`vipExpireTime`, `createdAt`, `lastLoginAt`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(LOCAL_USER_ID, "本机测试用户", 1, 1, 1_800_000_000_000L, 100L, 200L)
            )
        }

        val database = openMigratedDatabase()
        try {
            database.openHelper.readableDatabase.query(
                "SELECT displayName, isLoggedIn, isVip, vipExpireTime, stockVipExpireTime, " +
                    "quantVipExpireTime FROM user_state WHERE id = ?",
                arrayOf(LOCAL_USER_ID)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("本机测试用户", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(1_800_000_000_000L, cursor.getLong(3))
                assertEquals(1_800_000_000_000L, cursor.getLong(4))
                assertEquals(1_800_000_000_000L, cursor.getLong(5))
            }
        } finally {
            database.close()
        }
    }

    private fun createLegacyDatabase(version: Int): SQLiteDatabase {
        context.deleteDatabase(TEST_DB)
        return context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null).apply {
            setVersion(version)
        }
    }

    private fun openMigratedDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .build()

    private fun SupportSQLiteDatabase.tableNames(): Set<String> {
        val tables = mutableSetOf<String>()
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }
        return tables
    }

    private companion object {
        const val TEST_DB = "tianxian_quant_migration_test.db"
    }
}
