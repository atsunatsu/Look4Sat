package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class UpdateRepository(
    private val remoteSource: IRemoteSource
) : IUpdateRepository {

    override suspend fun getLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        val result = remoteSource.getNetworkStream(LATEST_RELEASE_URL)
        val stream = result.stream ?: return@withContext null
        try {
            val json = stream.bufferedReader().use { it.readText() }
            parseRelease(json)
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

    private fun parseRelease(json: String): LatestRelease? {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name")
        if (tag.isBlank()) return null
        val assets = obj.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "").ifBlank { null }
                    if (apkUrl != null) break
                }
            }
        }
        return LatestRelease(
            versionTag = tag,
            title = obj.optString("name", ""),
            body = obj.optString("body", ""),
            apkUrl = apkUrl
        )
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/atsunatsu/Look4Sat/releases/latest"
    }
}
