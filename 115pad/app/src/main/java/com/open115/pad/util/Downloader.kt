package com.open115.pad.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment

object Downloader {
    /** 用系统 DownloadManager 下载，保存到 公共下载目录/115OpenPad/ */
    fun enqueue(context: Context, url: String, name: String): Long {
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription("115 OpenPad 下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "115OpenPad/$name")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }
}

fun copyToClipboard(context: Context, text: String, label: String = "115OpenPad") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
