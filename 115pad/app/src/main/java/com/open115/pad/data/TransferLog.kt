package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.transferDataStore by preferencesDataStore(name = "transfer")

/** 一次本机下载的开始记录（DownloadManager 不暴露任务创建时间，只能自己记） */
@Serializable
data class DownloadRecord(
    val id: Long,
    val name: String,
    val startedAt: Long,
)

/**
 * 一次上传的记录。上传是"一次性 PUT"，没有进度可查，但开始/结束时间、目标目录、
 * 成败与是否秒传都值得留痕——云盘里文件名相同、时间久了根本想不起来传哪去了。
 */
@Serializable
data class UploadRecord(
    val id: Long,
    val name: String,
    val size: Long,
    /** 目标目录 cid（根目录为 "0"） */
    val targetCid: String,
    /** 目标目录展示名；调用方拿不到（如"与视频同目录"）时为 null */
    val targetName: String? = null,
    val startedAt: Long,
    /** null = 上传中 */
    val finishedAt: Long? = null,
    val ok: Boolean? = null,
    val reused: Boolean = false,
    val error: String? = null,
)

/**
 * 传输中心的历史记录：本机下载（DownloadManager 任务）+ 上传（文件页 / 播放器字幕上传）。
 *
 * 两份记录都只有"时间 + 名称 + 目标"，不参与业务判断，纯展示，所以用 DataStore 存
 * JSON 就够了，也不怕丢——真删了只是历史少一条。
 */
class TransferLog(private val context: Context) {

    val downloads: Flow<List<DownloadRecord>> = context.transferDataStore.data
        .map { decode(it[KEY_DOWNLOADS]) }

    val uploads: Flow<List<UploadRecord>> = context.transferDataStore.data
        .map { decode(it[KEY_UPLOADS]) }

    suspend fun addDownload(rec: DownloadRecord) = context.transferDataStore.edit { p ->
        p[KEY_DOWNLOADS] = Json.encodeToString(
            (decode<DownloadRecord>(p[KEY_DOWNLOADS]).filterNot { it.id == rec.id } + rec)
                .takeLast(MAX_RECORDS),
        )
    }

    suspend fun removeDownload(id: Long) = context.transferDataStore.edit { p ->
        val cur = decode<DownloadRecord>(p[KEY_DOWNLOADS])
        val next = cur.filterNot { it.id == id }
        if (next.size != cur.size) p[KEY_DOWNLOADS] = Json.encodeToString(next)
    }

    /** 记一条"上传中"，返回记录 id 供结束后回填 */
    suspend fun beginUpload(
        name: String,
        size: Long,
        targetCid: String,
        targetName: String?,
    ): Long {
        var id = 0L
        context.transferDataStore.edit { p ->
            val cur = decode<UploadRecord>(p[KEY_UPLOADS])
            id = (cur.maxOfOrNull { it.id } ?: 0L) + 1L
            val rec = UploadRecord(
                id = id,
                name = name,
                size = size,
                targetCid = targetCid,
                targetName = targetName,
                startedAt = System.currentTimeMillis(),
            )
            p[KEY_UPLOADS] = Json.encodeToString((cur + rec).takeLast(MAX_RECORDS))
        }
        return id
    }

    /** 回填上传结果；ok=false 时带上错误信息 */
    suspend fun finishUpload(id: Long, ok: Boolean, reused: Boolean = false, error: String? = null) =
        context.transferDataStore.edit { p ->
            val cur = decode<UploadRecord>(p[KEY_UPLOADS])
            val next = cur.map { r ->
                if (r.id != id) r
                else r.copy(
                    finishedAt = System.currentTimeMillis(),
                    ok = ok,
                    reused = reused,
                    error = error,
                )
            }
            p[KEY_UPLOADS] = Json.encodeToString(next)
        }

    suspend fun removeUpload(id: Long) = context.transferDataStore.edit { p ->
        val cur = decode<UploadRecord>(p[KEY_UPLOADS])
        val next = cur.filterNot { it.id == id }
        if (next.size != cur.size) p[KEY_UPLOADS] = Json.encodeToString(next)
    }

    suspend fun clearUploads() = context.transferDataStore.edit { it[KEY_UPLOADS] = "[]" }

    /** 传输中心的记录总数（用于导航角标之类的轻量展示） */
    suspend fun counts(): Pair<Int, Int> {
        val p = context.transferDataStore.data.first()
        return decode<DownloadRecord>(p[KEY_DOWNLOADS]).size to decode<UploadRecord>(p[KEY_UPLOADS]).size
    }

    private inline fun <reified T> decode(raw: String?): List<T> =
        raw?.let { runCatching { Json.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_RECORDS = 200
        val KEY_DOWNLOADS = stringPreferencesKey("download_records")
        val KEY_UPLOADS = stringPreferencesKey("upload_records")
    }
}
