package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadDataStore by preferencesDataStore(name = "download")

/** 云下载相关偏好：剪贴板识别的静默模式 + 上次已处理内容的指纹 */
class DownloadPrefs(private val context: Context) {

    /** 开启后：识别到剪贴板里的下载链接直接静默提交，不再弹窗二次确认 */
    val autoSubmitClipboardDownload: Flow<Boolean> =
        context.downloadDataStore.data.map { it[KEY_AUTO_SUBMIT] ?: false }

    /** 上一次已解析/已提示过的剪贴板内容指纹（MD5），跨重启保留以避免重复打扰 */
    val lastClipboardKey: Flow<String?> =
        context.downloadDataStore.data.map { it[KEY_LAST_CLIP] }

    suspend fun setAutoSubmitClipboardDownload(v: Boolean) =
        context.downloadDataStore.edit { it[KEY_AUTO_SUBMIT] = v }

    /**
     * 云下载保存位置：设置一次，后续所有提交路径都落到这里——
     * 手动添加、剪贴板识别、外部 App 唤起共用同一份，跨重启保留。
     */
    val saveLocation: Flow<SaveLocation> = context.downloadDataStore.data.map { p ->
        SaveLocation(p[KEY_WP_CID] ?: "0", p[KEY_WP_NAME] ?: "根目录")
    }

    suspend fun setSaveLocation(loc: SaveLocation) = context.downloadDataStore.edit {
        it[KEY_WP_CID] = loc.cid
        it[KEY_WP_NAME] = loc.name
    }

    /** 云下载保存位置（cid + 可展示名） */
    data class SaveLocation(val cid: String, val name: String)

    suspend fun setLastClipboardKey(key: String) =
        context.downloadDataStore.edit { it[KEY_LAST_CLIP] = key }

    suspend fun currentLastClipboardKey(): String? = lastClipboardKey.first()

    private companion object {
        val KEY_AUTO_SUBMIT = booleanPreferencesKey("auto_submit_clipboard_download")
        val KEY_LAST_CLIP = stringPreferencesKey("last_clipboard_key")
        val KEY_WP_CID = stringPreferencesKey("wp_path_cid")
        val KEY_WP_NAME = stringPreferencesKey("wp_path_name")
    }
}
