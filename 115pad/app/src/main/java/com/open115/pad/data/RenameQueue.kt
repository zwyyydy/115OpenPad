package com.open115.pad.data

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

private val Context.renameDataStore by preferencesDataStore(name = "rename")

/** 最多保留多少个任务记录。超了**只裁已结束的**，未完成的一个都不丢 */
private const val MAX_TASKS = 20

/** 单个文件在任务里的状态 */
enum class RenameItemStatus { PENDING, DONE, FAILED }

/**
 * 执行速度档位。115 的限流细则不公开，所以给用户一个能自己往保守调的旋钮。
 *
 * 默认 [NORMAL]：400ms 一个，100 个文件约 40 秒。嫌慢可以调快，但**快档有触发限流的风险**。
 * 任务里存的是 [intervalMs] 数值而不是档位名 —— 以后加档位、改名字都不会让旧任务失效。
 */
enum class RenameSpeed(val label: String, val intervalMs: Long) {
    FAST("快", 200L),
    NORMAL("标准", 400L),
    SLOW("保守", 800L),
}

/**
 * 任务里的一个文件。
 *
 * 逐条落库是**崩溃恢复精度的关键**：进程被杀后重启，只重发还是 PENDING 的那些，
 * 已改过的不再发请求（115 有限流，重复发是实打实的浪费）。
 */
@Serializable
data class RenameTaskItem(
    val fid: String,
    val oldName: String,
    val newName: String,
    val status: String = RenameItemStatus.PENDING.name,
    val error: String? = null,
) {
    /** 枚举化。认不出的值一律当 PENDING（重发一次是无害的幂等操作，好过漏改） */
    val st: RenameItemStatus
        get() = runCatching { RenameItemStatus.valueOf(status) }.getOrDefault(RenameItemStatus.PENDING)
}

/**
 * 一次批量重命名任务。
 *
 * [finishedAt] == null 表示**未结束**（排队中 / 进行中 / 已暂停），这是恢复扫描的判据；
 * 与上传记录同一套约定（`finishedAt == null && !paused` = 该继续跑）。
 */
@Serializable
data class RenameTask(
    val id: Long,
    val createdAt: Long,
    /** 来源目录，仅用于显示与操作记录 */
    val cid: String,
    val dirName: String,
    /** 限频间隔（毫秒）。存数值而不是档位名 —— 以后加档位/改名字都不会让旧数据失效 */
    val intervalMs: Long,
    val items: List<RenameTaskItem>,
    val finishedAt: Long? = null,
    /** 用户主动暂停：保留剩余项，等手动继续；重启后保持暂停不被自动恢复 */
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    /** 暂停/中止的原因（如"连续失败过多已中止"），给界面显示 */
    val note: String? = null,
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.st != RenameItemStatus.PENDING }
    val succeeded: Int get() = items.count { it.st == RenameItemStatus.DONE }
    val failed: Int get() = items.count { it.st == RenameItemStatus.FAILED }
    /** 还有没有待处理的：决定还能不能继续/重试 */
    val hasPending: Boolean get() = items.any { it.st == RenameItemStatus.PENDING }
    /** 该跑但还没跑完（排队中或进行中，由 worker 的 activeTaskId 区分是哪种） */
    val runnable: Boolean get() = finishedAt == null && !paused && hasPending
}

// ==================== 纯逻辑（可单测，不需要 Context） ====================

/**
 * 队列里下一个该跑的任务：未结束、未暂停、还有待处理项，取**创建最早**的（FIFO）。
 *
 * 已经全部处理完（没有 PENDING）但还没标结束的任务不会被选中 —— 那种状态说明
 * 上一次跑到最后一条就被杀了，交给 worker 收尾即可，不该重跑一遍。
 */
fun nextRunnableTask(tasks: List<RenameTask>): RenameTask? =
    tasks.filter { it.runnable }.minByOrNull { it.createdAt }

/**
 * 超出上限时丢掉哪些：**只丢已结束的**，从最旧的开始。
 *
 * 未结束的任务可能还要续跑，丢掉等于把用户的任务弄没了 —— 所以哪怕未结束的
 * 数量本身已经超过上限，也一个都不裁。
 */
fun trimTasks(tasks: List<RenameTask>, max: Int = MAX_TASKS): List<RenameTask> {
    if (tasks.size <= max) return tasks
    val (finished, unfinished) = tasks.partition { it.finishedAt != null }
    val keepFinished = (max - unfinished.size).coerceAtLeast(0)
    return (unfinished + finished.sortedByDescending { it.finishedAt }.take(keepFinished))
        .sortedBy { it.createdAt }
}

/**
 * 批量重命名任务的持久化队列。
 *
 * 读侧是**内存 StateFlow**、写侧落 DataStore：UI 每完成一个文件都要刷新进度，
 * 如果读侧也走 DataStore 流，每次都要等一次磁盘往返，进度条会一顿一顿的。
 * 所有读改写都在同一把锁里做，避免"启动加载"和"用户入队"抢着写导致丢更新。
 */
class RenameQueue(private val context: Context) {

    private val lock = Mutex()
    private var loaded = false

    private val _tasks = MutableStateFlow<List<RenameTask>>(emptyList())
    val tasks: StateFlow<List<RenameTask>> = _tasks.asStateFlow()

    fun snapshot(): List<RenameTask> = _tasks.value

    /** 下一个该跑的任务（给 worker 用） */
    fun nextRunnable(): RenameTask? = nextRunnableTask(_tasks.value)

    /**
     * 把落库的任务读进内存。
     *
     * ⚠️ **worker 在进入循环前必须先调它**：内存表在进程刚起来时是空的，而
     * [nextRunnable] 读的就是内存表 —— 不先加载的话重启后队列看着是空的，
     * 未完成的任务永远等不到人来跑（这正是"启动即恢复"最关键的一步）。
     */
    suspend fun ensureLoaded() = lock.withLock {
        if (!loaded) {
            _tasks.value = read()
            loaded = true
        }
    }

    /**
     * 入队。返回新任务 id。
     *
     * [items] 里新旧同名的项在这里就被剔掉 —— 既省调用，也避免对文件做无意义的写操作。
     */
    suspend fun enqueue(
        items: List<RenameTaskItem>,
        cid: String,
        dirName: String,
        intervalMs: Long,
    ): Long? {
        val work = items.filter { it.fid.isNotBlank() && it.newName != it.oldName }
        if (work.isEmpty()) return null
        var newId = 0L
        mutate { cur ->
            val now = System.currentTimeMillis()
            newId = maxOf((cur.maxOfOrNull { it.id } ?: 0L) + 1, now)
            trimTasks(cur + RenameTask(
                id = newId,
                createdAt = now,
                cid = cid,
                dirName = dirName,
                intervalMs = intervalMs,
                items = work,
            ))
        }
        return newId
    }

    /** 单个文件的处理结果。每处理完一条就落一次库，这就是崩溃恢复点 */
    suspend fun setItemStatus(taskId: Long, index: Int, status: RenameItemStatus, error: String? = null) =
        mutate { cur ->
            cur.map { t ->
                if (t.id != taskId) t
                else t.copy(items = t.items.mapIndexed { i, it ->
                    if (i != index) it else it.copy(status = status.name, error = error)
                })
            }
        }

    suspend fun finish(taskId: Long, cancelled: Boolean = false, note: String? = null) =
        mutate { cur ->
            cur.map {
                if (it.id != taskId) it
                else it.copy(finishedAt = System.currentTimeMillis(), cancelled = cancelled, paused = false, note = note)
            }
        }

    suspend fun setPaused(taskId: Long, paused: Boolean, note: String? = null) =
        mutate { cur ->
            cur.map {
                if (it.id != taskId) it
                // 继续时清掉 finishedAt：它必须回到"未结束"，否则 worker 不会再捡起它
                else it.copy(paused = paused, note = note, finishedAt = if (paused) it.finishedAt else null)
            }
        }

    /** 重试：把失败项打回 PENDING 并重新打开任务（已完成的任务也能重试） */
    suspend fun retryFailed(taskId: Long) = mutate { cur ->
        cur.map { t ->
            if (t.id != taskId) t
            else t.copy(
                items = t.items.map {
                    if (it.st == RenameItemStatus.FAILED) it.copy(status = RenameItemStatus.PENDING.name, error = null) else it
                },
                finishedAt = null,
                cancelled = false,
                paused = false,
                note = null,
            )
        }
    }

    suspend fun remove(taskId: Long) = mutate { cur -> cur.filterNot { it.id == taskId } }

    /** 清掉所有已结束的任务 */
    suspend fun clearFinished() = mutate { cur -> cur.filter { it.finishedAt == null } }

    /**
     * 读改写全程持锁：不持锁的话「启动时首次加载」和「用户立刻入队」会互相覆盖，
     * 表现就是刚建的任务凭空消失。
     */
    private suspend fun mutate(block: (List<RenameTask>) -> List<RenameTask>) = lock.withLock {
        if (!loaded) {
            _tasks.value = read()
            loaded = true
        }
        val next = block(_tasks.value)
        _tasks.value = next
        context.renameDataStore.edit { it[KEY_TASKS] = Json.encodeToString(next) }
    }

    private suspend fun read(): List<RenameTask> = runCatching {
        // 只取当前值：这是一次性的启动加载，不需要持续订阅
        val raw = context.renameDataStore.data.first()[KEY_TASKS]
        raw?.let { Json.decodeFromString<List<RenameTask>>(it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        val KEY_TASKS = stringPreferencesKey("rename_tasks")
    }
}
