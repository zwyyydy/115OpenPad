package com.open115.pad.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 上传「继续/重试」的公共执行体：传输中心上传行的「继续/重试」按钮与文件页的启动
 * 恢复扫描共用——按记录里落库的 SAF uri 重新打开原文件。
 *
 * - 分片记录（>32MB）：走 resumeLargeLogged，ListParts 跳片续传（会话过期自动降级
 *   全新 init，文件变更自动重算走新上传）。
 * - 小文件记录（一次性 PUT，无会话）：走 resumeSmallLogged，重读文件从头传。
 *
 * 失败抛异常，由调用方提示。
 */
suspend fun resumeUploadRecord(
    context: Context,
    log: TransferLog,
    api: OpenApi,
    rec: UploadRecord,
): Uploader.UploadResult {
    val uri = rec.uri ?: error("记录没有文件来源，无法续传")
    val parsed = Uri.parse(uri)
    return withContext(Dispatchers.IO) {
        if (rec.size <= Uploader.SMALL_UPLOAD_LIMIT) {
            val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: error("无法访问原文件（权限可能已失效）")
            Uploader.resumeSmallLogged(log, api, rec, bytes)
        } else {
            val pfd = context.contentResolver.openFileDescriptor(parsed, "r")
                ?: error("无法访问原文件（权限可能已失效）")
            pfd.use { Uploader.resumeLargeLogged(log, api, rec, it) }
        }
    }
}
