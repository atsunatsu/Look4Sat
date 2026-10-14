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

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.ReleaseEdition
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class UpdateRepository(
    private val remoteSource: IRemoteSource,
    context: Context
) : IUpdateRepository {
    private val context = context.applicationContext

    override suspend fun getLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        var metadata: LatestRelease? = null
        for (prefix in listOf("") + UPDATE_MIRRORS) {
            coroutineContext.ensureActive()
            val source = "$prefix$RELEASE_REPOSITORY/releases/latest"
            val html = readPage(source) ?: continue
            val release = parseReleasePage(html, prefix) ?: continue
            metadata = metadata ?: release
            if (release.assetUrls.isNotEmpty()) return@withContext release
            // GitHub lazy-loads the actual asset list in an include-fragment.
            val assets = readPage("$prefix$RELEASE_REPOSITORY/releases/expanded_assets/${release.versionTag}") ?: continue
            val withAssets = parseReleasePage(html + "\n" + assets, prefix)
            if (withAssets?.assetUrls?.isNotEmpty() == true) return@withContext withAssets
        }
        metadata
    }

    private suspend fun readPage(url: String): String? = try {
        remoteSource.getNetworkStream(url).stream?.bufferedReader()?.use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var read = reader.read(buffer)
            while (read >= 0) {
                coroutineContext.ensureActive()
                text.append(buffer, 0, read)
                if (text.length > 4_000_000) return@use null
                read = reader.read(buffer)
            }
            text.toString()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun downloadApk(url: String, edition: ReleaseEdition, dest: File): Boolean =
        withContext(Dispatchers.IO) {
        if (!isTrustedDownloadUrl(url)) return@withContext false
        val partial = File(dest.parentFile, "${dest.name}.part")
        try {
            val canonical = canonicalDownloadUrl(url) ?: return@withContext false
            val candidates = (listOf(url, canonical) + UPDATE_MIRRORS.map { it + canonical }).distinct()
            for (candidate in candidates) {
                coroutineContext.ensureActive()
                val stream = remoteSource.getNetworkStream(candidate).stream ?: continue
                val downloaded = try {
                    stream.use { input ->
                        partial.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var count = input.read(buffer)
                            while (count >= 0) {
                                coroutineContext.ensureActive()
                                total += count
                                if (total > MAX_APK_BYTES) throw java.io.IOException("APK exceeds size limit")
                                out.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                        }
                    }
                    validateApk(partial, edition)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!downloaded) continue
                if (dest.exists() && !dest.delete()) return@withContext false
                if (partial.renameTo(dest)) return@withContext true
                partial.copyTo(dest, overwrite = false)
                partial.delete()
                return@withContext true
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            println("UpdateRepository download failure: $e")
            false
        } finally {
            partial.delete()
        }
    }

    private fun validateApk(apk: File, edition: ReleaseEdition): Boolean {
        if (!apk.isFile || apk.length() !in MIN_APK_BYTES..MAX_APK_BYTES) return false
        val archive = packageInfo(apk.absolutePath) ?: return false
        val installed = packageInfo(context.packageName, archive = false) ?: return false
        if (archive.packageName != context.packageName) return false
        // Same versionCode is a valid install on purpose: switching editions reinstalls the
        // same version of the other APK, and Android treats that as a plain update. Only a
        // genuine downgrade (older version) is refused.
        if (versionCode(archive) < versionCode(installed)) return false
        if (!signaturesMatch(installed, archive)) return false
        if (edition == ReleaseEdition.JSUENHANCED && !containsFt4Core(apk)) return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(value: String, archive: Boolean = true): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (archive) {
            context.packageManager.getPackageArchiveInfo(value, flags)
        } else {
            context.packageManager.getPackageInfo(value, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo, includeHistory: Boolean): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (includeHistory && signingInfo.hasPastSigningCertificates()) {
                signingInfo.signingCertificateHistory
            } else {
                signingInfo.apkContentsSigners
            }
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }

    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedCurrent = certificateDigests(installed, includeHistory = false)
        val archiveCurrent = certificateDigests(archive, includeHistory = false)
        if (installedCurrent.isEmpty() || archiveCurrent.isEmpty()) return false
        if (archiveCurrent == installedCurrent) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || archive.signingInfo?.hasMultipleSigners() == true) {
            return false
        }
        val archiveHistory = certificateDigests(archive, includeHistory = true)
        return archive.signingInfo?.hasPastSigningCertificates() == true &&
            installedCurrent.all(archiveHistory::contains)
    }

    private fun containsFt4Core(apk: File): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            val packagedAbis = zip.entries().asSequence()
                .mapNotNull { entry ->
                    entry.name.takeIf {
                        it.startsWith("lib/") && it.endsWith("/liblook4sat_ft4.so")
                    }
                        ?.substringAfter("lib/")
                        ?.substringBefore('/')
                }
                .toSet()
            packagedAbis.isNotEmpty() && Build.SUPPORTED_ABIS.any(packagedAbis::contains)
        }
    }.getOrDefault(false)

    private companion object {
        const val MIN_APK_BYTES = 100_000L
        const val MAX_APK_BYTES = 250_000_000L
    }
}

internal data class ReleaseAsset(
    val name: String,
    val url: String,
    val contentType: String,
    val state: String,
    val size: Long
)

internal fun isTrustedDownloadUrl(url: String): Boolean = canonicalDownloadUrl(url) != null

internal fun canonicalDownloadUrl(url: String): String? = runCatching {
    val prefix = UPDATE_MIRRORS.firstOrNull { url.startsWith(it) }.orEmpty()
    val canonical = url.removePrefix(prefix)
    val uri = URI(canonical)
    val pathPrefix = "/atsunatsu/Look4Sat/releases/download/"
    val segments = uri.path.orEmpty().takeIf { it.startsWith(pathPrefix, true) }?.substring(pathPrefix.length)?.split('/')
    canonical.takeIf {
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) && uri.query == null && uri.fragment == null &&
            uri.normalize().path == uri.path &&
            segments?.size == 2 && segments.none { it.isBlank() || it == "." || it == ".." } &&
            segments.last().endsWith(".apk", true)
    }
}.getOrNull()
