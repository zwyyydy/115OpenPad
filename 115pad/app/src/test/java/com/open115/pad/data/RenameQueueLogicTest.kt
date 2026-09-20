package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 改名队列的两条纯逻辑：**下一个跑谁**、**超上限丢谁**。
 *
 * 这两条错了的后果都很重 —— 前者会让已暂停/已跑完的任务被重跑（白费 API 额度），
 * 后者会把用户还没跑完的任务丢掉。所以单独测。
 */
class RenameQueueLogicTest {

    private fun task(
        id: Long,
        createdAt: Long,
        statuses: List<RenameItemStatus>,
        finishedAt: Long? = null,
        paused: Boolean = false,
    ) = RenameTask(
        id = id,
        createdAt = createdAt,
        cid = "0",
        dirName = "目录$id",
        intervalMs = 400L,
        items = statuses.mapIndexed { i, s -> RenameTaskItem("f$i", "old$i", "new$i", s.name) },
        finishedAt = finishedAt,
        paused = paused,
    )

    private val P = RenameItemStatus.PENDING
    private val D = RenameItemStatus.DONE
    private val F = RenameItemStatus.FAILED

    // ---------------- 下一个跑谁 ----------------

    @Test
    fun `按创建顺序取最早的那个`() {
        val tasks = listOf(
            task(2, createdAt = 200, listOf(P)),
            task(1, createdAt = 100, listOf(P)),
            task(3, createdAt = 300, listOf(P)),
        )
        assertEquals(1L, nextRunnableTask(tasks)?.id)
    }

    @Test
    fun `已结束的跳过`() {
        val tasks = listOf(
            task(1, 100, listOf(D), finishedAt = 999),
            task(2, 200, listOf(P)),
        )
        assertEquals(2L, nextRunnableTask(tasks)?.id)
    }

    @Test
    fun `已暂停的跳过（等用户手动继续）`() {
        val tasks = listOf(
            task(1, 100, listOf(P), paused = true),
            task(2, 200, listOf(P)),
        )
        assertEquals(2L, nextRunnableTask(tasks)?.id)
    }

    @Test
    fun `没有待处理项的跳过`() {
        // 全部处理完但还没标结束（上次跑到最后一条被杀），不该重跑一遍
        val tasks = listOf(task(1, 100, listOf(D, D)), task(2, 200, listOf(P)))
        assertEquals(2L, nextRunnableTask(tasks)?.id)
    }

    @Test
    fun `只剩失败项时不算可跑（失败项要用户点重试才打回 PENDING）`() {
        val tasks = listOf(task(1, 100, listOf(D, F)))
        assertNull(nextRunnableTask(tasks))
    }

    @Test
    fun `空队列返回 null`() {
        assertNull(nextRunnableTask(emptyList()))
    }

    // ---------------- 超上限丢谁 ----------------

    @Test
    fun `没超上限就原样返回`() {
        val tasks = List(3) { task(it + 1L, (it + 1L) * 100, listOf(P)) }
        assertEquals(tasks, trimTasks(tasks, max = 5))
    }

    @Test
    fun `超上限时先丢最旧的已结束任务`() {
        val tasks = listOf(
            task(1, 100, listOf(D), finishedAt = 1000),
            task(2, 200, listOf(D), finishedAt = 2000),
            task(3, 300, listOf(P)),
            task(4, 400, listOf(D), finishedAt = 3000),
        )
        val kept = trimTasks(tasks, max = 2)
        // 保留未完成的 3 号，外加最新的一个已结束（4 号）；最旧的 1、2 号被丢
        assertEquals(listOf(3L, 4L), kept.map { it.id })
    }

    @Test
    fun `未完成的一个都不丢——哪怕它们本身已经超过上限`() {
        val tasks = listOf(
            task(1, 100, listOf(P)),
            task(2, 200, listOf(P)),
            task(3, 300, listOf(P)),
            task(4, 400, listOf(D), finishedAt = 1000),
        )
        val kept = trimTasks(tasks, max = 2)
        // 三个未完成的全部保留，已结束的因为额度用光被丢
        assertEquals(listOf(1L, 2L, 3L), kept.map { it.id })
    }

    @Test
    fun `裁剪后仍按创建顺序排列`() {
        val tasks = listOf(
            task(3, 300, listOf(P)),
            task(1, 100, listOf(D), finishedAt = 1000),
            task(2, 200, listOf(P)),
        )
        assertEquals(listOf(2L, 3L), trimTasks(tasks, max = 2).map { it.id })
    }

    // ---------------- 派生字段 ----------------

    @Test
    fun `进度字段按逐条状态算`() {
        val t = task(1, 100, listOf(D, D, F, P))
        assertEquals(4, t.total)
        assertEquals(3, t.doneCount)   // DONE + FAILED 都算处理过
        assertEquals(2, t.succeeded)
        assertEquals(1, t.failed)
        assertEquals(true, t.hasPending)
        assertEquals(true, t.runnable)
    }
}
