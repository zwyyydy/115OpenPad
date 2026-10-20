package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移队列的纯逻辑：**谁先跑、什么时候收尾、超上限裁谁**。
 *
 * 这三条都是"错了不会报错、只会悄悄做错事"的类型：先跑了不该跑的那一批、
 * 或者把没跑完的一批裁掉 —— 界面上都看不出来，只有落到用例里才拦得住。
 */
class MigrationQueueTest {

    private fun item(key: String, title: String = key, st: MigrationItemStatus = MigrationItemStatus.PENDING) =
        MigrationItem(mediaKey = key, title = title, status = st.name)

    private fun task(
        id: Long,
        createdAt: Long = id,
        items: List<MigrationItem> = listOf(item("m$id")),
        finishedAt: Long? = null,
        paused: Boolean = false,
        subPath: String = "",
    ) = MigrationTask(
        id = id,
        createdAt = createdAt,
        targetLibraryId = 7,
        targetName = "目标库",
        rootIndex = 0,
        subPath = subPath,
        items = items,
        finishedAt = finishedAt,
        paused = paused,
    )

    @Test
    fun `挑最早创建的那批 且跳过暂停与已结束的`() {
        val older = task(1, createdAt = 100)
        val newer = task(2, createdAt = 200)
        assertEquals(older, nextRunnableMigration(listOf(newer, older)))

        // 暂停的（用户点了停止）不该被自动捡起来 —— 等他到记录里点「继续」
        assertNull(nextRunnableMigration(listOf(task(3, paused = true))))
        // 已结束的同样
        assertNull(nextRunnableMigration(listOf(task(4, finishedAt = 999))))
        // 全部处理完但没标结束的：也不是"该跑"的（交给收尾那一支）
        val allDone = task(5, items = listOf(item("a", st = MigrationItemStatus.DONE)))
        assertNull(nextRunnableMigration(listOf(allDone)))
        // 暂停 + 新的可跑：挑新的
        assertEquals(newer, nextRunnableMigration(listOf(task(3, createdAt = 50, paused = true), newer)))
    }

    @Test
    fun `全部处理完但没标结束 需要收尾`() {
        val allDone = task(1, items = listOf(item("a", st = MigrationItemStatus.DONE)))
        assertTrue(migrationTaskNeedsFinish(allDone))
        // 还有待办的：不需要收尾，该跑
        assertFalse(migrationTaskNeedsFinish(task(2)))
        // 已经结束的：也不用再收
        assertFalse(migrationTaskNeedsFinish(task(3, finishedAt = 1)))
    }

    @Test
    fun `超上限只裁已结束的 从最旧的开始`() {
        val tasks = listOf(
            task(1, createdAt = 1, finishedAt = 10),   // 已结束，最旧 → 先裁它
            task(2, createdAt = 2, finishedAt = 20),
            task(3, createdAt = 3),                    // 没结束
            task(4, createdAt = 4, paused = true),     // 暂停也属于"没结束"，不能裁
            task(5, createdAt = 5, finishedAt = 30),
        )
        val kept = trimMigrationTasks(tasks, max = 3)
        assertEquals(3, kept.size)
        // 两个没结束的必须都在
        assertTrue(kept.any { it.id == 3L })
        assertTrue(kept.any { it.id == 4L })
        // 留下的那个已结束的应当是**最新**的那个（5）
        assertEquals(listOf(3L, 4L, 5L), kept.map { it.id }.sorted())
    }

    @Test
    fun `没结束的比上限还多时 一个都不裁`() {
        val tasks = (1..5).map { task(it.toLong()) }
        assertEquals(5, trimMigrationTasks(tasks, max = 2).size)
    }

    @Test
    fun `计数与文案`() {
        val t = task(
            1,
            items = listOf(
                item("a", st = MigrationItemStatus.DONE),
                item("b", st = MigrationItemStatus.DONE),
                item("c", st = MigrationItemStatus.SKIPPED),
                item("d", st = MigrationItemStatus.FAILED),
                item("e"),
            ),
            subPath = "异形（系列）",
        )
        assertEquals(5, t.total)
        assertEquals(4, t.doneCount)
        assertEquals(2, t.succeeded)
        assertEquals(1, t.skipped)
        assertEquals(1, t.failed)
        assertTrue(t.hasPending)
        assertTrue(t.hasFailed)
        assertEquals("5 部 · 成功 2 · 跳过 1 · 失败 1", t.summaryLine)
        assertEquals("「目标库」/异形（系列）", t.targetLabel)
        assertEquals("「目标库」库根", task(9).targetLabel)
    }

    @Test
    fun `状态认不出一律当待办`() {
        // 旧数据/手改过的值不能让它整批卡住 —— 当待办重跑（会先过校验，比漏搬安全）
        assertEquals(MigrationItemStatus.PENDING, MigrationItem("k", "t", status = "WEIRD").st)
        assertEquals(MigrationItemStatus.DONE, MigrationItem("k", "t", status = "DONE").st)
    }
}
