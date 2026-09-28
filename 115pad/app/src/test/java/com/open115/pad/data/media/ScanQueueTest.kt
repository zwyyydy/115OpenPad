package com.open115.pad.data.media

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // ---- 快速扫描（ScanMode.Fast）相关的纯规则 ----

    @Test
    fun `三个扫描方式由两个布尔值还原`() {
        assertSame(ScanMode.Full, task(1, incremental = false).mode)
        assertSame(ScanMode.Incremental, task(1).mode)
        // fast=true 但 incremental=false 是非法组合（永远不会被构造出来），按全量算
        assertSame(ScanMode.Full, task(1, incremental = false).copy(fast = true).mode)
    }

    @Test
    fun `未跑完的快速扫描恢复时降级成普通增量`() {
        val restored = tasksToRestore(
            ScanQueueSnapshot(running = task(1).copy(fast = true)),
        ).single()
        // 快速模式判据多（hasSubDirs / logicVersion），"跑了一半"的状态上接着用最难查
        assertTrue(restored.incremental)
        assertFalse(restored.fast)
        assertSame(ScanMode.Incremental, restored.mode)
    }

    @Test
    fun `老快照没有 fast 字段_读回来是普通增量`() {
        // 升级前的落盘 JSON（只有 incremental），读回来必须是原增量而不是快速
        val raw = """{"pending":[{"libraryId":3,"libraryName":"库3","rootCids":["c3"],
            "rootPaths":["/p3"],"incremental":true,"rateLimitMs":0,"minVideoSizeMb":0}]}"""
        val t = tasksToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw)).single()
        assertSame(ScanMode.Incremental, t.mode)
    }

    @Test
    fun `抽查数量_至少一个_最多封顶`() {
        assertEquals(0, fastSampleSize(0))
        // 小库：10% 取整是 0，但必须至少抽 1 个 —— 否则"目录内改名"永远发现不了
        assertEquals(1, fastSampleSize(3))
        assertEquals(1, fastSampleSize(10))
        assertEquals(2, fastSampleSize(25))
        // 大库：封顶，别把剪枝省下来的请求又吃回去
        assertEquals(FAST_SAMPLE_MAX, fastSampleSize(5000))
    }

    // ---- 单目录任务（流水线同步，noRecurse）：与整库任务各占一格 ----

    private fun targeted(id: Long, dirs: List<String> = listOf("/a")) = QueuedScan(
        libraryId = id,
        libraryName = "库$id",
        rootCids = dirs.map { "cid$it" },
        rootPaths = dirs,
        incremental = false,
        noRecurse = true,
    )

    @Test
    fun `单目录任务不顶整库任务_反过来也一样`() {
        // 卡位只按库号的话：自动扫描排的整库任务会被一条定向任务顶掉（反之亦然），
        // 用户看到的是"排好的全量扫描不见了"
        val q = queueWithTask(queueWithTask(listOf(task(1)), targeted(1)), task(1, incremental = false))
        assertEquals(listOf(1L, 1L), ids(q))
        assertFalse(q[0].noRecurse)
        assertTrue(q[1].noRecurse)
        // 定向任务恒全量：被增量判据跳过就等于白排
        assertFalse(q[1].incremental)
    }

    @Test
    fun `同一条单目录任务再入队_仍是就地替换`() {
        val q = queueWithTask(listOf(targeted(1)), targeted(1, listOf("/a", "/b")))
        assertEquals(1, q.size)
        assertEquals(listOf("/a", "/b"), q.single().rootPaths)
    }

    @Test
    fun `未跑完的单目录任务恢复时不降级`() {
        val restored = tasksToRestore(ScanQueueSnapshot(running = targeted(1))).single()
        assertTrue(restored.noRecurse)
        assertFalse(restored.incremental)
        assertSame(ScanMode.Full, restored.mode)
    }

    @Test
    fun `老快照没有 noRecurse 字段_读回来是整根扫描`() {
        val raw = """{"pending":[{"libraryId":3,"libraryName":"库3","rootCids":["c3"],
            "rootPaths":["/p3"],"incremental":false,"rateLimitMs":0,"minVideoSizeMb":0}]}"""
        val t = tasksToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw)).single()
        assertFalse(t.noRecurse)
    }
}
