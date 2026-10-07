package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局请求节拍器（设置 → 后台任务 → 「请求限速」）。
 *
 * 打的是"预约下一个槽位"这套边界：首请求不等、并发请求被排成一列、
 * 关掉（0）时彻底不等、空闲很久之后不补睡。
 * 时钟是注入的，所以这里完全不依赖真实时间。
 */
class ApiPacerTest {

    /** 可控时钟 */
    private class FakeClock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    /** 模拟"请求真的等了 w 毫秒再发出去"：时钟推进 w，返回这次等的时长 */
    private fun ApiPacer.hit(clock: FakeClock): Long {
        val wait = reserve()
        clock.advance(wait)
        return wait
    }

    @Test
    fun `关闭时永不等（0 与负数）`() {
        val clock = FakeClock()
        val pacer = ApiPacer(intervalMs = { 0L }, clock = clock::now)
        repeat(5) { assertEquals(0L, pacer.reserve()) }
        val negative = ApiPacer(intervalMs = { -100L }, clock = clock::now)
        repeat(5) { assertEquals(0L, negative.reserve()) }
    }

    @Test
    fun `首个请求不等，后续按间隔依次排队（并发也不会一起放行）`() {
        val clock = FakeClock()
        val pacer = ApiPacer(intervalMs = { 300L }, clock = clock::now)
        // 时钟不动：模拟"同一瞬间来了 4 个请求"
        assertEquals(0L, pacer.reserve())
        assertEquals(300L, pacer.reserve())
        assertEquals(600L, pacer.reserve())
        assertEquals(900L, pacer.reserve())
    }

    @Test
    fun `稳住之后每个请求都只等一个间隔，而不是越等越久`() {
        val clock = FakeClock()
        val pacer = ApiPacer(intervalMs = { 300L }, clock = clock::now)
        assertEquals(0L, pacer.hit(clock))
        repeat(4) { assertEquals(300L, pacer.hit(clock)) }
    }

    @Test
    fun `空闲超过间隔后不补睡`() {
        val clock = FakeClock()
        val pacer = ApiPacer(intervalMs = { 300L }, clock = clock::now)
        assertEquals(0L, pacer.hit(clock))
        assertEquals(300L, pacer.hit(clock))
        // 用户看了两分钟再点：不该为"之前落后了多少拍"补睡
        clock.advance(120_000)
        assertEquals(0L, pacer.hit(clock))
        assertEquals(300L, pacer.hit(clock))
    }

    @Test
    fun `只差一点点就等差额，而不是整个间隔`() {
        val clock = FakeClock()
        val pacer = ApiPacer(intervalMs = { 300L }, clock = clock::now)
        assertEquals(0L, pacer.reserve())
        clock.advance(120)
        assertEquals(180L, pacer.reserve()) // 300 - 120
    }

    @Test
    fun `间隔设置改了立刻生效：已排好的那一拍不动，下一拍按新档`() {
        val clock = FakeClock()
        var interval = 300L
        val pacer = ApiPacer(intervalMs = { interval }, clock = clock::now)
        assertEquals(0L, pacer.hit(clock)) // 排到 1300
        interval = 1000L
        assertEquals(300L, pacer.hit(clock)) // 1300 这一拍是之前就预约好的，只等差额
        assertEquals(1000L, pacer.hit(clock)) // 之后按新档
        interval = 0L
        assertEquals(0L, pacer.hit(clock)) // 关掉之后彻底不等
        assertEquals(0L, pacer.hit(clock))
    }
}
