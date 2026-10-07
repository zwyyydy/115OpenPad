package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 段（深挖）的纯逻辑：真实参数怎么用、结构一致怎么判、哪一档排前面。
 *
 * 判据只用两条硬指标（真实宽高比 + 毫秒级时长），**不比内容** —— 内容近似与否要抽帧（C 段）。
 * 实测依据：剧集各集时长差是秒级（2496s vs 2502s），一进这里就该被判"结构对不上"。
 */
class VideoMetaVerdictTest {

    private fun meta(w: Int, h: Int, durMs: Long = 100 * 60_000L, bitrate: Long = 5_000_000L, rotation: Int = 0) =
        VideoMeta(width = w, height = h, durationMs = durMs, bitrate = bitrate, rotation = rotation, mime = "video/mp4")

    private fun v(name: String, size: Long = 1_000_000_000L, duration: Long = 6000L) = VideoVersion(
        item = FileItem(fid = name, fc = 1, fn = name, fs = size, play_long = duration),
        dirCid = "cid_$name",
        dirPath = "全部文件/A",
    )

    @Test
    fun `旋转 90 度时宽高要换过来（竖屏片不能被算成横屏）`() {
        val stored = meta(1920, 1080, rotation = 90)
        assertEquals(1080, stored.dispWidth)
        assertEquals(1920, stored.dispHeight)
        assertEquals("1080×1920", stored.resolution)
        // 与"本来就存成竖屏"的同一部片，宽高比应当一致
        assertEquals(meta(1080, 1920).aspectRatio, stored.aspectRatio, 0.0001)
    }

    @Test
    fun `宽高比与时长都对得上：判结构一致`() {
        val g = VersionGroup(
            members = listOf(v("a"), v("b")),
            sameContent = false, sharedToken = null, commonSubstring = 0,
            sizeRatio = 3.0, durationSpan = 0,
        )
        val metaMap = mapOf(
            "a" to meta(1920, 1080, durMs = 6_000_000L),
            "b" to meta(3840, 2160, durMs = 6_000_000L),
        )
        assertEquals(VersionVerdict.LIKELY_SAME, verdictOf(g, metaMap))
    }

    @Test
    fun `宽高比不一致：判结构对不上`() {
        val g = VersionGroup(listOf(v("a"), v("b")), false, null, 0, 1.0, 0)
        val metaMap = mapOf(
            "a" to meta(1920, 1080),      // 16:9
            "b" to meta(1920, 800),       // 2.4:1
        )
        assertEquals(VersionVerdict.DIFFERENT_SHAPE, verdictOf(g, metaMap))
    }

    @Test
    fun `时长差 6 秒（剧集各集的典型情况）：判结构对不上`() {
        val g = VersionGroup(listOf(v("a"), v("b")), false, null, 0, 1.0, 0)
        val metaMap = mapOf(
            "a" to meta(1280, 720, durMs = 2_496_000L),
            "b" to meta(1280, 720, durMs = 2_502_000L),
        )
        assertEquals(VersionVerdict.DIFFERENT_SHAPE, verdictOf(g, metaMap))
    }

    @Test
    fun `只有一条拿到真实参数：算未深挖`() {
        val g = VersionGroup(listOf(v("a"), v("b")), false, null, 0, 1.0, 0)
        assertEquals(VersionVerdict.UNKNOWN, verdictOf(g, mapOf("a" to meta(1920, 1080))))
    }

    @Test
    fun `组里混着别的片子时，只挑出结构一致的最大子集`() {
        // 3 个互相一致（同画幅同毫秒时长）+ 2 个混进来的（时长差好几秒）
        val good = listOf(v("a.2160p"), v("a.1080p"), v("a.720p"))
        val noise = listOf(v("b.ep1"), v("b.ep2"))
        val members = good + noise
        val metaMap = mapOf(
            "a.2160p" to meta(3840, 2160, durMs = 6_000_000L),
            "a.1080p" to meta(1920, 1080, durMs = 6_000_000L),
            "a.720p" to meta(1280, 720, durMs = 6_000_100L),
            "b.ep1" to meta(1280, 720, durMs = 2_496_000L),
            "b.ep2" to meta(1280, 720, durMs = 2_502_000L),
        )
        val cluster = consistentCluster(members, metaMap)
        assertEquals(listOf("a.1080p", "a.2160p", "a.720p"), cluster.map { it.name }.sorted())
        // 整组仍判"结构一致"（因为子集一致）—— 推荐保留与删除只在这个子集上做
        val g = VersionGroup(members, false, null, 0, 3.0, 0)
        assertEquals(VersionVerdict.LIKELY_SAME, verdictOf(g, metaMap))
    }

    @Test
    fun `时长只差 300 毫秒的两集也不算同一簇（41-38 与 41-39 的真实差就是这么多）`() {
        val members = listOf(v("a"), v("b"))
        val metaMap = mapOf(
            "a" to meta(1280, 720, durMs = 2_498_600L),
            "b" to meta(1280, 720, durMs = 2_498_900L),
        )
        assertTrue("同内容文件的毫秒时长是 0 差，容差得比 300ms 更紧", consistentCluster(members, metaMap).isEmpty())
    }

    @Test
    fun `画幅不同但时长相同的两个片子：不成子集`() {
        val members = listOf(v("x"), v("y"))
        val metaMap = mapOf(
            "x" to meta(1920, 1080, durMs = 6_000_000L),
            "y" to meta(1080, 1080, durMs = 6_000_000L), // 方形画幅
        )
        assertTrue(consistentCluster(members, metaMap).isEmpty())
    }

    @Test
    fun `顺序：像素量优先，没深挖的排最后`() {
        val a = v("a-1080p", size = 900)
        val b = v("b-2160p", size = 500)
        val c = v("c-not-probed", size = 9999)
        val ordered = orderByQuality(
            listOf(a, b, c),
            mapOf(
                a.fid to meta(1920, 1080, bitrate = 9_000_000L),
                b.fid to meta(3840, 2160, bitrate = 4_000_000L),
            ),
        )
        assertEquals(listOf("b-2160p", "a-1080p", "c-not-probed"), ordered.map { it.name })
    }

    @Test
    fun `同像素量时比码率，再比体积`() {
        val low = v("low", size = 4_000)
        val high = v("high", size = 1_000)
        val ordered = orderByQuality(
            listOf(low, high),
            mapOf(
                low.fid to meta(1920, 1080, bitrate = 2_000_000L),
                high.fid to meta(1920, 1080, bitrate = 8_000_000L),
            ),
        )
        assertEquals(listOf("high", "low"), ordered.map { it.name })
        // 两条都没深挖时退回按体积降序（A 段的老口径）
        assertTrue(orderByQuality(listOf(low, high), emptyMap()).first().name == "low")
    }
}
