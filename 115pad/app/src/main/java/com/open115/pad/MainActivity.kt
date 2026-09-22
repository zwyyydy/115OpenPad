package com.open115.pad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.lifecycleScope
import com.open115.pad.ui.AppRoot
import com.open115.pad.ui.theme.Open115Theme
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
            Open115Theme {
                val widthClass = calculateWindowSizeClass(this).widthSizeClass
                AppRoot(container = appContainer, widthClass = widthClass)
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
     * 一旦消费就立刻把 Intent 里的 Data / EXTRA_TEXT 抹掉：
     * 防止横竖屏切换或进程重建时同一个 Intent 被再次解析、重复提交云下载任务。
     */
    private fun consumeDownloadIntent(intent: Intent?) {
        val target = intent ?: return
        val url = extractDownloadUrl(target) ?: return
        target.data = null
        target.removeExtra(Intent.EXTRA_TEXT)
        appContainer.downloadLinks.offerExternal(url)
    }

    /**
     * 支持四种入口形态：
     * - 自定义协议带参数：mypad://download?url=<encoded>
     * - 自定义协议把链接放 path：mypad://download/<encoded>
     * - 协议本身就是链接：magnet:?xt=... / ed2k://...
     * - 系统分享纯文本：ACTION_SEND + EXTRA_TEXT
     */
    private fun extractDownloadUrl(intent: Intent): String? {
        intent.data?.let { data ->
            val scheme = data.scheme?.lowercase()
            if (scheme == SCHEME_PAD || scheme == SCHEME_PAN) {
                val param = data.getQueryParameter("url")?.takeIf { it.isNotBlank() }
                    ?: data.getQueryParameter("link")?.takeIf { it.isNotBlank() }
                if (param != null) return LinkParser.urlDecode(param) ?: param
                val seg = data.lastPathSegment?.takeIf { it.isNotBlank() }
                if (seg != null) return LinkParser.urlDecode(seg) ?: seg
                return null
            }
            return data.toString()
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }

    private companion object {
        const val SCHEME_PAD = "mypad"
        const val SCHEME_PAN = "pan115"
    }
}
