package com.open115.pad.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量重命名的**队列执行器**：一条常驻协程，把 [RenameQueue] 里的任务**一个一个**跑完。
 *
 * 为什么是"一条循环"而不是"每个任务起一个协程"：
 * - 115 对文件操作有频率限制，**串行**是防限流的主要手段，任务之间也不该并行；
 * - 更重要的是**启动即恢复**：worker 一跑起来就会捡起队列里所有未完成的任务，
 *   不需要像上传那样在某个界面的 LaunchedEffect 里做一次扫描（那种做法依赖用户先打开那个页面）。
 *
 * ⚠️ 全程一次只发一个重命名请求。限频靠每条之间的 [RenameTask.intervalMs]。
 */
class RenameQueueWorker(
    private val api: OpenApi,
    private val queue: RenameQueue,
    private val opLog: OpLog?,
) {

    private val _activeTaskId = MutableStateFlow<Long?>(null)

    /** 当前正在跑的任务 id。界面用它区分「排队中」和「重命名中」 */
    val activeTaskId: StateFlow<Long?> = _activeTaskId.asStateFlow()

    private var worker: Job? = null
    private var runningTask: Job? = null

    /**
     * 意图集。与上传同一套写法：先登记意图再 cancel，由 catch 分支决定落库成"暂停"还是"取消"
     * —— 直接看 CancellationException 是分不出用户到底想干嘛的。
     */
    private val pauseRequests = mutableSetOf<Long>()
    private val cancelRequests = mutableSetOf<Long>()

    /** 幂等：已在跑就什么都不做。登录后调用一次，启动即恢复未完成的任务 */
    fun start(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            // 先把落库的任务读进内存：不然内存队列看着是空的，重启后就什么都不跑
            queue.ensureLoaded()
            while (isActive) {
                val next = queue.nextRunnable()
                if (next == null) {
                    // 挂起等队列里出现可跑任务 —— 由 StateFlow 驱动，不空转，
                    // 也不依赖调用方"记得"唤醒（入队/继续/重试都会自动触发）
                    queue.tasks.first { nextRunnableTask(it) != null }
                    continue
                }
                runTask(next)
            }
        }
    }

    /** 登出时停掉：没有 token 还继续跑只会把任务跑成一堆失败 */
    fun stop() {
        runningTask?.cancel()
        worker?.cancel()
        worker = null
        runningTask = null
        _activeTaskId.value = null
    }

    // ---- 界面操作。跑着的任务走"登记意图 + cancel"，排队中的直接落库 ----

    fun pauseTask(taskId: Long, scope: CoroutineScope) {
        if (_activeTaskId.value == taskId) {
            pauseRequests.add(taskId)
            runningTask?.cancel()
        } else {
            scope.launch { queue.setPaused(taskId, paused = true) }
        }
    }

    fun cancelTask(taskId: Long, scope: CoroutineScope) {
        if (_activeTaskId.value == taskId) {
            cancelRequests.add(taskId)
            runningTask?.cancel()
        } else {
            // 排队中的还没动过任何文件，直接标结束保留记录（要清掉用"移除"）
            scope.launch { queue.finish(taskId, cancelled = true, note = "已取消") }
        }
    }

    fun resumeTask(taskId: Long, scope: CoroutineScope) {
        scope.launch { queue.setPaused(taskId, paused = false) }
    }

    fun retryTask(taskId: Long, scope: CoroutineScope) {
        scope.launch { queue.retryFailed(taskId) }
    }

    fun removeTask(taskId: Long, scope: CoroutineScope) {
        scope.launch { queue.remove(taskId) }
    }

    /**
     * 跑完一个任务。只处理还是 PENDING 的项 —— 重启后已改过的不再发请求。
     */
    private suspend fun runTask(task: RenameTask) {
        _activeTaskId.value = task.id
        runningTask = kotlinx.coroutines.currentCoroutineContext()[Job]
        var consecutive = 0
        try {
            val pending = task.items.withIndex().filter { it.value.st == RenameItemStatus.PENDING }
            for ((n, entry) in pending.withIndex()) {
                val (index, item) = entry
                // 限频就靠这一行：第一个不等，之后每个之间等一个间隔
                if (n > 0) delay(task.intervalMs)

                val reason = renameWithRetry(task.id, index, item)
                if (reason == null) {
                    queue.setItemStatus(task.id, index, RenameItemStatus.DONE)
                    consecutive = 0
                } else {
                    queue.setItemStatus(task.id, index, RenameItemStatus.FAILED, reason)
                    consecutive++
                }
                if (consecutive >= ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    queue.setPaused(task.id, paused = true, note = "连续 $consecutive 个失败，已中止")
                    return
                }
            }
            queue.finish(task.id)
        } catch (e: CancellationException) {
            // 协程已经被取消了，落库必须放到 NonCancellable 里，否则 edit 一进去就又被取消
            withContext(NonCancellable) {
                when {
                    cancelRequests.remove(task.id) ->
                        queue.finish(task.id, cancelled = true, note = "已取消")
                    else -> {
                        pauseRequests.remove(task.id)
                        queue.setPaused(task.id, paused = true)
                    }
                }
            }
            throw e
        } finally {
            _activeTaskId.value = null
            runningTask = null
            logSummary(task.id)
        }
    }

    /**
     * 单个文件重命名，失败退避重试。
     *
     * 115 文档只说「接口受文件操作频率限制；触发限制时返回异常」，**细则不公开**，
     * 所以没法按错误码精确识别限流，只能对所有失败一视同仁地退避重试：
     * 真被限流时这样能扛过去，其他原因的失败重试几次也不会更糟。
     *
     * @return null = 成功；否则是给用户看的失败原因
     */
    private suspend fun renameWithRetry(taskId: Long, index: Int, item: RenameTaskItem): String? {
        var last: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) delay(BACKOFF_BASE_MS shl (attempt - 2)) // 1s / 2s / 4s
            try {
                val resp = api.updateFile(item.fid, item.newName)
                if (resp.envOk()) return null
                last = resp.envMsg() ?: "重命名失败"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = "网络错误：${e.message}"
            }
        }
        return last
    }

    /** 结束时写一条操作记录（逐条明细已经在任务里了，这里只留一行汇总） */
    private suspend fun logSummary(taskId: Long) {
        val t = queue.snapshot().firstOrNull { it.id == taskId } ?: return
        if (t.doneCount == 0) return
        val detail = buildString {
            append("成功 ${t.succeeded}")
            if (t.failed > 0) append("，失败 ${t.failed}")
            t.note?.let { append("（$it）") }
        }
        opLog?.log(OpType.RENAME, "批量重命名 ${t.doneCount} 个文件", detail, t.cid, t.dirName)
    }

    private companion object {
        /** 首次 + 3 次重试 */
        const val MAX_ATTEMPTS = 4
        const val BACKOFF_BASE_MS = 1000L

        /**
         * 连续失败到这个数就中止该任务。
         * 偶发单条失败重试后继续没问题，但连着几条全挂基本就是被限流或 token 失效了，
         * 这时候继续发请求只会加重问题 —— 停下来比跑完一批错误更安全。
         */
        const val ABORT_AFTER_CONSECUTIVE_FAILURES = 5
    }
}
