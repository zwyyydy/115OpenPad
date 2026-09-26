package com.open115.pad.data.media

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列的顺序、去重、落盘与重启续跑规则（纯函数 [queueWithTask] / [tasksToRestore]）。
 *
 * 抽成纯函数就是为了测这些：它们决定了"点了扫描之后到底排成什么样""重启之后又跑什么"，
 * 错了的后果不好看 —— 顺序乱（用户排的库不按顺序跑）、去重失效（连点两下变成扫两遍），
 * 或者重启后把已经扫完的库按全量从头再问一遍（白耗频控额度）。
 *
 * 真正跑任务的那一半（worker 循环、与"跳过"/"全部停止"/删库的配合）没法在这里测：
 * 它要一个真的 OpenApi / Room，真机手测更实在。
 */
class ScanQueueTest {

    private fun task(id: Long, incremental: Boolean = true) = QueuedScan(
        libraryId = id,
        libraryName = "库$id",
        rootCids = listOf("cid-$id"),
        rootPaths = listOf("/路径/$id"),
        incremental = incremental,
    )

    private fun ids(queue: List<QueuedScan>) = queue.map { it.libraryId }

    @Test
    fun `不同库按入队顺序追加_队首就是下一个要跑的`() {
        val q = queueWithTask(queueWithTask(listOf(task(1)), task(2)), task(3))
        assertEquals(listOf(1L, 2L, 3L), ids(q))
    }

    @Test
    fun `同一个库再入队是就地替换_不换位置也不变长`() {
        val q = queueWithTask(listOf(task(1), task(2), task(3)), task(2, incremental = false))
        assertEquals(listOf(1L, 2L, 3L), ids(q))
        assertEquals(3, q.size)
        // 改主意（增量 → 全量）要生效，否则用户点了全量却还是按增量跑
        assertFalse(q[1].incremental)
        assertTrue(q[0].incremental)
    }

    @Test
    fun `替换只认库号_不会串到别的库`() {
        val q = queueWithTask(listOf(task(1), task(2)), task(9, incremental = false))
        assertEquals(listOf(1L, 2L, 9L), ids(q))
        assertTrue(q[0].incremental)
    }

    @Test
    fun `空队列直接排进去`() {
        assertEquals(listOf(7L), ids(queueWithTask(emptyList(), task(7))))
    }

    @Test
    fun `入队不改原列表_StateFlow 里旧值还能用`() {
        val before = listOf(task(1))
        val after = queueWithTask(before, task(2))
        assertEquals(1, before.size)
        assertEquals(2, after.size)
    }

    // ---- 落盘 / 重启续跑（快照结构与恢复规则） ----

    @Test
    fun `快照能原样落盘再读回`() {
        val snapshot = ScanQueueSnapshot(
            running = task(1, incremental = false),
            pending = listOf(task(2), task(3, incremental = false)),
        )
        val raw = MediaScanner.queueJson.encodeToString(snapshot)
        assertEquals(snapshot, MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw))
    }

    @Test
    fun `快照缺字段也能读_以后加字段不至于把老数据读炸`() {
        // 老版本写的 JSON（或者干脆什么都没写）读回来必须是"空队列"，而不是抛异常
        assertEquals(ScanQueueSnapshot(), MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>("{}"))
    }

    @Test
    fun `多了不认识的字段也能读_降级版本不至于读不回来`() {
        // 新版本加了字段、用户又装回旧版本：旧版本要能读新快照（ignoreUnknownKeys）
        val raw = """{"pending":[{"libraryId":7,"libraryName":"库7","rootCids":["c7"],
            "rootPaths":["/p7"],"incremental":true,"rateLimitMs":0,"minVideoSizeMb":0,
            "somethingNew":"x"}],"anotherNew":1}"""
        val tasks = tasksToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw))
        assertEquals(listOf(7L), tasks.map { it.libraryId })
    }

    @Test
    fun `恢复时未跑完的那条降级成增量_并排在最前面`() {
        val tasks = tasksToRestore(
            ScanQueueSnapshot(running = task(1, incremental = false), pending = listOf(task(2, incremental = false))),
        )
        assertEquals(listOf(1L, 2L), tasks.map { it.libraryId })
        // 跑了一半的那条降级成增量：已完成目录在 scan_state 里，接着跑就行，
        // 原样按全量恢复会把扫完的目录从头再问一遍
        assertTrue(tasks[0].incremental)
        // 还没轮到的那条**原样**：用户当时选的就是全量
        assertFalse(tasks[1].incremental)
    }

    @Test
    fun `恢复时没有未跑完的就只恢复排队的`() {
        val tasks = tasksToRestore(ScanQueueSnapshot(pending = listOf(task(5))))
        assertEquals(listOf(5L), tasks.map { it.libraryId })
        assertTrue(tasks.single().incremental)
    }

    @Test
    fun `空快照恢复出空队列_队列早就跑完时重启不该又扫一遍`() {
        assertTrue(tasksToRestore(ScanQueueSnapshot()).isEmpty())
    }
}
