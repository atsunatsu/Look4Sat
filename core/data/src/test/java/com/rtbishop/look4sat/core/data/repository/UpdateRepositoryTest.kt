package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateRepositoryTest {
    private val tag = "v4.4.8-ba7opf.20.3"
    private val release = LatestRelease(tag, "Update", "Notes", "unused")

    @Test fun automaticCheckFallsBackToMirrorAfterGithubError() = runBlocking {
        val remote = FakeRemoteSource { url ->
            if (url.startsWith("https://ghfast.top/")) 200 to releasePage().toByteArray()
            else 503 to null
        }
        val seen = mutableListOf<UpdateSource>()
        val result = UpdateRepository(remote).getLatestRelease(UpdateSource.AUTO) { seen += it }
        assertNotNull(result)
        assertEquals(tag, result!!.versionTag)
        assertEquals(listOf(UpdateSource.GITHUB, UpdateSource.GHFAST), seen)
        assertTrue(result.apkUrl!!.startsWith("https://ghfast.top/"))
    }

    @Test fun manualSourceDoesNotSilentlySwitchMirrors() = runBlocking {
        val remote = FakeRemoteSource { 503 to null }
        val seen = mutableListOf<UpdateSource>()
        val result = UpdateRepository(remote).getLatestRelease(UpdateSource.GITHUB) { seen += it }
        assertNull(result)
        assertEquals(listOf(UpdateSource.GITHUB), seen)
        assertEquals(1, remote.requests.size)
    }

    @Test fun automaticDownloadRetriesAfterInvalidProxyResponse() = runBlocking {
        val apk = smallApk()
        val remote = FakeRemoteSource { url ->
            if (url.startsWith("https://ghfast.top/")) 200 to apk
            else 200 to "<html>Proxy error</html>".toByteArray()
        }
        val dest = File.createTempFile("look4sat-update-", ".apk")
        try {
            val seen = mutableListOf<UpdateSource>()
            val ok = UpdateRepository(remote).downloadApk(
                release, dest, UpdateSource.AUTO, UpdateSource.GITHUB,
                { seen += it }, { true }
            )
            assertTrue(ok)
            assertEquals(listOf(UpdateSource.GITHUB, UpdateSource.GHFAST), seen)
            assertTrue(dest.readBytes().contentEquals(apk))
            assertFalse(File(dest.parentFile, "${dest.nameWithoutExtension}.download.apk").exists())
        } finally {
            dest.delete()
        }
    }

    @Test fun rejectedCandidateNeverBecomesAnInstallableApk() = runBlocking {
        val remote = FakeRemoteSource { 200 to smallApk() }
        val dest = File.createTempFile("look4sat-rejected-", ".apk")
        dest.delete()
        try {
            val ok = UpdateRepository(remote).downloadApk(
                release, dest, UpdateSource.GITHUB, null, {}, { false }
            )
            assertFalse(ok)
            assertFalse(dest.exists())
            assertFalse(File(dest.parentFile, "${dest.nameWithoutExtension}.download.apk").exists())
            assertEquals(1, remote.requests.size)
        } finally {
            dest.delete()
        }
    }

    private fun releasePage() = """
        <html><head><meta property="og:url" content="https://github.com/atsunatsu/Look4Sat/releases/tag/$tag">
        <title>Release $tag</title></head><body></body></html>
    """.trimIndent()

    private fun smallApk(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            listOf("AndroidManifest.xml", "classes.dex").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private class FakeRemoteSource(
        private val respond: (String) -> Pair<Int, ByteArray?>
    ) : IRemoteSource {
        val requests = mutableListOf<String>()
        override suspend fun getNetworkStream(url: String): NetworkResult = getNetworkStream(url, 0)
        override suspend fun getNetworkStream(url: String, timeoutMs: Long): NetworkResult {
            requests += url
            val (code, data) = respond(url)
            return NetworkResult(code, data?.let { ByteArrayInputStream(it) })
        }
        override suspend fun getFileStream(uri: String): InputStream? = null
        override suspend fun getAmSatCatalog(): String? = null
        override suspend fun getAmSatReports(hours: Int, limit: Int): String? = null
        override suspend fun submitAmSatReport(payloadJson: String): Pair<Int, String>? = null
    }
}
