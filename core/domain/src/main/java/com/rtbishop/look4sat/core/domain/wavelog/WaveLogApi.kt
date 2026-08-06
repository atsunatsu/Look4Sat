/*
 * WaveLogApi.kt — WaveLog 日志服务器 API 客户端(4.5.2 覆盖修复 2)。
 *
 * 同时支持 v1 与 v2(用户服务器实测只有 v1, v2 返回 404):
 *   v2: POST {base}/api/v2/qso            (Authorization: Bearer + JSON 字段)
 *   v1: POST {base}/index.php/api/qso     (key 在 JSON body + ADIF 字符串)
 * 策略: 优先 v2, 404 自动降级 v1。
 * 测试连接: v2 GET api/v2/token → 404 时 v1 POST api/get_contacts_adif。
 * 站点网格: 仅 v2 有 GET api/v2/station/{id}; v1 无此端点 → 降级用用户 QTH。
 */
package com.rtbishop.look4sat.core.domain.wavelog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 站点信息(GET /api/v2/station/{id} 结果) */
data class WavelogStation(
    val id: Int,
    val name: String,
    val callsign: String,
    val gridsquare: String
)

sealed class WavelogResult {
    data class Success(val message: String) : WavelogResult()
    data class Failure(val message: String) : WavelogResult()
}

object WaveLogApi {

    private const val TIMEOUT_MS = 15000

    /** 规范化服务器地址: 去尾斜杠/去尾部 index.php; 无协议前缀时补 https:// */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.endsWith("/index.php")) u = u.removeSuffix("/index.php")
        return u
    }

    /** 测试连接: v2 GET api/v2/token → 404 时 v1 POST api/get_contacts_adif */
    suspend fun testToken(url: String, apiKey: String, stationId: String = ""): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")

        // v2: GET /index.php/api/v2/token
        val v2 = httpRequest("$base/index.php/api/v2/token", "GET", apiKey, null)
        if (v2.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v2)")

        // v1: POST /index.php/api/get_contacts_adif(key 在 body)
        if (stationId.isNotBlank()) {
            val body = JSONObject().apply {
                put("key", apiKey)
                put("station_id", stationId)
                put("fetchfromid", 0)
            }.toString()
            val v1 = httpRequest("$base/index.php/api/get_contacts_adif", "POST", apiKey, body)
            if (v1.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v1)")
            if (v1.first == 401) return@withContext WavelogResult.Failure("API 密钥无效 (v1: 401)")
        }
        // 无 index.php 的 v1 尝试
        val body = JSONObject().apply {
            put("key", apiKey)
            put("station_id", stationId)
            put("fetchfromid", 0)
        }.toString()
        val v1b = httpRequest("$base/api/get_contacts_adif", "POST", apiKey, body)
        if (v1b.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v1)")
        if (v1b.first == 401) return@withContext WavelogResult.Failure("API 密钥无效 (v1: 401)")

        WavelogResult.Failure("连接失败: v2 HTTP ${v2.first}, v1 HTTP ${v1b.first} — 请确认服务器地址/密钥正确")
    }

    /** 站点信息: 仅 v2; v1 无此端点返回 null 标记(网格检测降级用用户 QTH) */
    suspend fun getStation(url: String, apiKey: String, stationId: String): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")
        val (code, resp) = httpRequest("$base/index.php/api/v2/station/$stationId", "GET", apiKey, null)
        if (code in 200..299) {
            return@withContext try {
                val obj = JSONObject(resp)
                val data = obj.optJSONObject("data") ?: obj
                val station = WavelogStation(
                    id = data.optInt("id"),
                    name = data.optString("name"),
                    callsign = data.optString("callsign"),
                    gridsquare = data.optString("gridsquare")
                )
                WavelogResult.Success(JSONObject().apply {
                    put("id", station.id); put("name", station.name)
                    put("callsign", station.callsign); put("gridsquare", station.gridsquare)
                }.toString())
            } catch (e: Exception) {
                WavelogResult.Failure("解析失败: ${e.message}")
            }
        }
        // v1 无 station 端点 → 返回 Success 空(调用方降级用用户 QTH)
        WavelogResult.Success("")
    }

    /** LoTW 认可的卫星名: 取括号前主名 + 大写(ISS 特判) */
    fun normalizeSatName(raw: String): String {
        val main = raw.substringBefore('(').trim()
            .ifBlank { raw.trim() }
            .uppercase(Locale.ENGLISH)
        // ISS 特判: 主名是 ISS/ZARYA/ARISS 变体 → ISS(LoTW 认可名)
        return when {
            main == "ZARYA" || main.startsWith("ISS") -> "ISS"
            main == "ARISS" -> "ISS"
            else -> main
        }
    }

    /** 创建 QSO: 优先 v2, 404 降级 v1(ADIF) */
    suspend fun postQso(
        url: String,
        apiKey: String,
        stationProfileId: String,
        qso: WavelogQso,
        gridsquare: String
    ): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")

        val satName = normalizeSatName(qso.satName)

        // v2: POST /index.php/api/v2/qso(JSON 字段)
        val v2Body = JSONObject().apply {
            put("station_profile_id", stationProfileId.toIntOrNull() ?: 0)
            put("call", qso.call)
            put("band", "SAT")
            put("mode", qso.mode)
            put("qso_date", utcDate(qso.timeUtcMs))
            put("time_on", utcTime(qso.timeUtcMs))
            put("freq", qso.freqTxHz)
            put("freq_rx", qso.freqRxHz)
            put("gridsquare", gridsquare)
            put("sat_name", satName)
        }
        val (code, resp) = httpRequest("$base/index.php/api/v2/qso", "POST", apiKey, v2Body.toString())
        if (code in 200..299) return@withContext WavelogResult.Success("已上传 (v2)")
        if (code == 409) return@withContext WavelogResult.Success("重复(已存在)")

        // v1: POST /index.php/api/qso(key 在 body + ADIF)
        val v1Body = JSONObject().apply {
            put("key", apiKey)
            put("station_profile_id", stationProfileId)
            put("type", "adif")
            put("string", toAdif(qso, gridsquare, satName))
        }
        val (code1, resp1) = httpRequest("$base/index.php/api/qso", "POST", apiKey, v1Body.toString())
        if (code1 in 200..299) return@withContext WavelogResult.Success("已上传 (v1)")

        // 无 index.php 的 v1
        val (code1b, resp1b) = httpRequest("$base/api/qso", "POST", apiKey, v1Body.toString())
        if (code1b in 200..299) return@withContext WavelogResult.Success("已上传 (v1)")

        WavelogResult.Failure("上传失败: v2 HTTP $code, v1 HTTP $code1 — ${shortError(resp1.ifBlank { resp1b })}")
    }

    /** v1 ADIF 字符串(频率 MHz, 长度=UTF-8 字节数, sat_name 已规范化) */
    private fun toAdif(qso: WavelogQso, gridsquare: String, satName: String): String {
        fun field(name: String, value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8).size
            return "<$name:$bytes>$value"
        }
        return buildString {
            append(field("call", qso.call))
            append(field("band", "SAT"))
            append(field("mode", qso.mode))
            append(field("freq", String.format(Locale.ENGLISH, "%.6f", qso.freqTxHz / 1_000_000.0)))
            if (qso.freqRxHz > 0) {
                append(field("freq_rx", String.format(Locale.ENGLISH, "%.6f", qso.freqRxHz / 1_000_000.0)))
            }
            append(field("qso_date", utcDateCompact(qso.timeUtcMs)))
            append(field("time_on", utcTimeCompact(qso.timeUtcMs)))
            if (gridsquare.isNotBlank()) append(field("gridsquare", gridsquare.take(4)))
            if (satName.isNotBlank()) {
                append(field("sat_name", satName))
                append(field("prop_mode", "SAT"))
            }
            append("<eor>")
        }
    }

    /** 通用 HTTP 请求(返回 code + body) */
    private fun httpRequest(url: String, method: String, apiKey: String, jsonBody: String?): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            code to body
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun shortError(body: String): String {
        if (body.startsWith("<")) return body.take(80) // HTML 错误页
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            err?.optString("message")?.ifBlank { body.take(120) }
                ?: obj.optString("reason").ifBlank { obj.optString("message").ifBlank { body.take(120) } }
        } catch (_: Exception) {
            body.take(120)
        }
    }

    private fun utcDate(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun utcTime(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    private fun utcDateCompact(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun utcTimeCompact(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%02d%02d%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }
}
