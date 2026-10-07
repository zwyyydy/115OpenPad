package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同片多版本的 **A 段** 归组逻辑（时长预筛）。
 *
 * 这里打三条底线：
 *  ① 时长几乎一样才成组，且**锚定不漂移**（100/102/104/106 不该被链成一串）；
 *  ② **剧集各集/分P 必须整桶排掉**（实测一部剧 50 集时长几乎一致，不排会撑爆候选）；
 *  ③ 打分只影响排序，不影响出入 —— 体积接近的一对（同规格不同压制）同样要留在列表里。
 */
class VersionGroupingTest {

    private fun v(
        name: String,
        duration: Long,
        size: Long = 1_000_000_000L,
        sha1: String? = null,
        dirPath: String = "全部文件/A",
    ) = VideoVersion(
        item = FileItem(fid = name, fc = 1, fn = name, fs = size, play_long = duration, sha1 = sha1),
        dirCid = "cid_$name",
        dirPath = dirPath,
    )

    @Test
    fun `时长几乎一样才成组，锚定不漂移`() {
        val groups = buildVersionGroups(
            listOf(
                v("a.mkv", 100), v("b.mkv", 102), v("c.mkv", 104), v("d.mkv", 106),
            ),
        )
        // 100/102 一组、104/106 一组 —— 不是 100→106 链成一串
        assertEquals(2, groups.size)
        assertEquals(listOf(100L, 102L), groups[0].members.map { it.duration }.sorted())
        assertEquals(listOf(104L, 106L), groups[1].members.map { it.duration }.sorted())
    }

    @Test
    fun `时长差太远的不成组`() {
        assertTrue(buildVersionGroups(listOf(v("a.mkv", 100), v("b.mkv", 200))).isEmpty())
    }

    @Test
    fun `剧集各集整桶排掉（实测一部剧 50 集时长几乎一致）`() {
        val groups = buildVersionGroups(
            listOf(
                v("[剧].S01E05.720p.mkv", 2503, 499_000_000L),
                v("[剧].S01E06.720p.mkv", 2504, 498_000_000L),
                v("[剧].S01E07.720p.mkv", 2505, 499_000_000L),
            ),
        )
        assertTrue("清一色同胞不该出现在候选里", groups.isEmpty())
    }

    @Test
    fun `分P（Part与CD）也当同胞排掉，但不同清晰度不算同胞`() {
        // Part 1 / Part 2：同一部片的两部分，不是"版本"
        assertTrue(
            buildVersionGroups(
                listOf(v("Movie.Part.1.mkv", 3000), v("Movie.Part.2.mkv", 3002)),
            ).isEmpty(),
        )
        // 同一部片的 4K 与 1080p：同胞身份不同（画质 token 留着）→ 保留
        assertEquals(
            1,
            buildVersionGroups(
                listOf(v("Movie.2024.2160p.mkv", 3000), v("Movie.2024.1080p.mkv", 3001)),
            ).size,
        )
    }

    @Test
    fun `混了一个不同版本时整桶保留`() {
        val groups = buildVersionGroups(
            listOf(
                v("[剧].S01E05.720p.mkv", 2503, 499_000_000L),
                v("[剧].S01E06.720p.mkv", 2504, 498_000_000L),
                v("[剧].S01E05.2160p.mkv", 2505, 2_100_000_000L),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].members.size)
    }

    @Test
    fun `名字里的番号式 token 相同就当作强证据排在最前`() {
        // 共同 token：ABC101（名字其它部分完全不一样）
        val withToken = buildVersionGroups(
            listOf(v("ABC101_4k_h265.mkv", 3000), v("ABC101 完整版.mp4", 3001)),
        ).single()
        val noToken = buildVersionGroups(
            listOf(v("some.movie.2019.1080p.mkv", 3000), v("完全不同的名字.mkv", 3001)),
        ).single()
        assertEquals("ABC101", withToken.sharedToken)
        assertNull(noToken.sharedToken)
        assertTrue(
            "极差相同时，有共同番号的应排在没共同点的前面",
            listOf(withToken, noToken).sortedWith(
                compareBy<VersionGroup> { it.durationSpan }
                    .thenByDescending { it.sharedToken != null }
                    .thenByDescending { it.commonSubstring }
                    .thenByDescending { it.sizeRatio },
            ).first().sharedToken == "ABC101",
        )
    }

    @Test
    fun `时长极差小的组排在前面`() {
        // 两组都是候选：一组时长分秒不差（极差 0），另一组差了 2 秒 —— 时间差越小越像同一部片
        val groups = buildVersionGroups(
            listOf(
                v("差一点的.1080p.mkv", 2400),
                v("差一点的.2160p.mkv", 2402),
                v("分秒不差.1080p.mkv", 3000),
                v("分秒不差.2160p.mkv", 3000),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(0L, groups[0].durationSpan)
        assertEquals(2L, groups[1].durationSpan)
    }

    @Test
    fun `画质与编码 token 不算名字共同点`() {
        // 两个 1080p / x265 不该因为画质词相同就被算成"名字像"
        val g = buildVersionGroups(
            listOf(
                v("MovieA.2024.1080p.x265.mkv", 3000),
                v("MovieB.2024.1080p.x265.mkv", 3001),
            ),
        ).single()
        // 共同的"2024"是年份（保留），1080P/X265 不算；名字主体 MovieA/MovieB 不同
        assertEquals("2024", g.sharedToken)
    }

    @Test
    fun `同规格不同压制（体积接近）也要留在候选里`() {
        val g = buildVersionGroups(
            listOf(v("ABC101.A.mkv", 3000, 1_000_000_000L), v("ABC101.B.mkv", 3001, 1_050_000_000L)),
        ).single()
        assertTrue("体积比接近 1 不能过滤掉", g.sizeRatio < 1.1)
        assertEquals(2, g.members.size)
        assertTrue("名字里的共同片段（ABC101）应当被看出来", g.commonSubstring >= 3)
    }

    @Test
    fun `组内保留项先按体积降序（B段会用真实分辨率校正）`() {
        val g = buildVersionGroups(
            listOf(
                v("ABC101.1080p.mkv", 3000, 500_000_000L),
                v("ABC101.2160p.mkv", 3001, 4_000_000_000L),
            ),
        ).single()
        assertEquals("ABC101.2160p.mkv", g.members.first().name)
        assertTrue(g.sizeRatio > 7.0)
    }

    @Test
    fun `内容完全相同的那组会被标记出来`() {
        val same = "0".repeat(40)
        val g = buildVersionGroups(
            listOf(
                v("ABC101.x.mkv", 3000, 1_000_000_000L, sha1 = same),
                v("ABC101.y.mkv", 3001, 1_000_000_000L, sha1 = same),
            ),
        ).single()
        assertTrue("同 sha1 应提示这组其实可以直接用「内容相同」清理", g.sameContent)
    }

    @Test
    fun `没有时长的文件不参与`() {
        assertTrue(buildVersionGroups(listOf(v("a.mkv", 0), v("b.mkv", 1))).isEmpty())
    }

    @Test
    fun `清洗片名会抹掉画质编码与集号，保留标题与年份`() {
        assertEquals("movie 2024", cleanName("Movie.2024.2160p.WEB-DL.x265.HDR.mkv"))
        // 发布组名（abc）不属于画质词，留着 —— 它只是相似度里的弱信号，不参与过滤
        assertEquals(
            "示例剧 sample show abc",
            cleanName("[示例剧].Sample.Show.S01E51.HDTV.720p.x264.AC3-ABC.mkv"),
        )
    }

    @Test
    fun `同胞身份：集号与分P抹掉后相同，画质不同则不同`() {
        assertEquals(
            siblingKey("[剧].S01E05.720p.mkv"),
            siblingKey("[剧].S01E06.720p.mkv"),
        )
        assertTrue(
            siblingKey("Movie.2160p.mkv") != siblingKey("Movie.1080p.mkv"),
        )
    }
}
