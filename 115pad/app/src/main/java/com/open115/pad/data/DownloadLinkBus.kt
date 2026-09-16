package com.open115.pad.data

import android.content.ClipboardManager
import android.content.Context
import com.open115.pad.util.LinkParser
import com.open115.pad.util.LinkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 链接来源：决定是弹窗二次确认还是直接自动提交 */
enum class LinkSource {
    /** 剪贴板识别 → 是否静默由设置项 autoSubmitClipboardDownload 决定 */
    CLIPBOARD,

    /** 外部 App 唤起（Deep Link / 磁力 / 系统分享）→ 默认自动提交 */
    EXTERNAL,
}

/** 一条待处理的下载请求 */
data class DownloadRequest(
    val url: String,
    val type: LinkType,
    val preview: String,
    val source: LinkSource,
)

/**
 * 剪贴板与外部唤起的链接汇聚点。
 *
 * 两条来源都汇到这里，UI 只盯一个 StateFlow —— "去重""静默 / 二次确认"这类策略只写一份。
 * 用 StateFlow 承载待处理请求：即使请求到达时界面还没建好（冷启动），也不会丢。
 */
class DownloadLinkBus(
    private val context: Context,
    private val prefs: DownloadPrefs,
) {

    private val _pending = MutableStateFlow<DownloadRequest?>(null)
    val pending: StateFlow<DownloadRequest?> = _pending.asStateFlow()

    /**
     * 读取剪贴板并识别链接。
     *
     * 必须在窗口拿到焦点后调用：Android 10+ 只在应用处于前台且持有焦点时允许读剪贴板，
     * 否则只会拿到 null，并在系统日志里留下 "Denying clipboard access" 警告。
     *
     * 命中后按**整段文本的 MD5** 记入偏好：同一内容只解析一次，切来切去不再重复弹窗。
     */
    suspend fun checkClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return

        val key = LinkParser.contentKey(text)
        if (key == prefs.currentLastClipboardKey()) return
        // 先落盘再解析：即使这段文本里没有链接（或提交失败），也不会反复读同一段剪贴板
        prefs.setLastClipboardKey(key)

        val link = LinkParser.parse(text) ?: return
        _pending.value = DownloadRequest(link.url, link.type, link.preview, LinkSource.CLIPBOARD)
    }

    /**
     * 外部 App 唤起：解析出链接就入队（是否自动提交由调用方按来源决定）。
     * @return 是否成功识别出链接
     */
    fun offerExternal(rawText: String?): Boolean {
        val link = LinkParser.parse(rawText) ?: return false
        _pending.value = DownloadRequest(link.url, link.type, link.preview, LinkSource.EXTERNAL)
        return true
    }

    /**
     * 取出并清空当前请求。
     * 处理完或用户取消后**必须**调用，否则重组/重建时会被反复消费。
     */
    fun consume() {
        _pending.value = null
    }
}
