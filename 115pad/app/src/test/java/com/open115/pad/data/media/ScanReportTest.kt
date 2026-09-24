package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描记录的文案与形状。
 *
 * 「扫描记录」页里每条记录的第二行就是 [ScanReport.summary]，里面全是分支
 * （无新增 / 跳过 / 已停止 / 列目录就中断）—— 错了不会报错、只会"看着不对"，所以钉住它；
 * 顺带钉住 [ScanLogEntity.toReport] 的换算（存的是 `at + elapsedMs + newKeys`，
 * 界面要的是起止时间与新增条数，换算错了时间就会整片错位）。
 */
class ScanReportTest {

    private fun report(
        newCount: Int = 2,
        stopped: Boolean = false,
        totalDirs: Int = 84,
        doneDirs: Int = 84,
        skippedDirs: Int = 83,
        indexed: Int = 3,
        postersFetched: Int = 5,
    ) = ScanReport(
        libraryName = "示例演员",
        startedAt = 1_000_000L,
        finishedAt = 1_000_000L + 112_000L,
        totalDirs = totalDirs,
        doneDirs = doneDirs,
        skippedDirs = skippedDirs,
        indexed = indexed,
        postersFetched = postersFetched,
        newCount = newCount,
        stopped = stopped,
    )

    @Test
    fun `摘要按"新增→目录→索引→海报→用时"排_最关心的在最前`() {
        assertEquals(
            "新增 2 部 · 目录 84/84（跳过 83） · 索引 3 项 · 缓存海报 5 张 · 用时 01:52",
            report().summary(),
        )
    }

    @Test
    fun `无新增时也要说清是扫完了没新增`() {
        val s = report(newCount = 0).summary()
        assertTrue("要明说是 0 新增", s.startsWith("新增 0 部"))
        assertFalse("不能写成中断", s.contains("未完成"))
    }

    @Test
    fun `没有跳过与海报时对应的段整段消失_不留空档`() {
        val s = report(skippedDirs = 0, postersFetched = 0, indexed = 1).summary()
        assertEquals("新增 2 部 · 目录 84/84 · 索引 1 项 · 用时 01:52", s)
        assertFalse("不该出现空括号", s.contains("（）"))
        assertFalse("不该出现 0 张海报", s.contains("海报"))
    }

    @Test
    fun `已停止_与"扫完了"必须区分开`() {
        val s = report(stopped = true, doneDirs = 30, totalDirs = 84).summary()
        assertTrue("要带已停止", s.startsWith("已停止 · 新增"))
        assertTrue("要显示跑到哪儿了", s.contains("目录 30/84"))
    }

    @Test
    fun `列目录就中断_不能显示成0目录扫完`() {
        // doneDirs=0 且没停 = 列目录阶段就抛了（网络问题），这是"未完成"而不是"没新增"
        val s = report(doneDirs = 0, totalDirs = 0, skippedDirs = 0).summary()
        assertTrue(s.startsWith("未完成（列目录中断）"))
        assertFalse(s.startsWith("新增"))
        // 列目录阶段被用户停掉是另一种说法
        assertTrue(
            report(stopped = true, doneDirs = 0, totalDirs = 0, skippedDirs = 0)
                .summary().startsWith("已停止（未开始索引）"),
        )
    }

    @Test
    fun `用时用格式化时长_不是裸毫秒`() {
        // 时长格式沿用播放器那套（%02d:%02d），别在这里另立一套
        assertEquals("01:52", com.open115.pad.util.Format.duration(report().elapsedMs / 1000))
    }

    @Test
    fun `入库的实体能还原成同一份摘要`() {
        // 存的是 at + elapsedMs + newKeys，界面要的是起止时间与新增条数
        val row = ScanLogEntity(
            libraryId = 3,
            libraryName = "示例演员",
            at = 1_000_000L + 112_000L,
            elapsedMs = 112_000L,
            totalDirs = 84, doneDirs = 84, skippedDirs = 83,
            indexed = 3, postersFetched = 5,
            newKeys = "ABC-101-4K-C\nABC-304",
        )
        val r = row.toReport()
        assertEquals("起止时间要能还原", 1_000_000L, r.startedAt)
        assertEquals(1_000_000L + 112_000L, r.finishedAt)
        assertEquals("新增条数 = 键的条数", 2, r.newCount)
        assertEquals("恢复出来的摘要要与扫描时一致", report().summary(), r.summary())
    }

    @Test
    fun `新增键的解析_空串不算一条`() {
        assertEquals(emptyList<String>(), ScanLogEntity(libraryName = "库", at = 1L).newKeyList)
        assertEquals(emptyList<String>(), ScanLogEntity(libraryName = "库", at = 1L, newKeys = "").newKeyList)
        assertEquals(
            listOf("A", "B"),
            ScanLogEntity(libraryName = "库", at = 1L, newKeys = "A\nB").newKeyList,
        )
        // 末尾多个换行也不该多出空键
        assertEquals(
            listOf("A"),
            ScanLogEntity(libraryName = "库", at = 1L, newKeys = "A\n\n").newKeyList,
        )
    }
}
