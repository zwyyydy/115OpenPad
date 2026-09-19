package com.open115.pad.data

import android.content.Context
import android.net.Uri

/**
 * 断点续传「继续/重试」的公共执行体：传输中心上传行的「继续/重试」按钮与文件页的
 * 启动恢复扫描共用——按记录里落库的 SAF uri 重新打开原文件，走 resumeLargeLogged
 * （ListParts 跳片续传；会话过期自动降级全新 init；文件变更自动重算走新上传）。
 * 失败抛异常，由调用方提示。
 */
suspend fun resumeUploadRecord(
    context: Context,
    log: TransferLog,
    api: OpenApi,
    rec: UploadRecord,
): Uploader.UploadResult {
    val uri = rec.uri ?: error("记录没有文件来源，无法续传")
    val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        ?: error("无法访问原文件（权限可能已失效）")
    pfd.use { return Uploader.resumeLargeLogged(log, api, rec, it) }
}
