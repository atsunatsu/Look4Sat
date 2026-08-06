/*
 * CwDecodeScreen.kt — Look4Sat 的 CW 解码页面(Compose 外壳)。
 *
 * 照搬自 Morse Expert 1.15 的 com.ve3nea.morse_expert.MainActivity(已改为普通控制器类):
 * - AndroidView 嵌入原版 activity_main.xml(ConstraintLayout 根, 含 decodedTextView/scaleView/
 *   statusTextView/verticalLayout/waterfallView);
 * - 生命周期 onCreate/onResume/onPause/onDestroy 全部委托给控制器, 顺序与原 Activity 一致
 *   (onCreate 必须在 onResume 前, onDispose 先 onPause 再 onDestroy);
 * - 麦克风权限(RECORD_AUDIO)由 Compose 侧管理, 授予后调用 onPermissionGranted()(= v()),
 *   用 initialized 标记保证只调一次(v() 会 new a() 覆盖 f11036E);
 * - 原 options_menu 的 清除/暂停/保存/录音/设置 改为顶部一行按钮(图标用模块内原版 drawable)。
 *
 * 注意: 控制器 onCreate 内的 setImmersive(window, false) 会把宿主窗口恢复为
 * decorFitsSystemWindows(true)(原版行为照搬, 会对整个宿主 Activity 生效)。
 */
package com.rtbishop.look4sat.feature.cw

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import com.ve3nea.morse_expert.MainActivity

@Composable
fun CwDecodeScreen(navigateUp: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = remember(context) {
        context as? Activity ?: error("CwDecodeScreen must be hosted in an Activity")
    }
    // 照搬的控制器(普通类, 非 Activity); 每次进入页面新建实例
    val controller = remember { MainActivity() }
    // 提前 inflate 原版布局, 供 AndroidView 与控制器 onCreate 共用同一根视图
    val rootView = remember(context) {
        LayoutInflater.from(context).inflate(R.layout.activity_main, null) as ConstraintLayout
    }

    var permissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    // v() 会 new a() 覆盖 f11036E, 必须保证只初始化一次
    var initialized by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 生命周期: onCreate 必须在 onResume 前; onDispose 先 onPause 再 onDestroy
    DisposableEffect(Unit) {
        controller.onCreate(activity, rootView)
        controller.onResume()
        onDispose {
            controller.onPause()
            controller.onDestroy()
        }
    }

    // 权限授予后启动解码核心; "首次进入已授权" 与 "弹窗回调授权" 两条路径统一走这里
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && !initialized) {
            controller.onPermissionGranted() // = v(): 创建音频采集 + 解码核心
            // v() 只创建核心; 页面此刻已处于 resumed, 需再走一次 onResume 立即启动录音
            // (AudioRecord 是新建的, startRecording 不会重复; GLSurfaceView.onResume 幂等)
            controller.onResume()
            initialized = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permanentlyDenied = !granted &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    // 原版 tap_back_again_to_close: 单击返回只提示, 2 秒内再按才退出
    BackHandler {
        if (!controller.handleBackPress()) navigateUp()
    }

    if (showSettings) {
        CwSettingsDialog(controller = controller, onDismiss = { showSettings = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // 原 options_menu: 暂停/清除/保存(原版图标) + 录音/设置(文字按钮)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { controller.togglePause() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_pause_24),
                    contentDescription = stringResource(R.string.pause)
                )
            }
            IconButton(onClick = { controller.clearDecoded() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_delete_24),
                    contentDescription = stringResource(R.string.clear)
                )
            }
            IconButton(onClick = { controller.saveText() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_save_24),
                    contentDescription = stringResource(R.string.save_text)
                )
            }
            // recordSignals 需要解码核心(f11037F), 未初始化时禁用
            TextButton(
                onClick = { if (initialized) controller.recordSignals() },
                enabled = initialized
            ) {
                Text(stringResource(R.string.record_signals))
            }
            TextButton(onClick = { showSettings = true }) {
                Text(stringResource(R.string.settings))
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { rootView },
                modifier = Modifier.fillMaxSize()
            )
            if (!permissionGranted) {
                // 未授予麦克风权限: 遮罩 + 提示文字 + 请求按钮
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.cw_mic_permission),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Text(stringResource(R.string.cw_grant_permission))
                        }
                        if (permanentlyDenied) {
                            // 勾选了"不再询问": 引导去系统设置开启
                            TextButton(onClick = {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${activity.packageName}")
                                    )
                                )
                            }) {
                                Text(stringResource(R.string.cw_open_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}
