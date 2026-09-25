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

private val Context.quickDirsDataStore by preferencesDataStore(name = "quick_dirs")

/**
 * 一条快捷目录（文件页侧栏）。
 *
 * [cid] 是 115 的目录 id（跳转走 [FilesViewModel.openByCid]，只需 cid + 名字）；
 * [path] 是收藏时的完整路径（`test/刮削测试`），只用来在侧栏显示副标题 ——
 * 同名目录不少见，光看名字认不出是哪一个。
 */
@Serializable
data class QuickDir(
    val cid: String,
    val name: String,
    val path: String? = null,
)

/**
 * 快捷目录收藏：文件页侧栏"快捷目录"区的数据，点击直达对应目录。
 *
 * 与 [OpLog] 同一套做法：DataStore 存 JSON，丢了也只是少几个书签，不值得上数据库。
 * 按添加顺序保存（先加的在前），去重按 cid —— 同一个目录不会出现两条。
 */
class QuickDirs(private val context: Context) {

    val entries: Flow<List<QuickDir>> = context.quickDirsDataStore.data
        .map { decode(it[KEY_DIRS]) }

    /**
     * 收藏一个目录。同一个 cid 已存在或超过 [MAX_DIRS] 时不做任何事并返回 false
     * （调用方据此提示"已收藏过 / 收藏满了"），否则追加到末尾返回 true。
     */
    suspend fun add(cid: String, name: String, path: String?): Boolean {
        val cur = decode<QuickDir>(context.quickDirsDataStore.data.first()[KEY_DIRS])
        if (cur.any { it.cid == cid } || cur.size >= MAX_DIRS) return false
        context.quickDirsDataStore.edit { p ->
            // 重新读一遍再拼：add/remove 理论上可能并发（都来自 UI 线程，这里只是求稳）
            p[KEY_DIRS] = Json.encodeToString(decode<QuickDir>(p[KEY_DIRS]) + QuickDir(cid, name, path))
        }
        return true
    }

    suspend fun remove(cid: String) = context.quickDirsDataStore.edit { p ->
        p[KEY_DIRS] = Json.encodeToString(decode<QuickDir>(p[KEY_DIRS]).filterNot { it.cid == cid })
    }

    private inline fun <reified T> decode(raw: String?): List<T> =
        raw?.let { runCatching { Json.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        /** 侧栏一屏滚好几屏就没意义了，封顶 30 个 */
        const val MAX_DIRS = 30
        val KEY_DIRS = stringPreferencesKey("quick_dirs")
    }
}
