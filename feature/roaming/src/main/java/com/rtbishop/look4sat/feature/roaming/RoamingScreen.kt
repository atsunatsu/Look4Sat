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
package com.rtbishop.look4sat.feature.roaming

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 漫游页 —— 原样照搬自 QTH定位器 2.0 (com.us1pm.gridsquarelocator)。
 * UI: res/layout/main.xml (结构/尺寸/颜色逐项照搬, 不做任何更改)
 * 逻辑: MainActivity.showLocation() / checkEnabled() / GPS 实时监听
 * 红线: UI 与逻辑均不更改, 与成品截图逐像素对齐。
 */
@Composable
fun RoamingScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(RoamingState()) }
    // 照搬 onCreate: 日期固定文本 + 小时前缀(定位时拼接分钟)
    val dateText = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()) }
    var hourPrefix by remember { mutableStateOf("") }
    // 照搬 onCreate: 初始 checkEnabled() -> 红点+设置按钮, 日期隐藏
    var hasPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        hourPrefix = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()).substring(0, 3)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        hasPermission = fine || coarse
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            // 照搬 onResume: 绿点+进度条+提示+日期显示, 红点/按钮隐藏
            state = state.copy(gpsOn = true, gpsOff = false, showSettings = false, showProgress = true, showNotice = true, showDate = true)
        }
    }
    // 照搬 onResume/onPause: GPS + network 监听, 10 秒 / 10 米
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    DisposableEffect(locationManager, hasPermission) {
        if (hasPermission) {
            @SuppressLint("MissingPermission")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // 照搬 showLocation 开头: 只接受 gps / network provider
                    if (location.provider != "gps" && location.provider != "network") return
                    state = roamingStateFromLocation(location.latitude, location.longitude, location.time, hourPrefix)
                        .copy(date = dateText)
                }

                override fun onProviderDisabled(provider: String) {
                    // 照搬 checkEnabled: 红点+按钮, 日期隐藏
                    state = state.copy(gpsOn = false, gpsOff = true, showSettings = true, showProgress = false, showNotice = false, showDate = false)
                }

                override fun onProviderEnabled(provider: String) {
                    state = state.copy(gpsOn = true, gpsOff = false, showSettings = false, showProgress = true, showNotice = true, showDate = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                locationManager.requestLocationUpdates("gps", 10_000L, 10f, listener)
                locationManager.requestLocationUpdates("network", 10_000L, 10f, listener)
            } catch (_: SecurityException) {
            }
            onDispose { locationManager.removeUpdates(listener) }
        } else {
            onDispose { }
        }
    }
    // 照搬 res/layout/main.xml: 浅色页面, 蓝色信息区, 43sp 定位码, 3x3 九宫格, 底部版权
    // 按用户要求: 上下往内缩进系统栏安全区, 避免内容被状态栏/导航栏遮挡
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // relativeLayout7: 顶部 GPS 状态条, 25dp 高, holo_blue_bright
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .height(25.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // tvTitleGPS: "GPS" 14sp 黑, 左 8dp
                Text(
                    text = "GPS",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
                // imGps: toRightOf tvTitleGPS + marginLeft 15dp (成品 main.xml)
                Spacer(modifier = Modifier.width(15.dp))
                // imGps: 绿点 (15dp, 主题色 primary -> 夜间滤镜下可见)
                if (state.gpsOn) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
                // imGpsOff: 红点叠层 (15dp, 主题色 error)
                if (state.gpsOff) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // tvDate: 14sp 黑, 居中
                if (state.showDate) {
                    Text(text = state.date.ifEmpty { dateText }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                // tvTime: 14sp 黑, 右对齐
                Text(
                    text = state.time,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            // btnLocationSettings: 黄色按钮, 居中
            // btnLocationSettings: 成品 #faf8f54a 半透明黄, 14sp 黑字, 居中
            if (state.showSettings) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "设置启用GPS", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        // linearLayout4: 经纬度区, holo_blue_bright
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 纬度行: tvLati 16sp 黑 左 8dp | tvLat 右对齐 3dp
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = state.latLabel, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                Text(text = state.latValue, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 3.dp))
            }
            // 经度行
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = state.lonLabel, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                Text(text = state.lonValue, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 3.dp))
            }
        }
        // relativeLayout6: 定位码区, 43sp bold 黑居中
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = state.loc,
                fontSize = 43.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            // progressBar: 加载中, 居中
            if (state.showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp
                )
            }
            // tvNotice: 提示文字, 居中顶部
            if (state.showNotice) {
                Text(
                    text = "请稍等，加载可能需要几分钟的时间。",
                    fontSize = 16.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                )
            }
        }
        // relativeLayout: 九宫格, 占满剩余, 底部 15dp
        // 用 Box 等价 RelativeLayout 布局: 左格贴左 / 中格水平居中 / 右格贴右 (成品 main.xml)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 15.dp)) {
            // 行1: relativeLayout5 (上排: 纬度 +1)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GridCell(text = state.grids[0], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                GridCell(text = state.grids[1], modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center))
                GridCell(text = state.grids[2], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
            // 行2: relativeLayout4, 205dp 高 (中排: 中央)
            Box(modifier = Modifier.fillMaxWidth().height(205.dp)) {
                GridCell(text = state.grids[3], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                // 中央列: tv22 (100x80dp centerInParent) 叠在 linearLayout3 (200x200dp) 之上
                Box(modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center)) {
                    // linearLayout3: 200x200dp, marginTop 2dp, holo_blue_bright
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 2.dp)
                            .size(width = 200.dp, height = 200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        // iv: 红点 10x10dp, top|left, leftMargin/topMargin 查表绝对定位
                        Image(
                            painter = painterResource(R.drawable.roam_pnt),
                            contentDescription = null,
                            modifier = Modifier
                                .size(10.dp)
                                .offset(x = state.markerLeft.dp, y = state.markerTop.dp)
                        )
                    }
                    // tv22: 100x80dp centerInParent, 30sp bold, textColorHighlight(白)
                    Text(
                        text = state.grids[4],
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .size(width = 100.dp, height = 80.dp)
                            .align(Alignment.Center)
                    )
                }
                GridCell(text = state.grids[5], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
            // 行3: relativeLayout3 (下排: 纬度 -1)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GridCell(text = state.grids[6], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                GridCell(text = state.grids[7], modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center))
                GridCell(text = state.grids[8], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
        }
        // tv4: 底部版权, 10sp 黑 (往上拉, 底部预留空间避免被导航栏遮挡)
        Text(
            text = "制作：US1PM  汉化：BA7LCE",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
    }
}

// 周边格: 60/200/60dp, #0BACF1, bold 居中, 2dp margin (照搬 tv11/tv13/tv21/tv23/tv31/tv33)
@Composable
private fun GridCell(text: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 照搬 MainActivity.showLocation() 的状态。
 * 网格/九宫格/红点查表与成品逐行一致。
 */
data class RoamingState(
    val date: String = "",
    val time: String = "",
    val latLabel: String = "纬度",
    val latValue: String = "",
    val lonLabel: String = "经度",
    val lonValue: String = "",
    val loc: String = "",
    val grids: List<String> = List(9) { "" },
    /** 红点 leftMargin(dp), 经度第 3 对字符查表 */
    val markerLeft: Int = 0,
    /** 红点 topMargin(dp), 纬度第 3 对字符查表(屏幕 Y 反转) */
    val markerTop: Int = 0,
    val gpsOn: Boolean = false,
    val gpsOff: Boolean = true,
    val showSettings: Boolean = true,
    val showProgress: Boolean = false,
    val showNotice: Boolean = false,
    val showDate: Boolean = false
)

/**
 * 照搬 showLocation() 的完整计算: 时间/经纬度 DMS/8 位网格/九宫格/红点查表。
 */
fun roamingStateFromLocation(lat: Double, lon: Double, fixTime: Long, hourPrefix: String): RoamingState {
    // 照搬: tvTime = 缓存小时前缀 + 定位时间分钟
    val minute = SimpleDateFormat("mm", Locale.getDefault()).format(Date(fixTime))
    val time = hourPrefix + minute
    // 方向字符
    val ew = if (lon >= 0.0) "E" else "W"
    val ns = if (lat >= 0.0) "N" else "S"
    // 照搬: 纬度 DMS (String.valueOf 无补零)
    val latAbs = if (lat < 0.0) -lat else lat
    val latDeg = latAbs.toInt()
    val latMinFull = (latAbs - latDeg) * 60.0
    val latMin = latMinFull.toInt()
    val latSec = ((latMinFull - latMin) * 60.0).toInt()
    val latLabel = "纬度  $latDeg° $latMin' $latSec\" $ns"
    val latValue = "$lat° "
    // 照搬: 经度 DMS
    val lonAbs = if (lon < 0.0) -lon else lon
    val lonDeg = lonAbs.toInt()
    val lonMinFull = (lonAbs - lonDeg) * 60.0
    val lonMin = lonMinFull.toInt()
    val lonSec = ((lonMinFull - lonMin) * 60.0).toInt()
    val lonLabel = "经度 $lonDeg° $lonMin' $lonSec\" $ew"
    val lonValue = "$lon° "
    // 照搬: 8 位网格编码 (区间查表)
    val (str, str2, str3, str4) = encodeLon(lon)
    val (str5, str6, str38, str9) = encodeLat(lat)
    val loc = str + str5 + str2 + str6 + str3 + str38 + str4 + str9
    // 照搬: 九宫格 parseInt3 五分支
    val grids = buildGrids(str, str5, str2, str6, str3, str38)
    // 照搬: 红点查表 (第 3 对字符)
    val markerLeft = lonMargin[str3.firstOrNull()] ?: 0
    val markerTop = latMargin[str38.firstOrNull()] ?: 0
    return RoamingState(
        time = time,
        latLabel = latLabel,
        latValue = latValue,
        lonLabel = lonLabel,
        lonValue = lonValue,
        loc = loc,
        grids = grids,
        markerLeft = markerLeft,
        markerTop = markerTop,
        gpsOn = true,
        gpsOff = false,
        showSettings = false,
        showProgress = false,
        showNotice = false,
        showDate = true
    )
}

// 红点 leftMargin 查表 (MainActivity 1286-1386 行): 经度第 3 对 a..x -> -2..190 dp
private val lonMargin = mapOf(
    'a' to -2, 'b' to 8, 'c' to 16, 'd' to 24, 'e' to 32, 'f' to 40, 'g' to 48, 'h' to 56,
    'i' to 65, 'j' to 74, 'k' to 83, 'l' to 92, 'm' to 101, 'n' to 110, 'o' to 119, 'p' to 128,
    'q' to 137, 'r' to 146, 's' to 155, 't' to 163, 'u' to 171, 'v' to 179, 'w' to 184, 'x' to 190
)

// 红点 topMargin 查表 (MainActivity 1315-1363 行): 纬度第 3 对 a..x -> 190..-2 dp (屏幕 Y 反转)
private val latMargin = mapOf(
    'a' to 190, 'b' to 182, 'c' to 174, 'd' to 169, 'e' to 163, 'f' to 155, 'g' to 146, 'h' to 137,
    'i' to 128, 'j' to 119, 'k' to 110, 'l' to 101, 'm' to 92, 'n' to 83, 'o' to 74, 'p' to 65,
    'q' to 56, 'r' to 48, 's' to 40, 't' to 32, 'u' to 24, 'v' to 16, 'w' to 8, 'x' to -2
)

// 照搬 311-611 行: 经度 -> (20°区字母, 2°数字, 2'字母, 30"数字)
private fun encodeLon(inputLon: Double): List<String> {
    var longitude = inputLon
    var d = 0.0
    val str = when {
        (longitude >= -180.0) && (longitude <= -160.0) -> { longitude += 180.0; "A" }
        (longitude >= -160.0) && (longitude <= -140.0) -> { longitude += 160.0; "B" }
        (longitude >= -140.0) && (longitude <= -120.0) -> { longitude += 140.0; "C" }
        (longitude >= -120.0) && (longitude <= -100.0) -> { longitude += 120.0; "D" }
        (longitude >= -100.0) && (longitude <= -80.0) -> { longitude += 100.0; "E" }
        (longitude >= -80.0) && (longitude <= -60.0) -> { longitude += 80.0; "F" }
        (longitude >= -60.0) && (longitude <= -40.0) -> { longitude += 60.0; "G" }
        (longitude >= -40.0) && (longitude <= -20.0) -> { longitude += 40.0; "H" }
        (longitude >= -20.0) && (longitude <= 0.0) -> { longitude += 20.0; "I" }
        (longitude >= 0.0) && (longitude <= 20.0) -> "J"
        (longitude >= 20.0) && (longitude <= 40.0) -> { longitude -= 20.0; "K" }
        (longitude >= 40.0) && (longitude <= 60.0) -> { longitude -= 40.0; "L" }
        (longitude >= 60.0) && (longitude <= 80.0) -> { longitude -= 60.0; "M" }
        (longitude >= 80.0) && (longitude <= 100.0) -> { longitude -= 80.0; "N" }
        (longitude >= 100.0) && (longitude <= 120.0) -> { longitude -= 100.0; "O" }
        (longitude >= 120.0) && (longitude <= 140.0) -> { longitude -= 120.0; "P" }
        (longitude >= 140.0) && (longitude <= 160.0) -> { longitude -= 140.0; "Q" }
        (longitude >= 160.0) && (longitude <= 180.0) -> { longitude -= 160.0; "R" }
        else -> { longitude = 0.0; " " }
    }
    val str2 = when {
        ((longitude >= 0.0) && (longitude <= 2.0)) || ((longitude >= -20.0) && (longitude <= -18.0)) -> { d = longitude * 60.0; "0" }
        ((longitude >= 2.0) && (longitude <= 4.0)) || ((longitude >= -18.0) && (longitude <= -16.0)) -> { d = (longitude - 2.0) * 60.0; "1" }
        ((longitude >= 4.0) && (longitude <= 6.0)) || ((longitude >= -16.0) && (longitude <= -14.0)) -> { d = (longitude - 4.0) * 60.0; "2" }
        ((longitude >= 6.0) && (longitude <= 8.0)) || ((longitude >= -14.0) && (longitude <= -12.0)) -> { d = (longitude - 6.0) * 60.0; "3" }
        ((longitude >= 8.0) && (longitude <= 10.0)) || ((longitude >= -12.0) && (longitude <= -10.0)) -> { d = (longitude - 8.0) * 60.0; "4" }
        ((longitude >= 10.0) && (longitude <= 12.0)) || ((longitude >= -10.0) && (longitude <= -8.0)) -> { d = (longitude - 10.0) * 60.0; "5" }
        ((longitude >= 12.0) && (longitude <= 14.0)) || ((longitude >= -8.0) && (longitude <= -6.0)) -> { d = (longitude - 12.0) * 60.0; "6" }
        ((longitude >= 14.0) && (longitude <= 16.0)) || ((longitude >= -6.0) && (longitude <= -4.0)) -> { d = (longitude - 14.0) * 60.0; "7" }
        ((longitude >= 16.0) && (longitude <= 18.0)) || ((longitude >= -4.0) && (longitude <= -2.0)) -> { d = (longitude - 16.0) * 60.0; "8" }
        ((longitude >= 18.0) && (longitude <= 20.0)) || ((longitude >= -2.0) && (longitude <= 0.0)) -> { d = (longitude - 18.0) * 60.0; "9" }
        else -> { d = 0.0; " " }
    }
    val str3 = when {
        ((d >= 0.0) && (d <= 5.0)) || ((d >= -120.0) && (d <= -115.0)) -> { longitude = d * 60.0; "a" }
        ((d >= 5.0) && (d <= 10.0)) || ((d >= -115.0) && (d <= -110.0)) -> { longitude = (d - 5.0) * 60.0; "b" }
        ((d >= 10.0) && (d <= 15.0)) || ((d >= -110.0) && (d <= -105.0)) -> { longitude = (d - 10.0) * 60.0; "c" }
        ((d >= 15.0) && (d <= 20.0)) || ((d >= -105.0) && (d <= -100.0)) -> { longitude = (d - 15.0) * 60.0; "d" }
        ((d >= 20.0) && (d <= 25.0)) || ((d >= -100.0) && (d <= -95.0)) -> { longitude = (d - 20.0) * 60.0; "e" }
        ((d >= 25.0) && (d <= 30.0)) || ((d >= -95.0) && (d <= -90.0)) -> { longitude = (d - 25.0) * 60.0; "f" }
        ((d >= 30.0) && (d <= 35.0)) || ((d >= -90.0) && (d <= -85.0)) -> { longitude = (d - 30.0) * 60.0; "g" }
        ((d >= 35.0) && (d <= 40.0)) || ((d >= -85.0) && (d <= -80.0)) -> { longitude = (d - 35.0) * 60.0; "h" }
        ((d >= 40.0) && (d <= 45.0)) || ((d >= -80.0) && (d <= -75.0)) -> { longitude = (d - 40.0) * 60.0; "i" }
        ((d >= 45.0) && (d <= 50.0)) || ((d >= -75.0) && (d <= -70.0)) -> { longitude = (d - 45.0) * 60.0; "j" }
        ((d >= 50.0) && (d <= 55.0)) || ((d >= -70.0) && (d <= -65.0)) -> { longitude = (d - 50.0) * 60.0; "k" }
        ((d >= 55.0) && (d <= 60.0)) || ((d >= -65.0) && (d <= -60.0)) -> { longitude = (d - 55.0) * 60.0; "l" }
        ((d >= 60.0) && (d <= 65.0)) || ((d >= -60.0) && (d <= -55.0)) -> { longitude = (d - 60.0) * 60.0; "m" }
        ((d >= 65.0) && (d <= 70.0)) || ((d >= -55.0) && (d <= -50.0)) -> { longitude = (d - 65.0) * 60.0; "n" }
        ((d >= 70.0) && (d <= 75.0)) || ((d >= -50.0) && (d <= -45.0)) -> { longitude = (d - 70.0) * 60.0; "o" }
        ((d >= 75.0) && (d <= 80.0)) || ((d >= -45.0) && (d <= -40.0)) -> { longitude = (d - 75.0) * 60.0; "p" }
        ((d >= 80.0) && (d <= 85.0)) || ((d >= -40.0) && (d <= -35.0)) -> { longitude = (d - 80.0) * 60.0; "q" }
        ((d >= 85.0) && (d <= 90.0)) || ((d >= -35.0) && (d <= -30.0)) -> { longitude = (d - 85.0) * 60.0; "r" }
        ((d >= 90.0) && (d <= 95.0)) || ((d >= -30.0) && (d <= -25.0)) -> { longitude = (d - 90.0) * 60.0; "s" }
        ((d >= 95.0) && (d <= 100.0)) || ((d >= -25.0) && (d <= -20.0)) -> { longitude = (d - 95.0) * 60.0; "t" }
        ((d >= 100.0) && (d <= 105.0)) || ((d >= -20.0) && (d <= -15.0)) -> { longitude = (d - 100.0) * 60.0; "u" }
        ((d >= 105.0) && (d <= 110.0)) || ((d >= -15.0) && (d <= -10.0)) -> { longitude = (d - 105.0) * 60.0; "v" }
        ((d >= 110.0) && (d <= 115.0)) || ((d >= -10.0) && (d <= -5.0)) -> { longitude = (d - 110.0) * 60.0; "w" }
        ((d >= 115.0) && (d <= 120.0)) || ((d >= -5.0) && (d <= 0.0)) -> { longitude = (d - 115.0) * 60.0; "x" }
        else -> { longitude = 0.0; " " }
    }
    val str4 = when {
        ((longitude >= 0.0) && (longitude <= 30.0)) || ((longitude >= -300.0) && (longitude <= -270.0)) -> "0"
        ((longitude >= 30.0) && (longitude <= 60.0)) || ((longitude >= -270.0) && (longitude <= -240.0)) -> "1"
        ((longitude >= 60.0) && (longitude <= 90.0)) || ((longitude >= -240.0) && (longitude <= -210.0)) -> "2"
        ((longitude >= 90.0) && (longitude <= 120.0)) || ((longitude >= -210.0) && (longitude <= -180.0)) -> "3"
        ((longitude >= 120.0) && (longitude <= 150.0)) || ((longitude >= -180.0) && (longitude <= -150.0)) -> "4"
        ((longitude >= 150.0) && (longitude <= 180.0)) || ((longitude >= -150.0) && (longitude <= -120.0)) -> "5"
        ((longitude >= 180.0) && (longitude <= 210.0)) || ((longitude >= -120.0) && (longitude <= -90.0)) -> "6"
        ((longitude >= 210.0) && (longitude <= 240.0)) || ((longitude >= -90.0) && (longitude <= -60.0)) -> "7"
        ((longitude >= 240.0) && (longitude <= 270.0)) || ((longitude >= -60.0) && (longitude <= -30.0)) -> "8"
        ((longitude >= 270.0) && (longitude <= 300.0)) || ((longitude >= -30.0) && (longitude <= 0.0)) -> "9"
        else -> " "
    }
    return listOf(str, str2, str3, str4)
}

// 照搬 612-914 行: 纬度 -> (10°区字母, 1°数字, 1'字母, 15"数字)
private fun encodeLat(inputLat: Double): List<String> {
    var latitude = inputLat
    var d2 = 0.0
    var d3 = 0.0
    val str5 = when {
        (latitude >= -90.0) && (latitude <= -80.0) -> { d2 = latitude + 90.0; "A" }
        (latitude >= -80.0) && (latitude <= -70.0) -> { d2 = latitude + 80.0; "B" }
        (latitude >= -70.0) && (latitude <= -60.0) -> { d2 = latitude + 70.0; "C" }
        (latitude >= -60.0) && (latitude <= -50.0) -> { d2 = latitude + 60.0; "D" }
        (latitude >= -50.0) && (latitude <= -40.0) -> { d2 = latitude + 50.0; "E" }
        (latitude >= -40.0) && (latitude <= -30.0) -> { d2 = latitude + 40.0; "F" }
        (latitude >= -30.0) && (latitude <= -20.0) -> { d2 = latitude + 30.0; "G" }
        (latitude >= -20.0) && (latitude <= -10.0) -> { d2 = latitude + 20.0; "H" }
        (latitude >= -10.0) && (latitude <= 0.0) -> { d2 = latitude + 10.0; "I" }
        (latitude >= 0.0) && (latitude <= 10.0) -> { d2 = latitude; "J" }
        (latitude >= 10.0) && (latitude <= 20.0) -> { d2 = latitude - 10.0; "K" }
        (latitude >= 20.0) && (latitude <= 30.0) -> { d2 = latitude - 20.0; "L" }
        (latitude >= 30.0) && (latitude <= 40.0) -> { d2 = latitude - 30.0; "M" }
        (latitude >= 40.0) && (latitude <= 50.0) -> { d2 = latitude - 40.0; "N" }
        (latitude >= 50.0) && (latitude <= 60.0) -> { d2 = latitude - 50.0; "O" }
        (latitude >= 60.0) && (latitude <= 70.0) -> { d2 = latitude - 60.0; "P" }
        (latitude >= 70.0) && (latitude <= 80.0) -> { d2 = latitude - 70.0; "Q" }
        (latitude >= 80.0) && (latitude <= 90.0) -> { d2 = latitude - 80.0; "R" }
        else -> { d2 = 0.0; " " }
    }
    var str6 = " "
    when {
        ((d2 >= 0.0) && (d2 <= 1.0)) || ((d2 >= -10.0) && (d2 <= -9.0)) -> { d3 = d2 * 60.0; str6 = "0" }
        ((d2 >= 1.0) && (d2 <= 2.0)) || ((d2 >= -9.0) && (d2 <= -8.0)) -> { d3 = (d2 - 1.0) * 60.0; str6 = "1" }
        ((d2 >= 2.0) && (d2 <= 3.0)) || ((d2 >= -8.0) && (d2 <= -7.0)) -> { d3 = (d2 - 2.0) * 60.0; str6 = "2" }
        ((d2 >= 3.0) && (d2 <= 4.0)) || ((d2 >= -7.0) && (d2 <= -6.0)) -> { d3 = (d2 - 3.0) * 60.0; str6 = "3" }
        ((d2 >= 4.0) && (d2 <= 5.0)) || ((d2 >= -6.0) && (d2 <= -5.0)) -> { d3 = (d2 - 4.0) * 60.0; str6 = "4" }
        ((d2 >= 5.0) && (d2 <= 6.0)) || ((d2 >= -5.0) && (d2 <= -4.0)) -> { d3 = (d2 - 5.0) * 60.0; str6 = "5" }
        ((d2 >= 6.0) && (d2 <= 7.0)) || ((d2 >= -4.0) && (d2 <= -3.0)) -> { d3 = (d2 - 6.0) * 60.0; str6 = "6" }
        ((d2 >= 7.0) && (d2 <= 8.0)) || ((d2 >= -3.0) && (d2 <= -2.0)) -> { d3 = (d2 - 7.0) * 60.0; str6 = "7" }
        ((d2 >= 8.0) && (d2 <= 9.0)) || ((d2 >= -2.0) && (d2 <= -1.0)) -> { d3 = (d2 - 8.0) * 60.0; str6 = "8" }
        ((d2 >= 9.0) && (d2 <= 10.0)) || ((d2 >= -1.0) && (d2 <= 0.0)) -> { d3 = (d2 - 9.0) * 60.0; str6 = "9" }
    }
    val str38 = when {
        ((d3 >= 0.0) && (d3 <= 2.5)) || ((d3 >= -60.0) && (d3 <= -57.5)) -> { d2 = d3 * 60.0; "a" }
        ((d3 >= 2.5) && (d3 <= 5.0)) || ((d3 >= -57.5) && (d3 <= -55.0)) -> { d2 = (d3 - 2.5) * 60.0; "b" }
        ((d3 >= 5.0) && (d3 <= 7.5)) || ((d3 >= -55.0) && (d3 <= -52.5)) -> { d2 = (d3 - 5.0) * 60.0; "c" }
        ((d3 >= 7.5) && (d3 <= 10.0)) || ((d3 >= -52.5) && (d3 <= -50.0)) -> { d2 = (d3 - 7.5) * 60.0; "d" }
        ((d3 >= 10.0) && (d3 <= 12.5)) || ((d3 >= -50.0) && (d3 <= -47.5)) -> { d2 = (d3 - 10.0) * 60.0; "e" }
        ((d3 >= 12.5) && (d3 <= 15.0)) || ((d3 >= -47.5) && (d3 <= -45.0)) -> { d2 = (d3 - 12.5) * 60.0; "f" }
        ((d3 >= 15.0) && (d3 <= 17.5)) || ((d3 >= -45.0) && (d3 <= -42.5)) -> { d2 = (d3 - 15.0) * 60.0; "g" }
        ((d3 >= 17.5) && (d3 <= 20.0)) || ((d3 >= -42.5) && (d3 <= -40.0)) -> { d2 = (d3 - 17.5) * 60.0; "h" }
        ((d3 >= 20.0) && (d3 <= 22.5)) || ((d3 >= -40.0) && (d3 <= -37.5)) -> { d2 = (d3 - 20.0) * 60.0; "i" }
        ((d3 >= 22.5) && (d3 <= 25.0)) || ((d3 >= -37.5) && (d3 <= -35.0)) -> { d2 = (d3 - 22.5) * 60.0; "j" }
        ((d3 >= 25.0) && (d3 <= 27.5)) || ((d3 >= -35.0) && (d3 <= -32.5)) -> { d2 = (d3 - 25.0) * 60.0; "k" }
        ((d3 >= 27.5) && (d3 <= 30.0)) || ((d3 >= -32.5) && (d3 <= -30.0)) -> { d2 = (d3 - 27.5) * 60.0; "l" }
        ((d3 >= 30.0) && (d3 <= 32.5)) || ((d3 >= -30.0) && (d3 <= -27.5)) -> { d2 = (d3 - 30.0) * 60.0; "m" }
        ((d3 >= 32.5) && (d3 <= 35.0)) || ((d3 >= -27.5) && (d3 <= -25.0)) -> { d2 = (d3 - 32.5) * 60.0; "n" }
        ((d3 >= 35.0) && (d3 <= 37.5)) || ((d3 >= -25.0) && (d3 <= -22.5)) -> { d2 = (d3 - 35.0) * 60.0; "o" }
        ((d3 >= 37.5) && (d3 <= 40.0)) || ((d3 >= -22.5) && (d3 <= -20.0)) -> { d2 = (d3 - 37.5) * 60.0; "p" }
        ((d3 >= 40.0) && (d3 <= 42.5)) || ((d3 >= -20.0) && (d3 <= -17.5)) -> { d2 = (d3 - 40.0) * 60.0; "q" }
        ((d3 >= 42.5) && (d3 <= 45.0)) || ((d3 >= -17.5) && (d3 <= -15.0)) -> { d2 = (d3 - 42.5) * 60.0; "r" }
        ((d3 >= 45.0) && (d3 <= 47.5)) || ((d3 >= -15.0) && (d3 <= -12.5)) -> { d2 = (d3 - 45.0) * 60.0; "s" }
        ((d3 >= 47.5) && (d3 <= 50.0)) || ((d3 >= -12.5) && (d3 <= -10.0)) -> { d2 = (d3 - 47.5) * 60.0; "t" }
        ((d3 >= 50.0) && (d3 <= 52.5)) || ((d3 >= -10.0) && (d3 <= -7.5)) -> { d2 = (d3 - 50.0) * 60.0; "u" }
        ((d3 >= 52.5) && (d3 <= 55.0)) || ((d3 >= -7.5) && (d3 <= -5.0)) -> { d2 = (d3 - 52.5) * 60.0; "v" }
        ((d3 >= 55.0) && (d3 <= 57.5)) || ((d3 >= -5.0) && (d3 <= -2.5)) -> { d2 = (d3 - 55.0) * 60.0; "w" }
        ((d3 >= 57.5) && (d3 <= 60.0)) || ((d3 >= -2.5) && (d3 <= 0.0)) -> { d2 = (d3 - 57.5) * 60.0; "x" }
        else -> { d2 = 0.0; " " }
    }
    val str9 = when {
        ((d2 >= 0.0) && (d2 <= 15.0)) || ((d2 >= -150.0) && (d2 <= -135.0)) -> "0"
        ((d2 >= 15.0) && (d2 <= 30.0)) || ((d2 >= -135.0) && (d2 <= -120.0)) -> "1"
        ((d2 >= 30.0) && (d2 <= 45.0)) || ((d2 >= -120.0) && (d2 <= -105.0)) -> "2"
        ((d2 >= 45.0) && (d2 <= 60.0)) || ((d2 >= -105.0) && (d2 <= -90.0)) -> "3"
        ((d2 >= 60.0) && (d2 <= 75.0)) || ((d2 >= -90.0) && (d2 <= -75.0)) -> "4"
        ((d2 >= 75.0) && (d2 <= 90.0)) || ((d2 >= -75.0) && (d2 <= -60.0)) -> "5"
        ((d2 >= 90.0) && (d2 <= 105.0)) || ((d2 >= -60.0) && (d2 <= -45.0)) -> "6"
        ((d2 >= 105.0) && (d2 <= 120.0)) || ((d2 >= -45.0) && (d2 <= -30.0)) -> "7"
        ((d2 >= 120.0) && (d2 <= 135.0)) || ((d2 >= -30.0) && (d2 <= -15.0)) -> "8"
        ((d2 >= 135.0) && (d2 <= 150.0)) || ((d2 >= -15.0) && (d2 <= 0.0)) -> "9"
        else -> " "
    }
    return listOf(str5, str6, str38, str9)
}

/**
 * 照搬 917-1283 行: 九宫格 parseInt3 五分支(内部/西界/东界/北界/南界 + 四角)。
 * 返回 9 格文本: [上左, 上中, 上右, 左中, 中央, 右中, 下左, 下中, 下右]。
 */
private fun buildGrids(str: String, str5: String, str2: String, str6: String, str3: String, str38: String): List<String> {
    val parseInt = str2.toInt()
    val parseInt2 = str6.toInt()
    val str39 = str + str5
    val str40 = str2 + str6
    val parseInt3 = str40.toInt()
    val str41 = str39 + str40
    val g = MutableList(9) { "" }
    fun fmt2(n: Int) = if (n <= 9) "0$n" else n.toString()
    if ((parseInt > 0) && (parseInt < 9) && (parseInt2 > 0) && (parseInt2 < 9)) {
        // 内部: 数字对 ±1
        val i7 = parseInt3 + 1
        val i8 = i7 - 10
        val valueOf9 = fmt2(i8)
        val str11 = i7.toString()
        val valueOf18 = (i7 + 10).toString()
        val i9 = parseInt3 - 10
        val valueOf10 = fmt2(i9)
        val valueOf19 = (parseInt3 + 10).toString()
        val i10 = parseInt3 - 1
        val i11 = i10 - 10
        val valueOf11 = fmt2(i11)
        val str14 = i10.toString()
        val str15 = (i10 + 10).toString()
        g[0] = str39 + valueOf9
        g[1] = str39 + str11
        g[2] = str39 + valueOf18
        g[3] = str39 + valueOf10
        g[4] = str41
        g[5] = str39 + valueOf19
        g[6] = str39 + valueOf11
        g[7] = str39 + str14
        g[8] = str39 + str15
    } else if ((parseInt == 0) && (parseInt2 > 0) && (parseInt2 < 9)) {
        // 西边界: 经度 0 -> 9, 经度字母 -1
        val str43 = (str[0] - 1).toString() + str5
        val str44 = "9" + (parseInt2 + 1)
        val i12 = parseInt3 + 1
        val valueOf7 = fmt2(i12)
        val str12 = (i12 + 10).toString()
        val sb4 = "9" + parseInt2
        val valueOf20 = (parseInt3 + 10).toString()
        val str46 = "9" + (parseInt2 - 1)
        val i13 = parseInt3 - 1
        val valueOf8 = fmt2(i13)
        val str15 = (i13 + 10).toString()
        g[0] = str43 + str44
        g[1] = str39 + valueOf7
        g[2] = str39 + str12
        g[3] = str43 + sb4
        g[4] = str41
        g[5] = str39 + valueOf20
        g[6] = str43 + str46
        g[7] = str39 + valueOf8
        g[8] = str39 + str15
    } else if ((parseInt == 9) && (parseInt2 > 0) && (parseInt2 < 9)) {
        // 东边界: 经度 9 -> 0, 经度字母 +1
        val str47 = (str[0] + 1).toString() + str5
        val i14 = parseInt3 + 1
        val valueOf21 = (i14 - 10).toString()
        val valueOf22 = i14.toString()
        val str48 = "0" + (parseInt2 + 1)
        val valueOf23 = (parseInt3 - 10).toString()
        val str49 = "0" + parseInt2
        val i15 = parseInt3 - 1
        val valueOf24 = (i15 - 10).toString()
        val valueOf25 = i15.toString()
        val str15 = "0" + (parseInt2 - 1)
        g[0] = str39 + valueOf21
        g[1] = str39 + valueOf22
        g[2] = str47 + str48
        g[3] = str39 + valueOf23
        g[4] = str41
        g[5] = str47 + str49
        g[6] = str39 + valueOf24
        g[7] = str39 + valueOf25
        g[8] = str47 + str15
    } else if ((parseInt < 9) && (parseInt > 0) && (parseInt2 == 9)) {
        // 北边界: 纬度 9 -> 0, 纬度字母 +1
        val str50 = str + (str5[0] + 1).toString()
        val i16 = parseInt3 - 9
        val i17 = i16 - 10
        val valueOf4 = fmt2(i17)
        val valueOf26 = i16.toString()
        val valueOf27 = (i16 + 10).toString()
        val i18 = parseInt3 - 10
        val valueOf5 = fmt2(i18)
        val valueOf28 = (parseInt3 + 10).toString()
        val i19 = parseInt3 - 1
        val i20 = i19 - 10
        val valueOf6 = fmt2(i20)
        val str14 = i19.toString()
        val str15 = (i19 + 10).toString()
        g[0] = str39 + valueOf4
        g[1] = str50 + valueOf26
        g[2] = str39 + valueOf27
        g[3] = str39 + valueOf5
        g[4] = str41
        g[5] = str39 + valueOf28
        g[6] = str39 + valueOf6
        g[7] = str39 + str14
        g[8] = str39 + str15
    } else if ((parseInt < 9) && (parseInt > 0) && (parseInt2 == 0)) {
        // 南边界: 纬度 0 -> 9, 纬度字母 -1
        val str52 = str + (str5[0] - 1).toString()
        val i21 = parseInt3 + 1
        val i22 = i21 - 10
        val valueOf = fmt2(i22)
        val valueOf29 = i21.toString()
        val valueOf30 = (i21 + 10).toString()
        val i23 = parseInt3 - 10
        val valueOf2 = fmt2(i23)
        val valueOf31 = (parseInt3 + 10).toString()
        val i24 = parseInt3 - 1
        val valueOf3 = fmt2(i24)
        val str14 = (i24 + 10).toString()
        val str15 = (i24 + 20).toString()
        g[0] = str39 + valueOf
        g[1] = str39 + valueOf29
        g[2] = str39 + valueOf30
        g[3] = str39 + valueOf2
        g[4] = str41
        g[5] = str39 + valueOf31
        g[6] = str52 + valueOf3
        g[7] = str52 + str14
        g[8] = str52 + str15
    } else if (parseInt3 == 0) {
        // 四角 00: 经纬度数字均 -1 进位
        val v32 = (str[0] - 1).toString()
        val v33 = (str5[0] - 1).toString()
        g[0] = v32 + str5 + "91"
        g[1] = str39 + "01"
        g[2] = str39 + "11"
        g[3] = v32 + str5 + "90"
        g[4] = str41
        g[5] = v32 + str5 + "10"
        g[6] = v32 + v33 + "99"
        g[7] = str + v33 + "09"
        g[8] = str + v33 + "19"
    } else if (parseInt3 == 9) {
        // 四角 09: 经度 -1, 纬度 +1
        val v34 = (str[0] - 1).toString()
        val v35 = (str5[0] + 1).toString()
        g[0] = v34 + v35 + "90"
        g[1] = str + v35 + "00"
        g[2] = str + v35 + "10"
        g[3] = v34 + str5 + "99"
        g[4] = str41
        g[5] = v34 + str5 + "19"
        g[6] = v34 + str5 + "98"
        g[7] = v34 + str5 + "08"
        g[8] = v34 + str5 + "18"
    } else if (parseInt3 == 99) {
        // 四角 99: 经纬度数字均 +1 进位
        val v36 = (str[0] + 1).toString()
        val v37 = (str5[0] + 1).toString()
        g[0] = str + v37 + "80"
        g[1] = str + v37 + "90"
        g[2] = v36 + v37 + "00"
        g[3] = str39 + "89"
        g[4] = str41
        g[5] = v36 + str5 + "09"
        g[6] = str39 + "88"
        g[7] = str39 + "98"
        g[8] = v36 + str5 + "08"
    } else if (parseInt3 == 90) {
        // 四角 90: 经度 +1, 纬度 -1
        val v38 = (str[0] + 1).toString()
        val v39 = (str5[0] - 1).toString()
        g[0] = str39 + "81"
        g[1] = str39 + "91"
        g[2] = v38 + str5 + "01"
        g[3] = str39 + "80"
        g[4] = str41
        g[5] = v38 + str5 + "00"
        g[6] = str + v39 + "89"
        g[7] = str + v39 + "99"
        g[8] = v38 + v39 + "09"
    }
    return g
}
