package com.open115.pad.player

import com.open115.pad.data.HlsSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预下载的两块纯逻辑：位置 → 分片下标、已缓存分片 → 时间区间。
 *
 * 这两个错了的表现都不报错：前者会让预下载从头/从错的地方开始灌（白下几百 MB），
 * 后者会让进度条上的"已缓存"那段画错位置（用户以为缓存没生效）。所以单独盯住。
 */
class PlayerPrefetchTest {

    /** 10 秒一片，共 10 片（0~100 秒） */
    private fun segs(count: Int = 10, durSec: Double = 10.0): List<HlsSegment> =
        (0 until count).map {
            HlsSegment(
                startSec = it * durSec,
                durationSec = durSec,
                url = "https://cpats01.115.com/x/y/${"a".repeat(40)}_1920-${"%05d".format(it + 1)}.ts",
            )
        }

    @Test
    fun `位置落在哪一片`() {
        val s = segs()
        assertEquals(0, segmentIndexAt(s, 0L))
        assertEquals(0, segmentIndexAt(s, 9_999L))
        assertEquals(1, segmentIndexAt(s, 10_000L))
        assertEquals(3, segmentIndexAt(s, 35_000L))
        assertEquals(9, segmentIndexAt(s, 90_000L))
        // 超出片尾：仍返回最后一片（调用方自己判断"到片尾"）
        assertEquals(9, segmentIndexAt(s, 10_000_000L))
    }

    @Test
    fun `空分片表不炸`() {
        assertEquals(0, segmentIndexAt(emptyList(), 1234L))
        val none = mergeCachedRanges(LongArray(0), LongArray(0), BooleanArray(0))
        assertEquals(emptyList<LongRange>(), none)
    }

    /** 10 段的时间表：i 片的区间是 [i*10s, (i+1)*10s) */
    private fun times(count: Int = 10): Pair<LongArray, LongArray> {
        val starts = LongArray(count) { it * 10_000L }
        val ends = LongArray(count) { (it + 1) * 10_000L }
        return starts to ends
    }

    @Test
    fun `只有整片缓存的分片才计入区间`() {
        val (starts, ends) = times()
        val cached = BooleanArray(10)
        cached[2] = true
        cached[3] = true
        cached[7] = true
        assertEquals(listOf(20_000L..40_000L, 70_000L..80_000L), mergeCachedRanges(starts, ends, cached))
    }

    @Test
    fun `全缓存合成一整段`() {
        val (starts, ends) = times()
        assertEquals(listOf(0L..100_000L), mergeCachedRanges(starts, ends, BooleanArray(10) { true }))
    }

    @Test
    fun `首尾单独缓存也不丢`() {
        val (starts, ends) = times()
        val cached = BooleanArray(10)
        cached[0] = true
        cached[9] = true
        assertEquals(listOf(0L..10_000L, 90_000L..100_000L), mergeCachedRanges(starts, ends, cached))
    }

    @Test
    fun `直链地址识别`() {
        assertTrue(isDirectFileUrl("https://cdnfhnfile.115cdn.net/abc/AAA-001.mp4?t=1&k=x"))
        assertTrue(isDirectFileUrl("https://x/AAA-001.MKV"))
        assertFalse(isDirectFileUrl("http://videoplay.115.com/m3u8/pickcode?definition=4"))
        assertFalse(isDirectFileUrl("https://cpats01.115.com/a/b/abc_1920.m3u8?s=1"))
    }

    /** 循环缓存：回退保留时长（锚点之前留多少不删，供小幅回退立即命中） */
    @Test
    fun `回退保留时长`() {
        // 时长未知 / 长片：固定 2 分钟
        assertEquals(120_000L, keepBackMsFor(0L))
        assertEquals(120_000L, keepBackMsFor(2 * 60 * 60_000L))
        // 短片压到片长的 1/3（3 分钟的片 → 1 分钟），否则永远等不到可舍弃的旧内容
        assertEquals(60_000L, keepBackMsFor(3 * 60_000L))
        // 更短的片不低于 10 秒
        assertEquals(10_000L, keepBackMsFor(15_000L))
    }

    /** 循环缓存：只有**完整播完**的分片能舍弃，正在播的那一片绝不碰 */
    @Test
    fun `完全播完的段才舍弃`() {
        val s = segs()
        assertEquals(0, segmentsBefore(s, 0L))
        assertEquals(1, segmentsBefore(s, 10_000L))   // 第 1 片（0~10s）刚好播完
        assertEquals(3, segmentsBefore(s, 35_000L))   // 第 4 片（30~40s）还没播完 → 只到第 3 片
        assertEquals(10, segmentsBefore(s, 100_000L))
        assertEquals(10, segmentsBefore(s, 10_000_000L))
    }

    /** 循环缓存：直链的字节区间只有整段落在锚点之前才删 */
    @Test
    fun `直链字节区间的舍弃判定`() {
        assertTrue(spanBefore(0, 100, 100))
        assertFalse(spanBefore(0, 100, 99))
        assertTrue(spanBefore(50, 100, 150))
        assertFalse(spanBefore(50, 100, 149))
        // 长度未知/无效的区间不删
        assertFalse(spanBefore(0, 0, 1_000_000))
    }

    /** 动态挡位：约片长的 5%（等价于文件大小的 5%）；短片与未知值各有兜底 */
    @Test
    fun `动态挡位的目标大小`() {
        val mb = 1024L * 1024
        // 2 小时、6GB → 5% = 300MB
        assertEquals(300L * mb, PlayerCache.dynamicLimitBytes(2 * 60 * 60_000L, 6_000L * mb))
        // 10 分钟、500MB → 5% 只有 25MB，抬到下限 128MB
        assertEquals(128L * mb, PlayerCache.dynamicLimitBytes(10 * 60_000L, 500L * mb))
        // 不足 5 分钟 → 不套比例，按整片缓存（给到上限）
        assertEquals(4096L * mb, PlayerCache.dynamicLimitBytes(4 * 60_000L, 300L * mb))
        // 超大文件 → 5% 超上限就压到上限
        assertEquals(4096L * mb, PlayerCache.dynamicLimitBytes(2 * 60 * 60_000L, 100_000L * mb))
        // 大小未知 → 下限
        assertEquals(128L * mb, PlayerCache.dynamicLimitBytes(2 * 60 * 60_000L, 0L))
        // 时长未知 → 按大小算
        assertEquals(300L * mb, PlayerCache.dynamicLimitBytes(0L, 6_000L * mb))
    }
}
