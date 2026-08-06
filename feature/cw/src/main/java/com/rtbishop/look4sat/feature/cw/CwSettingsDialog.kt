/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.cw

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ve3nea.morse_expert.MainActivity
import kotlin.math.roundToInt

/** 键名/默认值照搬原 Morse Expert(root_preferences.xml + SettingsActivity 逻辑)。 */
private const val KEY_MESSAGE_TYPE = "message_type"
private const val KEY_TEXT_FONT_SIZE = "text_font_size"
private const val DEFAULT_MESSAGE_TYPE = "general_text"
private const val DEFAULT_TEXT_FONT_SIZE = 18
private const val MIN_FONT_SIZE = 7
private const val MAX_FONT_SIZE = 99

/** 预置色板(9 色, 3x3 网格);原 ColorPreferenceCompat 用第三方 colorpicker, 不引入。 */
private val PaletteColors = listOf(
    0xFFD0F0F0.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
    0xFFAAAAAA.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
    0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt(),
)

/**
 * CW 设置弹窗(替代原 Morse Expert 的 SettingsActivity)。
 * 设置项与键名照搬原 APK: message_type / text_font_size / 9 个颜色
 * (I2.b.f663b 键名, I2.b.f664d 默认值, I2.b.c 英文标题)。
 * 存储: SharedPreferences(getPackageName() + "_preferences")。
 * 点击 OK 保存并调用 controller.onResume() 立即生效; Cancel/点外部仅关闭不保存。
 */
@Composable
fun CwSettingsDialog(controller: MainActivity, onDismiss: () -> Unit) {
    val activity = controller.mActivity ?: return
    val prefs = remember(activity) {
        activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
    }

    var messageType by remember {
        mutableStateOf(prefs.getString(KEY_MESSAGE_TYPE, DEFAULT_MESSAGE_TYPE) ?: DEFAULT_MESSAGE_TYPE)
    }
    var fontSize by remember {
        mutableStateOf(
            prefs.getInt(KEY_TEXT_FONT_SIZE, DEFAULT_TEXT_FONT_SIZE)
                .toFloat().coerceIn(MIN_FONT_SIZE.toFloat(), MAX_FONT_SIZE.toFloat())
        )
    }
    var colorValues by remember {
        mutableStateOf(IntArray(9) { i -> prefs.getInt(I2.b.f663b[i], I2.b.f664d[i]) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CW Settings") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                Text("Message type", style = MaterialTheme.typography.titleSmall)
                MessageTypeRow("general_text", "General Text", messageType) { messageType = it }
                MessageTypeRow("ham_radio_qso", "Ham Radio QSO", messageType) { messageType = it }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Text font size", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
                        steps = MAX_FONT_SIZE - MIN_FONT_SIZE - 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${fontSize.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Colors", style = MaterialTheme.typography.titleSmall)
                for (i in 0 until 9) {
                    ColorSettingRow(
                        title = I2.b.c[i],
                        value = colorValues[i],
                        onSelect = { selected ->
                            colorValues = colorValues.copyOf().also { it[i] = selected }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val editor = prefs.edit()
                editor.putString(KEY_MESSAGE_TYPE, messageType)
                editor.putInt(KEY_TEXT_FONT_SIZE, fontSize.roundToInt())
                for (i in 0 until 9) {
                    editor.putInt(I2.b.f663b[i], colorValues[i])
                }
                editor.apply()
                // 原 SettingsActivity 返回时由 MainActivity.onResume() 重新读取全部设置; 照搬逻辑已就位
                controller.onResume()
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MessageTypeRow(option: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onSelect(option) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option == selected, onClick = { onSelect(option) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColorSettingRow(title: String, value: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(value)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = String.format("#%06X", value and 0xFFFFFF),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        PaletteGrid(selected = value, onSelect = onSelect)
    }
}

@Composable
private fun PaletteGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PaletteColors.chunked(3).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowColors.forEach { color ->
                    val isSelected = color == selected
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(color))
                            .clickable { onSelect(color) },
                    )
                }
            }
        }
    }
}
