/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone

@Composable
@Preview(showBackground = true)
private fun TopBarPreview() = MainTheme {
    TopBar {
        IconCard(action = {}, resId = R.drawable.ic_filter)
        TimerRow(timeString = "88:88:88", isTimeAos = true)
        IconCard(action = {}, resId = R.drawable.ic_radios)
    }
}

@Composable
fun TopBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun RowScope.TimerRow(timeString: String, isTimeAos: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val (aosColor, losColor) = if (isTimeAos) {
        colorScheme.primary to colorScheme.onSurface
    } else {
        colorScheme.onSurface to colorScheme.primary
    }
    ElevatedCard(modifier = Modifier.weight(1f)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // In landscape the timer often shares a row with the pass card and
            // action buttons. Keep the clock readable before the AOS/LOS labels.
            val showLabels = maxWidth >= 270.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxSize()
            ) {
                if (showLabels) {
                    Text(text = "AOS", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = aosColor)
                }
                Text(
                    text = timeString,
                    fontSize = if (showLabels) 32.sp else if (maxWidth >= 200.dp) 28.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showLabels) {
                    Text(text = "LOS", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = losColor)
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun NextPassRowPreview() = MainTheme {
    TopBar { NextPassRow(pass = getDefaultPass()) }
}

@Composable
fun RowScope.NextPassRow(pass: OrbitalPass, modifier: Modifier = Modifier, isUtc: Boolean = false) {
    val timeZone = remember(isUtc) {
        if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    val sdfTime = remember(isUtc) {
        SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).also { it.timeZone = timeZone }
    }
    ElevatedCard(
        modifier = modifier
            .height(48.dp)
            .weight(1f)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Share the header with the timer/actions in landscape without
        // squeezing three equally weighted fields into unreadable fragments.
        val compact = maxWidth < 320.dp
        Column(
            verticalArrangement = Arrangement.spacedBy((-2).dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 1.dp, end = 6.dp, bottom = 0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pass.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                        .infiniteMarquee(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val elevColor = elevationColor(pass.maxElevation)
                Icon(
                    painter = painterResource(R.drawable.ic_elevation),
                    contentDescription = null,
                    tint = elevColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${pass.maxElevation}°",
                    color = elevColor
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = sdfTime.format(Date(pass.aosTime)),
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (!compact) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "${pass.altitude} km", fontSize = 15.sp)
                    }
                }
                Text(
                    text = stringResource(R.string.pass_aosLos, pass.aosAzimuth.toInt(), pass.losAzimuth.toInt()),
                    fontSize = if (compact) 12.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        }
    }
}

@Composable
fun CardButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    ElevatedButton(
        onClick = onClick,
        enabled = isEnabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) { Text(text = text, fontSize = 16.sp, textAlign = TextAlign.Center) }
}

@Composable
fun IconCard(action: () -> Unit, resId: Int, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ElevatedCard(modifier = Modifier.size(48.dp), enabled = enabled, onClick = action) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(resId), contentDescription = null, modifier = modifier)
        }
    }
}

@Composable
fun PrimaryIconCard(modifier: Modifier = Modifier, resId: Int, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.size(102.dp, 48.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Box(
            modifier = modifier
                .clickable(onClick = onClick)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { Icon(painter = painterResource(resId), contentDescription = null) }
    }
}

@Composable
fun CardLoadingIndicator() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        CircularProgressIndicator(modifier = Modifier.size(80.dp))
    }
}

@Composable
fun EmptyListCard(message: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = """¯\_(ツ)_/¯""", fontSize = 32.sp)
            Text(text = stringResource(R.string.empty_list_message), fontSize = 21.sp, textAlign = TextAlign.Center)
            Text(text = message, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
    }
}

private val defaultOrbitalData = OrbitalData(
    name = """ ¯\_(ツ)_/¯ ⚠️""",
    epoch = 0.0, meanmo = 0.0, eccn = 0.0, incl = 0.0,
    raan = 0.0, argper = 0.0, meanan = 0.0, catnum = 0, bstar = 0.0
)

fun getDefaultPass(): OrbitalPass = OrbitalPass(
    aosTime = 0L, aosAzimuth = 0.0, losTime = Long.MAX_VALUE, losAzimuth = 0.0,
    altitude = 0, maxElevation = 0.0, orbitalObject = NearEarthObject(defaultOrbitalData), progress = 0f
)

@Composable
fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    DialogShell(onDismissRequest = onDismiss) { padding ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = padding, top = padding, end = padding)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            extraAction?.let {
                it()
                Spacer(modifier = Modifier.width(8.dp))
            }
            CardButton(onClick = onAccept, text = stringResource(R.string.btn_accept))
        }
        content()
    }
}

@Composable
fun SharedDialog(
    title: String,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    acceptText: String? = null,
    acceptEnabled: Boolean = true,
    cancelText: String? = null,
    content: @Composable () -> Unit
) {
    SharedDialog(
        title = title,
        onDismissRequest = onCancel,
        onCancel = onCancel,
        onAccept = onAccept,
        acceptText = acceptText,
        acceptEnabled = acceptEnabled,
        cancelText = cancelText
    ) { _ ->
        content()
    }
}

@Composable
fun SharedDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onAccept: (() -> Unit)? = null,
    acceptText: String? = null,
    acceptEnabled: Boolean = true,
    /** Label of the leading button; [R.string.btn_cancel] when null. */
    cancelText: String? = null,
    /** Optional second leading button, drawn next to the leading one (logbook's Query). */
    extraText: String? = null,
    onExtra: (() -> Unit)? = null,
    titleFontSize: Int = 16,
    titleTextAlign: TextAlign = if (onCancel != null && onAccept != null) TextAlign.Center else TextAlign.Start,
    content: @Composable (padding: Dp) -> Unit
) {
    DialogShell(onDismissRequest = onDismissRequest) { padding ->
        // Measured width of the optional second leading button. A spacer of the same width goes
        // after the title so the two sides of the header stay balanced: without it the title
        // would be centred in the room the extra button leaves over, i.e. off to the right.
        var extraLeadingWidth by remember { mutableStateOf(0) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = padding, top = padding, end = padding)
        ) {
            if (onCancel != null) {
                CardButton(onClick = onCancel, text = cancelText ?: stringResource(R.string.btn_cancel))
            }
            if (onExtra != null) {
                Spacer(modifier = Modifier.width(4.dp))
                CardButton(
                    onClick = onExtra,
                    text = extraText.orEmpty(),
                    modifier = Modifier.onSizeChanged { extraLeadingWidth = it.width }
                )
            }
            Text(
                text = title,
                fontSize = titleFontSize.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = titleTextAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (onCancel != null) padding else 0.dp,
                        end = if (onAccept != null) padding else 0.dp
                    )
            )
            // Balances the extra leading button (and its 4 dp gap) on the trailing side.
            if (onExtra != null && extraLeadingWidth > 0) {
                val extraWidth = with(LocalDensity.current) { extraLeadingWidth.toDp() } + 4.dp
                Spacer(modifier = Modifier.width(extraWidth))
            }
            if (onAccept != null) {
                CardButton(
                    onClick = onAccept,
                    text = acceptText ?: stringResource(R.string.btn_accept),
                    isEnabled = acceptEnabled
                )
            }
        }
        content(padding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogShell(
    onDismissRequest: () -> Unit,
    content: @Composable (padding: Dp) -> Unit
) {
    val padding = LocalSpacing.current.large
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stopSheetFling = remember {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset.Zero
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = MaterialTheme.shapes.medium,
        scrimColor = Color.Black.copy(alpha = 0.64f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(padding),
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(stopSheetFling)
        ) {
            content(padding)
        }
    }
}

@Composable
fun hasEnoughHeight(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isHeightAtLeastBreakpoint(480)

@Composable
fun hasEnoughWidth(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)

@Composable
fun isVerticalLayout(): Boolean = !hasEnoughWidth()

@Composable
fun Modifier.infiniteMarquee(): Modifier =
    basicMarquee(iterations = Int.MAX_VALUE, spacing = MarqueeSpacing(16.dp))

/** 共享的滑动行展开状态：同一列表内同时只允许一行展开（短信式联动）。 */
class SwipeController {
    var openKey by mutableStateOf<String?>(null)
        private set
    fun open(key: String) {
        openKey = key
    }
    fun close() {
        openKey = null
    }
}

@Composable
fun rememberSwipeController(): SwipeController = remember { SwipeController() }

/** 短信式滑动行：右划露出 [revealButton]（默认红底垃圾桶+文字），点击按钮触发 [revealAction]。
 *  通过 [controller] 联动：滑开新行时其他行自动收回；点按已展开的行内容也可收回（无涟漪）。 */
@Composable
fun SwipeRevealRow(
    key: String,
    controller: SwipeController,
    modifier: Modifier = Modifier,
    revealWidth: Dp = 64.dp,
    gesturesEnabled: Boolean = true,
    revealAction: () -> Unit,
    revealButton: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    },
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val revealPx = with(density) { revealWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isOpen = controller.openKey == key

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetX, target, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) { value, _ ->
                offsetX = value
            }
        }
    }

    // 另一行展开（或全部收回）时，本行随之收回。
    LaunchedEffect(controller.openKey) {
        if (!isOpen && offsetX < 0f) settle(0f)
    }

    Box(modifier.fillMaxWidth().clipToBounds()) {
        // 背景删除按钮：初始位于内容右侧屏幕外，与内容同步平移（跟手滑入）。
        // 父 Box clipToBounds 裁剪容器外部分——即便外层 item 带 padding、按钮
        // 左缘落在容器外的屏幕内，也因裁剪而不可见、不可点。
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(revealWidth)
                .offset(x = revealWidth)
                .graphicsLayer { translationX = offsetX }
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controller.close()
                    settle(0f)
                    revealAction()
                }
        ) { revealButton() }
        // 前景内容：右划平移露出按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(revealPx, gesturesEnabled) {
                    // Selection mode turns reveals off; rows must not shift under the checkboxes.
                    if (!gesturesEnabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob?.cancel() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-revealPx, 0f)
                        },
                        onDragEnd = {
                            if (offsetX < -revealPx / 2) {
                                controller.open(key)
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                settle(-revealPx)
                            } else {
                                controller.close()
                                settle(0f)
                            }
                        },
                        onDragCancel = {
                            if (offsetX < -revealPx / 2) {
                                controller.open(key)
                                settle(-revealPx)
                            } else {
                                controller.close()
                                settle(0f)
                            }
                        }
                    )
                }
                // 已展开的行，点按内容直接收回（无涟漪，避免整行出现点击反馈）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (isOpen) {
                        controller.close()
                        settle(0f)
                    }
                }
        ) { content() }
    }
}

@Composable
fun Modifier.layoutPadding(): Modifier {
    val spacing = LocalSpacing.current.extraSmall
    return statusBarsPadding().padding(start = spacing, top = 0.dp, end = spacing, bottom = spacing)
}

@Composable
fun ScreenColumn(
    topBar: @Composable (Boolean) -> Unit = {},
    floatingBar: @Composable () -> Unit = {},
    content: @Composable (Boolean) -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.layoutPadding(), contentAlignment = Alignment.BottomCenter) {
            // The space inside a navigation rail is narrower than the window size
            // class. Use the actual screen content width when arranging page headers.
            val isVertical = maxWidth < 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall)) {
                topBar(isVertical)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) { content(isVertical) }
            }
            floatingBar()
        }
    }
}

@Composable
fun TopBar(
    isVerticalLayout: Boolean,
    startAction: @Composable () -> Unit,
    topInfo: @Composable RowScope.() -> Unit,
    bottomInfo: @Composable RowScope.() -> Unit,
    endAction: @Composable () -> Unit
) {
    if (isVerticalLayout) {
        TopBar { startAction(); topInfo(); endAction() }
        TopBar { bottomInfo() }
    } else {
        TopBar { startAction(); topInfo(); bottomInfo(); endAction() }
    }
}

@Composable
fun elevationColor(elevation: Double): Color {
    val thresholds = LocalElevationThresholds.current
    return when {
        elevation < thresholds.low -> ElevationLowColor // soft red for low elevation
        elevation < thresholds.high -> MaterialTheme.colorScheme.primary // accent yellow for normal
        else -> ElevationHighColor // soft green for high elevation
    }
}

/** User-configurable elevation highlight thresholds (in degrees). */
data class ElevationThresholds(val low: Double = 15.0, val high: Double = 45.0)

/** Provided at the app root from settings; defaults keep the original 15°/45° behavior. */
val LocalElevationThresholds = compositionLocalOf { ElevationThresholds() }

/** Soft red used for elevations below the low threshold. */
val ElevationLowColor = Color(0xFFEF5350)

/** Soft green used for elevations above the high threshold. */
val ElevationHighColor = Color(0xFF66BB6A)

@Composable
fun OutlinedText(
    text: String,
    modifier: Modifier = Modifier,
    fillColor: Color = Color.Unspecified,
    outlineColor: Color,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    outlineDrawStyle: Stroke = Stroke(width = 8f),
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.semantics { hideFromAccessibility() },
            color = outlineColor,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = null,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style.copy(shadow = null, drawStyle = outlineDrawStyle),
        )

        Text(
            text = text,
            color = fillColor,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style,
        )
    }
}

// Formats a frequency in Hz as "MMM.KKK.HHH" (e.g. 145.825.000) or "---"
fun formatFrequency(frequencyHz: Long): String {
    if (frequencyHz <= 0) return "---"
    val mhz = frequencyHz / 1_000_000
    val khz = (frequencyHz % 1_000_000) / 1_000
    val hz = frequencyHz % 1_000
    return String.format(Locale.ENGLISH, "%d.%03d.%03d", mhz, khz, hz)
}
