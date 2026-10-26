package com.open115.pad.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库卡片的 pad 网格排布（[libraryTiles] / [packLibraryTiles]）。
 *
 * 这里钉的是用户定下的四条规则：
 * - 1×1 铺 1 张封面、1×2 铺 2 张、**1×3 铺 6 张**；
 * - 定义过大小后 pad 网格一行三等分（1×2 = 2/3、1×3 = 整行）；列数不够时 span 夹到列数，
 *   封面数仍按定义的大小；
 * - 没有任何库设过时保持老规则（奇数个/单库时第一张当通栏主推位）；
 * - 手机（compact）永远是 1×1，不看存过的大小。
 */
class LibraryCardLayoutTest {

    private val ids = (1L..5L).toList()

    @Test
    fun `封面数映射 —— 1 1、2 2、3 6`() {
        assertEquals(1, coversForLibrarySize(1))
        assertEquals(2, coversForLibrarySize(2))
        assertEquals(6, coversForLibrarySize(3))
        // 越界值不崩（0 当 1 张）
        assertEquals(1, coversForLibrarySize(0))
    }

    @Test
    fun `自动·2 列里奇数个库 —— 第一张 1×2 铺 2 张，其余 1×1`() {
        val tiles = libraryTiles(ids.take(3), columns = 2, compact = false, explicitSizes = emptyMap())
        assertEquals(listOf(2, 1, 1), tiles.map { it.span })
        assertEquals(listOf(2, 1, 1), tiles.map { it.covers })
        assertEquals(2, tiles[0].size)
    }

    @Test
    fun `自动·2 列里偶数个库 —— 不拆主推位，全是 1×1`() {
        val tiles = libraryTiles(ids.take(4), columns = 2, compact = false, explicitSizes = emptyMap())
        assertEquals(listOf(1, 1, 1, 1), tiles.map { it.span })
        assertEquals(listOf(1, 1, 1, 1), tiles.map { it.covers })
    }

    @Test
    fun `自动·3 列里 4 个库 —— 第一张占满 3 列，铺 6 张封面`() {
        val tiles = libraryTiles(ids.take(4), columns = 3, compact = false, explicitSizes = emptyMap())
        assertEquals(listOf(3, 1, 1, 1), tiles.map { it.span })
        assertEquals(listOf(6, 1, 1, 1), tiles.map { it.covers })
    }

    @Test
    fun `自动·只有一个库 —— 整行都是它，按 1×2 铺 2 张`() {
        val tiles = libraryTiles(listOf(7L), columns = 1, compact = false, explicitSizes = emptyMap())
        assertEquals(1, tiles.single().span)
        assertEquals(2, tiles.single().covers)
    }

    @Test
    fun `手动·列数不足 —— span 夹到列数，但封面仍按定义的大小`() {
        val tiles = libraryTiles(ids.take(3), columns = 2, compact = false, explicitSizes = mapOf(1L to 3))
        assertEquals(2, tiles[0].span)
        assertEquals(6, tiles[0].covers)
        // 没设过的库算 1×1（手动模式下不再有自动主推位）
        assertEquals(listOf(1, 1), tiles.drop(1).map { it.span })
    }

    @Test
    fun `手动·三等分 —— 1×1 是 1、1×2 是 2、1×3 是 3（1×2 与 1×3 宽度必须不同）`() {
        val tiles = libraryTiles(
            listOf(1L, 2L, 3L), columns = 3, compact = false,
            explicitSizes = mapOf(1L to 1, 2L to 2, 3L to 3),
        )
        assertEquals(listOf(1, 2, 3), tiles.map { it.span })
        assertEquals(listOf(1, 2, 6), tiles.map { it.covers })
        // 1/3 + 2/3 占满第一行，1×3 单独占满第二行 —— 每张卡都按自己的定义取宽
        val rows = packLibraryTiles(tiles, columns = 3)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), rows.map { row -> row.map { it.id } })
    }

    @Test
    fun `手动混合 —— 1×2 + 1×1 能拼成一整行`() {
        val tiles = libraryTiles(
            ids.take(3), columns = 3, compact = false,
            explicitSizes = mapOf(1L to 2, 2L to 1, 3L to 1),
        )
        val rows = packLibraryTiles(tiles, columns = 3)
        assertEquals(2, rows.size)
        assertEquals(listOf(2, 1), rows[0].map { it.span })
        assertEquals(listOf(1), rows[1].map { it.span })
    }

    @Test
    fun `手机 —— 不管存过什么，一律 1×1 一张封面`() {
        val tiles = libraryTiles(
            ids.take(2), columns = 1, compact = true,
            explicitSizes = mapOf(1L to 3, 2L to 2),
        )
        assertEquals(listOf(1, 1), tiles.map { it.span })
        assertEquals(listOf(1, 1), tiles.map { it.covers })
    }

    @Test
    fun `装行 —— 放不下就换行，不影响顺序`() {
        // 4 个库 / 3 列 → 自动主推位：第一张占满一行，其余 3 张一行
        val tiles = libraryTiles(ids.take(4), columns = 3, compact = false, explicitSizes = emptyMap())
        val rows = packLibraryTiles(tiles, columns = 3)
        assertEquals(listOf(1, 3), rows.map { it.size })
        assertEquals(ids.take(4), rows.flatten().map { it.id })

        val allSmall = ids.map { LibraryTile(it, 1, 1, 1) }
        assertEquals(2, packLibraryTiles(allSmall, columns = 3).size)
        assertEquals(5, packLibraryTiles(allSmall, columns = 1).size)
        assertEquals(emptyList<List<LibraryTile>>(), packLibraryTiles(emptyList(), columns = 3))
    }

    @Test
    fun `每张卡都留在列表里 —— 不会因为装行丢卡片`() {
        val sizes = mapOf(1L to 3, 3L to 2)
        val tiles = libraryTiles(ids, columns = 2, compact = false, explicitSizes = sizes)
        val rows = packLibraryTiles(tiles, columns = 2)
        assertEquals(ids, rows.flatten().map { it.id })
    }

    @Test
    fun `格宽与卡宽 —— 横屏三等分 1 格 360dp，竖屏两列 1 格 333dp`() {
        // 横屏 1137dp：行宽 1105、列间距 2×12 → (1105-24)/3 = 360
        assertEquals(360, libraryUnitWidth(1137, 3))
        // 竖屏 711dp：行宽 679、1 个列间距 → (679-12)/2 = 333
        assertEquals(333, libraryUnitWidth(711, 2))
        // span 2 / 3 的卡宽 = 格宽×span + 中间列间距
        assertEquals(678, libraryCardWidth(711, 2, 2))
        assertEquals(1104, libraryCardWidth(1137, 3, 3))
    }

    @Test
    fun `6 张封面在窄卡上折成 3+3，宽卡才一行 6 张`() {
        // 竖屏 1×3（满行 678dp）→ 3 张一行，两行 —— 否则文字被挤成一条缝
        assertEquals(3, libraryCoverColumns(6, 678))
        assertEquals(3, libraryCoverColumns(6, 799))
        // 横屏三等分 1×3（1104dp）→ 一行 6 张
        assertEquals(6, libraryCoverColumns(6, 800))
        assertEquals(6, libraryCoverColumns(6, 1104))
        // 其他大小不受影响
        assertEquals(2, libraryCoverColumns(2, 678))
        assertEquals(1, libraryCoverColumns(1, 333))
    }

    @Test
    fun `窄卡布局 —— 竖屏两列、横屏三等分算窄；横屏两列不算`() {
        assertTrue(isNarrowLibraryGrid(711, 2, compact = false))
        assertTrue(isNarrowLibraryGrid(1137, 3, compact = false))
        assertFalse(isNarrowLibraryGrid(1137, 2, compact = false))
        // 手机（compact）不参与统一高度那套
        assertFalse(isNarrowLibraryGrid(411, 1, compact = true))
    }
}
