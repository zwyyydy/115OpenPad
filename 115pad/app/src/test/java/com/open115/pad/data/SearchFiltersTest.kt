package com.open115.pad.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFiltersTest {

    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun gteDaySubtractsCalendarDays() {
        assertEquals("2026-10-16", SearchFilters.gteDay(7, today))
        assertEquals("2026-09-23", SearchFilters.gteDay(30, today))
        // 跨年：365 天前（2026 不是闰年，起点落在 2025-10-23）
        assertEquals("2025-10-23", SearchFilters.gteDay(365, today))
    }

    /** 服务端实测只认成对的 gte_day + lte_day，所以 range 必须同时给出两端 */
    @Test
    fun rangeAlwaysPairsStartWithToday() {
        val (gte, lte) = SearchFilters.range(7, today)
        assertEquals("2026-10-16", gte)
        assertEquals("2026-10-23", lte)
    }

    @Test
    fun presetsAreSortedAndPositive() {
        assertEquals(listOf(7, 30, 365), SearchFilters.DAY_PRESETS.map { it.first })
    }
}
