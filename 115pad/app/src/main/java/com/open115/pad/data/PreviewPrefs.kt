package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.previewDataStore by preferencesDataStore(name = "preview")

/**
 * 视频预览图缓存（文件页列表缩略图 + 预览条）的设置。
 *
 * 与 [PlayerPrefs]、`MediaPrefs` 同一套：Flow 读、suspend 写。
 * 上限到顶后由 [VideoPreviewStore] 按「最久没看」把最早的循环清掉（封面与预览条一起算）。
 */
class PreviewPrefs(private val context: Context) {

    /** 上限（MB）。默认 [DEFAULT_MAX_MB]：一张封面约 40KB、一帧预览条约 12KB，512MB 够上万部片 */
    val maxMb: Flow<Int> = context.previewDataStore.data.map { it[KEY_MAX_MB] ?: DEFAULT_MAX_MB }

    suspend fun setMaxMb(mb: Int) =
        context.previewDataStore.edit { it[KEY_MAX_MB] = mb.coerceIn(MIN_MAX_MB, MAX_MAX_MB) }

    companion object {
        private val KEY_MAX_MB = intPreferencesKey("max_mb")

        /** 最小 256MB：再小连几百部片的缩略图都装不下，抓一次清一次，反而更费流量 */
        const val MIN_MAX_MB = 256

        /** 最大 4GB：本机缓存没必要更大（真按这个量存满也就几千部片） */
        const val MAX_MAX_MB = 4096

        /** 滑块步长：设置页与存储都只走它的整数倍 */
        const val STEP_MB = 256

        const val DEFAULT_MAX_MB = 512
    }
}
