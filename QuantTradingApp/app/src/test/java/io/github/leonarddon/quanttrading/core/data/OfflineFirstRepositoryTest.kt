package io.github.leonarddon.quanttrading.core.data

import io.github.leonarddon.quanttrading.core.model.DataOrigin
import io.github.leonarddon.quanttrading.model.TimestampSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFirstRepositoryTest {

    @Test
    fun `live payload is loaded with provenance and cached`() = runBlocking {
        val writes = mutableListOf<CacheEntry<String>>()
        val loader = OfflineFirstLoader<String, String>(
            fetchFromNetwork = {
                NetworkFetchResult.Live(
                    data = "fresh",
                    timestampMillis = 1_000L,
                    sourceLabel = "primary",
                )
            },
            readCache = { null },
            writeCache = { _, data, source, fetchedAt ->
                writes += CacheEntry(data = data, fetchedAt = fetchedAt, originalSources = listOf(source))
            },
        )

        val result = loader.load("600519", CachePolicy(maxCacheAgeMillis = 500L, now = { 1_200L }))

        assertTrue(result is RepositoryResponse.Loaded)
        assertEquals("fresh", result.dataOrNull())
        assertEquals(DataOrigin.Live, result.provenance.origin)
        assertEquals(200L, result.provenance.ageMillis)
        assertEquals("primary", result.provenance.sourceLabel)
        assertEquals(1, writes.size)
    }

    @Test
    fun `network failure falls back to usable cache with warning`() = runBlocking {
        val loader = OfflineFirstLoader<String, String>(
            fetchFromNetwork = { NetworkFetchResult.Failure("network down") },
            readCache = {
                CacheEntry(data = "cached", fetchedAt = 1_000L, originalSources = listOf("primary"))
            },
        )

        val result = loader.load("600519", CachePolicy(maxCacheAgeMillis = 1_000L, now = { 1_500L }))

        assertTrue(result is RepositoryResponse.Loaded)
        assertEquals("cached", result.dataOrNull())
        assertTrue(result.provenance.origin is DataOrigin.Cache)
        assertEquals(TimestampSource.LOCAL_CACHE, result.provenance.timestampSource)
        assertEquals(listOf("network down"), result.provenance.warnings)
    }

    @Test
    fun `expired cache fails instead of pretending stale data is fresh`() = runBlocking {
        val loader = OfflineFirstLoader<String, String>(
            fetchFromNetwork = { NetworkFetchResult.Failure("network down") },
            readCache = {
                CacheEntry(data = "too-old", fetchedAt = 1_000L, originalSources = listOf("primary"))
            },
        )

        val result = loader.load("600519", CachePolicy(maxCacheAgeMillis = 100L, now = { 1_500L }))

        assertTrue(result is RepositoryResponse.Failed)
        assertEquals(DataOrigin.Empty, result.provenance.origin)
        assertTrue(result.provenance.warnings.any { it.contains("缓存已过期") })
    }

    @Test
    fun `future network timestamp beyond tolerance is rejected with clock skew provenance`() = runBlocking {
        val loader = OfflineFirstLoader<String, String>(
            fetchFromNetwork = {
                NetworkFetchResult.Live(
                    data = "future",
                    timestampMillis = 2_000L,
                    sourceLabel = "primary",
                )
            },
            readCache = { null },
        )

        val result = loader.load(
            "600519",
            CachePolicy(
                maxCacheAgeMillis = 1_000L,
                clockSkewToleranceMillis = 100L,
                now = { 1_000L },
            ),
        )

        assertTrue(result is RepositoryResponse.Failed)
        assertTrue(result.provenance.hasClockSkew)
        assertEquals(1_000L, result.provenance.clockSkewMillis)
        assertFalse(result.isLoaded)
    }
}
