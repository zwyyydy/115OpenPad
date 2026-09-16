package com.open115.pad.data

import kotlinx.coroutines.flow.first

/**
 * 云下载提交（离线下载）的共用入口。
 *
 * 之所以从 OfflineViewModel 抽出来：剪贴板识别与外部 App 唤起这两条路径都没有 ViewModel，
 * 但需要与"云下载页手动添加"完全一致的提交语义与错误提示，避免两处各写一套。
 *
 * 响应结构（文档 §40 add_task_urls）：
 * ```
 * state: boolean     ← 顶层：请求是否被受理
 * code / message     ← 顶层错误
 * data: [            ← **每个链接一条结果**，真正的成败要看这里
 *   { state, code, message, info_hash, url }
 * ]
 * ```
 * 只看顶层 state 会把"链接级失败"（如空间不足 91006、配额用完 1000012）误报成成功。
 */
class DownloadSubmitter(private val prefs: DownloadPrefs) {

    /**
     * 提交下载链接（http / ftp / 磁力 / 电驴，多个用换行分隔）。
     * @param wpPathId 显式指定保存目录；不传时用持久化的保存位置——
     *   设置一次，手动添加 / 剪贴板识别 / 外部唤起全部生效
     * @return 成功返回 null；失败返回可直接展示给用户的原因
     */
    suspend fun submit(api: OpenApi, url: String, wpPathId: String? = null): String? {
        val target = wpPathId ?: prefs.saveLocation.first().cid
        val resp = try {
            api.offlineAddUrls(url, target)
        } catch (e: Exception) {
            return "网络错误：${e.message ?: "提交失败"}"
        }
        if (!resp.envOk()) return describe(codeOf(resp), resp.envMsg())

        val entries = (resp["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (entries.isEmpty()) return null

        val failed = entries.filterNot { it.envOk() }
        if (failed.isEmpty()) return null

        val reason = failed.joinToString("；") { describe(codeOf(it), it.envMsg()) }
        return if (failed.size == entries.size) reason else "部分链接提交失败：$reason"
    }

    /**
     * 把接口返回翻译成用户能看懂的原因。
     *
     * 已知错误码给确定文案（码表来自文档 §40 云下载 / §05 授权错误码）；
     * 未知码按 message 关键词给一个猜测性提示，但**始终保留服务端原文与错误码**，
     * 避免"友好提示"把真正的原因吞掉、导致无法排查。
     */
    private fun describe(code: Int?, msg: String?): String {
        val byCode = when (code) {
            91006 -> "云端空间不足，请清理或扩容后重试"
            980004 -> "操作失败，请稍后重试"
            990002 -> "参数错误，请检查链接是否有效"
            1000011 -> "一次最多提交 115 个链接，请分批"
            1000012 -> "云下载配额已用完，请购买配额后重试"
            // 40140125 时网络层会自动刷新并重试；走到这里说明刷新也没成功
            40140125 -> "登录状态已过期，自动续期未成功，请重新登录"
            // 终态：文档明确"停止重试并重新授权"，继续重试只会把令牌标记为永久失效
            40140116, 40140119, 40140120, 40140137 -> "授权已失效，请重新扫码登录"
            else -> null
        }
        if (byCode != null) return byCode

        val hint = when {
            msg == null -> null
            msg.contains("空间") || msg.contains("容量") -> "云端空间不足"
            msg.contains("重复") || msg.contains("已存在") -> "该任务已存在，无需重复提交"
            msg.contains("失效") || msg.contains("过期") || msg.contains("非法") -> "链接无效或已失效"
            else -> null
        }
        return when {
            hint != null && msg != null -> "$hint（$msg）"
            msg != null && code != null -> "$msg（错误码 $code）"
            msg != null -> msg
            code != null -> "提交失败（错误码 $code）"
            else -> "提交失败，请稍后重试"
        }
    }

    private fun codeOf(o: JsonObject): Int? = (o["code"] as? JsonPrimitive)?.content?.toIntOrNull()
}
