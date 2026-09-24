package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧挂素材：剧照（`extrafanart/`）与演员头像（`.actors/`）。
 *
 * 这两样出错都不报错、只是"看着不对"：剧照顺序乱了、演员配错头像、
 * 或者演员名取到了脏值（`-4K-C 示例演员`）。所以逐条钉住。
 *
 * 用例里的文件名按真实刮削目录的结构构造（`示例演员/ABC-103-C 示例演员/`，名字已换成占位名）。
 *
 * 另外钉住一条**有意为之的取舍**：扫描期不再列这两个目录的内容（每部片 2 次列表请求，
 * 实测占某库列目录阶段的 66%），所以父目录的指纹也不含它们 —— "只往 extrafanart 加图、
 * nfo 没动"要等全量扫描才更新。这条是设计决定，不是漏掉的 bug。
 */
class SideArtTest {

    private fun f(name: String) = FileRef(name = name, pickCode = "pc:$name", sizeBytes = 1L, upt = 1L)

    /** 真实影片目录的文件清单（`.actors` / `extrafanart` 是子目录，不进这个列表） */
    private val movieDir = listOf(
        f("ABC-103-C.chs.srt"),
        f("ABC-103-C.chs.vtt"),
        f("ABC-103-C.nfo"),
        f("ABC-103-C.nfo.bak"),
        f("ABC-103-C.wmv"),
        f("ABC-103-C-fanart.jpg"),
        f("ABC-103-C-poster.jpg"),
        f("ABC-103-C-thumb.jpg"),
    )

    // ---------------- 剧照 ----------------

    @Test
    fun `剧照按自然序排_fanart2 在 fanart10 前面`() {
        val files = listOf(f("fanart10.jpg"), f("fanart2.jpg"), f("fanart1.jpg"), f("fanart12.jpg"))
        assertEquals(
            listOf("fanart1.jpg", "fanart2.jpg", "fanart10.jpg", "fanart12.jpg"),
            extraFanartFilesOf(files).map { it.name },
        )
    }

    @Test
    fun `剧照只认图片_别的文件不算剧照`() {
        val files = listOf(
            f("fanart1.jpg"),
            f("Thumbs.db"),
            f("desktop.ini"),
            f("fanart2.jpg"),
        )
        assertEquals(listOf("fanart1.jpg", "fanart2.jpg"), extraFanartFilesOf(files).map { it.name })
    }

    // ---------------- 演员头像 ----------------

    @Test
    fun `头像按文件名去扩展名当演员名`() {
        val avatars = actorAvatarFilesOf(listOf(f("示例演员.jpg")))
        assertEquals("pc:示例演员.jpg", avatars["示例演员"]?.pickCode)
    }

    @Test
    fun `头像名归一化_大小写与空格都能配上`() {
        // nfo 里写 "John Doe"、目录里是 "john doe.png" 这种也要配上
        assertEquals(normalizeActorName("John Doe"), normalizeActorName("tom  hanks"))
        val avatars = actorAvatarFilesOf(listOf(f("John  Doe.png")))
        assertEquals("pc:John  Doe.png", avatars[normalizeActorName("John Doe")]?.pickCode)
    }

    @Test
    fun `同名多份只留先到的一张`() {
        val avatars = actorAvatarFilesOf(listOf(f("张三.jpg"), f("张三.png")))
        assertEquals(1, avatars.size)
        assertEquals("pc:张三.jpg", avatars["张三"]?.pickCode)
    }

    @Test
    fun `头像目录里的非图片忽略`() {
        assertTrue(actorAvatarFilesOf(listOf(f("张三.txt"), f("desktop.ini"))).isEmpty())
    }

    @Test
    fun `侧挂素材目录名不区分大小写`() {
        assertTrue(isSideArtDirName("extrafanart"))
        assertTrue(isSideArtDirName("ExtrafanArt"))
        assertTrue(isSideArtDirName(".actors"))
        assertTrue(isSideArtDirName("Actors"))
        // 普通目录名不能误判：一季的目录叫 Season 1，影片目录里也可能有别的子目录
        assertFalse(isSideArtDirName("Season 1"))
        assertFalse(isSideArtDirName("extrafanart2"))
    }

    // ---------------- 屏蔽的附加内容目录 ----------------

    @Test
    fun `花絮预告这类目录整棵不扫`() {
        // 实测：`ABC-201 示例演员/behind the scenes/` 里的花絮以前会各自变成一张卡
        assertTrue(isExtraDirName("behind the scenes"))
        assertTrue(isExtraDirName("Behind The Scenes"))
        assertTrue(isExtraDirName("extras"))
        assertTrue(isExtraDirName("Featurettes"))
        assertTrue(isExtraDirName("trailers"))
        assertTrue(isExtraDirName("deleted scenes"))
        // 反方向：真的分集目录不能被屏蔽
        assertFalse(isExtraDirName("Season 1"))
        assertFalse(isExtraDirName("Specials")) // 剧集包里的特别篇季，是真分集
        assertFalse(isExtraDirName("CD1"))
        // 统一入口：侧挂素材也走同一个判断（漏一个就是"扫了不该扫的"）
        assertTrue(isIgnoredDirName("extrafanart"))
        assertTrue(isIgnoredDirName(".actors"))
        assertTrue(isIgnoredDirName("behind the scenes"))
        assertFalse(isIgnoredDirName("Season 1"))
    }

    // ---------------- 分 CD 的资源合成一条 ----------------

    private fun clusterOf(prefix: String) = Cluster(prefix = prefix)

    @Test
    fun `分CD的两条合成一条_基名就是主键`() {
        // 真实结构：ABC-201 示例演员/ 下 cd1、cd2 各一套 视频+nfo+海报+缩略图
        assertEquals(
            "ABC-201",
            cdGroupBaseOf(listOf(clusterOf("ABC-201-cd1"), clusterOf("ABC-201-cd2"))),
        )
        // 实测库里一半是这种：**第一张盘不带后缀**（ABC-202 + ABC-202-cd2）
        assertEquals("ABC-202", cdGroupBaseOf(listOf(clusterOf("ABC-202"), clusterOf("ABC-202-cd2"))))
        assertEquals("ABC-302", cdGroupBaseOf(listOf(clusterOf("ABC-302"), clusterOf("ABC-302-cd1"))))
        // 分隔符与关键词的各种写法
        assertEquals("ABC-302", cdGroupBaseOf(listOf(clusterOf("ABC-302.CD1"), clusterOf("ABC-302_CD2"))))
        assertEquals("X", cdGroupBaseOf(listOf(clusterOf("X - disc 1"), clusterOf("X - disc 2"))))
        assertEquals("X", cdGroupBaseOf(listOf(clusterOf("X-part1"), clusterOf("X-part2"), clusterOf("X-part3"))))
    }

    @Test
    fun `不是分CD的绝不合并`() {
        // 剧集：前缀各不相同，合了会把整季压成一条
        assertNull(cdGroupBaseOf(listOf(clusterOf("剧集 - S01E01"), clusterOf("剧集 - S01E02"))))
        // 同一部片的不同清晰度：基名不同
        assertNull(cdGroupBaseOf(listOf(clusterOf("片子-1080p"), clusterOf("片子-720p"))))
        // 只有一条时谈不上合并（实测 ABC-203-cd2 就只有一个盘，仍是单独一张卡）
        assertNull(cdGroupBaseOf(listOf(clusterOf("ABC-203-cd2"))))
        // 基名不同也不合并（一条带 CD 后缀不够，得是同一个基名）
        assertNull(cdGroupBaseOf(listOf(clusterOf("A"), clusterOf("B-cd1"))))
        // nfo 与视频前缀对不上的那种目录（ABC-101-4K-C / ABC-101-U）不能误判成 CD 组
        assertNull(cdGroupBaseOf(listOf(clusterOf("ABC-101-4K-C"), clusterOf("ABC-101-U"))))
    }

    @Test
    fun `合并卡的元数据取第一张盘`() {
        // 列表顺序不稳定（115 按 upt 倒序返回），代表必须按自然序挑出来
        assertEquals(
            "ABC-201-cd1",
            cdGroupLead(listOf(clusterOf("ABC-201-cd2"), clusterOf("ABC-201-cd1")))?.prefix,
        )
        assertEquals(
            "ABC-202",
            cdGroupLead(listOf(clusterOf("ABC-202-cd2"), clusterOf("ABC-202")))?.prefix,
        )
    }

    @Test
    fun `第一张盘的键不能和合成行撞`() {
        // 实测踩到：`ABC-202` + `ABC-202-cd2` 里第一张盘就叫基名，直接用前缀当键的话
        // 合成行被它覆盖成"seriesKey = 自己"的自引用行，海报墙上直接消失
        assertEquals(
            listOf("ABC-202-cd1", "ABC-202-cd2"),
            cdChildKeys("ABC-202", listOf("ABC-202", "ABC-202-cd2")),
        )
        // 本来就带后缀的不动
        assertEquals(
            listOf("ABC-201-cd1", "ABC-201-cd2"),
            cdChildKeys("ABC-201", listOf("ABC-201-cd1", "ABC-201-cd2")),
        )
        // 真有一个叫 X-cd1 的文件时也要躲开（撞了顺延成 cd2）——
        // 键只是内部标识、界面上显示的标签来自各自的标题，所以顺延不影响观感
        assertEquals(
            listOf("X-cd1", "X-cd2"),
            cdChildKeys("X", listOf("X", "X-cd1")),
        )
    }

    @Test
    fun `合成卡的标题去掉 CD 尾巴`() {
        assertEquals(
            "ABC-201 示例演员假名 8 小时 BEST",
            stripCdMarker("ABC-201 示例演员假名 8 小时 BEST CD1"),
        )
        assertEquals("片子 (2020)", stripCdMarker("片子 (2020) - CD2"))
        // 没有 CD 尾巴的标题原样返回（不能把结尾的普通数字吃掉）
        assertEquals("示例影片 4 (1997)", stripCdMarker("示例影片 4 (1997)"))
        assertEquals("S01E02 第 2 集", stripCdMarker("S01E02 第 2 集"))
    }

    // ---------------- 锚点：目录级素材挂给谁 ----------------

    @Test
    fun `单影片目录_目录级海报挂给这条`() {
        val cs = clusterFiles(movieDir)
        assertEquals("一个视频 + 一个 nfo 应聚成一条", 1, cs.size)
        val c = cs.single()
        assertEquals("ABC-103-C", c.prefix)
        assertEquals("ABC-103-C.wmv", c.video?.name)
        assertEquals("nfo.bak 不能顶掉真 nfo", "ABC-103-C.nfo", c.nfo?.name)
        assertEquals("ABC-103-C-poster.jpg", c.poster?.name)
        assertEquals("ABC-103-C-fanart.jpg", c.fanart?.name)
        assertEquals("ABC-103-C-thumb.jpg", c.thumb?.name)
    }

    @Test
    fun `一季多集_目录级海报不挂给任何一集`() {
        // 9 集各带 nfo + 一张季海报：认不出"这张海报属于谁"，所以一张都不挂
        // （挂给哪一集都是张冠李戴；剧照不同 —— 它是整目录的素材，扫描器给每一条都挂，
        //  见 MediaScanner.indexCluster 的 sideArt 参数）
        val season = (1..9).map { f("剧集 - S01E%02d - 第%d集.mkv".format(it, it)) } +
            (1..9).map { f("剧集 - S01E%02d - 第%d集.nfo".format(it, it)) } +
            listOf(f("poster.jpg"), f("fanart.jpg"))
        val cs = clusterFiles(season)
        assertEquals(9, cs.size)
        assertTrue("季海报不该挂给任何一集", cs.all { it.poster == null && it.fanart == null })
    }

    // ---------------- 演员名从哪来：nfo 优先 ----------------

    @Test
    fun `nfo 有演员就用 nfo 的`() {
        assertEquals(
            listOf("John Doe", "示例演员丁"),
            actorsOf(
                nfoActors = listOf("John Doe", "示例演员丁"),
                actorAvatarNames = listOf("别人的名字"),
                dirPath = "阿甘正传 (1994)",
            ),
        )
    }

    @Test
    fun `nfo 没演员时用 _actors 里的文件名_而不是目录名猜的脏名`() {
        // 实测：`ABC-101-4K-C 示例演员/` 里那个没 nfo 的视频（ABC-101-U），
        // 目录名启发式会猜出 `-4K-C 示例演员`（番号正则只吃到 ABC-101），
        // 而 `.actors/示例演员.jpg` 给的是干净真名 —— 而且正是头像的键。
        // （那个目录现在被 clusterFiles 合成了一条；nfo 里没写演员时仍走这条兜底。）
        assertEquals(
            listOf("示例演员"),
            actorsOf(
                nfoActors = emptyList(),
                actorAvatarNames = listOf("示例演员"),
                dirPath = "示例库三/示例库二/示例演员/演员目录/示例演员/ABC-101-4K-C 示例演员",
            ),
        )
    }

    @Test
    fun `两个来源都没有才从目录名猜`() {
        assertEquals(
            listOf("示例演员二", "示例演员三"),
            actorsOf(
                nfoActors = emptyList(),
                actorAvatarNames = emptyList(),
                dirPath = "/演员目录/ABC-301 示例演员二,示例演员三",
            ),
        )
    }

    // ---------------- 多值 pick_code 列的编解码 ----------------

    @Test
    fun `剧照列表编解码_空列表存 null`() {
        assertNull("没有剧照时 DB 里只有一种表示", encodePickCodes(emptyList()))
        assertNull(encodePickCodes(listOf("", " ")))
        val codes = listOf("abc123", "def456")
        assertEquals(codes, decodePickCodes(encodePickCodes(codes)))
        assertEquals(emptyList<String>(), decodePickCodes(null))
    }

    // ---------------- 背景图取哪一张（详情页与海报墙必须一致） ----------------

    @Test
    fun `背景源_fanart 优先_没有就用第一张剧照`() {
        assertEquals("fanartPc", backgroundSourceOf("fanartPc", "a\nb"))
        // 实测：ABC-101-U 没有 fanart.jpg，兜底海报的源就是它的第一张剧照 ——
        // 海报墙原来只传 fanartPickCode（null），于是墙上永远查不到这张兜底图
        assertEquals("a", backgroundSourceOf(null, "a\nb"))
        // 空串也算没有（老数据里可能是空串而不是 NULL）
        assertEquals("a", backgroundSourceOf("", "a\nb"))
        assertNull(backgroundSourceOf(null, null))
        assertNull(backgroundSourceOf("  ", ""))
    }
}

/**
 * 列表筛选：年份 / 类型 / 标签三个维度，维度之间"与"、同维度内"或"。
 *
 * 规则写错的方向都不报错、只是"筛出来不对"（少了几部 / 多了别的年份），所以钉住。
 */
class WorksFilterTest {

    private fun card(key: String, year: Int?, genre: String? = null) =
        MovieCard(mediaKey = key, title = key, year = year, rating = 4.5, posterPickCode = null, isEpisodeLike = false, genre = genre)

    private val movies = listOf(
        card("a", 2024, "动作 / 科幻"),
        card("b", 2023, "动作 / 喜剧"),
        card("c", 2024, null),
        card("d", null, "科幻"),
    )

    /** 标签与演员都在关联表里：测试里直接给一份"卡片上查不到的那些维度" */
    private val extras = mapOf(
        "a" to CardFacets(tags = setOf("4K", "中文字幕"), actors = setOf("甲", "乙")),
        "b" to CardFacets(tags = setOf("4K"), actors = setOf("乙")),
        "c" to CardFacets(tags = emptySet(), actors = setOf("丙")),
        "d" to CardFacets(tags = setOf("中文字幕"), actors = emptySet()),
    )

    private fun run(f: WorksFilter) =
        movies.filter { f.matches(it, extras[it.mediaKey] ?: CardFacets.Empty) }.map { it.mediaKey }

    @Test
    fun `不筛时全都留下`() {
        assertEquals(listOf("a", "b", "c", "d"), run(WorksFilter()))
        assertTrue(WorksFilter().isEmpty)
    }

    @Test
    fun `年份同维度内是或`() {
        assertEquals(listOf("a", "b", "c"), run(WorksFilter(years = setOf(2024, 2023))))
    }

    @Test
    fun `年份缺失的不会被年份筛选中`() {
        // d 没有年份：勾了任何年份都不该出现（而不是"当成 0 蒙混过关"）
        assertEquals(listOf("a", "c"), run(WorksFilter(years = setOf(2024))))
    }

    @Test
    fun `类型按列里的分隔串拆开比`() {
        assertEquals(listOf("a", "b"), run(WorksFilter(kinds = setOf("动作"))))
        assertEquals(listOf("a", "d"), run(WorksFilter(kinds = setOf("科幻"))))
        // 整串匹配是错的（列里存的是 "动作 / 科幻"）
        assertEquals(emptyList<String>(), run(WorksFilter(kinds = setOf("动作 / 科幻"))))
    }

    @Test
    fun `标签命中其一即可`() {
        assertEquals(listOf("a", "d"), run(WorksFilter(kinds = setOf("中文字幕"))))
        assertEquals(listOf("a", "b", "d"), run(WorksFilter(kinds = setOf("4K", "中文字幕"))))
    }

    @Test
    fun `演员命中其一即可_不同维度之间仍是与`() {
        assertEquals(listOf("a", "b"), run(WorksFilter(actors = setOf("乙"))))
        assertEquals(listOf("a", "c"), run(WorksFilter(actors = setOf("甲", "丙"))))   // b 只有乙
        // 演员 + 年份：都要满足
        assertEquals(listOf("b"), run(WorksFilter(years = setOf(2023), actors = setOf("乙"))))
        // 没有演员的行（d）勾了演员就出局
        assertEquals(listOf("c"), run(WorksFilter(actors = setOf("丙"))))
    }

    @Test
    fun `不同维度之间是与`() {
        assertEquals(
            listOf("a"),
            run(WorksFilter(years = setOf(2024), kinds = setOf("动作"))),
        )
        assertEquals(
            emptyList<String>(),
            run(WorksFilter(years = setOf(2023), kinds = setOf("科幻"))),
        )
    }

    @Test
    fun `选项是从这批作品现算的`() {
        val f = facetsOf(movies) { extras[it.mediaKey] ?: CardFacets.Empty }
        assertEquals("年份新的在前", listOf(2024, 2023), f.years)
        // 类型/标签按出现次数排序（同次数按字典序）
        // 类型与标签合并后按出现次数排（同次数按字典序）
        assertEquals(listOf("4K", "中文字幕", "动作", "科幻", "喜剧"), f.kinds)
        assertEquals(listOf("乙", "丙", "甲"), f.actors.orEmpty())   // 出现次数多的在前
        assertTrue(facetsOf(emptyList<MovieCard>()) { CardFacets.Empty }.isEmpty)
    }

    @Test
    fun `类型与标签重复的词只列一次`() {
        // 实测某库的 nfo 把同一批词既写进 <genre> 也写进 <tag>（两份一样的选项没意义）
        val dup = listOf(card("a", 2024, "动作"), card("b", 2024, "动作"))
        val f = facetsOf(dup) { CardFacets(tags = setOf("动作", "4K")) }
        assertEquals(listOf("4K"), f.kinds.filter { it != "动作" })
        assertEquals(1, f.kinds.count { it == "动作" })
    }

    @Test
    fun `勾了几项要能显示出来`() {
        assertEquals(0, WorksFilter().selectedCount)
        assertEquals(3, WorksFilter(years = setOf(2024), kinds = setOf("动作"), actors = setOf("甲")).selectedCount)
    }
}
