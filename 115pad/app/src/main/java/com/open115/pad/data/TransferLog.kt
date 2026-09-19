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
 * 一次上传的记录。小文件是"一次性 PUT"没有进度；大文件走分片上传，
 * 每传完一片回写一次 uploaded（整数百分比变化才落库），传输中心能显示进度。
 * 开始/结束时间、目标目录、成败与是否秒传都值得留痕——云盘里文件名相同、
 * 时间久了根本想不起来传哪去了。
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
    /** 已上传字节（分片上传才有意义；小文件保持 0 = 不显示进度） */
    val uploaded: Long = 0,
    /** null = 上传中 */
    val finishedAt: Long? = null,
    val ok: Boolean? = null,
    val reused: Boolean = false,
    val error: String? = null,
    // ---- 断点续传会话（init/resume 拿到后立刻落库，进程被杀也能恢复）----
    /** SAF 文件 uri（takePersistableUriPermission 后跨进程仍可读） */
    val uri: String? = null,
    /** 115 上传任务 ID（init/resume 返回） */
    val pickCode: String? = null,
    /** OSS 分片会话 ID（POST ?uploads 返回；会话服务端默认保留，ListParts 探测续传） */
    val ossUploadId: String? = null,
    /** 全量 SHA1（小写）；恢复时重算比对，文件变了就不续传 */
    val fileSha1: String? = null,
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
        uri: String? = null,
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
                uri = uri,
            )
            p[KEY_UPLOADS] = Json.encodeToString((cur + rec).takeLast(MAX_RECORDS))
        }
        activeUploads.add(id)
        return id
    }

    /** init/resume 拿到上传会话后立刻落库——之后进程被杀也能按会话恢复 */
    suspend fun updateUploadSession(id: Long, sha1: String, pickCode: String, ossUploadId: String?) =
        context.transferDataStore.edit { p ->
            val cur = decode<UploadRecord>(p[KEY_UPLOADS])
            p[KEY_UPLOADS] = Json.encodeToString(
                cur.map {
                    if (it.id != id) it
                    else it.copy(fileSha1 = sha1, pickCode = pickCode, ossUploadId = ossUploadId)
                },
            )
        }

    /** 回填上传结果；ok=false 时带上错误信息 */
    suspend fun finishUpload(id: Long, ok: Boolean, reused: Boolean = false, error: String? = null) {
        activeUploads.remove(id)
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
    }

    /**
     * 取走"中断待续传"的上传记录（进程被杀时 finishUpload 没跑，记录停留在上传中）。
     * 只认领带会话信息（pickCode/ossUploadId）且当前不在进行中的记录；认领结果在
     * 进程内记账，重复调用（反复进出文件页）不会二次接管同一条。
     */
    suspend fun claimPendingUploads(): List<UploadRecord> {
        val p = context.transferDataStore.data.first()
        val fresh = decode<UploadRecord>(p[KEY_UPLOADS])
            .filter {
                it.finishedAt == null &&
                    (it.ossUploadId != null || it.pickCode != null) &&
                    it.uri != null &&
                    it.id !in activeUploads &&
                    resuming.add(it.id)
            }
        return fresh
    }

    /**
     * 分片上传的进度回写。调用方（uploadLargeLogged）已按整数百分比变化节流，
     * 这里不重复节流——DataStore 每次edit都是一次事务，避免大文件高频小步写。
     */
    suspend fun updateUploadProgress(id: Long, uploaded: Long) =
        context.transferDataStore.edit { p ->
            val cur = decode<UploadRecord>(p[KEY_UPLOADS])
            p[KEY_UPLOADS] = Json.encodeToString(
                cur.map { if (it.id == id) it.copy(uploaded = uploaded) else it },
            )
        }

    /** 单条上传记录（自动续传重试前查会话信息用） */
    suspend fun uploadRecord(id: Long): UploadRecord? =
        context.transferDataStore.data.first()
            .let { decode<UploadRecord>(it[KEY_UPLOADS]).find { r -> r.id == id } }

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

    /** 进程内正在上传/已认领续传的记录 id（内存态，防恢复扫描与进行中的上传双开） */
    private val activeUploads = mutableSetOf<Long>()
    private val resuming = mutableSetOf<Long>()
}
