package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.filterDataStore by preferencesDataStore(name = "filter")

/**
 * 过滤方案持久化 + 文件页右上角的总开关。
 *
 * 方案整体序列化成一个 JSON 字符串存 DataStore：
 * 方案数量是"个位数到几十个"量级，整存整取比逐字段拆 key 简单可靠得多。
 */
class FilterPrefs(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** 全部方案（列表顺序即优先级：同目录命中多个时取靠前的） */
    val schemes: Flow<List<FilterRules.FilterScheme>> = context.filterDataStore.data.map { p ->
        p[KEY_SCHEMES]?.let { raw ->
            runCatching { json.decodeFromString<List<FilterRules.FilterScheme>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /** 文件页右上角的过滤总开关（跨重启保留） */
    val toggle: Flow<Boolean> = context.filterDataStore.data.map { it[KEY_ENABLED] ?: true }

    suspend fun setSchemes(list: List<FilterRules.FilterScheme>) =
        context.filterDataStore.edit { it[KEY_SCHEMES] = json.encodeToString(list) }

    suspend fun setToggle(v: Boolean) =
        context.filterDataStore.edit { it[KEY_ENABLED] = v }

    private companion object {
        val KEY_SCHEMES = stringPreferencesKey("schemes_json")
        val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
