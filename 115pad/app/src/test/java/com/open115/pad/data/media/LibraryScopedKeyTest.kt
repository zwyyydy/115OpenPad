package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨库同名影片的键归属（判据见 [needsLibraryScopedKey]）。
 *
 * 这里钉的是"什么时候该另起一行"：判错一边都是用户看得见的事故 ——
 * 判宽了，同一个库里的重复目录会变成两张卡；判窄了，后扫的库会把先扫那行 upsert 走
 * （前一个库海报墙上少一张卡，就是用户报的"后者夺取前者"）。
 */
class LibraryScopedKeyTest {

    private val rootsB = listOf("/媒体库B")

    @Test
    fun `基键空着 —— 第一个库用基键`() {
        assertFalse(needsLibraryScopedKey(null, null, "/媒体库B/ABC-301", rootsB))
    }

    @Test
    fun `就是同一个目录 —— 重扫还是基键`() {
        assertFalse(
            needsLibraryScopedKey("/媒体库B/ABC-301", null, "/媒体库B/ABC-301", rootsB),
        )
    }

    @Test
    fun `基键落在本库另一个目录 —— 库内重复仍旧一张卡（后扫的赢）`() {
        assertFalse(
            needsLibraryScopedKey("/媒体库B/别的目录/ABC-301", null, "/媒体库B/ABC-301", rootsB),
        )
    }

    @Test
    fun `基键落在本库另一个根 —— 多根库也是同一个库`() {
        val roots = listOf("/媒体库B", "/另一个根")
        assertFalse(
            needsLibraryScopedKey("/另一个根/ABC-301", null, "/媒体库B/ABC-301", roots),
        )
    }

    @Test
    fun `基键被另一个不相交的库占着 —— 本库另起一行`() {
        assertTrue(
            needsLibraryScopedKey("/媒体库A/ABC-301", null, "/媒体库B/ABC-301", rootsB),
        )
    }

    @Test
    fun `路径前缀只认整段 —— 媒体库B 不是媒体库BB 的父目录`() {
        assertTrue(
            needsLibraryScopedKey("/媒体库BB/ABC-301", null, "/媒体库B/ABC-301", rootsB),
        )
        assertFalse(
            needsLibraryScopedKey("/媒体库B/子目录/ABC-301", null, "/媒体库B/ABC-301", rootsB),
        )
    }

    @Test
    fun `本库已经建过 scoped 行 —— 基键后来空出来也不回基键`() {
        assertTrue(
            needsLibraryScopedKey(null, "/媒体库B/ABC-301", "/媒体库B/ABC-301", rootsB),
        )
        // 目录搬了家（同一库内）也继续用 scoped 键，键不来回换
        assertTrue(
            needsLibraryScopedKey(null, "/媒体库B/旧目录", "/媒体库B/新目录", rootsB),
        )
    }

    @Test
    fun `scoped 键格式与库 id 绑定`() {
        assertEquals("ABC-301#lib7", libraryScopedKey("ABC-301", 7))
        assertEquals("tmdb-123#lib42", libraryScopedKey("tmdb-123", 42))
        // 不同库不会撞
        assertTrue(libraryScopedKey("ABC-301", 1) != libraryScopedKey("ABC-301", 2))
    }

    @Test
    fun `根路径带尾斜杠也认得出`() {
        assertTrue(isDirUnderRoots("/媒体库B/ABC-301/", listOf("/媒体库B/")))
        assertTrue(isDirUnderRoots("/媒体库B", listOf("/媒体库B/")))
        assertFalse(isDirUnderRoots("", listOf("/媒体库B")))
        assertFalse(isDirUnderRoots(null, listOf("/媒体库B")))
    }
}
