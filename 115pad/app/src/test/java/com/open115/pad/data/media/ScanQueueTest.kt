package com.open115.pad.data.media

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列的顺序、去重、落盘与重启续跑规则（纯函数 [queueWithTask] / [pendingToRestore] /
 * [resumeTaskOf] / [queueWithoutAuto]）。
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
        val tasks = pendingToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw))
        assertEquals(listOf(7L), tasks.map { it.libraryId })
    }

    @Test
    fun `恢复只恢复排队的_未跑完的那条不自动接着跑`() {
        // 未跑完的那条要经过用户确认（见 resumeTaskOf 与界面的那次弹窗）：
        // 自动续跑的话，"用户其实已经不想扫了"就无从表达
        val tasks = pendingToRestore(
            ScanQueueSnapshot(running = task(1, incremental = false), pending = listOf(task(2, incremental = false))),
        )
        assertEquals(listOf(2L), tasks.map { it.libraryId })
        // 还没轮到的那条**原样**：用户当时选的就是全量
        assertFalse(tasks.single().incremental)
    }

    @Test
    fun `空快照恢复出空队列_队列早就跑完时重启不该又扫一遍`() {
        assertTrue(pendingToRestore(ScanQueueSnapshot()).isEmpty())
    }

    // ---- 未跑完那一轮的续扫规则（用户点了"继续扫描"才走这条路） ----

    @Test
    fun `全量没跑完_续扫降级成增量`() {
        // 全量的语义是"重新索引一切"，拿它续扫等于把已经扫完的目录从头再问一遍（白耗频控额度）；
        // 降成增量之后 scan_state 会让它从断点接着跑
        val t = resumeTaskOf(
            ScanResume(task = task(1, incremental = false), doneDirs = 30, totalDirs = 84),
        )!!
        assertTrue(t.incremental)
        assertFalse(t.fast)
        assertSame(ScanMode.Incremental, t.mode)
    }

    @Test
    fun `增量与快速没跑完_续扫保持原方式`() {
        // 这两条的跳过判据都基于 scan_state 的目录指纹，半路恢复不会出偏差 ——
        // 没必要把用户选的快速扫描偷偷换成更费请求的增量
        assertSame(ScanMode.Incremental, resumeTaskOf(ScanResume(task = task(1), doneDirs = 5))!!.mode)
        assertSame(ScanMode.Fast, resumeTaskOf(ScanResume(task = task(1).copy(fast = true), doneDirs = 5))!!.mode)
    }

    @Test
    fun `没有任务就没有可续的`() {
        assertNull(resumeTaskOf(ScanResume(task = null, doneDirs = 3)))
    }

    @Test
    fun `两份现场都能原样落盘再读回_老快照读回来是空的`() {
        val scene = ScanResume(
            task = task(1),
            at = 123L,
            reason = "连接超时",
            doneDirs = 7,
            totalDirs = 20,
        )
        val snapshot = ScanQueueSnapshot(
            pending = listOf(task(2)),
            inflight = scene,
            proposal = scene.copy(task = task(3)),
        )
        val raw = MediaScanner.queueJson.encodeToString(snapshot)
        assertEquals(snapshot, MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw))
        // 老快照（本功能之前那一版）只有 running/pending → 两份现场都是 null = 不无故弹窗
        val legacy = MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>("{}")
        assertNull(legacy.inflight)
        assertNull(legacy.proposal)
    }

    // ---- 重启后该问哪一条（在途现场 vs 待回答的问题） ----

    @Test
    fun `没回答的问题优先于在途现场`() {
        val inflight = ScanResume(task = task(1), doneDirs = 3)
        val proposal = ScanResume(task = task(2), doneDirs = 9, reason = "连接超时")
        val picked = proposalToRestore(ScanQueueSnapshot(inflight = inflight, proposal = proposal))!!
        assertEquals(2L, picked.task?.libraryId)
        assertEquals("连接超时", picked.reason)
    }

    @Test
    fun `没有问题时用进程被杀留下的在途现场`() {
        val picked = proposalToRestore(ScanQueueSnapshot(inflight = ScanResume(task = task(5), doneDirs = 2)))!!
        assertEquals(5L, picked.task?.libraryId)
    }

    @Test
    fun `只有 running 时不问_用户主动停掉那一轮不能被当成没跑完`() {
        // running 非空只说明"进程死的时候队列里有条任务在跑" —— 那一轮也可能是用户刚点过
        // 「全部停止」的（stopAll 会清掉两份现场，盘上只剩 running），拿它去问就是在问一个
        // 用户明确停掉的扫描
        assertNull(proposalToRestore(ScanQueueSnapshot(running = task(1, incremental = false))))
        // 现场里没有任务（理论上不会出现）也不问
        assertNull(proposalToRestore(ScanQueueSnapshot(inflight = ScanResume(task = null, doneDirs = 4))))
        assertNull(proposalToRestore(ScanQueueSnapshot()))
    }

    // ---- 「跳过本次」：这次不扫，下次启动还要再问 ----

    @Test
    fun `跳过本次留下的现场_下次启动照问`() {
        val skipped = ScanResume(task = task(1), doneDirs = 7, reason = "请求过于频繁")
        // 跳过之后内存里那个问题没了，盘上写的是被跳过的那份
        val persisted = proposalToPersist(active = null, deferred = skipped)!!
        // 重启：从同一个槽里捡回来，照问（这就是"下次启动还会再问"的全部机制）
        val asked = proposalToRestore(ScanQueueSnapshot(proposal = persisted))!!
        assertEquals(1L, asked.task?.libraryId)
        assertEquals(7, asked.doneDirs)
    }

    @Test
    fun `有活问题时_盘上只写活的那个`() {
        val alive = ScanResume(task = task(1))
        val skipped = ScanResume(task = task(2))
        assertEquals(1L, proposalToPersist(active = alive, deferred = skipped)?.task?.libraryId)
        // 两处都空（答过继续/不再询问、或全部停止）→ 盘上就没有待回答的问题，下次启动不问
        assertNull(proposalToPersist(active = null, deferred = null))
    }

    @Test
    fun `同库同类型的新一轮扫描_作废跳过的现场`() {
        val skipped = ScanResume(task = task(1))
        assertTrue(taskCoversResume(task(1), skipped))
        // 别的库不覆盖它
        assertFalse(taskCoversResume(task(2), skipped))
        // 整库与定向任务互不覆盖（卡位规则与 queueWithTask 一致）：定向只扫几个目录，
        // 整库那轮仍然没扫完，反过来定向那轮也没被整库覆盖
        assertFalse(taskCoversResume(targeted(1), skipped))
        assertTrue(taskCoversResume(targeted(1), ScanResume(task = targeted(1))))
        // 没有现场 → 谈不上覆盖
        assertFalse(taskCoversResume(task(1), null))
    }

    // ---- 定时扫描任务被流水线同步撤掉（两件事同时触发时忽略定时任务） ----

    private fun autoTask(id: Long) = task(id).copy(auto = true)

    @Test
    fun `只撤定时任务_手动点的与流水线自己的都不动`() {
        val q = listOf(autoTask(1), task(1, incremental = false), targeted(1), autoTask(2))
        val rest = queueWithoutAuto(q, 1)
        // 库 1 的定时任务没了；它的手动全量任务与流水线定向任务都留着（都是明确的意图）
        assertEquals(3, rest.size)
        assertTrue(rest.none { it.auto && it.libraryId == 1L })
        assertTrue(rest.any { it.libraryId == 1L && !it.noRecurse && !it.auto })
        assertTrue(rest.any { it.libraryId == 1L && it.noRecurse })
        // 别的库的定时任务不受影响
        assertTrue(rest.any { it.libraryId == 2L && it.auto })
    }

    @Test
    fun `没有定时任务时_撤不掉任何东西`() {
        val q = listOf(task(1), targeted(1))
        assertEquals(q, queueWithoutAuto(q, 1))
    }

    @Test
    fun `入队替换时_定时标记跟着新任务走`() {
        // 同库同类型只有一格：用户随后手动点了一次扫描，那一条就**不再是**定时任务，
        // 流水线同步不能替他把这一条撤掉
        assertFalse(queueWithTask(listOf(autoTask(1)), task(1)).single().auto)
    }

    @Test
    fun `老快照没有 auto 字段_当成手动任务`() {
        val raw = """{"pending":[{"libraryId":3,"libraryName":"库3","rootCids":["c3"],
            "rootPaths":["/p3"],"incremental":true,"rateLimitMs":0,"minVideoSizeMb":0}]}"""
        val t = pendingToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw)).single()
        // 宁可多扫一次，也不能误撤用户的任务
        assertFalse(t.auto)
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
    fun `老快照没有 fast 字段_读回来是普通增量`() {
        // 升级前的落盘 JSON（只有 incremental），读回来必须是原增量而不是快速
        val raw = """{"pending":[{"libraryId":3,"libraryName":"库3","rootCids":["c3"],
            "rootPaths":["/p3"],"incremental":true,"rateLimitMs":0,"minVideoSizeMb":0}]}"""
        val t = pendingToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw)).single()
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
        val restored = resumeTaskOf(ScanResume(task = targeted(1), doneDirs = 2))!!
        assertTrue(restored.noRecurse)
        assertFalse(restored.incremental)
        assertSame(ScanMode.Full, restored.mode)
    }

    @Test
    fun `老快照没有 noRecurse 字段_读回来是整根扫描`() {
        val raw = """{"pending":[{"libraryId":3,"libraryName":"库3","rootCids":["c3"],
            "rootPaths":["/p3"],"incremental":false,"rateLimitMs":0,"minVideoSizeMb":0}]}"""
        val t = pendingToRestore(MediaScanner.queueJson.decodeFromString<ScanQueueSnapshot>(raw)).single()
        assertFalse(t.noRecurse)
    }
}
