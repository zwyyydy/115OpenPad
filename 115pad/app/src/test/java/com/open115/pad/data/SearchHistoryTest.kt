package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun newQueryGoesFirstAndDuplicatesAreDropped() {
        val merged = SearchHistory.merge(listOf("alpha", "beta"), "BETA")
        assertEquals(listOf("BETA", "alpha"), merged)
    }

    /** 边打边搜的中间态被最终词顶掉："庆"、"庆余" 不应该留在 "庆余年" 前面 */
    @Test
    fun prefixIntermediatesAreDropped() {
        var list = SearchHistory.merge(emptyList(), "庆")
        list = SearchHistory.merge(list, "庆余")
        list = SearchHistory.merge(list, "庆余年")
        assertEquals(listOf("庆余年"), list)
    }

    /** 反过来（先搜长词、再搜短词）保留两条：短词不等于长词的前缀关系，用户可能真的想再搜一次短的 */
    @Test
    fun shorterQueryIsKept() {
        val merged = SearchHistory.merge(listOf("庆余年"), "庆")
        assertEquals(listOf("庆", "庆余年"), merged)
    }

    @Test
    fun cappedAtMax() {
        var list = emptyList<String>()
        repeat(SearchHistory.MAX + 5) { i ->
            list = SearchHistory.merge(list, "q$i")
        }
        assertEquals(SearchHistory.MAX, list.size)
        assertEquals("q${SearchHistory.MAX + 4}", list.first())
    }

    @Test
    fun blankQueryIsIgnored() {
        assertEquals(listOf("a"), SearchHistory.merge(listOf("a"), "   "))
    }
}
