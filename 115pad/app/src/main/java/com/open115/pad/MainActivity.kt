package com.open115.pad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.open115.pad.cast.CastSession
import com.open115.pad.cast.CastUiState
import com.open115.pad.data.ThemeMode
import com.open115.pad.ui.AppRoot
import com.open115.pad.ui.theme.Open115Theme
import com.open115.pad.ui.theme.WallpaperLayer
import com.open115.pad.util.AppLog as Log
import com.open115.pad.util.LinkParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 单 Activity 壳。除了承载 Compose 界面，还负责两个"外部入口"：
 * 1. 剪贴板识别下载链接（须在窗口获得焦点后读，见 onWindowFocusChanged）
 * 2. 第三方 App 唤起（自定义协议 / magnet / 系统分享），见 consumeDownloadIntent
 */
class MainActivity : ComponentActivity() {

    /** 读剪贴板的协程句柄：焦点变化可能连续回调，取消上一次避免堆积 */
    private var clipboardJob: Job? = null

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：主界面隐藏系统状态栏（灰色顶条），下滑可临时唤出。
        // 放在 onWindowFocusChanged 再补一次：Dialog/权限窗抢焦点后系统会恢复状态栏。
        hideSystemBars()
        consumeDownloadIntent(intent) // 冷启动带参
        setContent {
            // 外观三选一（设置 → 界面显示）：明亮 / 黑暗 / 跟随系统。
            // DataStore 首读是毫秒级，initial 给默认明亮即可，不为此加转圈。
            val themeMode by appContainer.appPrefs.themeMode.collectAsState(initial = ThemeMode.LIGHT)
            val wallpaperUri by appContainer.appPrefs.wallpaperUri.collectAsState(initial = null as String?)
            // 换壁纸时 uri 的值不变（裁切结果固定写同两个文件），靠这个标记才能立刻刷新（见 AppPrefs）
            val wallpaperStamp by appContainer.appPrefs.wallpaperStamp.collectAsState(initial = 0L)
            val wallpaperMask by appContainer.appPrefs.wallpaperMask.collectAsState(initial = 0.45f)
            val wallpaperBlur by appContainer.appPrefs.wallpaperBlur.collectAsState(initial = 0.3f)
            val glassAlpha by appContainer.appPrefs.glassCardAlpha.collectAsState(initial = 0.78f)
            val glassGapDp by appContainer.appPrefs.glassGapDp.collectAsState(initial = 8)
            val glassRadiusDp by appContainer.appPrefs.glassRadiusDp.collectAsState(initial = 14)
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
            }
            // 壁纸只作用于明亮模式（用户设定）；黑暗/媒体库/播放器不受影响
            val wallpaperOn = !dark && wallpaperUri != null
            Box(Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
                if (wallpaperOn) {
                    // 壁纸铺在应用最底层：页面底色变半透明透出它，卡片仍不透明
                    WallpaperLayer(
                        uri = wallpaperUri!!,
                        mask = wallpaperMask,
                        blur = wallpaperBlur,
                        stamp = wallpaperStamp,
                    )
                }
                Open115Theme(
                    dark = dark,
                    wallpaper = wallpaperOn,
                    wallpaperMask = wallpaperMask,
                    glassCardAlpha = glassAlpha,
                    glassGapDp = glassGapDp,
                    glassRadiusDp = glassRadiusDp,
                ) {
                    val widthClass = calculateWindowSizeClass(this@MainActivity).widthSizeClass
                    AppRoot(container = appContainer, widthClass = widthClass)
                }
                // 投屏中的全局浮标（盖在页面上方，只在主界面里出现；播放页自己有遥控界面）
                CastReturnChip()
            }
        }
    }

    /** 热启动带参：已声明 launchMode=singleTop，复用本实例而不会新建 Activity */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDownloadIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        hideSystemBars()
        // Android 10+ 只在应用前台且持有焦点时允许读取剪贴板，早于焦点只能拿到 null。
        // 焦点刚拿到时系统"最后聚焦包"可能还没刷新，所以留 250ms 缓冲再读。
        clipboardJob?.cancel()
        clipboardJob = lifecycleScope.launch {
            delay(250)
            appContainer.downloadLinks.checkClipboard()
        }
    }

    /** 隐藏系统状态栏/导航栏；BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 让下滑以半透明浮层临时唤出 */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 解析外部唤起的下载链接并投递给 DownloadLinkBus。
     *
     * 一旦消费就立刻把 Intent 里的链接文本抹掉：
     * 防止横竖屏切换或进程重建时同一个 Intent 被再次解析、重复提交云下载任务。
     */
    private fun consumeDownloadIntent(intent: Intent?) {
        val target = intent ?: return
        for (candidate in downloadCandidates(target)) {
            if (!appContainer.downloadLinks.offerExternal(candidate.text)) continue
            target.data = null
            target.clipData = null
            target.removeExtra(Intent.EXTRA_TEXT)
            candidate.extraKey?.let { target.removeExtra(it) }
            return
        }
        // VIEW 进来了却什么都没认出来：留一条现场，方便判断第三方 App 把链接放在了哪
        if (target.data != null) {
            Log.w(
                TAG,
                "外部唤起未识别到下载链接：action=${target.action} " +
                    "scheme=${target.data?.scheme} extras=${target.extras?.keySet()?.toList()}",
            )
        }
    }

    /** 一条候选链接文本；[extraKey] 记录它来自哪个 extra，消费后好抹掉 */
    private class LinkCandidate(val text: String, val extraKey: String? = null)

    /**
     * 依次给出可能承载下载链接的文本，顺序即优先级：
     * data → EXTRA_TEXT → clipData → 其余 extras。
     *
     * 为什么要翻 extras：JavDB 点磁力发的是 `ACTION_VIEW` + `data=magnet:`（没有 `?xt=...`），
     * 真正的磁力链放在 extras 里。只读 data 会拿到一串光秃秃的 "magnet:"，
     * 被 [LinkParser] 判成无效链接，表现就是"跳过来了但什么都没发生"。
     *
     * 支持四种入口形态：
     * - 自定义协议带参数：mypad://download?url=<encoded>
     * - 自定义协议把链接放 path：mypad://download/<encoded>
     * - 协议本身就是链接：magnet:?xt=... / ed2k://...
     * - 系统分享纯文本：ACTION_SEND + EXTRA_TEXT
     */
    private fun downloadCandidates(intent: Intent): List<LinkCandidate> {
        val out = mutableListOf<LinkCandidate>()
        intent.data?.let { data ->
            val scheme = data.scheme?.lowercase()
            if (scheme == SCHEME_PAD || scheme == SCHEME_PAN) {
                val param = data.getQueryParameter("url")?.takeIf { it.isNotBlank() }
                    ?: data.getQueryParameter("link")?.takeIf { it.isNotBlank() }
                val raw = param ?: data.lastPathSegment?.takeIf { it.isNotBlank() }
                if (raw != null) out += LinkCandidate(LinkParser.urlDecode(raw) ?: raw)
            } else {
                out += LinkCandidate(data.toString())
            }
        }

        // 只有 VIEW / SEND 才是"外部送链接"；普通启动不翻 extras，
        // 免得把启动器或系统塞进来的无关字符串当成下载链接
        val external = intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        if (!external) return out

        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { out += LinkCandidate(it, Intent.EXTRA_TEXT) }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                item.text?.toString()?.takeIf { it.isNotBlank() }
                    ?.let { out += LinkCandidate(it) }
                item.uri?.toString()?.takeIf { it.isNotBlank() }
                    ?.let { out += LinkCandidate(it) }
            }
        }

        intent.extras?.let { extras ->
            for (key in extras.keySet().sortedBy(::extraKeyRank)) {
                if (key == Intent.EXTRA_TEXT) continue
                @Suppress("DEPRECATION") // 类型不确定，只能取 Object 再摊平（Bundle.get 自 API 33 起标了过时）
                val value = runCatching { extras.get(key) }.getOrNull()
                for (text in textValues(value)) out += LinkCandidate(text, key)
            }
        }
        return out
    }

    /**
     * extras 键的扫描顺序：名字像磁力/链接的先看。
     * 值的合法性仍由 [LinkParser] 判定，这里只决定先试谁。
     */
    private fun extraKeyRank(key: String): Int {
        val k = key.lowercase()
        return when {
            "magnet" in k || "torrent" in k -> 0
            "url" in k || "link" in k || "uri" in k || "text" in k -> 1
            else -> 2
        }
    }

    /** 把 extra 的值摊平成文本：常见是 String，也有 App 塞 Uri / Bundle / 数组 */
    @Suppress("DEPRECATION") // 同上：Bundle.get 过时，但这里就是要拿类型不确定的值
    private fun textValues(value: Any?, depth: Int = 0): List<String> = when {
        value == null -> emptyList()
        value is String -> listOf(value)
        value is Uri -> listOf(value.toString())
        value is CharSequence -> listOf(value.toString())
        depth >= 2 -> emptyList()
        value is Bundle ->
            value.keySet().flatMap { textValues(runCatching { value.get(it) }.getOrNull(), depth + 1) }
        value is Array<*> -> value.flatMap { textValues(it, depth + 1) }
        value is Iterable<*> -> value.flatMap { textValues(it, depth + 1) }
        else -> emptyList()
    }.filter { it.isNotBlank() }

    private companion object {
        const val TAG = "MainActivity"
        const val SCHEME_PAD = "mypad"
        const val SCHEME_PAN = "pan115"
    }
}

/**
 * 「投屏中」的全局浮标：点一下回到遥控界面。
 *
 * 为什么必须有它：App 是全局沉浸式的（系统状态栏被隐藏、投屏通知平时看不见），
 * 用户从遥控界面返回文件页/媒体库之后，这是屏幕上唯一一眼能找到的回路。
 *
 * 只在 MainActivity 里挂：播放页（PlayerActivity）本身就有遥控界面，不需要重复入口。
 */
@Composable
private fun CastReturnChip() {
    val context = LocalContext.current
    // 远端事件（"电视端已退出投屏""与电视端失联"）的落地点选在这里而不是播放页：
    // 提示要在**任何页面**都看得到，而播放页可能根本不在前台（用户正在文件页/媒体库）
    LaunchedEffect(Unit) {
        CastSession.notices.collect {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val state by CastSession.state.collectAsState()
    val active = state is CastUiState.Casting || state is CastUiState.Connecting
    if (!active) return
    val deviceName = when (val s = state) {
        is CastUiState.Casting -> s.device.name
        is CastUiState.Connecting -> s.device.name
        else -> ""
    }
    // 右下角：不压各页顶栏的操作键；bottom 留出手机模式底部导航条的高度
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = Color(0xE61E2430),
            shape = RoundedCornerShape(50),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(end = 18.dp, bottom = 96.dp)
                .clip(RoundedCornerShape(50))
                .clickable { context.startActivity(CastSession.remoteIntent(context)) },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CastConnected,
                    contentDescription = null,
                    tint = Color(0xFF9FC3FF),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "投屏中 · $deviceName",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
