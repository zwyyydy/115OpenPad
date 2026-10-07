package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.filesDataStore by preferencesDataStore(name = "files")

/** 文件页状态记忆：排序、视图、筛选 */
class FilesPrefs(private val context: Context) {

    val order: Flow<String> = context.filesDataStore.data.map { it[KEY_ORDER] ?: "file_name" }
    val asc: Flow<Int> = context.filesDataStore.data.map { it[KEY_ASC] ?: 1 }

    /**
     * 视图模式：0=列表 1=小图标 2=大图标。
     * 旧存档是布尔 grid_mode（true=图标），首次读取时迁移成对应的三态值；
     * 一旦写过新键就不再看旧键。
     */
    val viewMode: Flow<Int> = context.filesDataStore.data.map {
        it[KEY_VIEW] ?: if (it[KEY_GRID] == false) 0 else 1
    }
    val typeFilter: Flow<Int> = context.filesDataStore.data.map { it[KEY_TYPE] ?: -1 } // -1 = 全部
    val starOnly: Flow<Boolean> = context.filesDataStore.data.map { it[KEY_STAR] ?: false }

    /** 双面板模式（类 MT 管理器，跨栏快速移动）：记住开关，重启恢复 */
    val dualPane: Flow<Boolean> = context.filesDataStore.data.map { it[KEY_DUAL] ?: false }

    /** 列表里视频行下面那条宽幅预览条（按片长抓的 2~10 帧）。默认开 */
    val videoStrip: Flow<Boolean> = context.filesDataStore.data.map { it[KEY_VIDEO_STRIP] ?: true }

    suspend fun setSort(order: String, asc: Int) = context.filesDataStore.edit {
        it[KEY_ORDER] = order
        it[KEY_ASC] = asc
    }

    suspend fun setViewMode(v: Int) = context.filesDataStore.edit { it[KEY_VIEW] = v }
    suspend fun setTypeFilter(v: Int?) = context.filesDataStore.edit { it[KEY_TYPE] = v ?: -1 }
    suspend fun setStarOnly(v: Boolean) = context.filesDataStore.edit { it[KEY_STAR] = v }
    suspend fun setDualPane(v: Boolean) = context.filesDataStore.edit { it[KEY_DUAL] = v }
    suspend fun setVideoStrip(v: Boolean) = context.filesDataStore.edit { it[KEY_VIDEO_STRIP] = v }

    private companion object {
        val KEY_ORDER = stringPreferencesKey("order")
        val KEY_ASC = intPreferencesKey("asc")
        val KEY_GRID = booleanPreferencesKey("grid_mode") // 旧键：只为迁移读取，不再写入
        val KEY_VIEW = intPreferencesKey("view_mode")
        val KEY_TYPE = intPreferencesKey("type_filter")
        val KEY_STAR = booleanPreferencesKey("star_only")
        val KEY_DUAL = booleanPreferencesKey("dual_pane")
        val KEY_VIDEO_STRIP = booleanPreferencesKey("video_strip")
    }
}
