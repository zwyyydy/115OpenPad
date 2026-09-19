package com.open115.pad.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

/**
 * 开放平台的文件列表/搜索接口实际返回旧版结构：data 直接是数组，count/path 在顶层，
 * 与语雀文档描述（data 为嵌套对象）不一致。这里同时兼容两种结构。
 */

internal fun JsonElement?.asPrimitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.optStr(key: String): String? = this[key].asPrimitive()?.content

internal fun JsonObject.optLong(key: String): Long {
    val p = this[key].asPrimitive() ?: return 0L
    return p.content.toLongOrNull() ?: (p.doubleOrNull?.toLong() ?: 0L)
}

internal fun JsonObject.optInt(key: String): Int = optLong(key).toInt()

internal fun JsonObject.stateOk(): Boolean = when (val el = this["state"]) {
    is JsonPrimitive ->
        if (el.isString) el.content == "1" || el.content.equals("true", ignoreCase = true)
        else el.content == "1" || el.content == "true"
    else -> false
}

internal fun JsonObject.msgOrNull(): String? = optStr("message") ?: optStr("error")

fun JsonObject.toFileItem(): FileItem = FileItem(
    fid = optStr("fid"),
    pid = optStr("pid"),
    fc = optInt("fc"),
    fn = optStr("fn") ?: "",
    fs = optLong("fs"),
    ico = optStr("ico"),
    pc = optStr("pc"),
    sha1 = optStr("sha1"),
    ism = optInt("ism"),
    isv = optInt("isv"),
    thumb = optStr("thumb"),
    fco = optStr("fco"),
    uo = optStr("uo"),
    play_long = optLong("play_long"),
    upt = optLong("upt"),
    uppt = optLong("uppt"),
)

fun JsonObject.toSearchItem(): SearchRawItem = SearchRawItem(
    fileId = optStr("file_id"),
    fileName = optStr("file_name"),
    fileSize = optLong("file_size"),
    pickCode = optStr("pick_code"),
    parentId = optStr("parent_id"),
    fileCategory = optStr("file_category"),
    ico = optStr("ico"),
    sha1 = optStr("sha1"),
    userPtime = optLong("user_ptime"),
)

/** data 可能是数组（实际）或对象（文档），统一取出条目列表 */
private fun itemsArrayOf(root: JsonObject): List<JsonObject> {
    val arr = when (val dataEl = root["data"]) {
        is JsonArray -> dataEl
        is JsonObject -> dataEl["data"] as? JsonArray
        else -> null
    } ?: JsonArray(emptyList())
    return arr.mapNotNull { it as? JsonObject }
}

private fun countOf(root: JsonObject): Long {
    val nested = (root["data"] as? JsonObject)?.get("count")
    val el = nested ?: root["count"] ?: return 0L
    val p = el.asPrimitive() ?: return 0L
    return p.content.toLongOrNull() ?: (p.doubleOrNull?.toLong() ?: 0L)
}

fun parseFilesResponse(root: JsonObject): FilesPage {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "加载失败")
    val pathArr = ((root["data"] as? JsonObject)?.get("path") as? JsonArray)
        ?: (root["path"] as? JsonArray)
    return FilesPage(
        count = countOf(root),
        items = itemsArrayOf(root).map { it.toFileItem() },
        path = pathArr?.mapNotNull { e ->
            (e as? JsonObject)?.let { o ->
                PathNode(name = o.optStr("name") ?: "", cid = o.optStr("cid"), pid = o.optStr("pid"))
            }
        } ?: emptyList(),
    )
}

fun parseSearchResponse(root: JsonObject): SearchPage {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "搜索失败")
    return SearchPage(count = countOf(root), data = itemsArrayOf(root).map { it.toSearchItem() })
}

// ---------------- 视频播放（2026-09-12 实测结构） ----------------
// 实测：definition_list/definition_list_new 是映射 {"4":"1080P","5":"4K"}；
// video_url 是数组 [{url,definition,height,width,title}]；
// file_size/play_long 为字符串，user_def 为 int；无观看记录时 history 的 data 是空数组。

data class VideoPlayData(
    val fileId: String? = null,
    val parentId: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val playLong: Long = 0,
    val userDef: Int? = null,
    val videoUrls: List<VideoUrlEntry> = emptyList(),
    val definitionLabels: Map<Int, String> = emptyMap(),
    val multitrackList: List<String> = emptyList(),
)

data class VideoUrlEntry(
    val url: String,
    val definition: Int,
    val height: Int,
    val width: Int,
    val title: String? = null,
)

private fun JsonObject.toVideoUrlEntry(keyDef: Int? = null): VideoUrlEntry? {
    val url = optStr("url") ?: return null
    if (url.isBlank()) return null
    return VideoUrlEntry(
        url = url,
        definition = keyDef ?: optInt("definition"),
        height = optInt("height"),
        width = optInt("width"),
        title = optStr("title"),
    )
}

fun parseVideoPlayResponse(root: JsonObject): VideoPlayData {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "获取播放地址失败")
    val d = root["data"] as? JsonObject ?: throw RuntimeException("播放地址响应结构异常")
    val urls: List<VideoUrlEntry> = when (val vu = d["video_url"]) {
        is JsonArray -> vu.mapNotNull { (it as? JsonObject)?.toVideoUrlEntry() }
        is JsonObject -> vu.entries.mapNotNull { (k, v) ->
            (v as? JsonObject)?.toVideoUrlEntry(k.toIntOrNull())
        }
        else -> emptyList()
    }
    val labels = ((d["definition_list_new"] as? JsonObject) ?: (d["definition_list"] as? JsonObject))
        ?.entries?.mapNotNull { (k, v) ->
            k.toIntOrNull()?.let { def -> def to (v.asPrimitive()?.content ?: "") }
        }?.toMap() ?: emptyMap()
    val multitrack = (d["multitrack_list"] as? JsonArray)
        ?.mapNotNull { el -> (el as? JsonObject)?.optStr("title")?.takeIf { it.isNotBlank() } }
        ?: emptyList()
    return VideoPlayData(
        fileId = d.optStr("file_id"),
        parentId = d.optStr("parent_id"),
        fileName = d.optStr("file_name"),
        fileSize = d.optLong("file_size"),
        playLong = d.optLong("play_long"),
        userDef = d["user_def"].asPrimitive()?.content?.toIntOrNull(),
        videoUrls = urls,
        definitionLabels = labels,
        multitrackList = multitrack,
    )
}

/**
 * 从 `open/ufile/downurl` 的回包里取下载地址。
 * 结构是 `{ data: { <pick_code>: { url: {...} } } }`，`url` 有时是对象（含 `url` 字段、
 * 可能还带 `auth_cookie`）、有时直接是字符串，两种都兼容。
 *
 * 这个地址指向 **原始文件字节**（不经过 115 转码），拿它当播放源就是"原盘"画质
 * （等价 115master 的 Ultra）。注意带签名、有有效期，过期会 403。
 */
fun parseFileDownloadUrl(root: JsonObject): String? {
    val data = root.envData() ?: return null
    val entry = data.values.firstOrNull() as? JsonObject ?: return null
    return when (val el = entry["url"]) {
        is JsonObject -> el.optStr("url")?.takeIf { it.isNotBlank() }
        is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }
        else -> null
    }
}

/** 播放进度：无记录时 data 是 []，有记录时是对象 */
fun parseVideoHistoryTime(root: JsonObject): Long {
    if (!root.stateOk()) return 0L
    return when (val d = root["data"]) {
        is JsonObject -> d.optLong("time")
        else -> 0L
    }
}

// ---------------- 通用信封助手（错误响应里 data 可能是 []，统一走原始解析） ----------------

fun JsonObject.envData(): JsonObject? = this["data"] as? JsonObject
fun JsonObject.envOk(): Boolean = stateOk()
fun JsonObject.envMsg(): String? = msgOrNull()

private val lenientJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

fun parseUserInfo(root: JsonObject): UserInfo {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "获取用户信息失败")
    val d = root["data"] as? JsonObject ?: throw RuntimeException("用户信息响应结构异常")
    return lenientJson.decodeFromJsonElement(UserInfo.serializer(), d)
}

fun parseOfflineTasks(root: JsonObject): OfflinePage {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "获取任务列表失败")
    val d = root["data"] as? JsonObject ?: return OfflinePage()
    return runCatching { lenientJson.decodeFromJsonElement(OfflinePage.serializer(), d) }
        .getOrElse { OfflinePage() }
}

fun parseOfflineQuota(root: JsonObject): OfflineQuota {
    if (!root.stateOk()) throw RuntimeException(root.msgOrNull() ?: "获取配额信息失败")
    val d = root["data"] as? JsonObject ?: return OfflineQuota()
    return runCatching { lenientJson.decodeFromJsonElement(OfflineQuota.serializer(), d) }
        .getOrElse { OfflineQuota() }
}
