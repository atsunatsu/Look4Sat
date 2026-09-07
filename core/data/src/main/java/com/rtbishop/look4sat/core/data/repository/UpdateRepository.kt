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
        // When GitHub is unreachable (common on mainland-China networks without a
        // proxy), fall back to community GitHub accelerator mirrors that proxy the
        // same page; each mirror resolves the identical tag and asset URLs.
        for (baseUrl in LATEST_RELEASE_URLS) {
            val result = remoteSource.getNetworkStream(baseUrl)
            val stream = result.stream ?: continue
            val parsed = try {
                parseRelease(stream.bufferedReader().use { it.readText() }, baseUrl)
            } catch (e: Exception) {
                println("UpdateRepository parse failure: $e")
                null
            }
            if (parsed != null) return@withContext parsed
        }
        null
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

    private fun parseRelease(html: String, sourceUrl: String): LatestRelease? {
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
        // Release notes live in the page's markdown-body section (first occurrence
        // is the release description). Strip HTML tags for plain-text display.
        val raw = Regex("<div[^>]*class=\"[^\"]*markdown-body[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: ""
        val body = raw.replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim()
        // The release APK asset naming: Look4Sat-<tag without leading v>.apk
        // (since v4.4.6-ba7opf.9.9 the releases are uploaded with the bare
        // versioned filename, no "-release" suffix). When the page was fetched
        // through an accelerator mirror (https://<mirror>/https://github.com/...),
        // download through the same mirror — raw github.com is unreachable on the
        // networks that needed the mirror in the first place.
        val mirrorPrefix = sourceUrl.substringBefore("https://github.com")
        val apkName = "Look4Sat-${tag.removePrefix("v")}.apk"
        val apkUrl = if (mirrorPrefix.isEmpty()) {
            "$DOWNLOAD_BASE_URL/$tag/$apkName"
        } else {
            "${mirrorPrefix}https://github.com/atsunatsu/Look4Sat/releases/download/$tag/$apkName"
        }
        return LatestRelease(
            versionTag = tag,
            title = title,
            body = body,
            apkUrl = apkUrl
        )
    }

    private companion object {
        // Web pages are used instead of api.github.com to avoid the 60 req/hour
        // anonymous rate limit (see getLatestRelease above). The GitHub URL is
        // tried first; if it is unreachable (no proxy on mainland networks) the
        // accelerator mirrors are tried in order. Download URLs always point at
        // the mirrors too — raw github.com release downloads are equally blocked
        // without a proxy, so a mirror-resolved tag must be downloaded via the
        // same mirror.
        val LATEST_RELEASE_URLS = listOf(
            "https://github.com/atsunatsu/Look4Sat/releases/latest",
            "https://ghfast.top/https://github.com/atsunatsu/Look4Sat/releases/latest",
            "https://gh.llkk.cc/https://github.com/atsunatsu/Look4Sat/releases/latest",
            "https://github.moeyy.xyz/https://github.com/atsunatsu/Look4Sat/releases/latest"
        )
        const val DOWNLOAD_BASE_URL = "https://github.com/atsunatsu/Look4Sat/releases/download"
    }
}
