package com.open115.pad.data.media

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶梯限速（防 ban）与"每 100 个目录休息一次"的纯规则。
 *
 * 抽成纯函数就是为了钉住档位边界：差一个数（比如第 11 个目录还按 500ms 走）不会报错，
 * 只会让本来该慢下来的那一段继续猛冲 —— 被 115 限流时才暴露，而那时已经晚了。
 */
class TierRateTest {

    @Test
    fun `五档边界_用户定的就是这几个数`() {
        // 前 10 个目录（正在扫第 1~10 个）：500ms
        assertEquals(500L, tierRateLimitMs(1))
        assertEquals(500L, tierRateLimitMs(10))
        // 11~20 个：1000ms（第 11 个目录起就该慢下来）
        assertEquals(1_000L, tierRateLimitMs(11))
        assertEquals(1_000L, tierRateLimitMs(20))
        // 21~50 个：1500ms
        assertEquals(1_500L, tierRateLimitMs(21))
        assertEquals(1_500L, tierRateLimitMs(50))
        // 51~100 个：2000ms
        assertEquals(2_000L, tierRateLimitMs(51))
        assertEquals(2_000L, tierRateLimitMs(100))
        // 100 往上：2500ms
        assertEquals(2_500L, tierRateLimitMs(101))
        assertEquals(2_500L, tierRateLimitMs(5_000))
    }

    @Test
    fun `间隔只增不减_越扫越慢`() {
        // 单调不降是这套档位的全部意义：中途变快就等于"前面白慢了"
        val seq = (1..200).map { tierRateLimitMs(it) }
        assertEquals(seq.sorted(), seq)
    }

    @Test
    fun `休息_每跨过一个整百触发一次`() {
        // 第 100 个目录处理完（dirsDone=100）触发第一次
        assertTrue(tierRestDue(dirsDone = 100, restsDone = 0))
        assertFalse(tierRestDue(dirsDone = 99, restsDone = 0))
        // 触发过就不再触发（同一个整百只休一次）
        assertFalse(tierRestDue(dirsDone = 100, restsDone = 1))
        assertFalse(tierRestDue(dirsDone = 150, restsDone = 1))
        // 跨到 200 再休
        assertTrue(tierRestDue(dirsDone = 200, restsDone = 1))
        assertTrue(tierRestDue(dirsDone = 205, restsDone = 1))
        assertFalse(tierRestDue(dirsDone = 205, restsDone = 2))
    }

    @Test
    fun `休息_续扫时计数不从零开始也不会漏或重复`() {
        // 续扫接着上次的目录数走（scanDirsDone 是任务级的累计），
        // 用"跨过几个整百"比较而不是 `% 100 == 0`，从 150 起也不会漏掉第 200 个那次
        assertTrue(tierRestDue(dirsDone = 200, restsDone = 1))
        assertFalse(tierRestDue(dirsDone = 100, restsDone = 1))
        // 少于 100 个目录的小库永远不休息
        assertFalse(tierRestDue(dirsDone = 80, restsDone = 0))
        assertFalse(tierRestDue(dirsDone = 0, restsDone = 0))
    }

    @Test
    fun `任务快照带上限速设置_老快照读回来是关的`() {
        val task = QueuedScan(
            libraryId = 1,
            libraryName = "库",
            rootCids = listOf("c"),
            rootPaths = listOf("/p"),
            incremental = true,
            rateLimitMs = 0,
            rateTiered = true,
            tierRestMinutes = 5,
        )
        val raw = MediaScanner.queueJson.encodeToString(task)
        assertEquals(task, MediaScanner.queueJson.decodeFromString<QueuedScan>(raw))
        // 升级上来的老快照没有这两个字段 → 阶梯关、不休息（还是那个定死值的老行为）
        val legacy = MediaScanner.queueJson.decodeFromString<QueuedScan>(
            """{"libraryId":1,"libraryName":"库","rootCids":["c"],"rootPaths":["/p"],
               "incremental":true,"rateLimitMs":800,"minVideoSizeMb":0}""",
        )
        assertFalse(legacy.rateTiered)
        assertEquals(0, legacy.tierRestMinutes)
        assertEquals(800L, legacy.rateLimitMs)
    }

    @Test
    fun `从库造任务时_限速设置跟着带过去`() {
        val lib = MediaLibraryEntity(
            name = "库", rootCid = "c", rootPath = "/p",
            rateLimitMs = 300, rateTiered = true, tierRestMinutes = 3,
        )
        val task = QueuedScan.of(lib, ScanMode.Incremental)
        assertTrue(task.rateTiered)
        assertEquals(3, task.tierRestMinutes)
        // 单目录任务（流水线同步）也一样：频控是按账号算的，与任务类型无关
        val targeted = QueuedScan.targeted(lib, listOf("c" to "/p"))
        assertTrue(targeted.rateTiered)
        assertEquals(3, targeted.tierRestMinutes)
    }
}
