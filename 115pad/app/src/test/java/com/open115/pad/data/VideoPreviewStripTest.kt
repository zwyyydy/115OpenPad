package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 宽幅预览条的两件事：**抽几帧 / 抽在哪**，以及**本地缓存怎么存**。
 *
 * 这一段没有真机能替：帧数算错、取样点扎堆在同一个分片、旧张不删，都只会在界面上表现为
 * "这条预览条看着怪"，很难一眼归因，所以判据钉成用例。
 */
class VideoPreviewStripTest {

    // ---- 帧数：与片长正相关，夹在 [2, 10] ----

    @Test
    fun `帧数随片长增加并被夹在 2 到 10`() {
        assertEquals(2, stripFrameCount(0)) // 时长未知按最少
        assertEquals(2, stripFrameCount(96)) // 实测样本：VR 测试片 96 秒
        assertEquals(2, stripFrameCount(300)) // 正好 5 分钟 → 1 帧，夹到 2
        assertEquals(2, stripFrameCount(600))
        assertEquals(3, stripFrameCount(900))
        assertEquals(5, stripFrameCount(1500))
        assertEquals(10, stripFrameCount(3000))
        assertEquals(10, stripFrameCount(6060)) // 实测样本：杜比视界测试片 101 分钟 → 到顶
        assertEquals(2, stripFrameCount(-1))
    }

    // ---- 取样点：均分取中点 ----

    @Test
    fun `取样点均分且不贴首尾`() {
        assertEquals(listOf(0.25, 0.75), stripFractions(2))
        assertEquals(listOf(0.125, 0.375, 0.625, 0.875), stripFractions(4))
        val ten = stripFractions(10)
        assertEquals(10, ten.size)
        assertEquals(0.05, ten.first(), 1e-9)
        assertEquals(0.95, ten.last(), 1e-9)
        assertTrue(ten.all { it > 0.0 && it < 1.0 })
        // 越界入参不能抛：0 张没有意义，按 1 张处理；超过上限按上限
        assertEquals(listOf(0.5), stripFractions(0))
        assertEquals(10, stripFractions(99).size)
    }

    // ---- 按分片去重：短视频取 10 帧只该落在少数几个分片上 ----

    @Test
    fun `短视频取多帧会反复落在同几个分片上`() {
        // 25 秒的片子（10 + 10 + 5），要 10 帧 → 取样点必然重压在同三个分片上
        val p = parseHlsPlaylist(MEDIA_25S, "https://c/x/video-1080p.m3u8")
        val idx = stripFractions(10).map { segmentIndexOf(p.segments, it) }
        assertTrue(idx.all { it != null && it in 0..2 })
        // 去重的意义就在这里：10 次取样只对应 3 个分片 → 只拉 3 次字节、解 3 次码
        assertEquals(3, idx.filterNotNull().distinct().size)
    }

    @Test
    fun `取样点对应的分片包含该时刻`() {
        val p = parseHlsPlaylist(MEDIA_25S, "https://c/x/video-1080p.m3u8")
        // 总长 25s：30% = 7.5s → 第一片；60% = 15s → 第二片；100% 不越界 → 最后一片
        assertEquals(0, segmentIndexOf(p.segments, 0.3))
        assertEquals(1, segmentIndexOf(p.segments, 0.6))
        assertEquals(2, segmentIndexOf(p.segments, 1.0))
        assertNull(segmentIndexOf(emptyList(), 0.5))
    }

    // ---- 本地缓存：封面一个目录、预览条一个目录，写少要把尾巴删掉 ----

    @Test
    fun `写预览条能查到帧数，写少时旧张被删掉`() {
        val store = VideoPreviewStore(newTempDir())
        val pc = "pick-code-A"
        assertEquals(0, store.stripCount(pc))

        assertEquals(4, store.putStrip(pc, List(4) { bytes(100 + it) }))
        assertEquals(4, store.stripCount(pc))
        assertTrue(store.stripFileFor(pc, 3).isFile)
        assertFalse(store.stripFileFor(pc, 4).exists())

        // 重新抓：帧数变少（换了更短的源），第 4 张不能留成尾巴
        val v = store.version.value
        assertEquals(2, store.putStrip(pc, List(2) { bytes(50) }))
        assertEquals(2, store.stripCount(pc))
        assertFalse(store.stripFileFor(pc, 2).exists())
        assertFalse(store.stripFileFor(pc, 3).exists())
        assertTrue(store.version.value > v)
    }

    @Test
    fun `没抓到的位跳过，封面与预览条分别计数`() {
        val store = VideoPreviewStore(newTempDir())
        val pc = "pick-code-B"
        store.put(pc, bytes(5000))
        assertTrue(store.has(pc))
        assertEquals(0, store.stripCount("别的片"))

        // 中间两位没抓到：写进去的只有第 0、2 张，索引按"最大序号 + 1"算（预览条允许中间缺）
        val written = store.putStrip(pc, listOf(bytes(10), null, bytes(10)))
        assertEquals(2, written)
        assertEquals(3, store.stripCount(pc))
        assertFalse(store.stripFileFor(pc, 1).exists())

        val v = store.version.value
        assertEquals(5000L + 20L, store.sizeBytes())
        store.clear()
        assertEquals(0, store.stripCount(pc))
        assertEquals(0L, store.sizeBytes())
        assertTrue(store.version.value > v)
    }

    // ---- 上限：超了从最旧的开始清，清到上限的 90% 就停 ----

    @Test
    fun `超上限时从最旧的整组删，删到上限的 90% 就停`() {
        // 上限先放到很大，把 6 部片各一张封面（250B）摆好、mtime 明确排好，再调小上限手动剪一次
        var limit = Long.MAX_VALUE
        val store = VideoPreviewStore(newTempDir(), maxBytes = { limit })
        val codes = (0 until 6).map { "pc-$it" }
        codes.forEachIndexed { i, pc ->
            store.put(pc, ByteArray(250) { 7 })
            store.fileFor(pc).setLastModified(1_000_000L + i * 1000L)
        }
        assertEquals(1500L, store.sizeBytes())

        limit = 1000L
        store.prune()
        // 1500B → 删最旧的三张到 750B（≤ 900 = 上限的 90%），不是只删到 1000B
        assertEquals(listOf("pc-3", "pc-4", "pc-5"), codes.filter { store.has(it) })
        assertEquals(750L, store.sizeBytes())
    }

    @Test
    fun `写入路径自己触发淘汰，之后一直不超过上限`() {
        val store = VideoPreviewStore(newTempDir(), maxBytes = { 1000L })
        val codes = (0 until 6).map { "pc-$it" }
        codes.forEachIndexed { i, pc ->
            store.put(pc, ByteArray(250) { 7 })
            store.fileFor(pc).setLastModified(1_000_000L + i * 1000L)
        }
        assertTrue(store.sizeBytes() <= 1000L)
        assertFalse(store.has("pc-0"))   // 最早的先走
        assertTrue(store.has("pc-5"))    // 最新的留着
    }

    @Test
    fun `上限 0 视为不限制，不外删`() {
        val store = VideoPreviewStore(newTempDir(), maxBytes = { 0L })
        store.put("pc-a", ByteArray(5000) { 7 })
        assertTrue(store.has("pc-a"))
    }

    @Test
    fun `同一部片的封面与预览条一起被淘汰，不留半条预览条`() {
        var limit = Long.MAX_VALUE
        val store = VideoPreviewStore(newTempDir(), maxBytes = { limit })
        val old = "pc-old"
        store.put(old, ByteArray(300) { 7 })
        store.putStrip(old, List(4) { ByteArray(100) { 7 } })
        // 整组都算"很久没看"（封面、预览条都摆成旧时间）
        store.fileFor(old).setLastModified(1_000_000L)
        store.stripFiles(old).forEachIndexed { i, f -> f.setLastModified(1_000_001L + i) }
        store.put("pc-new", ByteArray(300) { 7 })          // 新的那部
        assertEquals(1000L, store.sizeBytes())               // 旧组 300+400，新组 300
        assertEquals(4, store.stripCount(old))

        limit = 500L                                          // 700 > 500 → 删最旧那组
        store.prune()
        // ★整组走：封面和 4 张预览条全没了，不是只删掉几张留下"半条有洞的预览条"
        assertFalse(store.has(old))
        assertEquals(0, store.stripCount(old))                // 索引跟着失效重算
        assertTrue(store.has("pc-new"))
        assertTrue(store.sizeBytes() <= 450L)
    }

    @Test
    fun `按 pick_code 删除：只动点名的那部片，别的片一个不碰`() {
        val store = VideoPreviewStore(newTempDir())            // 默认上限，别让淘汰掺进来
        store.put("pc-a", bytes(500))
        store.putStrip("pc-a", List(3) { bytes(100) })
        store.put("pc-b", bytes(400))
        store.putStrip("pc-b", List(2) { bytes(90) })
        assertEquals(3, store.stripCount("pc-a"))
        assertEquals(2, store.stripCount("pc-b"))

        val v = store.version.value
        // 封面 + 3 张条 = 4 个文件
        assertEquals(4, store.deleteFor(listOf("pc-a")))
        assertFalse(store.has("pc-a"))
        assertEquals(0, store.stripCount("pc-a"))             // 索引要重算（不是还报着 3）
        assertTrue(store.version.value > v)                   // 列表要能感知到"这行该重抓了"

        // 没点名的片原封不动
        assertTrue(store.has("pc-b"))
        assertEquals(2, store.stripCount("pc-b"))
        assertEquals(400L + 180L, store.sizeBytes())

        // 空集合/空白串不误删
        assertEquals(0, store.deleteFor(emptyList()))
        assertEquals(0, store.deleteFor(listOf("   ")))
        assertTrue(store.has("pc-b"))
    }

    private fun bytes(n: Int) = ByteArray(n) { 7 }
    private fun newTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "vstrip-test-" + UUID.randomUUID()).apply {
            mkdirs()
            deleteOnExit()
        }

    private companion object {
        val MEDIA_25S = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.000000,
            /abcdef1234567890/video-1080p/video-00001.ts
            #EXTINF:10.000000,
            /abcdef1234567890/video-1080p/video-00002.ts
            #EXTINF:5.000000,
            /abcdef1234567890/video-1080p/video-00003.ts
        """.trimIndent()
    }
}
