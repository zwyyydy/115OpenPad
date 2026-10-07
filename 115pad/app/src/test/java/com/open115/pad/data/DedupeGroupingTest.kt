package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复文件分组的纯逻辑（去重一期的判据）。
 *
 * 这里打的是"宁漏不误"的几条底线：**没有 sha1 的不参与**（判不了就别报）、
 * **0 字节排除**（空文件 sha1 全一样，会把整盘串成一组）、**保留项取云端最早的那份**且顺序稳定。
 */
class DedupeGroupingTest {

    private fun dup(
        fid: String,
        sha1: String?,
        size: Long = 1_000L,
        upt: Long = 1_700_000_000L,
        dirPath: String = "全部文件/A",
    ) = DupFile(
        item = FileItem(
            fid = fid,
            fc = 1,
            fn = "$fid.mp4",
            fs = size,
            sha1 = sha1,
            upt = upt,
        ),
        dirCid = "cid_$fid",
        dirPath = dirPath,
    )

    /** 随手编的 40 位十六进制（真实环境里的值一律不进仓库） */
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `同 sha1 的文件归到一组，保留云端最早的那份`() {
        val groups = buildDupGroups(
            listOf(
                dup("new", sha, upt = 1_800_000_000L),
                dup("old", sha, upt = 1_600_000_000L),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("old", "new"), groups[0].files.map { it.fid })
    }

    @Test
    fun `只有一个副本不算重复`() {
        assertTrue(buildDupGroups(listOf(dup("solo", sha))).isEmpty())
    }

    @Test
    fun `没有 sha1 的文件一律不参与判重`() {
        val groups = buildDupGroups(
            listOf(
                dup("a", null),
                dup("b", null),
                dup("c", ""),
                dup("d", "   "),
            ),
        )
        assertTrue("判不了内容就宁可漏报", groups.isEmpty())
    }

    @Test
    fun `0 字节文件排除，不把整盘空文件串成一组`() {
        val groups = buildDupGroups(
            listOf(
                dup("empty1", sha, size = 0),
                dup("empty2", sha, size = 0),
                dup("real1", sha, size = 5),
                dup("real2", sha, size = 5),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("real1", "real2"), groups[0].files.map { it.fid }.sorted())
        assertEquals(0, groups[0].removable.count { it.size == 0L })
    }

    @Test
    fun `三个副本只删两个，可释放空间按各副本自身大小算`() {
        val groups = buildDupGroups(
            listOf(
                dup("a", sha, size = 10, upt = 100),
                dup("b", sha, size = 10, upt = 200),
                dup("c", sha, size = 10, upt = 300),
            ),
        )
        val g = groups.single()
        assertEquals(3, g.files.size)
        assertEquals(listOf("b", "c"), g.removable.map { it.fid })
        assertEquals(20L, g.wasteBytes)
    }

    @Test
    fun `组按可释放空间从大到小排，同大小时副本多的在前`() {
        val small = "11".repeat(20)
        val big = "22".repeat(20)
        val groups = buildDupGroups(
            listOf(
                dup("s1", small, size = 100, upt = 1),
                dup("s2", small, size = 100, upt = 2),
                dup("b1", big, size = 5_000, upt = 1),
                dup("b2", big, size = 5_000, upt = 2),
                dup("b3", big, size = 5_000, upt = 3),
            ),
        )
        assertEquals(listOf(big, small), groups.map { it.sha1 })
        assertEquals(10_000L, groups[0].wasteBytes)
    }

    @Test
    fun `时间一样时路径短的优先，再一样用 fid 兜底保证顺序稳定`() {
        val t = 1_700_000_000L
        val groups = buildDupGroups(
            listOf(
                dup("zzz", sha, upt = t, dirPath = "全部文件/很深的/一层/再一层/B"),
                dup("aaa", sha, upt = t, dirPath = "全部文件/A"),
                dup("bbb", sha, upt = t, dirPath = "全部文件/A"),
            ),
        )
        // 路径最短的 aaa/bbb 打头（同为 全部文件/A），其中 fid 小的在前
        assertEquals(listOf("aaa", "bbb", "zzz"), groups[0].files.map { it.fid })
    }

    @Test
    fun `时间缺失的副本排在有时间的后面（不因为 0 而误当最老）`() {
        val groups = buildDupGroups(
            listOf(
                dup("unknown", sha, upt = 0),
                dup("dated", sha, upt = 1_700_000_000L),
            ),
        )
        assertEquals("dated", groups[0].files.first().fid)
    }

    @Test
    fun `sha1 大小写不同视为同一个内容`() {
        val groups = buildDupGroups(
            listOf(
                dup("a", sha.uppercase()),
                dup("b", sha),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].files.size)
    }

    @Test
    fun `删光多余副本后结果里不再留这组，且话术说清清理了什么`() {
        val groups = buildDupGroups(
            listOf(dup("a", sha, upt = 1), dup("b", sha, upt = 2), dup("c", sha, upt = 3)),
        )
        val (kept, summary) = afterDelete(groups, setOf("b", "c"))
        assertTrue("只剩一个副本就不再是重复了", kept.isEmpty())
        assertTrue("不能还挂着扫描时的旧结论：$summary", summary.startsWith("已清理 2 个重复副本"))
        assertTrue(summary.contains("回收站"))
    }

    @Test
    fun `只删掉一部分时其余组继续留着，并提示还剩几组`() {
        val other = "22".repeat(20)
        val groups = buildDupGroups(
            listOf(
                dup("a", sha, upt = 1), dup("b", sha, upt = 2), dup("c", sha, upt = 3),
                dup("d", other, upt = 1), dup("e", other, upt = 2),
            ),
        )
        val (kept, summary) = afterDelete(groups, setOf("b", "c"))
        assertEquals(1, kept.size)
        assertEquals(listOf("d", "e"), kept[0].files.map { it.fid }.sorted())
        assertTrue(summary, summary.contains("还剩 1 组"))
    }

    @Test
    fun `删除的 fid 不在结果里时什么都不变（比如别处删的）`() {
        val groups = buildDupGroups(listOf(dup("a", sha, upt = 1), dup("b", sha, upt = 2)))
        val (kept, _) = afterDelete(groups, setOf("zzz"))
        assertEquals(groups, kept)
    }
}
