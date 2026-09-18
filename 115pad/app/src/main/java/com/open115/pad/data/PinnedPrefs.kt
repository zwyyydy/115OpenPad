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

private val Context.pinDataStore by preferencesDataStore(name = "pinned")

/**
 * 一个被置顶的文件夹。
 *
 * 只以 [fid] 认人：文件夹改名或被移动后 fid 不变，置顶关系自动跟随；
 * 而"它属于哪个目录"是由 fid 唯一决定的（一个文件夹只会出现在它的父目录里），
 * 所以判断是否置顶不需要 pid 参与——[pid] 仅用于排查与将来按目录分组的展示。
 */
@Serializable
data class PinnedFolder(
    val fid: String,
    val name: String = "",
    val pid: String? = null,
    val at: Long = 0,
)

/**
 * 文件夹置顶持久化（只在本机生效）。
 *
 * 115 开放平台没有置顶接口，所以置顶是纯客户端行为：整个有序列表序列化成一个 JSON 串
 * 存进 DataStore。列表顺序 = 置顶条目的展示优先级（先置顶的排在更前面），
 * 条目规模是"个位到几十"，整存整取比拆成一堆 key 简单可靠得多（同 FilterPrefs 的做法）。
 */
class PinnedPrefs(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** 全部置顶文件夹，顺序即展示优先级 */
    val pins: Flow<List<PinnedFolder>> = context.pinDataStore.data.map { it[KEY_PINS].decode() }

    /** 置顶；重复置顶同一文件夹不会产生两条记录 */
    suspend fun pin(folder: PinnedFolder) = context.pinDataStore.edit { p ->
        val list = p[KEY_PINS].decode().filterNot { it.fid == folder.fid }
        p[KEY_PINS] = json.encodeToString(list + folder)
    }

    suspend fun unpin(fid: String) = context.pinDataStore.edit { p ->
        val list = p[KEY_PINS].decode()
        val next = list.filterNot { it.fid == fid }
        if (next.size != list.size) p[KEY_PINS] = json.encodeToString(next)
    }

    /**
     * 批量取消置顶。
     * 置顶的文件夹被删除或移出当前目录后，置顶记录本身不会再产生任何效果，
     * 但会一直留在库里——删除操作顺手清理，避免记录无限累积。
     */
    suspend fun unpinAll(fids: Collection<String>) {
        if (fids.isEmpty()) return
        context.pinDataStore.edit { p ->
            val list = p[KEY_PINS].decode()
            val next = list.filterNot { it.fid in fids }
            if (next.size != list.size) p[KEY_PINS] = json.encodeToString(next)
        }
    }

    /** 容错解析：存档损坏时按"没有置顶"处理，不让文件页整体挂掉 */
    private fun String?.decode(): List<PinnedFolder> =
        this?.let { runCatching { json.decodeFromString<List<PinnedFolder>>(it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        val KEY_PINS = stringPreferencesKey("pins_json")
    }
}
