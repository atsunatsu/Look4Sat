/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * GitHub Releases + multiple independent community accelerators.
 *
 * A chosen endpoint is used exclusively; AUTO retries the endpoints in order.
 * Downloads are first written to a temporary APK and validated before being
 * swapped into the cache path consumed by Android's installer.
 */
class UpdateRepository(private val remoteSource: IRemoteSource) : IUpdateRepository {

    override suspend fun getLatestRelease(): LatestRelease? =
        getLatestRelease(UpdateSource.AUTO) {}

    override suspend fun getLatestRelease(
        source: UpdateSource,
        onSourceChanged: (UpdateSource) -> Unit
    ): LatestRelease? = withContext(Dispatchers.IO) {
        for (candidate in orderedSources(source)) {
            onSourceChanged(candidate)
            val url = releasePageUrl(candidate)
            try {
                val network = remoteSource.getNetworkStream(url, CHECK_TIMEOUT_MS)
                val page = network.stream?.bufferedReader()?.use { it.readText() } ?: continue
                if (network.code !in 200..299) continue
                parseRelease(page, candidate)?.let { return@withContext it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("UpdateRepository check failed for $candidate: $e")
            }
        }
        null
    }

    /** Legacy single-URL download for other callers; still rejects HTML responses. */
    override suspend fun downloadApk(url: String, dest: File): Boolean =
        downloadCandidate(url, dest) { true }

    override suspend fun downloadApk(
        release: LatestRelease,
        dest: File,
        source: UpdateSource,
        preferred: UpdateSource?,
        onSourceChanged: (UpdateSource) -> Unit,
        validate: (File) -> Boolean
    ): Boolean {
        val tag = release.versionTag
        // Do not construct download requests from arbitrary URLs or markup.
        if (!TAG_PATTERN.matches(tag)) return false
        for (candidate in orderedSources(source, preferred)) {
            onSourceChanged(candidate)
            if (downloadCandidate(downloadUrl(candidate, tag), dest, validate)) return true
        }
        return false
    }

    private suspend fun downloadCandidate(
        url: String,
        dest: File,
        validate: (File) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        // Keep the .apk suffix for PackageManager.getPackageArchiveInfo().
        val temp = File(dest.parentFile, "${dest.nameWithoutExtension}.download.apk")
        temp.delete()
        try {
            val network = remoteSource.getNetworkStream(url, DOWNLOAD_TIMEOUT_MS)
            val stream = network.stream ?: return@withContext false
            if (network.code !in 200..299) {
                stream.close()
                return@withContext false
            }
            stream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_APK_BYTES) throw IllegalStateException("APK exceeded safe size")
                        output.write(buffer, 0, count)
                    }
                }
            }
            // Reject proxy error pages, incomplete archives and a substituted package.
            if (!hasApkStructure(temp) || !validate(temp)) return@withContext false
            // A candidate is made visible to FileProvider only after all checks pass.
            if (dest.exists() && !dest.delete()) return@withContext false
            temp.renameTo(dest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("UpdateRepository download failed: $e")
            false
        } finally {
            temp.delete()
        }
    }

    private fun hasApkStructure(file: File): Boolean = try {
        file.length() > 0 && ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null
        }
    } catch (_: Exception) {
        false
    }

    private fun parseRelease(html: String, source: UpdateSource): LatestRelease? {
        // releases/latest redirects to a tag page. Do not guess the tag from its title.
        val ogUrl = Regex("""<meta\s+property=["']og:url["']\s+content=["']([^"']*)["']""")
            .find(html)?.groupValues?.get(1) ?: return null
        val tag = Regex("""/releases/tag/(v[0-9][0-9A-Za-z.-]*)""")
            .find(ogUrl)?.groupValues?.get(1) ?: return null
        if (!TAG_PATTERN.matches(tag)) return null
        val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.substringBefore("·")?.removePrefix("Release")?.trim() ?: tag
        val raw = Regex("""<div[^>]*class=["'][^"']*markdown-body[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: ""
        val body = raw.replace(Regex("""<[^>]+>"""), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").trim()
        return LatestRelease(
            versionTag = tag,
            title = title,
            body = body,
            apkUrl = downloadUrl(source, tag)
        )
    }

    private fun orderedSources(source: UpdateSource, preferred: UpdateSource? = null): List<UpdateSource> =
        if (source != UpdateSource.AUTO) listOf(source)
        else (listOfNotNull(preferred?.takeUnless { it == UpdateSource.AUTO }) + ALL_SOURCES).distinct()

    private fun releasePageUrl(source: UpdateSource): String = prefix(source) + RELEASE_PAGE
    private fun downloadUrl(source: UpdateSource, tag: String): String =
        prefix(source) + "https://github.com/atsunatsu/Look4Sat/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}.apk"

    private fun prefix(source: UpdateSource): String = when (source) {
        UpdateSource.AUTO, UpdateSource.GITHUB -> ""
        UpdateSource.GHFAST -> "https://ghfast.top/"
        UpdateSource.LLKK -> "https://gh.llkk.cc/"
        UpdateSource.MOEYY -> "https://github.moeyy.xyz/"
    }

    private companion object {
        const val RELEASE_PAGE = "https://github.com/atsunatsu/Look4Sat/releases/latest"
        const val CHECK_TIMEOUT_MS = 9_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        val ALL_SOURCES = listOf(UpdateSource.GITHUB, UpdateSource.GHFAST, UpdateSource.LLKK, UpdateSource.MOEYY)
        val TAG_PATTERN = Regex("""^v[0-9][0-9A-Za-z.-]*$""")
    }
}
