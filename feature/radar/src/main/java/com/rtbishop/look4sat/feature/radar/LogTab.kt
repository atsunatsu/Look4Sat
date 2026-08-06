/*
 * LogTab.kt — 雷达页「Log」标签(4.5.2 WaveLog 日志)。
 *
 * 布局: 转发器列表(点开选一个, 边看频率边填) → 呼号输入回车存本地 →
 * 下方本地记录列表(时间/频率/呼号)。记录支持自绘左滑删除:
 * 滑动中显示黄色垃圾桶, 滑过 75% 变撤销键, 5 秒内不点撤销则删除。
 *
 * 回车只存本地(WavelogQueue), 上传由 10 分钟周期/手动触发(防抄收错误)。
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQso
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQueue
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

private val WaveLogYellow = Color(0xFFFFC107)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTab(
    transceivers: List<SatRadio>,
    orbitalPos: OrbitalPos?,
    satelliteName: String,
    queue: WavelogQueue,
    wavelogConfigured: Boolean,
    showToast: (String) -> Unit,
    txBaseFrequencyHz: Long? = null,
    aosTimeMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries = remember(refreshTick) { queue.all() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!wavelogConfigured) {
            Text(
                text = stringResource(id = R.string.wavelog_need_config),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 转发器选择(下拉选择框): 选中后底下出现输入区
        // 选中项按 uuid 从 transceivers 实时派生(多普勒频率每秒更新, 频率显示随之刷新)
        var menuExpanded by remember { mutableStateOf(false) }
        val selectedRadio = transceivers.firstOrNull { it.uuid == selectedUuid }
        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedRadio?.info ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(id = R.string.wavelog_select_transponder)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                transceivers.forEach { radio ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = radio.info,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            selectedUuid = radio.uuid
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        // 选中转发器后: 输入区(实时频率 + 呼号 + 模式)
        selectedRadio?.let { radio ->
            ExpandedLogInput(
                radio = radio,
                orbitalPos = orbitalPos,
                satelliteName = satelliteName,
                queue = queue,
                showToast = showToast,
                txBaseFrequencyHz = txBaseFrequencyHz,
                aosTimeMs = aosTimeMs,
                onSaved = { refreshTick++ }
            )
        }

        // 本地记录列表(时间/频率/呼号)
        Text(
            text = stringResource(id = R.string.wavelog_saved),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(id = R.string.wavelog_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // 按场次分组(sessionId = 卫星名-AOS 时间戳), 组间粗线分隔
            entries.groupBy { it.sessionId.ifBlank { "un" } }.forEach { (sessionId, groupEntries) ->
                // 组标题 + 分隔线
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (sessionId == "un") stringResource(id = R.string.wavelog_ungrouped)
                    else sessionId.substringBeforeLast('-'),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                groupEntries.forEach { entry ->
                SwipeDeleteRow(
                    onDelete = {
                        queue.remove(entry.id)
                        refreshTick++
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = formatLocalTime(entry.timeUtcMs),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            text = formatFrequency(entry.freqTxHz),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp)
                        )
                        Text(
                            text = entry.call,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                }
            }
        }
    }
}

/** 转发器展开输入区: 实时频率 + 呼号(回车存本地) + 模式修正 */
@Composable
private fun ExpandedLogInput(
    radio: SatRadio,
    orbitalPos: OrbitalPos?,
    satelliteName: String,
    queue: WavelogQueue,
    showToast: (String) -> Unit,
    txBaseFrequencyHz: Long? = null,
    aosTimeMs: Long = 0L,
    onSaved: () -> Unit
) {
    var callsign by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(radio.uplinkMode ?: "FM") }

    val savedMsg = stringResource(id = R.string.wavelog_saved)

    fun submit() {
        val call = callsign.trim().uppercase()
        if (call.length < 3) return
        // 频率直接取转发器栏的数字(radio 每秒多普勒修正, 回车那一秒的值)
        val tx = radio.uplinkLow ?: radio.downlinkLow ?: 0L
        val rx = radio.downlinkLow ?: radio.uplinkLow ?: 0L
        queue.add(
            WavelogQso(
                id = UUID.randomUUID().toString(),
                timeUtcMs = System.currentTimeMillis(),
                call = call,
                mode = mode.trim().ifBlank { "FM" }.uppercase(),
                freqTxHz = tx,
                freqRxHz = rx,
                satName = satelliteName,
                sessionId = buildSessionId(satelliteName, aosTimeMs)
            )
        )
        callsign = ""
        onSaved()
        showToast(savedMsg)
    }

    // 频率显示与转发器栏完全一致: TX 行 + RX 行(radio 来自每秒多普勒重算, 实时刷新)
    val txForDisplay = radio.uplinkLow ?: radio.downlinkLow ?: 0L
    val rxForDisplay = radio.downlinkLow ?: radio.uplinkLow ?: 0L
    val upLow = radio.uplinkLow
    val upHigh = radio.uplinkHigh
    val downLow = radio.downlinkLow
    val downHigh = radio.downlinkHigh
    val isLinearRange = upLow != null && upHigh != null && upLow != upHigh
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // TX 行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TX: ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatFrequency(txForDisplay)} MHz",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (isLinearRange) {
                Text(
                    text = "  (${formatFrequency(upLow)} – ${formatFrequency(upHigh)})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // RX 行(与转发器栏 RX 一致)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RX: ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatFrequency(rxForDisplay)} MHz",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (downLow != null && downHigh != null && downLow != downHigh) {
                Text(
                    text = "  (${formatFrequency(downLow)} – ${formatFrequency(downHigh)})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it.take(12) },
            label = { Text(stringResource(id = R.string.wavelog_call_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mode,
            onValueChange = { mode = it.take(8) },
            label = { Text(stringResource(id = R.string.wavelog_mode_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 自绘左滑删除行(用户定制交互):
 * 左滑 → 右侧黄色垃圾桶; 滑过 75% → 变撤销键(待删除, 不立即删);
 * 5 秒倒计时内点撤销恢复, 不点自动删除。
 */
@Composable
internal fun SwipeDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var pending by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val threshold = rowWidth * 0.75f

    // 平滑弹回/滑入(动画)
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = if (pending) tween(200) else spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "swipeOffset"
    )

    // 倒计时: 进入待删除后 5 秒自动删
    LaunchedEffect(pending) {
        if (pending) {
            countdown = 5
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            if (pending) {
                onDelete()
                pending = false
                offsetX = 0f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width }
    ) {
        // 背景层(右侧图标: 垃圾桶 / 撤销+倒计时)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(WaveLogYellow.copy(alpha = 0.25f)),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$countdown",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = WaveLogYellow
                    )
                    Text(
                        text = stringResource(id = R.string.wavelog_delete_undo),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = WaveLogYellow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(WaveLogYellow.copy(alpha = 0.15f))
                            .clickable {
                                pending = false
                                offsetX = 0f
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                // 垃圾桶(滑动过程中一直显示, 黄色)
                Text(
                    text = "🗑",
                    fontSize = 20.sp,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }
        // 内容层(跟手滑动): 背景在 offset 内侧(随内容移动)!
        // 平时盖住垃圾桶, 左滑时内容带背景移开 → 右侧露出黄色垃圾桶/撤销倒计时
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { if (pending) { pending = false } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-rowWidth.toFloat(), 0f)
                        },
                        onDragEnd = {
                            if (offsetX <= -threshold) {
                                pending = true
                                countdown = 5
                            } else {
                                offsetX = 0f
                            }
                        },
                        onDragCancel = { offsetX = 0f }
                    )
                }
        ) {
            content()
        }
    }
}

private fun formatLocalTime(utcMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.timeInMillis = utcMs
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** 场次 ID: 卫星名-AOS 时间戳(UTC yyyyMMdd-HHmm); 无 AOS 时用回车时刻 */
private fun buildSessionId(satName: String, aosTimeMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = if (aosTimeMs > 0) aosTimeMs else System.currentTimeMillis()
    val stamp = "%04d%02d%02d-%02d%02d".format(
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
    )
    return "$satName-$stamp"
}
