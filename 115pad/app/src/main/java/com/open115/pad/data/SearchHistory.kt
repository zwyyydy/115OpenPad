package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.searchDataStore by preferencesDataStore(name = "search")

/**
 * 搜索历史（文件页 / 媒体库各一份，互不串）。
 *
 * 只存最近 [MAX] 条，倒序。输入过程中的中间态（"庆" → "庆余" → "庆余年"）会在
 * [merge] 里被更长的最终词顶掉，所以即使用户是边打边搜、每一步都落一次，也不会攒出一串前缀。
 *
 * 只在本机；换设备/清数据就没了（不属于 CONFIG 备份的白名单）。
 */
class SearchHistory(private val context: Context) {

    enum class Scope(val key: String) {
        FILES("files"),
        MEDIA("media"),
    }

    val files: Flow<List<String>> = flow(Scope.FILES)
    val media: Flow<List<String>> = flow(Scope.MEDIA)

    private fun flow(scope: Scope): Flow<List<String>> =
        context.searchDataStore.data.map { decode(it[keyOf(scope)]) }

    suspend fun add(scope: Scope, query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        context.searchDataStore.edit { prefs ->
            prefs[keyOf(scope)] = encode(merge(decode(prefs[keyOf(scope)]), q))
        }
    }

    suspend fun clear(scope: Scope) {
        context.searchDataStore.edit { it.remove(keyOf(scope)) }
    }

    companion object {
        const val MAX = 12

        /** 新词放最前；去掉重复（不分大小写）和"它是新词前缀"的旧中间态 */
        internal fun merge(old: List<String>, query: String): List<String> {
            val q = query.trim()
            if (q.isBlank()) return old
            val rest = old.filterNot {
                it.equals(q, ignoreCase = true) || q.startsWith(it, ignoreCase = true)
            }
            return (listOf(q) + rest).take(MAX)
        }

        private fun keyOf(scope: Scope) = stringPreferencesKey("${scope.key}_queries")

        private fun decode(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                Json.decodeFromString(ListSerializer(String.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        private fun encode(list: List<String>): String =
            Json.encodeToString(ListSerializer(String.serializer()), list)
    }
}
