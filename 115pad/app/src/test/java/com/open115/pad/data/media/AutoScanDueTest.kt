package com.open115.pad.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次启动该不该自动扫这个库」的边界。
 *
 * 抽成 [autoScanDue] 就是为了测这个：时间判断最容易差一个边界，
 * 而错了的后果是"该扫的时候不扫"（用户以为自动扫描坏了）或"每次启动都扫"（白花几十上百次请求）。
 */
class AutoScanDueTest {

    private val hour = 3_600_000L
    private fun lib(autoScan: Boolean = true, hours: Int = 0, lastScanAt: Long = 0L) =
        MediaLibraryEntity(
            name = "库",
            rootCid = "1",
            rootPath = "p",
            autoScanOnStart = autoScan,
            autoScanIntervalHours = hours,
            lastScanAt = lastScanAt,
        )

    @Test
    fun `间隔 0 每次启动都扫_这是老行为`() {
        // 老库升级上来 autoScanIntervalHours 默认 0，行为必须与升级前一致
        assertTrue(lib(hours = 0, lastScanAt = 999_999L).autoScanDue(now = 1_000_000L))
    }

    @Test
    fun `从未扫过_要扫`() {
        assertTrue(lib(hours = 6, lastScanAt = 0L).autoScanDue(now = 1_000_000L))
    }

    @Test
    fun `没到间隔_跳过`() {
        val now = 100 * hour
        assertFalse(lib(hours = 6, lastScanAt = now - 5 * hour).autoScanDue(now))
        assertFalse(lib(hours = 1, lastScanAt = now - 59 * 60_000L).autoScanDue(now))
    }

    @Test
    fun `正好等于间隔_要扫`() {
        // 设 1 小时 = "满 1 小时可以扫"，所以边界取 >= 而不是 >
        val now = 100 * hour
        assertTrue(lib(hours = 1, lastScanAt = now - hour).autoScanDue(now))
    }

    @Test
    fun `超过间隔_要扫`() {
        val now = 100 * hour
        assertTrue(lib(hours = 6, lastScanAt = now - 7 * hour).autoScanDue(now))
    }

    @Test
    fun `负数间隔当成 0 处理_不能变成永远不扫`() {
        // 界面上输入框只收数字，但库是 DB 来的、也可能被旧版本写脏；当 0 = 每次启动都扫
        assertTrue(lib(hours = -3, lastScanAt = 999_999L).autoScanDue(now = 1_000_000L))
    }
}
