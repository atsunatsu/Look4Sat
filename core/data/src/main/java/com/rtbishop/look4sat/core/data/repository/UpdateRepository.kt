package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UpdateRepository(
    private val remoteSource: IRemoteSource
) : IUpdateRepository {

    override suspend fun getLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        // Use the GitHub releases web page instead of the REST API endpoint:
        // the API endpoint is rate-limited to 60 requests/hour per IP, which is
        // quickly exhausted on shared egress IPs (e.g. VPN proxies), causing 403
        // failures. The web endpoint redirects to the latest tag with no such limit.
        val result = remoteSource.getNetworkStream(LATEST_RELEASE_URL)
        val stream = result.stream ?: return@withContext null
        try {
            val html = stream.bufferedReader().use { it.readText() }
            parseRelease(html)
        } catch (e: Exception) {
            println("UpdateRepository parse failure: $e")
            null
        }
    }

    override suspend fun downloadApk(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        val result = remoteSource.getNetworkStream(url)
        val stream = result.stream ?: return@withContext false
        try {
            dest.outputStream().use { out -> stream.use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            println("UpdateRepository download failure: $e")
            false
        }
    }

    private fun parseRelease(html: String): LatestRelease? {
        // The redirect target is the latest release's tag page. Extract the tag
        // from the og:url meta tag ("…/releases/tag/v4.4.6-ba7opf.8") — stable and
        // unambiguous. The <title> also holds the release name, but that is free
        // text and cannot be used to build the asset URL.
        val ogUrl = Regex("<meta\\s+property=\"og:url\"\\s+content=\"([^\"]*)\"").find(html)
            ?.groupValues?.get(1) ?: return null
        val tag = Regex("/releases/tag/(v[0-9][0-9A-Za-z.\\-]*)$").find(ogUrl)
            ?.groupValues?.get(1) ?: return null
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.substringBefore("·")?.removePrefix("Release")?.trim() ?: tag
        // The release APK asset follows the fixed naming scheme used by the build:
        // Look4Sat-<tag without leading v>-release.apk
        val apkUrl = "$DOWNLOAD_BASE_URL/$tag/Look4Sat-${tag.removePrefix("v")}-release.apk"
        return LatestRelease(
            versionTag = tag,
            title = title,
            body = "",
            apkUrl = apkUrl
        )
    }

    private companion object {
        // Web pages are used instead of api.github.com to avoid the 60 req/hour
        // anonymous rate limit (see getLatestRelease above).
        const val LATEST_RELEASE_URL = "https://github.com/atsunatsu/Look4Sat/releases/latest"
        const val DOWNLOAD_BASE_URL = "https://github.com/atsunatsu/Look4Sat/releases/download"
    }
}
