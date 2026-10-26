package com.open115.pad.data

import com.open115.pad.util.AppLog as Log
import kotlinx.coroutines.delay

/**
 * 回收站列表响应的 data 段 → [RecycleItem] 清单。
 *
 * 响应的 data 是"数字下标 → 对象"的 map，字段全是字符串、要逐个容错取
 * （判据与回收站页原来内联写的那份完全一致），提出来共用：回收站页之外，
 * 「操作记录的删除记录一键恢复」也要按目录/名字在回收站里找条目（见
 * [com.open115.pad.ui.files.FilesViewModel.restoreFromOpLog]）。
 */
fun parseRecycleList(data: JsonObject): List<RecycleItem> =
    data.entries
        .filter { it.key.toLongOrNull() != null }
        .mapNotNull { (id, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            RecycleItem(
                id = id,
                name = s("file_name") ?: "",
                isDir = s("type") == "2",
                size = s("file_size")?.toLongOrNull() ?: 0,
                dtime = s("dtime")?.toLongOrNull() ?: 0,
                thumbUrl = s("thumb_url"),
                cid = s("cid"),
                parentName = s("parent_name"),
                pickCode = s("pick_code"),
                status = s("status")?.toIntOrNull() ?: 0,
            )
        }

/**
 * 从回收站里清掉一批条目 —— 「彻底删除」的第二跳（`ufile/delete` 只是进回收站，
 * 开放平台没有"直接删干净"的口子，只能进站后再 `rb/del`）。文件页与媒体库共用。
 *
 * 选中条件：条目挂在 [cids] 这些目录下、进站时间不早于 [sinceSec]（**含**，秒）、
 * 且名字在 [names] 里（null = 不按名过滤）。两个兜底参数对应两类调用方：
 * 文件页给 [names]（删的就是这批名字，精确到名）；媒体库不给 —— 它一次删的是"一个目录里
 * 属于这部片的全部文件"，窗口内同目录的回收条目都来自这次删除，按 cid + 时间就够准。
 *
 * 115 的删除是**异步生效**的，刚删的可能还没出现在回收站列表里：没找到就等
 * [retryDelayMs] 再来，至多 [tries] 轮。返回真正清掉的条数；清不掉（列表一直没出现 /
 * rb/del 失败）返回 0 —— 失败模式是"进了回收站没删干净"，用户在回收站页里还能看到、
 * 还能自己清，永远比误删安全。★ 同目录同名的**旧**回收条目绝不能顺手带走：
 * 时间窗必须卡在本次操作之前一点（调用方给 now-120s 这个量级）， names 能给就给。
 */
suspend fun purgeRecycleEntries(
    api: OpenApi,
    cids: Collection<String>,
    sinceSec: Long,
    names: Set<String>? = null,
    tries: Int = 3,
    retryDelayMs: Long = 1_500L,
    tag: String = "Purge",
): Int {
    val cidSet = cids.filter { it.isNotEmpty() }.toSet()
    if (cidSet.isEmpty()) return 0
    repeat(tries) { attempt ->
        val found = mutableListOf<RecycleItem>()
        try {
            var offset = 0
            while (offset < 2000) {
                val root = api.recycleList(limit = 200, offset = offset)
                if (!root.envOk()) break
                val items = parseRecycleList(root.envData() ?: JsonObject(emptyMap()))
                found += items.filter {
                    it.cid in cidSet && it.dtime >= sinceSec && (names == null || it.name in names)
                }
                if (items.size < 200) break
                offset += items.size
            }
        } catch (e: Exception) {
            Log.w(tag, "彻底删除：回收站列表失败（第 ${attempt + 1} 轮）: ${e.message}")
        }
        if (found.isNotEmpty()) {
            val resp = runCatching {
                api.recycleDelete(found.joinToString(",") { it.id })
            }.getOrNull()
            if (resp != null && resp.envOk()) {
                Log.i(tag, "彻底删除：回收站里清掉 ${found.size} 项")
                return found.size
            }
            Log.w(tag, "彻底删除：rb/del 失败（第 ${attempt + 1} 轮）: ${resp?.envMsg() ?: "网络异常"}")
        }
        if (attempt < tries - 1) delay(retryDelayMs)
    }
    Log.w(tag, "彻底删除：没能从回收站清掉（留着，可在回收站页手动清）")
    return 0
}
