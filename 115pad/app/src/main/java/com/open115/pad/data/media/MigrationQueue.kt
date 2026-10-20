package com.open115.pad.data.media

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.migrationDataStore by preferencesDataStore(name = "migration")

/** 最多保留多少批记录。超了**只裁已结束的**，没跑完的一批都不丢 */
private const val MAX_TASKS = 30

/** 一部影片在某一批里的状态 */
enum class MigrationItemStatus { PENDING, DONE, SKIPPED, FAILED }

/**
 * 一批迁移里的一部影片。
 *
 * [fromDirPath] 是**入队那一刻**它所在的目录 —— 断点续跑靠它认"这一部上次跑没跑成"：
 * 重启后如果它的 dirPath 已经不是这个值了，说明上次确实搬过（云端搬完、扫描器也把行认回来了），
 * 直接标成功，不再对云端重发一次移动请求。
 */
@Serializable
data class MigrationItem(
    val mediaKey: String,
    val title: String,
    val fromDirPath: String = "",
    val status: String = MigrationItemStatus.PENDING.name,
    val message: String? = null,
) {
    /** 枚举化。认不出的值一律当 PENDING（重跑一次会先过一遍校验，比漏搬安全） */
    val st: MigrationItemStatus
        get() = runCatching { MigrationItemStatus.valueOf(status) }.getOrDefault(MigrationItemStatus.PENDING)
}

/**
 * 一批迁移（一次「开始迁移」= 一批，同一批共用同一个目标位置）。
 *
 * [finishedAt] == null 表示**未结束**（排队中 / 进行中 / 已暂停），重启后由 worker 自动捡起来续跑；
 * [paused] = 用户点了停止 —— 保留剩余项等他手动继续，重启后**不**自动恢复（与重命名队列同一套约定）。
 */
@Serializable
data class MigrationTask(
    val id: Long,
    val createdAt: Long,
    val targetLibraryId: Long,
    val targetName: String,
    val rootIndex: Int,
    val subDirCid: String? = null,
    val subPath: String = "",
    val items: List<MigrationItem>,
    val finishedAt: Long? = null,
    val paused: Boolean = false,
    val note: String? = null,
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.st != MigrationItemStatus.PENDING }
    val succeeded: Int get() = items.count { it.st == MigrationItemStatus.DONE }
    val skipped: Int get() = items.count { it.st == MigrationItemStatus.SKIPPED }
    val failed: Int get() = items.count { it.st == MigrationItemStatus.FAILED }
    val hasPending: Boolean get() = items.any { it.st == MigrationItemStatus.PENDING }

    /** 该跑但还没跑完（排队中 / 进行中） */
    val runnable: Boolean get() = finishedAt == null && !paused && hasPending

    /** 一部都没动过（用来判断"重试失败"值不值得给按钮） */
    val hasFailed: Boolean get() = failed > 0

    /** 目标位置的一句话描述：库名 + （可选的）子目录 */
    val targetLabel: String
        get() = if (subPath.isBlank()) "「$targetName」库根" else "「$targetName」/$subPath"

    val summaryLine: String
        get() = listOf(
            "$total 部",
            "成功 $succeeded",
            if (skipped > 0) "跳过 $skipped" else null,
            if (failed > 0) "失败 $failed" else null,
        ).filterNotNull().joinToString(" · ")
}

// ==================== 纯逻辑（可单测，不需要 Context） ====================

/**
 * 下一个该跑的任务：未结束、未暂停、还有待办项，取**创建最早**的（FIFO）。
 *
 * 全部处理完但还没标结束的（上次跑到最后一条被杀）不会被选中 —— 交给 worker 收尾即可。
 */
fun nextRunnableMigration(tasks: List<MigrationTask>): MigrationTask? =
    tasks.filter { it.runnable }.minByOrNull { it.createdAt }

/**
 * 超出上限时丢掉哪些：**只丢已结束的**，从最旧的开始。没结束的一批都不裁
 * （它可能还要续跑，丢掉等于把用户的任务弄没了）。
 */
fun trimMigrationTasks(tasks: List<MigrationTask>, max: Int = MAX_TASKS): List<MigrationTask> {
    if (tasks.size <= max) return tasks
    val (finished, unfinished) = tasks.partition { it.finishedAt != null }
    val keepFinished = (max - unfinished.size).coerceAtLeast(0)
    return (unfinished + finished.sortedByDescending { it.finishedAt }.take(keepFinished))
        .sortedBy { it.createdAt }
}

/**
 * 这批是不是"跑完了"：[finishedAt] 是 null 且没有待办项 = 上一次跑到最后一条就被杀了，
 * worker 该把它收尾成"结束"（而不是再跑一遍）。
 */
fun migrationTaskNeedsFinish(task: MigrationTask): Boolean =
    task.finishedAt == null && !task.hasPending

// ==================== 落盘队列 ====================

/**
 * 迁移队列的持久化（DataStore）。
 *
 * 为什么不用 Room：这里存的是"任务"不是"索引"，量小、只在入队/逐条更新时写，
 * 而且**加表要动数据库版本**（`MediaDatabase` 现在是 v19，一串手工迁移）——
 * 重命名队列当初就是这么选的，沿用同一套更省事也更稳。
 *
 * 读侧是内存 StateFlow、写侧落盘：界面每跑完一部都要刷新进度，读侧走 DataStore 流
 * 每次都要等磁盘往返，进度会一顿一顿的。所有读改写都在同一把锁里做。
 */
class MigrationQueue(private val context: Context) {

    private val lock = Mutex()
    private var loaded = false

    private val _tasks = MutableStateFlow<List<MigrationTask>>(emptyList())
    val tasks: StateFlow<List<MigrationTask>> = _tasks.asStateFlow()

    fun snapshot(): List<MigrationTask> = _tasks.value

    /** 队列里下一个该跑的（worker 用） */
    fun nextRunnable(): MigrationTask? = nextRunnableMigration(_tasks.value)

    /**
     * 把落盘的任务读进内存。**worker 进循环前必须先调它** —— 不然重启后内存表是空的，
     * 未完成的任务永远等不到人来跑（"启动即恢复"最关键的一步）。
     */
    suspend fun ensureLoaded() = lock.withLock {
        if (!loaded) {
            _tasks.value = read()
            loaded = true
        }
    }

    /** 入队一批。返回新批次 id */
    suspend fun enqueue(
        items: List<MigrationItem>,
        targetLibraryId: Long,
        targetName: String,
        rootIndex: Int,
        subDirCid: String?,
        subPath: String,
    ): Long {
        var newId = 0L
        mutate { cur ->
            val now = System.currentTimeMillis()
            newId = maxOf((cur.maxOfOrNull { it.id } ?: 0L) + 1, now)
            trimMigrationTasks(
                cur + MigrationTask(
                    id = newId,
                    createdAt = now,
                    targetLibraryId = targetLibraryId,
                    targetName = targetName,
                    rootIndex = rootIndex,
                    subDirCid = subDirCid,
                    subPath = subPath,
                    items = items,
                ),
            )
        }
        return newId
    }

    /** 一部的处理结果。**每处理完一条就落一次盘**，这就是崩溃恢复点 */
    suspend fun setItemStatus(
        taskId: Long,
        mediaKey: String,
        status: MigrationItemStatus,
        message: String? = null,
    ) = mutate { cur ->
        cur.map { t ->
            if (t.id != taskId) t
            else t.copy(
                items = t.items.map { it ->
                    if (it.mediaKey != mediaKey) it
                    else it.copy(status = status.name, message = message)
                },
            )
        }
    }

    /** 收尾：这一批跑完（或用户停了之后决定不再续） */
    suspend fun finish(taskId: Long, note: String? = null) = mutate { cur ->
        cur.map {
            if (it.id != taskId) it
            else it.copy(finishedAt = System.currentTimeMillis(), paused = false, note = note)
        }
    }

    /**
     * 记一条**已经跑完**的迁移（单部迁移用：详情页/海报墙只勾了一部时就地跑，不经过 worker）。
     *
     * 为什么不直接 [enqueue] 再 [setItemStatus] + [finish]：那样中间有一小段是 PENDING，
     * 而 worker 一直在等"有活可干" —— 它会当场把这一条捡起来**再搬一次**
     * （单部那条是对话框里自己 await 的，压根没进队列）。所以这里一次落成"已结束"，
     * 只进「迁移记录」，永远不进队列。
     *
     * [items] 必须**自带最终状态**（DONE/SKIPPED/FAILED），本方法只负责落盘。
     */
    suspend fun recordFinished(
        items: List<MigrationItem>,
        targetLibraryId: Long,
        targetName: String,
        rootIndex: Int,
        subDirCid: String?,
        subPath: String,
        note: String,
    ): Long {
        var newId = 0L
        mutate { cur ->
            val now = System.currentTimeMillis()
            newId = maxOf((cur.maxOfOrNull { it.id } ?: 0L) + 1, now)
            trimMigrationTasks(
                cur + MigrationTask(
                    id = newId,
                    createdAt = now,
                    targetLibraryId = targetLibraryId,
                    targetName = targetName,
                    rootIndex = rootIndex,
                    subDirCid = subDirCid,
                    subPath = subPath,
                    items = items,
                    finishedAt = now,
                    note = note,
                ),
            )
        }
        return newId
    }

    /**
     * 暂停 / 继续。继续时要**清掉 finishedAt**：它必须回到"未结束"，否则 worker 不会再捡起来。
     * 同理 [clearNote] 让"已停止"那句话跟着消失。
     */
    suspend fun setPaused(taskId: Long, paused: Boolean, note: String? = null) = mutate { cur ->
        cur.map {
            if (it.id != taskId) it
            else it.copy(
                paused = paused,
                finishedAt = if (paused) it.finishedAt else null,
                note = if (paused) (note ?: it.note) else null,
            )
        }
    }

    /** 把失败的那些退回待办（「重试失败」） */
    suspend fun retryFailed(taskId: Long) = mutate { cur ->
        cur.map { t ->
            if (t.id != taskId) t
            else t.copy(
                items = t.items.map { it ->
                    if (it.st == MigrationItemStatus.FAILED) {
                        it.copy(status = MigrationItemStatus.PENDING.name, message = null)
                    } else {
                        it
                    }
                },
                finishedAt = null,
                paused = false,
                note = null,
            )
        }
    }

    /** 删掉一批记录（只允许删已结束且没在跑的） */
    suspend fun remove(taskId: Long) = mutate { cur ->
        cur.filterNot { it.id == taskId && it.finishedAt != null }
    }

    // ---- 落盘 ----

    private suspend fun mutate(block: (List<MigrationTask>) -> List<MigrationTask>) = lock.withLock {
        if (!loaded) {
            _tasks.value = read()
            loaded = true
        }
        val next = block(_tasks.value)
        _tasks.value = next
        context.migrationDataStore.edit { p -> p[KEY] = json.encodeToString(next) }
    }

    private suspend fun read(): List<MigrationTask> {
        val raw = context.migrationDataStore.data.first()[KEY] ?: return emptyList()
        return runCatching { json.decodeFromString<List<MigrationTask>>(raw) }
            .onFailure { android.util.Log.w("MigrationQueue", "迁移队列解析失败，按空队列处理", it) }
            .getOrDefault(emptyList())
    }

    private companion object {
        val KEY = stringPreferencesKey("tasks")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
