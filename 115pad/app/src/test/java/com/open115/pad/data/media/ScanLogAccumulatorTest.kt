package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流水线同步的扫描记录汇总（[ScanLogAccumulator]）。
 *
 * 这条逻辑决定「扫描记录」里**一次同步是几条记录**：一次同步 N 个目录汇总成一条
 * 「流水线同步 · 新增 N 部」，而不是 N 条「全量 · 新增 1 部」—— 后者正是用户报的现象。
 *
 * 抽出来纯函数测，是因为它错了不会报错、只是记录页面变得没法读（而这条路径只有真机上
 * 服务器写了信号才会走，手测一次要跑整套流水线）。
 */
class ScanLogAccumulatorTest {

    private fun report(
        startedAt: Long,
        finishedAt: Long,
        totalDirs: Int = 1,
        doneDirs: Int = 1,
        skippedDirs: Int = 0,
        indexed: Int = 0,
        postersFetched: Int = 0,
        newCount: Int = 0,
        stopped: Boolean = false,
        listRequests: Int = 1,
    ) = ScanReport(
        libraryName = "库1",
        startedAt = startedAt,
        finishedAt = finishedAt,
        totalDirs = totalDirs,
        doneDirs = doneDirs,
        skippedDirs = skippedDirs,
        indexed = indexed,
        postersFetched = postersFetched,
        newCount = newCount,
        stopped = stopped,
        mode = "全量",
        listRequests = listRequests,
    )

    @Test
    fun `多个目录汇总成一条_模式是流水线同步`() {
        val acc = ScanLogAccumulator()
        acc.add(report(1000, 2000, indexed = 3, postersFetched = 3), listOf("k1"))
        acc.add(report(3000, 5000, indexed = 2, postersFetched = 2), listOf("k2"))

        val entity = acc.toEntity(libraryId = 7L, libraryName = "库A")!!
        assertEquals(SCAN_MODE_PIPELINE, entity.mode)
        assertEquals(7L, entity.libraryId)
        assertEquals("库A", entity.libraryName)
        // 完成时间取最晚的一轮、用时跨整批（1000 → 5000）
        assertEquals(5000L, entity.at)
        assertEquals(4000L, entity.elapsedMs)
        assertEquals(2, entity.totalDirs)
        assertEquals(2, entity.doneDirs)
        assertEquals(5, entity.indexed)
        assertEquals(5, entity.postersFetched)
        assertEquals(2, entity.listRequests)
        assertEquals(listOf("k1", "k2"), entity.newKeyList)
    }

    @Test
    fun `新增键去重_同一部片被两轮都算到只留一条`() {
        val acc = ScanLogAccumulator()
        acc.add(report(1, 2), listOf("k1", "k2"))
        acc.add(report(3, 4), listOf("k2", "k3"))
        assertEquals(listOf("k1", "k2", "k3"), acc.toEntity(0L, "库1")!!.newKeyList)
    }

    @Test
    fun `中途被停止也算一条_并且 stopped 传下去`() {
        val acc = ScanLogAccumulator()
        acc.add(report(1, 2, stopped = true, doneDirs = 1), emptyList())
        acc.add(report(3, 4, stopped = false, doneDirs = 1), emptyList())
        assertTrue(acc.toEntity(0L, "库1")!!.stopped)
    }

    @Test
    fun `一轮都没跑成时不写记录`() {
        // 任务开跑前就被停止 / 第一轮列目录就抛 —— 什么都没发生，不该在记录里留一条
        assertNull(ScanLogAccumulator().toEntity(0L, "库1"))
    }

    @Test
    fun `用时按最早开始到最晚结束算`() {
        val acc = ScanLogAccumulator()
        // 故意乱序喂：完成时间不该被后来的小值改写
        acc.add(report(500, 900), emptyList())
        acc.add(report(100, 200), emptyList())
        val entity = acc.toEntity(0L, "库1")!!
        assertEquals(900L, entity.at)
        assertEquals(800L, entity.elapsedMs)
    }
}
