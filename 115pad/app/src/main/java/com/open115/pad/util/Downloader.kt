package com.open115.pad.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import com.open115.pad.data.DownloadRecord
import com.open115.pad.data.TransferLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Downloader {

    /** 记录入队时间用的后台作用域（fire-and-forget，不阻塞调用方） */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用系统 DownloadManager 下载，保存到 公共下载目录/115OpenPad/ */
    fun enqueue(context: Context, url: String, name: String): Long {
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription("115 OpenPad 下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "115OpenPad/$name")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            // 115 的下载直链与"取直链时的 User-Agent"绑定：DownloadManager 默认用自己的
            // UA，取文件会被 CDN 判 403（下载任务入队后立刻失败，表现为"点了没反应"）。
            // 这里显式覆盖成 App 的 UA，与 App115 的 OkHttp 拦截器保持一致。
            addRequestHeader("User-Agent", APP_USER_AGENT)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        // 记下"什么时候下的"：DownloadManager 不暴露任务创建时间，传输中心只能靠这份记录
        val appContext = context.applicationContext
        logScope.launch {
            runCatching {
                TransferLog(appContext).addDownload(
                    DownloadRecord(id = id, name = name, startedAt = System.currentTimeMillis()),
                )
            }
        }
        return id
    }
}

fun copyToClipboard(context: Context, text: String, label: String = "115OpenPad") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
