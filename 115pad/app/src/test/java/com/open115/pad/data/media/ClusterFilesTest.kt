package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录聚类的两条核心规则：**哪些文件属于同一个条目**、**目录级海报挂给谁**。
 *
 * 这两条错了的后果都是"看起来不对但不报错"——海报墙上没有海报、或者凭空多出卡片，
 * 所以单独测。用例里的文件名按真实刮削目录的结构构造（标题与演员名已换成占位名）。
 */
class ClusterFilesTest {

    private fun f(name: String) = FileRef(name = name, pickCode = "pc:$name", sizeBytes = 1L, upt = 1L)

    /** 剧集根目录 `示例目录/示例剧集 (2023)/` 的真实文件清单（Season 1 是目录，不进这个列表） */
    private val seriesRoot = listOf(
        f("fanart.jpg"),
        f("poster.jpg"),
        f("season01-poster.jpg"),
        f("tvshow.nfo"),
    )

    // ---------------- 目录级海报挂给谁 ----------------

    @Test
    fun `剧集根目录_海报与背景挂到 tvshow 簇`() {
        val cs = clusterFiles(seriesRoot)
        assertEquals("只应聚出 tvshow 一个条目", 1, cs.size)
        val c = cs.single()
        assertEquals("tvshow", c.prefix)
        assertEquals("tvshow.nfo", c.nfo?.name)
        // 这就是「没有海报」的根因：旧逻辑要求 byPrefix.size == 1，而这里因
        // fanart.jpg / season01-poster.jpg 各自成组，组数是 3 → 兜底整个失效
        assertEquals("系列海报必须挂上", "poster.jpg", c.poster?.name)
        assertEquals("系列背景必须挂上", "fanart.jpg", c.fanart?.name)
    }

    @Test
    fun `裸 fanart 不成组_也不会凭空造出卡片`() {
        // 旧逻辑里 fanart.jpg 会聚出前缀为 `fanart` 的组（装饰后缀表是带连字符的 -fanart，裸名匹配不上）
        val cs = clusterFiles(listOf(f("fanart.jpg")))
        assertTrue("只有一张目录级背景图，不该产出任何条目", cs.isEmpty())
    }

    @Test
    fun `单影片目录_自带后缀海报优先于 folder_jpg`() {
        // 示例影片 (1979) 那种：自带 -poster/-fanart，另有 folder.jpg 兜底
        val cs = clusterFiles(
            listOf(
                f("示例影片 (1979) {tmdbid-348}.nfo"),
                f("示例影片 (1979) {tmdbid-348}-poster.jpg"),
                f("示例影片 (1979) {tmdbid-348}-fanart.jpg"),
                f("folder.jpg"),
                f("示例影片 (1979) {tmdbid-348}.iso"),
            ),
        )
        val c = cs.single()
        assertEquals("示例影片 (1979) {tmdbid-348}", c.prefix)
        assertEquals("示例影片 (1979) {tmdbid-348}-poster.jpg", c.poster?.name)
        assertEquals("示例影片 (1979) {tmdbid-348}-fanart.jpg", c.fanart?.name)
    }

    @Test
    fun `单影片目录_只有 folder_jpg 时兜底仍生效`() {
        val cs = clusterFiles(
            listOf(f("Some Movie (2001).nfo"), f("folder.jpg"), f("Some Movie (2001).mkv")),
        )
        assertEquals("folder.jpg", cs.single().poster?.name)
    }

    @Test
    fun `多视频目录_目录级海报不挂给任何一集`() {
        // 挂给哪一集都是张冠李戴
        val cs = clusterFiles(listOf(f("A.mkv"), f("B.mkv"), f("folder.jpg")))
        assertEquals(2, cs.size)
        assertTrue("两张卡片都不该拿到 folder.jpg", cs.all { it.poster == null })
    }

    // ---------------- 季描述不生成条目 ----------------

    @Test
    fun `season_nfo 不生成条目`() {
        val cs = clusterFiles(
            listOf(
                f("season.nfo"),
                f("示例剧集 - S01E01 - 第1集.mkv"),
                f("示例剧集 - S01E02 - 第2集.mkv"),
            ),
        )
        assertEquals("只应有两集，season.nfo 不算条目", 2, cs.size)
        assertTrue("不该出现前缀为 season 的条目", cs.none { it.prefix == "season" })
    }

    @Test
    fun `季描述的各种写法都不生成条目`() {
        for (name in listOf("season.nfo", "season01.nfo", "Season01.nfo", "specials.nfo")) {
            val cs = clusterFiles(listOf(f(name)))
            assertTrue("$name 不该生成条目", cs.isEmpty())
        }
    }

    @Test
    fun `tvshow_nfo 仍然生成条目`() {
        // 剧集根目录没有视频文件，系列条目正是靠 tvshow.nfo 建起来的，不能一起被排除
        val cs = clusterFiles(listOf(f("tvshow.nfo")))
        assertEquals(1, cs.size)
        assertEquals("tvshow", cs.single().prefix)
    }

    // ---------------- 单集自己的元数据仍然认 ----------------

    @Test
    fun `分集自带同前缀 nfo 时挂得上`() {
        val cs = clusterFiles(
            listOf(
                f("示例剧集 - S01E01 - 第1集.mkv"),
                f("示例剧集 - S01E01 - 第1集.nfo"),
                f("示例剧集 - S01E01 - 第1集-thumb.jpg"),
            ),
        )
        val c = cs.single()
        assertEquals("示例剧集 - S01E01 - 第1集", c.prefix)
        assertEquals("示例剧集 - S01E01 - 第1集.nfo", c.nfo?.name)
        assertEquals("示例剧集 - S01E01 - 第1集-thumb.jpg", c.thumb?.name)
    }

    @Test
    fun `字幕与季海报不干扰分组`() {
        // .ass 不在放行的扩展名里（只放行 srt）；season01-poster.jpg 无视频无 nfo，会被丢掉
        val cs = clusterFiles(
            listOf(
                f("示例剧集 - S01E01 - 第1集.chi.zh-cn.ass"),
                f("示例剧集 - S01E01 - 第1集.mkv"),
                f("season01-poster.jpg"),
            ),
        )
        assertEquals(1, cs.size)
        assertEquals("示例剧集 - S01E01 - 第1集", cs.single().prefix)
        assertNull("季海报不该挂到单集上", cs.single().poster)
    }

    @Test
    fun `多视频才是分集目录`() {
        assertTrue("一个目录多个视频组 = 分集目录", sniffDirectory(listOf(f("A.mkv"), f("B.mkv"))).isMultiVideo)
        assertTrue("一个视频组 = 影片目录", !sniffDirectory(listOf(f("A.mkv"))).isMultiVideo)
    }

    // ---------------- 分集继承：往上找祖先目录 ----------------

    @Test
    fun `季目录_往上找一层就是系列根`() {
        assertEquals(
            listOf("示例目录/示例剧集 (2023)"),
            ancestorPaths("示例目录/示例剧集 (2023)/Season 1", "示例目录/示例剧集 (2023)"),
        )
    }

    @Test
    fun `已经在库根_没有可继承的祖先`() {
        assertTrue(ancestorPaths("示例目录/示例剧集 (2023)", "示例目录/示例剧集 (2023)").isEmpty())
    }

    @Test
    fun `不越过库根`() {
        // 传了比库根还浅的路径时不能往外爬 —— 越出去就可能借到隔壁库的图
        assertTrue(ancestorPaths("A/B", "A/B/C").isEmpty())
    }

    @Test
    fun `深结构_逐层往上且封顶`() {
        assertEquals(
            listOf("S/Season 1", "S"),
            ancestorPaths("S/Season 1/Disc 1", "S"),
        )
        // 层数封顶：更上面的祖先不返回（正常结构一层就命中，封顶是防爬到不相干的地方）
        assertEquals(
            listOf("S/Season 1/Disc 1", "S/Season 1"),
            ancestorPaths("S/Season 1/Disc 1/Part A", "S", maxHops = 2),
        )
    }

    @Test
    fun `季级图的正则`() {
        for (n in listOf("season01-poster.jpg", "season1-poster.jpg", "season-specials-poster.png", "Season 01 - Poster.jpg")) {
            assertTrue("$n 应被认成季级海报", SEASON_ART_POSTER.matches(n))
        }
        for (n in listOf("poster.jpg", "season01-thumb.jpg", "season01.mkv", "season01-poster.ass")) {
            assertTrue("$n 不该被认成季级海报", !SEASON_ART_POSTER.matches(n))
        }
        assertTrue(SEASON_ART_FANART.matches("season01-fanart.jpg"))
        assertTrue(SEASON_ART_FANART.matches("season-specials-backdrop.png"))
        assertTrue("普通背景图不算季级", !SEASON_ART_FANART.matches("fanart.jpg"))
    }

    // ---------------- 分集排序 ----------------

    @Test
    fun `集号按数值排_不能按标题字符串排`() {
        val eps = listOf(
            "剧 - S01E10 - 第10集",
            "剧 - S01E2 - 第2集",
            "剧 - S01E09 - 第9集",
            "剧 - S01E01 - 第1集",
        )
        val sorted = eps.sortedWith(compareBy({ episodeSortKey(it) }, { it }))
        assertEquals(
            listOf("剧 - S01E01 - 第1集", "剧 - S01E2 - 第2集", "剧 - S01E09 - 第9集", "剧 - S01E10 - 第10集"),
            sorted,
        )
        // 反例：按标题字符串排，S01E2 会被甩到最后（"E2" 的 '2' 比 "E10" 的 '1' 大）。
        // 注意第一个元素恰好也是 E01 —— 只看"排对没排对"的首项是发现不了这个 bug 的。
        assertEquals(
            "字符串排序会把 S01E2 排到最后，这就是不能用它的原因",
            listOf("剧 - S01E01 - 第1集", "剧 - S01E09 - 第9集", "剧 - S01E10 - 第10集", "剧 - S01E2 - 第2集"),
            eps.sorted(),
        )
    }
    @Test
    fun `解析不出集号的沉底`() {
        val eps = listOf("乱七八糟的名字", "剧 - S02E01 - 第1集")
        val sorted = eps.sortedWith(compareBy({ episodeSortKey(it) }, { it }))
        assertEquals("剧 - S02E01 - 第1集", sorted.first())
    }

    // ---------------- 体积过滤（每库可配） ----------------

    private val MB = 1024L * 1024

    private fun fSize(name: String, mb: Long) =
        FileRef(name = name, pickCode = "pc:$name", sizeBytes = mb * MB, upt = 1L)

    @Test
    fun `小于阈值的视频整条不入库`() {
        val cs = clusterFiles(
            listOf(fSize("正片.mkv", 2000), fSize("预告.mkv", 20)),
            minVideoBytes = 100 * MB,
        )
        assertEquals("预告不该成条目", 1, cs.size)
        assertEquals("正片.mkv", cs.single().video?.name)
    }

    @Test
    fun `小视频连它的 nfo 一起丢_不能出有简介没片的卡片`() {
        // 只滤视频、留下 nfo 的话会生成一条 videoPickCode=null 的卡片：
        // 有海报有简介，点播放却说"没有找到可播放的视频文件"
        val cs = clusterFiles(
            listOf(fSize("样本.mkv", 5), fSize("样本.nfo", 0)),
            minVideoBytes = 100 * MB,
        )
        assertTrue("样本整条都该丢掉", cs.isEmpty())
    }

    @Test
    fun `纯 nfo 组不受体积过滤影响`() {
        // tvshow.nfo 没有视频，剧集条目正是靠它建起来的 —— 不能被当成"被滤掉的小视频"一起丢
        val cs = clusterFiles(listOf(fSize("tvshow.nfo", 0)), minVideoBytes = 100 * MB)
        assertEquals(1, cs.size)
        assertEquals("tvshow", cs.single().prefix)
    }

    @Test
    fun `正片加预告仍算影片_不能判成多集目录`() {
        // 这就是"必须在聚类阶段过滤"的理由：留到入库那一步再滤，这里会看到 2 个视频组
        // → isMultiVideo=true → 一部电影被拆成两条分集
        val files = listOf(fSize("电影 (2020).mkv", 3000), fSize("电影 (2020)-trailer.mkv", 30))
        val filtered = sniffDirectory(files, minVideoBytes = 100 * MB)
        assertEquals("预告被滤掉，只剩一个视频组", 1, filtered.clusters.size)
        assertTrue("应判成影片（单视频）", !filtered.isMultiVideo)

        // 对照组：不过滤时确实是 2 个视频组 —— 说明上面那条断言不是白过的
        assertTrue("不过滤时应判成多集", sniffDirectory(files).isMultiVideo)
    }

    @Test
    fun `刚好等于阈值算通过`() {
        // 用户写 100 的意思是"100MB 以下不要"，所以等于 100 的留下
        val cs = clusterFiles(listOf(fSize("刚好.mkv", 100)), minVideoBytes = 100 * MB)
        assertEquals(1, cs.size)
    }

    @Test
    fun `阈值为 0 时不过滤`() {
        val cs = clusterFiles(
            listOf(fSize("正片.mkv", 2000), fSize("预告.mkv", 20)),
            minVideoBytes = 0,
        )
        assertEquals(2, cs.size)
    }
}
