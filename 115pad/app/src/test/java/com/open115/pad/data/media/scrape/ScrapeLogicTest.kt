package com.open115.pad.data.media.scrape

import com.open115.pad.data.media.NfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 刮削这一层的纯逻辑单测。
 *
 * 最要紧的是 [NfoWriterTest] 的 round-trip：写回云盘的 nfo 必须能被
 * [NfoParser] 原样读回来 —— "写出去 → 重扫入库"是这条链路唯一的数据通路，
 * 写错一个标签名（或忘了转义）就是整批片静默丢字段。
 */
class NfoWriterTest {

    private val meta = ScrapedMetadata(
        sourceId = "tmdb",
        sourceKey = "tmdb-603",
        title = "示例影片",
        originalTitle = "Sample Movie Original",
        tagline = "一个示例标语",
        plot = "简介里带特殊字符 & < > \" ' 也不能弄坏 XML",
        year = 1999,
        premiered = "1999-03-31",
        runtimeMinutes = 136,
        rating = 8.2,
        votes = 12345,
        genres = listOf("动作", "科幻"),
        countries = listOf("美国"),
        languages = listOf("English"),
        studios = listOf("示例影业"),
        directors = listOf("示例导演"),
        writers = listOf("示例编剧"),
        set = "示例合集",
        trailer = "https://www.youtube.com/watch?v=abc123",
        actors = listOf(
            ScrapedActor("示例演员甲", "示例角色"),
            ScrapedActor("示例演员乙", null),
        ),
        tmdbId = "603",
        imdbId = "tt0133093",
        posterUrl = "https://example.invalid/t/p/w500/x.jpg",
    )

    private fun roundTrip(m: ScrapedMetadata) = NfoParser.parse(StringReader(NfoWriter.build(m)))

    @Test
    fun `写出的 nfo 能被解析器原样读回`() {
        val back = roundTrip(meta)
        assertEquals("示例影片", back.title)
        assertEquals("Sample Movie Original", back.originalTitle)
        assertEquals("一个示例标语", back.tagline)
        assertEquals("简介里带特殊字符 & < > \" ' 也不能弄坏 XML", back.plot)
        assertEquals(1999, back.year)
        assertEquals("1999-03-31", back.premiered)
        assertEquals(136, back.runtimeMinutes)
        assertEquals(8.2, back.rating!!, 0.0001)
        assertEquals(12345, back.votes)
        assertEquals(listOf("动作", "科幻"), back.genres)
        assertEquals(listOf("美国"), back.countries)
        assertEquals(listOf("English"), back.languages)
        assertEquals(listOf("示例影业"), back.studios)
        assertEquals(listOf("示例导演"), back.directors)
        assertEquals(listOf("示例编剧"), back.writers)
        assertEquals("示例合集", back.set)
        assertEquals("https://www.youtube.com/watch?v=abc123", back.trailer)
        assertEquals("603", back.uniqueTmdbid)
        assertEquals("tt0133093", back.imdbId)
        assertEquals(listOf("示例演员甲", "示例演员乙"), back.actors)
        // 角色名与演员名按下标一一对应；没角色的那条是空串
        assertEquals("示例角色", back.actorRoles.getOrNull(0))
        assertEquals("", back.actorRoles.getOrNull(1))
        // mediaKey 就靠它从文件前缀变成 tmdb-<id>（见 DirectorySniffer.mediaKeyOf）
        assertTrue(back.hasContent)
    }

    @Test
    fun `剧集写 tvshow 根且能被解析器读回`() {
        val xml = NfoWriter.build(
            ScrapedMetadata(
                sourceId = "tmdb",
                sourceKey = "tmdb-1399",
                kind = ScrapeKind.TV,
                title = "示例剧集",
                originalTitle = "Sample Show",
                plot = "剧的简介",
                year = 2011,
                premiered = "2011-04-17",
                rating = 9.2,
                genres = listOf("剧情", "科幻"),
                studios = listOf("HBO"),
                directors = listOf("示例主创"),
                actors = listOf(ScrapedActor("示例演员甲", "角色A")),
                tmdbId = "1399",
            ),
        )
        assertTrue("根标签应是 tvshow", xml.contains("<tvshow>"))
        assertTrue("收尾应是 tvshow", xml.contains("</tvshow>"))
        val back = NfoParser.parse(StringReader(xml))
        assertEquals("示例剧集", back.title)
        assertEquals("剧的简介", back.plot)
        assertEquals(2011, back.year)
        assertEquals(9.2, back.rating!!, 0.0001)
        assertEquals(listOf("剧情", "科幻"), back.genres)
        assertEquals("1399", back.uniqueTmdbid)
        assertTrue(back.hasContent)
    }

    @Test
    fun `分集写 episodedetails 且能被解析器读回`() {
        val xml = NfoWriter.buildEpisode(
            showTitle = "示例剧集",
            ep = ScrapedEpisode(
                season = 1, episode = 5, title = "第五集 标题",
                plot = "这一集的简介", aired = "2011-05-22", tmdbId = "63000",
            ),
        )
        assertTrue(xml.contains("<episodedetails>"))
        val back = NfoParser.parse(StringReader(xml))
        assertEquals("第五集 标题", back.title)
        assertEquals("示例剧集", back.showTitle)
        assertEquals(1, back.season)
        assertEquals(5, back.episode)
        assertEquals("这一集的简介", back.plot)
        assertEquals("2011-05-22", back.premiered)  // firstaired 被解析器认作 premiered
        assertEquals(2011, back.year)
        assertEquals("63000", back.uniqueTmdbid)
        assertTrue(back.hasContent)
    }

    @Test
    fun `没有 tmdbid 时不写 uniqueid 且不写空标签`() {
        val xml = NfoWriter.build(
            ScrapedMetadata(sourceId = "tmdb", sourceKey = "x", title = "只有标题"),
        )
        val back = NfoParser.parse(StringReader(xml))
        assertEquals("只有标题", back.title)
        assertNull(back.uniqueTmdbid)
        assertNull(back.rating)
        assertNull(back.plot)
        assertNull(back.year)
        // 原名与片名相同/为空时不重复写
        assertTrue(!xml.contains("originaltitle"))
        assertTrue(!xml.contains("uniqueid"))
    }

    @Test
    fun `评分固定用点号小数（不受系统 Locale 影响）`() {
        val xml = NfoWriter.build(
            ScrapedMetadata(sourceId = "tmdb", sourceKey = "x", title = "t", rating = 7.0),
        )
        assertTrue(xml.contains("<rating>7.0</rating>"))
        assertEquals(7.0, NfoParser.parse(StringReader(xml)).rating!!, 0.0001)
    }
}

/**
 * 检索词清洗与自动预选：这两条直接决定"搜出来的候选对不对"，
 * 也决定批量模式会不会替用户勾错片子。
 */
class ScrapeMatchTest {

    @Test
    fun `点与下划线换空格、发布标记被去掉`() {
        // 4 位数字留着（可能是片名的一部分，如 1917），发布组尾巴（-GROUP）丢掉
        assertEquals("Sample Movie 2011", ScrapeMatch.cleanQueryTitle("Sample.Movie.2011.1080p.BluRay.x264-GROUP"))
        assertEquals("示例影片", ScrapeMatch.cleanQueryTitle("[示例组] 示例影片 4K HDR 中字"))
        assertEquals("Sample Movie", ScrapeMatch.cleanQueryTitle("Sample_Movie_WEB-DL"))
    }

    @Test
    fun `连写的编解码与音轨标记整段去掉`() {
        // `H.264` 若不先整段去掉会被点号拆成 `H`+`264`；`AC3 2.0` 拆开会剩 `2`+`0`
        assertEquals(
            "Sample Movie",
            ScrapeMatch.cleanQueryTitle("Sample.Movie.1080p.SDR.H.264.24fps.AC3 2.0"),
        )
        assertEquals(
            "Sample Movie",
            ScrapeMatch.cleanQueryTitle("Sample.Movie.1080p.DTS-HD.MA.5.1.x265.10bit"),
        )
    }

    @Test
    fun `4 位数字的片名不能被当成发布标记`() {
        // 这两个是"标题本身含 4 位数字"，清洗不能把它们当分辨率/年份丢掉
        assertTrue(ScrapeMatch.cleanQueryTitle("1917.2019.1080p").contains("1917"))
        assertTrue(ScrapeMatch.cleanQueryTitle("Blade Runner 2049 2160p").contains("2049"))
    }

    @Test
    fun `清洗后为空时退回原文`() {
        assertEquals("1080p", ScrapeMatch.cleanQueryTitle("1080p"))
    }

    @Test
    fun `同名且唯一才自动预选`() {
        val unique = listOf(
            ScrapeCandidate("tmdb", "1", "别的片", year = 2001),
            ScrapeCandidate("tmdb", "2", "示例影片", originalTitle = "Sample Movie", year = 2011),
        )
        assertEquals(1, ScrapeMatch.autoPick(unique, "示例影片", 2011))

        // 同名有两个 → 并列，不预选（宁可不勾，也别勾错）
        val tie = listOf(
            ScrapeCandidate("tmdb", "1", "示例影片", year = 2011),
            ScrapeCandidate("tmdb", "2", "示例影片", year = 2012),
        )
        assertNull(ScrapeMatch.autoPick(tie, "示例影片", null))

        // 只是包含关系 → 只给 2 分，不自动勾
        val contains = listOf(ScrapeCandidate("tmdb", "1", "示例影片前传", year = 2011))
        assertNull(ScrapeMatch.autoPick(contains, "示例影片", 2011))

        // 标点/大小写差异不影响"同名"
        val punct = listOf(ScrapeCandidate("tmdb", "1", "sample movie: part 1".replace("part 1", ""), year = 2011))
        assertEquals(0, ScrapeMatch.autoPick(punct, "Sample Movie", 2011))
    }

    @Test
    fun `空标题或空候选不预选`() {
        assertNull(ScrapeMatch.autoPick(emptyList(), "示例影片", 2011))
        assertNull(ScrapeMatch.autoPick(listOf(ScrapeCandidate("tmdb", "1", "示例影片")), "  ", 2011))
    }

    @Test
    fun `本地标题带发布标记时也能自动预选`() {
        // 文件页的"本地标题"是带尾巴的原始前缀 —— 比对前必须先洗掉，否则自动预选永远不生效
        val cands = listOf(
            ScrapeCandidate("tmdb", "1", "示例影片", originalTitle = "Sample Movie", year = 2026),
        )
        assertEquals(
            0,
            ScrapeMatch.autoPick(cands, "示例影片 (2026) - 1080p.SDR.H.264.24fps.AC3 2.0", 2026),
        )
    }
}

/**
 * 「从目录名/文件名抽年份」是**文件页刮削**的关键一步：目录名常写成 `片名 (2011)`，
 * 而年份若被当成片名的一部分喂给 TMDB 搜索，会把结果带偏。
 */class ScrapeYearSplitTest {

    @Test
    fun `半角与全角括号的年份都能抽出并去掉`() {
        assertEquals("示例影片" to 2011, ScrapeMatch.splitYearFromTitle("示例影片 (2011)"))
        assertEquals("示例影片" to 2011, ScrapeMatch.splitYearFromTitle("示例影片（2011）"))
        assertEquals("Sample Movie" to 1999, ScrapeMatch.splitYearFromTitle("Sample Movie (1999)"))
    }

    @Test
    fun `片名里的 4 位数字不被当年份`() {
        // 没有括号 → 不当成年份（否则 "Blade Runner 2049" 会被套上 2049 年的过滤条件）
        assertEquals("Blade Runner 2049" to null, ScrapeMatch.splitYearFromTitle("Blade Runner 2049"))
        assertEquals("1917" to null, ScrapeMatch.splitYearFromTitle("1917"))
    }

    @Test
    fun `括号里不是年份时保持原样`() {
        assertEquals("Sample Movie (Director's Cut)" to null, ScrapeMatch.splitYearFromTitle("Sample Movie (Director's Cut)"))
        assertEquals("示例影片 (第1集)" to null, ScrapeMatch.splitYearFromTitle("示例影片 (第1集)"))
    }

    @Test
    fun `去掉年份后标题为空时退回原文`() {
        val (t, y) = ScrapeMatch.splitYearFromTitle("(2011)")
        assertEquals("(2011)", t)
        assertEquals(2011, y)
    }
}

/**
 * 代理地址解析：设置项是自由文本，**填错只能退回直连、绝不能抛**（否则一个手滑把整个刮削弄挂）。
 */
class ScrapeProxyParseTest {

    private fun proxy(raw: String) = parseScrapeProxy(raw)

    @Test
    fun `裸 host-port 与 http 前缀都是 HTTP 代理`() {
        assertEquals(java.net.Proxy.Type.HTTP, proxy("192.0.2.5:20171")?.type())
        assertEquals(20171, (proxy("192.0.2.5:20171")?.address() as java.net.InetSocketAddress).port)
        assertEquals(20171, (proxy("http://192.0.2.5:20171")?.address() as java.net.InetSocketAddress).port)
        assertEquals("192.0.2.5", (proxy("http://192.0.2.5:20171/")?.address() as java.net.InetSocketAddress).hostString)
    }

    @Test
    fun `socks5 前缀走 SOCKS`() {
        val p = proxy("socks5://10.0.0.9:1080")
        assertEquals(java.net.Proxy.Type.SOCKS, p?.type())
        assertEquals(1080, (p?.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `空值与垃圾输入退回直连`() {
        assertNull(proxy(""))
        assertNull(proxy("   "))
        assertNull(proxy("justhost"))
        assertNull(proxy("host:notaport"))
        assertNull(proxy("host:99999"))
    }
}

/**
 * 剧照挑选（`extrafanart/` 的来源）：**按赞数排、同数按平均分**，去空去重，上限兜底。
 */
class PickStillsTest {

    @Test
    fun `剧照按赞数排同数再按平均分`() {
        val refs = listOf(
            BackdropRef("/a.jpg", vote = 5.0, votes = 10),
            BackdropRef("/b.jpg", vote = 9.0, votes = 3),
            BackdropRef("/c.jpg", vote = 8.0, votes = 3),
            BackdropRef("/d.jpg", vote = 1.0, votes = 50),
        )
        assertEquals(listOf("/d.jpg", "/a.jpg", "/b.jpg", "/c.jpg"), pickStills(refs, 8))
        assertEquals(listOf("/d.jpg", "/a.jpg"), pickStills(refs, 2))
    }

    @Test
    fun `剧照跳过空路径并去重`() {
        val refs = listOf(
            BackdropRef("", vote = 10.0, votes = 99),
            BackdropRef("/x.jpg", vote = 4.0, votes = 2),
            BackdropRef("/x.jpg", vote = 4.0, votes = 2),
            BackdropRef("/y.jpg", vote = 3.0, votes = 1),
        )
        assertEquals(listOf("/x.jpg", "/y.jpg"), pickStills(refs, 8))
    }

    @Test
    fun `剧照张数上限兜底`() {
        val refs = listOf(BackdropRef("/p.jpg", 5.0, 1), BackdropRef("/q.jpg", 6.0, 2))
        assertEquals(0, pickStills(refs, 0).size)
        assertEquals(0, pickStills(refs, -3).size)
        assertEquals(2, pickStills(refs, 100).size)
    }
}

/** matchedFileName：用匹配片名生成新文件名，保留原扩展名 */
class MatchedFileNameTest {

    @Test
    fun `片名做主体并保留原扩展名`() {
        assertEquals("蜘蛛侠.mkv", matchedFileName("蜘蛛侠", "spider.2002.1080p.x264.mkv"))
        assertEquals("Big Buck Bunny.mp4", matchedFileName("Big Buck Bunny", "bbb.1080p.mp4"))
        // 原文件没扩展名 → 不加
        assertEquals("README", matchedFileName("README", "readme"))
        // 片名里的斜杠换成全角（115 文件名不允许 /）
        assertEquals("A／B.mkv", matchedFileName("A/B", "x.mkv"))
        // 空片名 → null（不该改）
        assertNull(matchedFileName("   ", "x.mkv"))
    }
}
