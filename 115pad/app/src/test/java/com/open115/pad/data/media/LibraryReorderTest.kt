package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 媒体库拖动排序的纯逻辑。
 *
 * 这段最容易悄悄错的地方是"移出去之后下标会整体前移一位"—— 写错了表现为"往左拖一格没反应、
 * 往右拖多走一格"，而界面上看起来只是"偶尔不太听话"，很难定位。所以钉住。
 */
class LibraryReorderTest {

    private val ids = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun `往右拖 —— 插到落点那张的位置上，落点那张往前让一格`() {
        // 把 1 拖到 3 头上：1 占 3 的位置，3 与之后的整体往前
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L), MediaLibraryEntity.reorderIds(ids, 0, 2))
    }

    @Test
    fun `往左拖 —— 同样是占位语义`() {
        // 把 5 拖到 2 头上：5 占 2 的位置，2 往后
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), MediaLibraryEntity.reorderIds(ids, 4, 1))
    }

    @Test
    fun `拖到第一张 = 排最前，拖到最后一张 = 排最后`() {
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), MediaLibraryEntity.reorderIds(ids, 4, 0))
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), MediaLibraryEntity.reorderIds(ids, 0, 4))
    }

    @Test
    fun `相邻互换与不用动的情况`() {
        // 1 拖到 2 上 → 互换
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L), MediaLibraryEntity.reorderIds(ids, 0, 1))
        // 4 拖到 3 上 → 也是互换（相邻两个方向都成立）
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), MediaLibraryEntity.reorderIds(ids, 3, 2))
    }

    @Test
    fun `拖回原位原样返回（不白写一次库）`() {
        assertSame(ids, MediaLibraryEntity.reorderIds(ids, 2, 2))
    }

    @Test
    fun `野下标原样返回 —— 拖动期间列表被扫描任务改了也会给出越界值`() {
        assertSame(ids, MediaLibraryEntity.reorderIds(ids, -1, 2))
        assertSame(ids, MediaLibraryEntity.reorderIds(ids, 2, 9))
        assertSame(ids, MediaLibraryEntity.reorderIds(ids, 9, 2))
    }

    @Test
    fun `只有一张或空的时候不折腾`() {
        val one = listOf(7L)
        assertSame(one, MediaLibraryEntity.reorderIds(one, 0, 0))
        val empty = emptyList<Long>()
        assertSame(empty, MediaLibraryEntity.reorderIds(empty, 0, 0))
    }

    @Test
    fun `结果永远是同一批 id 的一个排列`() {
        for (from in ids.indices) {
            for (hover in ids.indices) {
                val out = MediaLibraryEntity.reorderIds(ids, from, hover)
                assertEquals(ids.size, out.size)
                assertEquals(ids.toSet(), out.toSet())
            }
        }
    }
}
