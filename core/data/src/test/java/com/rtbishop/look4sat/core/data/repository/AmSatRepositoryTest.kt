package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.model.SatStatusPage
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.InputStream

class AmSatRepositoryTest {

    @Test
    fun fetchStatusReturnsSeededCacheUntilCacheIsCleared() = runTest {
        val remoteSource = FakeAmSatRemoteSource()
        val repository = AmSatRepository(remoteSource)
        val cachedPage = SatStatusPage(
            fetchedAtUtcMs = 123L,
            statuses = listOf(SatStatus(name = "AO-7", days = emptyList())),
            reports = emptyMap()
        )
        repository.seedStatusCache(cachedPage)

        val firstPage = repository.fetchStatus()
        val secondPage = repository.fetchStatus()

        assertSame(cachedPage, firstPage)
        assertSame(cachedPage, secondPage)
        assertSame(cachedPage, repository.getCachedStatus())
        assertEquals(0, remoteSource.catalogRequests)
        assertEquals(0, remoteSource.reportRequests)

        repository.clearStatusCache()
        assertNull(repository.getCachedStatus())
        repository.fetchStatus()

        assertEquals(1, remoteSource.catalogRequests)
        assertEquals(1, remoteSource.reportRequests)
    }

    @Test
    fun forceRefreshBypassesCachedPage() = runTest {
        val remoteSource = FakeAmSatRemoteSource()
        val repository = AmSatRepository(remoteSource)
        repository.seedStatusCache(
            SatStatusPage(
                fetchedAtUtcMs = 123L,
                statuses = listOf(SatStatus(name = "AO-7", days = emptyList())),
                reports = emptyMap()
            )
        )

        repository.fetchStatus(forceRefresh = true)

        assertEquals(1, remoteSource.catalogRequests)
        assertEquals(1, remoteSource.reportRequests)
    }
}

private fun AmSatRepository.seedStatusCache(page: SatStatusPage) {
    val cacheField = AmSatRepository::class.java.getDeclaredField("statusCache")
    cacheField.isAccessible = true
    cacheField.set(this, page)
}

private class FakeAmSatRemoteSource : IRemoteSource {
    var catalogRequests = 0
    var reportRequests = 0

    override suspend fun getFileStream(uri: String): InputStream? = null

    override suspend fun getNetworkStream(url: String): InputStream? = null

    override suspend fun getAmSatCatalog(): String? {
        catalogRequests += 1
        return """{"data":[{"name":"AO-7"}]}"""
    }

    override suspend fun getAmSatReports(hours: Int, limit: Int): String? {
        reportRequests += 1
        return """{"data":[]}"""
    }

    override suspend fun submitAmSatReport(payloadJson: String): Pair<Int, String>? = null
}
