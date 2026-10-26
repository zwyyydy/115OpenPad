package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「每库卡片大小」的落盘格式（`id:span,id:span`）。
 *
 * 这条格式是跨重启的界面状态：坏一个条目只能丢它自己，不能把整页卡片打回自动
 * （所以解析逐条 try，非法 id / span 直接跳过）。
 */
class LibraryCardSpanPrefsTest {

    @Test
    fun `往返 —— 按 id 排序，读回来一模一样`() {
        val map = mapOf(3L to 1, 11L to 3, 7L to 2)
        val raw = encodeLibraryCardSpans(map)
        assertEquals("3:1,7:2,11:3", raw)
        assertEquals(map, decodeLibraryCardSpans(raw))
    }

    @Test
    fun `空与空白 —— 读回来是空 map`() {
        assertEquals(emptyMap<Long, Int>(), decodeLibraryCardSpans(null))
        assertEquals(emptyMap<Long, Int>(), decodeLibraryCardSpans(""))
        assertEquals("", encodeLibraryCardSpans(emptyMap()))
    }

    @Test
    fun `脏条目逐条跳过 —— 不影响其它库`() {
        val raw = "3:1,坏id:2,7:x,9:0,11:4,12:3,13"
        assertEquals(mapOf(3L to 1, 12L to 3), decodeLibraryCardSpans(raw))
    }

    @Test
    fun `编码丢掉非法值 —— 0 表示自动，不该落盘`() {
        assertEquals("5:2", encodeLibraryCardSpans(mapOf(5L to 2, 6L to 0, 7L to 9)))
    }
}
