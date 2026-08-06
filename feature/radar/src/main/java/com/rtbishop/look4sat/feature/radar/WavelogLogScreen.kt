/*
 * WavelogLogScreen.kt — 「日志」页面(更多菜单, 4.5.2 修复)。
 *
 * 表格形式展示本地存储的日志: 时间 | 频率 | 卫星 | 呼号 | 已上传(✓)。
 * 完整格子线(表头横线 + 行横线 + 列竖线 + 外边框); 行复用左滑删除。
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQueue
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency
import java.util.Calendar
import java.util.TimeZone

private val CheckGreen = Color(0xFF4CAF50)
private val GridLineColor = Color(0xFF3A3A3A)

@Composable
fun WavelogLogScreen(
    queue: WavelogQueue,
    modifier: Modifier = Modifier
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries = remember(refreshTick) { queue.all() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        // 表格外边框(左右 + 上下由表头/行底线构成)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            // 表头
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            ) {
                HeaderCell(stringResource(id = R.string.wavelog_col_time), 72.dp)
                GridVLine()
                HeaderCell(stringResource(id = R.string.wavelog_col_freq), 64.dp)
                GridVLine()
                HeaderCell(stringResource(id = R.string.wavelog_col_sat), 0.dp, weight = 1.2f)
                GridVLine()
                HeaderCell(stringResource(id = R.string.wavelog_col_call), 0.dp, weight = 1f)
                GridVLine()
                HeaderCell(stringResource(id = R.string.wavelog_col_uploaded), 44.dp)
            }
            // 表头横线
            HorizontalDivider(thickness = 1.5.dp, color = GridLineColor)
            // 表头与数据区留白
            Box(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.wavelog_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            } else {
                // 按场次分组(sessionId = 卫星名-AOS 时间戳); 空 sessionId 归"未分组"放最后
                val groups = entries.groupBy { it.sessionId.ifBlank { UNGROUPED } }
                    .toSortedMap(compareByDescending<String> { id ->
                        entries.filter { it.sessionId.ifBlank { UNGROUPED } == id }.minOfOrNull { it.timeUtcMs } ?: 0L
                    }.thenByDescending { it })
                groups.forEach { (sessionId, groupEntries) ->
                    // 组标题(场次名 + 本地时间)
                    val (satPart, timePart) = parseSession(sessionId)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (sessionId == UNGROUPED) stringResource(id = R.string.wavelog_ungrouped)
                            else "$satPart - ${formatSessionTime(timePart)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    // 组间横线(md --- 效果)
                    HorizontalDivider(thickness = 1.5.dp, color = GridLineColor)
                    groupEntries.forEach { entry ->
                    SwipeDeleteRow(
                        onDelete = {
                            queue.remove(entry.id)
                            refreshTick++
                        }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                            ) {
                                Cell(formatLocalTime(entry.timeUtcMs), 72.dp)
                                GridVLine()
                                Cell(formatFrequency(entry.freqTxHz), 64.dp)
                                GridVLine()
                                Cell(entry.satName, 0.dp, weight = 1.2f)
                                GridVLine()
                                Cell(entry.call, 0.dp, weight = 1f)
                                GridVLine()
                                Box(modifier = Modifier.width(44.dp)) {
                                    if (entry.uploaded) {
                                        Text(
                                            text = "✓",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CheckGreen
                                        )
                                    }
                                }
                            }
                            // 行分隔线
                            HorizontalDivider(thickness = 1.dp, color = GridLineColor)
                        }
                    }
                }
                }
            }
        }
    }
}

/** 列竖线 */
@Composable
private fun GridVLine() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(GridLineColor)
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, width: Dp, weight: Float = 0f) {
    val mod = if (weight > 0f) Modifier.weight(weight) else Modifier.width(width)
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = mod.padding(horizontal = 6.dp)
    )
}

@Composable
private fun RowScope.Cell(text: String, width: Dp, weight: Float = 0f) {
    val mod = if (weight > 0f) Modifier.weight(weight) else Modifier.width(width)
    Text(
        text = text,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = mod.padding(horizontal = 6.dp)
    )
}

private const val UNGROUPED = "__ungrouped__"

/** 解析场次 ID: "ASRTU-1-20260804-2014" → (卫星名, "20260804-2014") */
private fun parseSession(sessionId: String): Pair<String, String> {
    val parts = sessionId.split('-')
    if (parts.size < 3) return sessionId to ""
    val timePart = parts.takeLast(2).joinToString("-")
    val satPart = parts.dropLast(2).joinToString("-")
    return satPart to timePart
}

/** 场次时间(UTC "20260804-2014") → 本地 "MM-dd HH:mm" */
private fun formatSessionTime(timePart: String): String {
    if (timePart.length != 13 || timePart[8] != '-') return timePart
    return try {
        val year = timePart.substring(0, 4).toInt()
        val month = timePart.substring(4, 6).toInt()
        val day = timePart.substring(6, 8).toInt()
        val hour = timePart.substring(9, 11).toInt()
        val minute = timePart.substring(11, 13).toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.timeInMillis = cal.timeInMillis + TimeZone.getDefault().getOffset(cal.timeInMillis)
        "%02d-%02d %02d:%02d".format(
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    } catch (_: Exception) {
        timePart
    }
}

private fun formatLocalTime(utcMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.timeInMillis = utcMs
    return "%02d-%02d %02d:%02d".format(
        cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
    )
}
