/*
 * WavelogUploader.kt — WaveLog 队列上传调度(4.5.2)。
 *
 * 手动/周期上传共用: 逐条网格检测(用户 QTH 前 4 位 vs 台站网格前 4 位)
 * → POST /api/v2/qso → 成功移出队列。
 * 网格不一致: 返回 NeedConfirm(由 UI 弹窗「忽略并上传/取消」),
 * 确认后带 force=true 重试本批。
 */
package com.rtbishop.look4sat.core.domain.wavelog

import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import org.json.JSONObject

sealed class UploadOutcome {
    data class NeedConfirm(val stationGrid: String, val userGrid: String) : UploadOutcome()
    data class Done(
        val successCount: Int,
        val failedCount: Int,
        val message: String,
        val firstError: String = ""
    ) : UploadOutcome()
}

class WavelogUploader(
    private val settingsRepo: ISettingsRepo,
    private val queue: WavelogQueue
) {

    // 台站网格缓存(每次上传前刷新; 失败用旧值)
    private var cachedStationGrid: String? = null

    /** 上传整个队列。force=true 跳过网格确认(用户已选「忽略并上传」) */
    suspend fun uploadQueue(force: Boolean = false): UploadOutcome {
        val settings = settingsRepo.otherSettings.value
        val url = settings.wavelogUrl
        val apiKey = settings.wavelogApiKey
        val stationId = settings.wavelogStationId
        if (url.isBlank() || apiKey.isBlank() || stationId.isBlank()) {
            return UploadOutcome.Done(0, queue.all().size, "未配置 WaveLog 服务器")
        }

        // 1. 拉站点信息(拿台站网格); v1 无此端点时降级用用户 QTH
        val stationGrid = getStationGrid(url, apiKey, stationId) ?: userQthGrid()
        if (stationGrid.isNullOrBlank()) {
            return UploadOutcome.Done(0, queue.all().size, "无法获取站点信息(检查站点 ID/密钥权限)")
        }

        // 2. 网格检测: 用户当前 QTH 前 4 位 vs 台站网格前 4 位(v1 降级时网格相同, 跳过)
        if (!force) {
            val userGrid = userQthGrid()
            if (userGrid != null && stationGrid.take(4).lowercase() != userGrid.take(4).lowercase()) {
                return UploadOutcome.NeedConfirm(stationGrid, userGrid)
            }
        }

        // 3. 逐条上传(成功后标记 uploaded, 保留在本地供日志页打勾)
        val entries = queue.all()
        var ok = 0
        var fail = 0
        var firstError = ""
        for (qso in entries) {
            if (qso.uploaded) { ok++; continue }
            val result = WaveLogApi.postQso(url, apiKey, stationId, qso, stationGrid)
            if (result is WavelogResult.Success) {
                ok++
                queue.markUploaded(qso.id)
            } else {
                fail++
                if (firstError.isBlank()) firstError = (result as? WavelogResult.Failure)?.message ?: ""
            }
        }
        val message = if (fail == 0) "成功上传 $ok 条" else "成功 $ok 条, 失败 $fail 条(保留待重试)"
        return UploadOutcome.Done(ok, fail, message, firstError)
    }

    private suspend fun getStationGrid(url: String, apiKey: String, stationId: String): String? {
        val result = WaveLogApi.getStation(url, apiKey, stationId)
        if (result is WavelogResult.Success) {
            return try {
                JSONObject(result.message).optString("gridsquare").takeIf { it.isNotBlank() }
                    ?: cachedStationGrid
            } catch (_: Exception) { cachedStationGrid }
        }
        return cachedStationGrid
    }

    /** 用户当前 QTH 网格(前 4 位; 无 QTH 返回 null = 跳过检测) */
    private fun userQthGrid(): String? {
        return settingsRepo.stationPosition.value.qthLocator?.takeIf { it.length >= 4 }
    }
}
