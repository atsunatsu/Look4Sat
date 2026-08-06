/*
 * WavelogQueue.kt — WaveLog 本地日志队列(4.5.2)。
 *
 * 纯 Kotlin(不依赖 Android): 存储走 IWavelogQueueStore 接口,
 * 由 core/data 用 SharedPreferences 实现。
 * 队列上限 500 条(超出丢最旧)。
 */
package com.rtbishop.look4sat.core.domain.wavelog

import org.json.JSONArray
import org.json.JSONObject

/** 存储抽象(SharedPreferences 实现见 core/data) */
interface IWavelogQueueStore {
    fun load(): String
    fun save(json: String)
}

/** 待上传的 QSO 条目(本地队列元素,与 POST /api/v2/qso 字段对应) */
data class WavelogQso(
    val id: String,              // 本地唯一 id(UUID)
    val timeUtcMs: Long,         // 回车时刻 UTC 毫秒(本地显示 + 组装 qso_date/time_on)
    val call: String,
    val mode: String,
    val freqTxHz: Long,          // 上行(回车那一秒多普勒修正)
    val freqRxHz: Long,          // 下行
    val satName: String,
    val sessionId: String = "",  // 场次 ID: 卫星名-AOS 时间戳(过境仰角 0 秒), 空=未分组(旧数据)
    val uploaded: Boolean = false // 是否已成功上传(4.5.2 修复: 成功后保留标记, 表格打勾)
)

class WavelogQueue(private val store: IWavelogQueueStore) {

    private val key = "wavelog_queue"

    fun all(): List<WavelogQso> {
        val raw = store.load()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WavelogQso(
                    id = o.getString("id"),
                    timeUtcMs = o.getLong("timeUtcMs"),
                    call = o.optString("call"),
                    mode = o.optString("mode"),
                    freqTxHz = o.optLong("freqTxHz"),
                    freqRxHz = o.optLong("freqRxHz"),
                    satName = o.optString("satName"),
                    sessionId = o.optString("sessionId"),
                    uploaded = o.optBoolean("uploaded", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun add(qso: WavelogQso) {
        val list = all().toMutableList()
        list.add(0, qso) // 最新在前
        if (list.size > 500) list.removeAt(list.size - 1)
        save(list)
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filter { it.id != id })
    }

    @Synchronized
    fun removeAll(ids: Set<String>) {
        save(all().filter { it.id !in ids })
    }

    /** 标记为已上传(保留在队列, 表格打勾) */
    @Synchronized
    fun markUploaded(id: String) {
        save(all().map { if (it.id == id) it.copy(uploaded = true) else it })
    }

    /** 移除所有已上传条目(可选项, 保持队列精简) */
    @Synchronized
    fun removeUploaded() {
        save(all().filter { !it.uploaded })
    }

    private fun save(list: List<WavelogQso>) {
        val arr = JSONArray()
        list.forEach { q ->
            arr.put(JSONObject().apply {
                put("id", q.id); put("timeUtcMs", q.timeUtcMs); put("call", q.call)
                put("mode", q.mode); put("freqTxHz", q.freqTxHz)
                put("freqRxHz", q.freqRxHz); put("satName", q.satName)
                put("sessionId", q.sessionId)
                put("uploaded", q.uploaded)
            })
        }
        store.save(arr.toString())
    }
}
