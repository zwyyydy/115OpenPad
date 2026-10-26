package com.open115.pad.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 云端 BT 任务：解析种子（`offline/torrent`）→ 勾选文件 → 建任务（`offline/add_task_bt`）。
 *
 * 为什么不再一律转磁力链：磁力链提交只能整包下载，一个 40GB 的合集想只要其中一集也得全下。
 * 开放平台这两个接口是分开的——
 * - `offline/torrent` 只要求种子已上传到当前账号，返回完整文件清单和默认勾选状态；
 * - `offline/add_task_bt` 的 `wanted` 是**从 0 开始**的文件索引（逗号分隔，文档与
 *   p115client 的字段说明一致），`save_path` 是相对 `wp_path_id` 的子路径。
 *
 * 种子文件本身仍要在云盘里（要校验 SHA1），所以本地种子会先上传到保存位置再解析。
 */
object BtTask {

    data class FileEntry(
        /** 索引 = 种子文件清单里的下标，提交 `wanted` 时原样使用 */
        val index: Int,
        val path: String,
        val size: Long,
        /** 服务端建议的默认勾选（`wanted` 字段） */
        val wanted: Boolean,
    )

    data class Parsed(
        val infoHash: String,
        val name: String,
        val totalSize: Long,
        val fileCount: Int,
        val files: List<FileEntry>,
    ) {
        /** 默认选中：服务端标了 wanted 的；一个都没标（老种子常见）就全选 */
        val defaultSelection: Set<Int>
            get() = files.filter { it.wanted }.mapTo(LinkedHashSet()) { it.index }
                .ifEmpty { files.mapTo(LinkedHashSet()) { it.index } }

        fun selectedSize(selection: Set<Int>): Long =
            files.filter { it.index in selection }.sumOf { it.size }
    }

    /** 种子文件字节的 SHA1（小写十六进制）——注意不是 info_hash */
    fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 解析云端种子。`pickCode`/`sha1` 来自文件列表（`FileItem.pc`/`FileItem.sha1`）；
     * 列表没给 sha1 时由调用方下载字节后用 [sha1Hex] 补上。
     */
    suspend fun parseOnCloud(api: OpenApi, pickCode: String, sha1: String): Parsed {
        val resp = try {
            api.offlineTorrent(torrentSha1 = sha1, pickCode = pickCode)
        } catch (e: Exception) {
            throw IllegalArgumentException("解析种子失败：${e.message ?: "网络错误"}")
        }
        return parse(resp)
    }

    fun parse(root: JsonObject): Parsed {
        if (!root.envOk()) {
            throw IllegalArgumentException(describe(root.optInt("code").takeIf { it != 0 }, root.envMsg()))
        }
        val d = root["data"] as? JsonObject ?: throw IllegalArgumentException("种子解析结果为空")
        val list = (d["torrent_filelist"] as? JsonArray).orEmpty()
        val files = list.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val path = o.optStr("path")?.trim().orEmpty()
            if (path.isEmpty()) return@mapIndexedNotNull null
            FileEntry(
                index = i,
                path = path,
                size = o.optLong("size"),
                wanted = o.optInt("wanted") == 1,
            )
        }
        val infoHash = d.optStr("info_hash")?.trim().orEmpty()
        if (infoHash.isBlank()) throw IllegalArgumentException("种子解析结果缺少 info_hash")
        val name = d.optStr("torrent_name")?.trim().orEmpty().ifBlank { infoHash }
        val total = d.optLong("file_size").takeIf { it > 0 } ?: files.sumOf { it.size }
        return Parsed(
            infoHash = infoHash,
            name = name,
            totalSize = total,
            fileCount = d.optInt("file_count").takeIf { it > 0 } ?: files.size,
            files = files,
        )
    }

    /**
     * 建 BT 任务。成功返回 null，失败返回可直接展示给用户的原因。
     *
     * @param savePath 相对保存目录的子路径；通常就是种子名（115 会把它当任务文件夹名）
     */
    suspend fun add(
        api: OpenApi,
        parsed: Parsed,
        selection: Set<Int>,
        savePath: String,
        wpPathId: String,
        torrentSha1: String,
        pickCode: String,
    ): String? {
        if (selection.isEmpty()) return "至少选择一个文件"
        val wanted = selection.sorted().joinToString(",")
        val resp = try {
            api.offlineAddBt(
                infoHash = parsed.infoHash,
                wanted = wanted,
                savePath = savePath,
                torrentSha1 = torrentSha1,
                pickCode = pickCode,
                wpPathId = wpPathId,
            )
        } catch (e: Exception) {
            return "网络错误：${e.message ?: "提交失败"}"
        }
        return if (resp.envOk()) null else describe(resp.optInt("code").takeIf { it != 0 }, resp.envMsg())
    }

    /**
     * 种子名 → 保存子路径。
     *
     * 115 把 `save_path` 当相对路径，`/` 会被解释成新建一级目录；种子名里真出现斜杠
     * （跨平台打包的种子偶尔有）时换成下划线，避免在我们选定的目录里凭空多一层。
     */
    fun savePathFor(raw: String?, fallback: String = "云下载"): String {
        val cleaned = (raw ?: "")
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', ' ')
            .trim()
            .trimEnd('.', ' ')
            .take(80)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    private fun describe(code: Int?, msg: String?): String {
        val byCode = when (code) {
            20018 -> "种子文件不存在或已删除，请重新上传"
            91006 -> "云端空间不足，请清理或扩容后重试"
            990002 -> "参数错误，请检查种子与保存位置"
            1000012 -> "云下载配额已用完，请购买配额后重试"
            else -> null
        }
        if (byCode != null) return byCode
        return when {
            msg != null && code != null -> "$msg（错误码 $code）"
            msg != null -> msg
            code != null -> "操作失败（错误码 $code）"
            else -> "操作失败，请稍后重试"
        }
    }
}
