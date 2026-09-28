package com.open115.pad.data.media

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mediaDataStore by preferencesDataStore(name = "media")

/**
 * 媒体库设置（跨重启保留）。
 *
 * 注意：**没有**"全局自动扫描"这一项 —— 每个库的开关是
 * [MediaLibraryEntity.autoScanOnStart]（每库独立），两者同名过一段时间，
 * 那个全局字段从来没被读过，已经删掉。
 */
class MediaPrefs(private val context: Context) {

    /** 媒体库落盘缓存总开关（默认开）。关掉 = 不读写磁盘缓存，已存的不动 */
    val cacheEnabled: Flow<Boolean> = context.mediaDataStore.data.map { it[KEY_CACHE_ENABLED] ?: true }

    /** 缓存上限（MB），默认 2 GB；[UNLIMITED_MB] = 不限制。主要限制海报字节，nfo 是 KB 级的 */
    val cacheMaxMb: Flow<Long> = context.mediaDataStore.data.map { it[KEY_CACHE_MAX_MB] ?: DEFAULT_MAX_MB }

    /** 作品列表的排序方式（作品页表头那个菜单选的），存名字不存序号 —— 序号改了不至于串味 */
    val worksSort: Flow<WorksSort> =
        context.mediaDataStore.data.map { WorksSort.ofName(it[KEY_WORKS_SORT]) }

    /**
     * 扫描队列的整份快照（JSON，结构见 [MediaScanner] 里的 ScanQueueSnapshot）。
     *
     * 存**整份**而不是逐条增删：队列最多几条，一次写清楚比逐条维护简单得多，
     * 也不会读出"删了一半"的状态。启动时读一次（[scanQueueJson]），之后由扫描器负责写。
     */
    suspend fun scanQueueJson(): String? = context.mediaDataStore.data.first()[KEY_SCAN_QUEUE]

    suspend fun setScanQueue(json: String) =
        context.mediaDataStore.edit { it[KEY_SCAN_QUEUE] = json }

    suspend fun setWorksSort(sort: WorksSort) =
        context.mediaDataStore.edit { it[KEY_WORKS_SORT] = sort.name }

    suspend fun setCacheEnabled(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_CACHE_ENABLED] = enabled }

    suspend fun setCacheMaxMb(mb: Long) = context.mediaDataStore.edit {
        it[KEY_CACHE_MAX_MB] = if (mb <= 0L) UNLIMITED_MB else mb.coerceIn(MIN_MAX_MB, MAX_MAX_MB)
    }

    // ---- 流水线同步（服务器刮削完把结果搬进媒体库，见 [PadSync]）----

    /** 自动同步总开关。默认开：没配过也能自己找到信号目录（媒体库根下的 `.pad_signal`） */
    val padSyncEnabled: Flow<Boolean> = context.mediaDataStore.data.map { it[KEY_PAD_ENABLED] ?: true }

    /** 信号目录的 cid（自动找到或用户手选；空 = 还没找到，下次轮询再找） */
    val padSignalCid: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_DIR_CID] ?: "" }

    /** 信号目录的名字，只为设置页显示（真正的定位靠 cid） */
    val padSignalName: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_DIR_NAME] ?: "" }

    /**
     * 已处理到哪个信号文件（文件名就是时间戳，按字符串比大小即时间序）。
     *
     * 落盘而不是只放内存：App 没开着的期间服务器攒下的信号，下次启动要接着处理。
     */
    val padCursor: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_CURSOR] ?: "" }

    /** 轮询间隔（分钟） */
    val padPollMinutes: Flow<Int> = context.mediaDataStore.data.map { it[KEY_PAD_POLL_MINUTES] ?: 10 }

    /** 上次轮询时刻（设置页状态行） */
    val padLastAt: Flow<Long> = context.mediaDataStore.data.map { it[KEY_PAD_LAST_AT] ?: 0L }

    /** 上次轮询结果一句话（设置页状态行） */
    val padLastNote: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_LAST_NOTE] ?: "" }

    suspend fun setPadSyncEnabled(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_PAD_ENABLED] = enabled }

    suspend fun setPadSignalDir(cid: String, name: String) = context.mediaDataStore.edit {
        it[KEY_PAD_DIR_CID] = cid
        it[KEY_PAD_DIR_NAME] = name
    }

    suspend fun setPadCursor(name: String) = context.mediaDataStore.edit { it[KEY_PAD_CURSOR] = name }

    suspend fun setPadPollMinutes(minutes: Int) =
        context.mediaDataStore.edit { it[KEY_PAD_POLL_MINUTES] = minutes.coerceIn(1, 24 * 60) }

    suspend fun setPadLast(at: Long, note: String) = context.mediaDataStore.edit {
        it[KEY_PAD_LAST_AT] = at
        it[KEY_PAD_LAST_NOTE] = note
    }

    companion object {
        const val DEFAULT_MAX_MB = 2048L
        const val MIN_MAX_MB = 512L
        const val MAX_MAX_MB = 8192L

        /** "不限制"挡位的存储值。0 的语义（上限 ≤ 0 视为不限）消费端各有判，见 textPoolBytes / pruneCache / sweep */
        const val UNLIMITED_MB = 0L

        /** 滑块步进 */
        const val STEP_MB = 256L

        private val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        private val KEY_CACHE_MAX_MB = longPreferencesKey("cache_max_mb")
        private val KEY_WORKS_SORT = stringPreferencesKey("works_sort")
        private val KEY_SCAN_QUEUE = stringPreferencesKey("scan_queue")
        private val KEY_PAD_ENABLED = booleanPreferencesKey("pad_sync_enabled")
        private val KEY_PAD_DIR_CID = stringPreferencesKey("pad_signal_cid")
        private val KEY_PAD_DIR_NAME = stringPreferencesKey("pad_signal_name")
        private val KEY_PAD_CURSOR = stringPreferencesKey("pad_cursor")
        private val KEY_PAD_POLL_MINUTES = intPreferencesKey("pad_poll_minutes")
        private val KEY_PAD_LAST_AT = longPreferencesKey("pad_last_at")
        private val KEY_PAD_LAST_NOTE = stringPreferencesKey("pad_last_note")

        /**
         * nfo 文本池从总上限里分到的份额（5%）。
         *
         * 单独切一小块而不是共用一个池：海报是 MB 级、nfo 是 KB 级，量级差三个数量级，
         * 混在一起淘汰时 nfo 会被海报挤光（而且几乎不影响总量，挤掉也不释放空间）。
         * 份额取总上限的 5% —— nfo 实际远用不满（几千部片才几 MB），这个数只是天花板；
         * 上限为 0（不限制）时算出来也是 0，MediaCache.sweep 把 ≤ 0 一并视为不限。
         */
        fun textPoolBytes(totalMb: Long): Long = totalMb * 1024 * 1024 / 20
    }
}
