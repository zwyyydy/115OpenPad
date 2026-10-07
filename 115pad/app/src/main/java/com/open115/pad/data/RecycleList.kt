package com.open115.pad.data

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
