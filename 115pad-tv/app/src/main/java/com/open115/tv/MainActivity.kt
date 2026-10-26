package com.open115.tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.open115.tv.receiver.PlayBus
import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.ReceiverService
import com.open115.tv.receiver.TvProtocol
import com.open115.tv.ui.PlayScreen
import com.open115.tv.ui.SettingsScreen
import com.open115.tv.ui.StandbyScreen
import com.open115.tv.ui.SystemPlayScreen
import com.open115.tv.ui.TvTheme
import com.open115.tv.util.TvPrefs

/**
 * 单 Activity：待机页与播放页都在这里，跟着 [PlayBus.pending] 切换。
 *
 * 为什么不给播放做独立 Activity：从服务侧"把播放页拉到屏幕前"是同一件事，
 * 一个 Activity 就少一层后台启动 Activity 的限制面，也少一份 intent 参数的传递。
 */
class MainActivity : ComponentActivity() {

    // registerForActivityResult 必须在 onStart 之前注册好：字段初始化时机最稳
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 界面一起来就确保接收服务在（用户点开过一次，之后就有常驻的等待能力）
        ReceiverService.start(this)
        applyImmersive()
        requestNotificationsIfNeeded()
        setContent {
            TvTheme { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestNotificationsIfNeeded() {
        // Android 13+：拒绝通知只是不显示，接收/播放不受影响，所以不解释、不纠缠
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    companion object {
        /** 服务侧靠它判断"界面是不是就在屏幕上"（决定投屏时要不要走通知兜底） */
        @Volatile
        var isVisible = false
            private set
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    // 偏好是落盘的，界面一起来就载入（幂等）；播放页与设置页都读同一份镜像
    LaunchedEffect(Unit) { TvPrefs.load(context) }

    val pending by PlayBus.pending.collectAsState()
    var current by remember { mutableStateOf<PlayRequest?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val playerMode by TvPrefs.player.collectAsState()

    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        // 播放中再收到新指令（另投一部片）同样走这里：current 变化会让播放页整体重建
        current = p
        // 投屏优先级高于本地翻设置：收到投屏就把设置页收起来
        settingsOpen = false
        PlayBus.consume()
    }

    // 设置类与"停止"指令**全局**处理：这三种在任何界面下都必须生效——
    // 待机页、设置页、以及"交给电视自带播放器"那层（那时播放页并不在组合里，收不到指令）
    LaunchedEffect(Unit) {
        PlayBus.control.collect { ctl ->
            when (ctl.action) {
                TvProtocol.ACTION_DECODE ->
                    TvPrefs.setDecode(context, ctl.mode ?: TvProtocol.DECODE_HW)

                TvProtocol.ACTION_PLAYER ->
                    TvPrefs.setPlayer(context, ctl.mode ?: TvProtocol.PLAYER_BUILTIN)

                TvProtocol.ACTION_STOP -> {
                    current = null
                    PlayBus.clearPlayback()
                }

                else -> Unit // 播放类动作由播放页处理（只有它有 player 实例）
            }
        }
    }

    val isPlaying = current != null
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        val window = view.context.findActivity()?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val request = current
    when {
        // 播放器设成"电视自带"时，走外派那一层（见 SystemPlayScreen 的能力边界）
        request != null && playerMode == TvProtocol.PLAYER_SYSTEM -> SystemPlayScreen(
            request = request,
            onExit = {
                current = null
                PlayBus.clearPlayback()
            },
        )

        request != null -> PlayScreen(
            request = request,
            onExit = {
                current = null
                PlayBus.clearPlayback()
            },
        )

        settingsOpen -> SettingsScreen(onBack = { settingsOpen = false })

        else -> StandbyScreen(onOpenSettings = { settingsOpen = true })
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
