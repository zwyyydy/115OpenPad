package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backupDataStore by preferencesDataStore(name = "backup")

/**
 * 可以备份的三类东西。**分开**存、分开设间隔、分开列文件 —— 用户要的就是这份自由：
 * 配置几乎不变（备份频率低）、文件操作记录天天在长；媒体库（数据+记录+图片）只
 * 在换机/恢复时用得上，且图片缓存几百 MB 起步，所以合成一类、默认只手动。
 */
enum class BackupKind(
    /** 存偏好与文件名用的短名 */
    val key: String,
    /** 设置页上的名字 */
    val label: String,
) {
    CONFIG("config", "配置文件"),
    MEDIA("media", "媒体库"),
    OPLOG("oplog", "文件操作记录"),
}

/**
 * 备份偏好：开关 / 间隔 / 上次备份时间（每个 kind 一套）+ 云端备份目录。
 *
 * 间隔语义与媒体库的自动扫描一致：0 = 不按间隔（只手动）；从未备份过（lastAt=0）视为"该备份了"。
 */
class BackupPrefs(private val context: Context) {

    fun enabled(kind: BackupKind): Flow<Boolean> =
        context.backupDataStore.data.map { it[booleanPreferencesKey("enabled_${kind.key}")] ?: false }

    suspend fun setEnabled(kind: BackupKind, v: Boolean) = context.backupDataStore.edit {
        it[booleanPreferencesKey("enabled_${kind.key}")] = v
    }

    /** 自动备份间隔（小时）。默认 24 小时；0 = 只手动。媒体库含几百 MB 图片，默认只手动 */
    fun intervalHours(kind: BackupKind): Flow<Int> =
        context.backupDataStore.data.map {
            it[intPreferencesKey("interval_${kind.key}")] ?: if (kind == BackupKind.MEDIA) 0 else 24
        }

    suspend fun setIntervalHours(kind: BackupKind, v: Int) = context.backupDataStore.edit {
        it[intPreferencesKey("interval_${kind.key}")] = v.coerceIn(0, 24 * 30)
    }

    /** 上次成功备份的时间戳（0 = 从没备份过） */
    fun lastAt(kind: BackupKind): Flow<Long> =
        context.backupDataStore.data.map { it[longPreferencesKey("last_${kind.key}")] ?: 0L }

    suspend fun setLastAt(kind: BackupKind, at: Long) = context.backupDataStore.edit {
        it[longPreferencesKey("last_${kind.key}")] = at
    }

    /** 单个 kind 的状态聚合（设置页一次 collect 用） */
    data class KindState(val enabled: Boolean, val intervalHours: Int, val lastAt: Long)

    fun state(kind: BackupKind): Flow<KindState> = kotlinx.coroutines.flow.combine(
        enabled(kind), intervalHours(kind), lastAt(kind),
    ) { e, i, l -> KindState(e, i, l) }

    /** 云端备份目录（cid + 名字）；空 = 还没定位过，备份时自动在根目录下找/建 */
    val dirCid: Flow<String> = context.backupDataStore.data.map { it[KEY_DIR_CID] ?: "" }
    val dirName: Flow<String> = context.backupDataStore.data.map { it[KEY_DIR_NAME] ?: "" }

    suspend fun setDir(cid: String, name: String) = context.backupDataStore.edit {
        it[KEY_DIR_CID] = cid
        it[KEY_DIR_NAME] = name
    }

    private companion object {
        /** 根目录建这个文件夹当备份落点 */
        const val DEFAULT_DIR_NAME = "115Pad备份"
        val KEY_DIR_CID = stringPreferencesKey("dir_cid")
        val KEY_DIR_NAME = stringPreferencesKey("dir_name")
    }
}
