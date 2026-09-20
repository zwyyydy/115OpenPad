package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.opLogDataStore by preferencesDataStore(name = "op_log")

/** 操作记录的类型（存枚举名，加新类型不破坏旧 JSON） */
enum class OpType { COPY, MOVE, DELETE, RENAME, UPLOAD, DOWNLOAD, OFFLINE, TEXT_PREVIEW, IMAGE_VIEW, VIDEO_PLAY }

/**
 * 一条用户操作记录。
 *
 * [name] 是主要对象（文件名 / 链接摘要），批量时是"xxx 等 N 项"；
 * [detail] 是补充说明（去了哪个目录、来源入口等），可空；
 * [cid] + [path] 是操作发生时文件所在目录（复制/移动为目的目录），有则侧栏点击可跳转，旧数据为 null。
 */
@Serializable
data class OpEntry(
    val type: String,
    val name: String,
    val detail: String? = null,
    val at: Long,
    val cid: String? = null,
    val path: String? = null,
)

/**
 * 用户操作记录：复制 / 移动 / 删除 / 上传 / 下载 / 云离线 / 文本预览 / 图片浏览 / 视频播放。
 *
 * 纯展示数据（侧栏"操作记录"区），不参与任何业务判断，DataStore 存 JSON 足够；
 * 丢了也只是历史少几条。记录按时间正序追加（最新在尾部），超过 [MAX_ENTRIES] 条裁掉头部（最旧的）。
 */
class OpLog(private val context: Context) {

    val entries: Flow<List<OpEntry>> = context.opLogDataStore.data
        .map { decode(it[KEY_ENTRIES]) }

    suspend fun log(
        type: OpType,
        name: String,
        detail: String? = null,
        cid: String? = null,
        path: String? = null,
    ) = context.opLogDataStore.edit { p ->
        val rec = OpEntry(
            type = type.name,
            name = name,
            detail = detail,
            at = System.currentTimeMillis(),
            cid = cid,
            path = path,
        )
        p[KEY_ENTRIES] = Json.encodeToString<List<OpEntry>>(
            (decode<OpEntry>(p[KEY_ENTRIES]) + rec).takeLast(MAX_ENTRIES),
        )
    }

    suspend fun clear() = context.opLogDataStore.edit { it[KEY_ENTRIES] = "[]" }

    private inline fun <reified T> decode(raw: String?): List<T> =
        raw?.let { runCatching { Json.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_ENTRIES = 1000
        val KEY_ENTRIES = stringPreferencesKey("op_entries")
    }
}
