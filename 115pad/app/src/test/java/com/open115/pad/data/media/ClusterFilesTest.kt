package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------------- 元数据簇与视频簇前缀对不上：合成一条 ----------------

    /**
     * 实测清单：`示例库三/示例库二/示例演员/演员目录/示例演员/ABC-101-4K-C 示例演员/`
     * （8 项，另有 `.actors/`、`extrafanart/` 两个目录，它们不进这个列表）。
     * 刮削器给文件夹改成了 `ABC-101-4K-C 示例演员`，视频还是下载时的原名 `ABC-101-U.mp4`。
     */
    private val prefixMismatchDir = listOf(
        f("ABC-101-4K-C-fanart.jpg"),
        f("ABC-101-4K-C-poster.jpg"),
        f("ABC-101-4K-C-thumb.jpg"),
        f("ABC-101-4K-C.nfo"),
        f("ABC-101-4K-C.nfo.bak"),
        f("ABC-101-U.mp4"),
    )

    @Test
    fun `nfo 与视频前缀对不上时合成一条_播放键落到视频上`() {
        // 用户报的就是这个：有海报有简介的那张卡点播放 →「没有找到可播放的视频文件」，
        // 因为元数据和视频被聚成了两个簇、入库成两条独立的行
        val cs = clusterFiles(prefixMismatchDir)
        assertEquals("同一部片只该出一条", 1, cs.size)
        val c = cs.single()
        assertEquals("键与标题用元数据那个前缀", "ABC-101-4K-C", c.prefix)
        assertEquals("ABC-101-4K-C.nfo", c.nfo?.name)
        assertEquals("视频必须挂在同一条上", "ABC-101-U.mp4", c.video?.name)
        assertEquals("ABC-101-4K-C-poster.jpg", c.poster?.name)
        assertEquals("ABC-101-4K-C-fanart.jpg", c.fanart?.name)
        assertEquals("ABC-101-4K-C-thumb.jpg", c.thumb?.name)
    }

    @Test
    fun `合成之后算影片目录_不是多集`() {
        // 判成多集的话入库时 isEpisodeLike=true，详情页会变成"播放第 1 集"而不是直接播
        assertTrue("合成后只剩一个视频组", !sniffDirectory(prefixMismatchDir).isMultiVideo)
    }

    @Test
    fun `合成之后目录级海报挂给合成的那条`() {
        // 合并必须在挑锚点之前做：早先"唯一带视频的簇"是那个没元数据的半边（ABC-101-U），
        // 目录级的 poster.jpg/fanart.jpg 会挂到它身上 —— 而它根本不出现在海报墙上。
        // （这一条用没有自带图的目录测：自带的 `…-poster.jpg` 本来就优先于目录级的，见上面那条用例）
        val cs = clusterFiles(
            listOf(f("ABC-101-4K-C.nfo"), f("ABC-101-U.mp4"), f("poster.jpg"), f("fanart.jpg")),
        )
        assertEquals(1, cs.size)
        val c = cs.single()
        assertEquals("ABC-101-4K-C", c.prefix)
        assertEquals("poster.jpg", c.poster?.name)
        assertEquals("fanart.jpg", c.fanart?.name)
    }

    @Test
    fun `合成的那条自带图时_目录级图不抢`() {
        val cs = clusterFiles(prefixMismatchDir + listOf(f("poster.jpg"), f("fanart.jpg")))
        val c = cs.single()
        assertEquals("ABC-101-4K-C-poster.jpg", c.poster?.name)
        assertEquals("ABC-101-4K-C-fanart.jpg", c.fanart?.name)
    }

    @Test
    fun `视频那边有图_元数据那边没有时借过来`() {
        val cs = clusterFiles(listOf(f("X.nfo"), f("X-U.mp4"), f("X-U-poster.jpg")))
        assertEquals(1, cs.size)
        assertEquals("X-U-poster.jpg", cs.single().poster?.name)
    }

    @Test
    fun `视频自带 nfo 时不合并`() {
        // A 自给自足（视频 + nfo），B 是另一个不相干的 nfo —— 合并会把两条毫不相干的并成一条
        val cs = clusterFiles(listOf(f("A.mkv"), f("A.nfo"), f("B.nfo")))
        assertEquals(2, cs.size)
        assertEquals("A.nfo", cs.first { it.prefix == "A" }.nfo?.name)
    }

    @Test
    fun `两个以上 nfo 簇说不清信谁_不合并`() {
        val cs = clusterFiles(listOf(f("A.mp4"), f("B.nfo"), f("C.nfo")))
        assertEquals(3, cs.size)
        assertNull("B 不该凭空拿到 A 的视频", cs.first { it.prefix == "B" }.video)
    }

    @Test
    fun `多视频目录不合并`() {
        // 一季的集：每条都自带视频，合成一条会把整季压成一部片
        val cs = clusterFiles(
            listOf(f("剧 - S01E01 - 第1集.mkv"), f("剧 - S01E02 - 第2集.mkv"), f("tvshow.nfo")),
        )
        assertEquals(3, cs.size)
        assertEquals("剧 - S01E01 - 第1集.mkv", cs.first { it.prefix.contains("E01") }.video?.name)
    }

    @Test
    fun `分CD的两张盘不归这条规则管`() {
        // 各盘都有自己的视频（还有自己的 nfo），合不合并是 cdGroupBaseOf 那套的事，别在这里先并了
        val cs = clusterFiles(
            listOf(
                f("ABC-201-cd1.mp4"), f("ABC-201-cd1.nfo"),
                f("ABC-201-cd2.mp4"), f("ABC-201-cd2.nfo"),
            ),
        )
        assertEquals(2, cs.size)
        assertEquals("ABC-201", cdGroupBaseOf(cs))
    }

    @Test
    fun `只有元数据没有视频时保持原样`() {
        // 实测同一个库的 `ABC-102-C 示例演员/`：目录里一个视频文件都没有（视频在库外的别的目录）
        // —— 没有视频可借，这条卡就是播不了，别去跟别的目录的东西凑
        val cs = clusterFiles(listOf(f("ABC-102-C.nfo"), f("ABC-102-C-poster.jpg")))
        assertEquals(1, cs.size)
        assertNull(cs.single().video)
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

    // ---------------- 「彻底删除」收尾时哪些文件算素材图 ----------------

    @Test
    fun `目录里没有归属的素材图都认得出来`() {
        // 名字来自真实目录 示例目录/示例影片（系列）/示例影片4 (1997)/：前两个正是扫描器不挂给任何条目的
        // （folder.jpg 被同簇裸图顶掉、-logo.png 没有归属），也就是用户实测里
        // "彻底删除后目录里还剩两张图"的那两张
        val art = listOf(
            "folder.jpg", "poster.jpg", "fanart.jpg", "backdrop.jpg", "thumb.jpg",
            "logo.png", "clearlogo.png", "banner.jpg", "disc.png", "landscape.jpg",
            "season01-poster.jpg", "season-specials-fanart.jpg",
            "示例影片4 (1997) {tmdbid-8078}-logo.png",
            "示例影片4 (1997) {tmdbid-8078}-fanart.jpg",
            // 真实库里最常见的海报形态：跟视频同名、没有任何角色后缀。
            // 按名字认会漏掉它 —— 这就是"只按扩展名认"的理由
            "示例影片4 (1997) {tmdbid-8078}.jpg",
            "随便什么名字.webp",
        )
        art.forEach { assertTrue("$it 应判成素材图", isArtImageFile(it)) }
    }

    @Test
    fun `视频_nfo_字幕绝不算素材图`() {
        // 最要紧的一条：名字像图的**视频**不能被当成素材图删掉 ——
        // 收尾那一步是照着文件名认的，认错就是把用户的片子删了
        val notArt = listOf(
            "示例影片4 (1997) {tmdbid-8078}.iso",
            "示例影片4 (1997) {tmdbid-8078}.nfo",
            "movie-poster.mkv",
            "movie-logo.mp4",
            "poster.ts",
            "字幕-fanart.srt",
            "readme.txt",
            "没有扩展名",
            "poster",
            ".jpg",
        )
        notArt.forEach { assertFalse("$it 不该判成素材图", isArtImageFile(it)) }
    }

    // ---------------- 目录指纹（增量扫描"这个目录变没变"的判据） ----------------

    private fun fr(name: String, size: Long = 1000, upt: Long = 100, pc: String = "pc:$name") =
        FileRef(name = name, pickCode = pc, sizeBytes = size, upt = upt)

    @Test
    fun `指纹与列表顺序无关`() {
        // 115 按 upt 倒序返回，同一个目录两次请求的顺序可能不一样 ——
        // 顺序进了指纹就会天天判成"变了"，增量扫描直接退化成全量
        val a = listOf(fr("a.mkv"), fr("b.mkv"), fr("c.mkv"))
        val b = listOf(fr("c.mkv"), fr("a.mkv"), fr("b.mkv"))
        assertEquals(dirFingerprintOf(a), dirFingerprintOf(b))
    }

    @Test
    fun `删除一个不是最新的文件也能发现_这是 upt 判据的死角`() {
        // 场景：目录里 b 最新，删掉 a —— max(upt) 不变，upt 判据看不见
        val before = listOf(
            fr("a.mkv", upt = 100),
            fr("b.mkv", upt = 900),
            fr("c.mkv", upt = 500),
        )
        val after = listOf(fr("b.mkv", upt = 900), fr("c.mkv", upt = 500))
        assertEquals("max(upt) 确实没变（所以老判据发现不了）", 900L, after.maxOf { it.upt })
        assertTrue("指纹必须变", dirFingerprintOf(before) != dirFingerprintOf(after))
    }

    @Test
    fun `改名_换文件_加文件都能发现`() {
        val base = listOf(fr("a.mkv", size = 100, upt = 100, pc = "p1"), fr("b.mkv"))
        assertTrue("改名", dirFingerprintOf(base) != dirFingerprintOf(listOf(fr("a2.mkv", size = 100, upt = 100, pc = "p1"), fr("b.mkv"))))
        assertTrue("换文件（大小变了）", dirFingerprintOf(base) != dirFingerprintOf(listOf(fr("a.mkv", size = 200, upt = 100, pc = "p1"), fr("b.mkv"))))
        assertTrue("重新上传（upt 变了）", dirFingerprintOf(base) != dirFingerprintOf(listOf(fr("a.mkv", size = 100, upt = 300, pc = "p1"), fr("b.mkv"))))
        // pickCode 是"我们存下来的字段"：它变了索引也得跟着更新，哪怕名字大小时间都没动
        assertTrue("pickCode 变了", dirFingerprintOf(base) != dirFingerprintOf(listOf(fr("a.mkv", size = 100, upt = 100, pc = "p9"), fr("b.mkv"))))
        assertTrue("多一个文件", dirFingerprintOf(base) != dirFingerprintOf(base + fr("c.mkv")))
    }

    @Test
    fun `空目录的指纹是空串_不参与比对`() {
        assertEquals("", dirFingerprintOf(emptyList()))
    }

    @Test
    fun `名字里带分隔符也不会撞车`() {
        // 用 NUL 当分隔符就是为这个：文件名里可以有任意字符（除了 NUL）
        val a = listOf(fr("a\u0001b.mkv"))
        val b = listOf(fr("a.mkv"), fr("b.mkv"))
        assertTrue("拼接歧义会撞成同一个指纹", dirFingerprintOf(a) != dirFingerprintOf(b))
    }

    // ---------------- 入库主键（mediaKey 是主键，撞了就互相覆盖） ----------------

    @Test
    fun `整季 nfo 里是同一个 tmdb id 时_每集仍是独立主键`() {
        // 「示例剧集三 (2021)」实测：36 集的 nfo 全写着同一个 uniqueid，拿它当主键
        // 36 集就互相覆盖成一行 —— 海报墙只剩一张卡、点进去只有一集
        val keys = (1..36).map {
            mediaKeyOf("示例剧集三 - S01E%02d - 第%d集".format(it, it), nfoTmdbId = "1712692", isEpisodeLike = true)
        }
        assertEquals("36 集应该是 36 个不同的主键", 36, keys.distinct().size)
        assertEquals("示例剧集三 - S01E07 - 第7集", keys[6])
    }

    @Test
    fun `文件名里带 tmdb id 的分集也不能拿它当主键`() {
        // 有些包把 {tmdbid-…} 写进每个文件名 —— 同样会撞
        val a = mediaKeyOf("示例剧集三 (2021) {tmdbid-118759} - S01E01", null, isEpisodeLike = true)
        val b = mediaKeyOf("示例剧集三 (2021) {tmdbid-118759} - S01E02", null, isEpisodeLike = true)
        assertTrue("两集主键不能相同", a != b)
    }

    @Test
    fun `影片优先用 nfo 的 tmdb id_换目录换文件名也是同一条`() {
        assertEquals("tmdb-8078", mediaKeyOf("示例影片4 (1997)", "8078", isEpisodeLike = false))
        assertEquals("tmdb-8078", mediaKeyOf("Sample Movie Resurrection 1997 1080p", "8078", isEpisodeLike = false))
    }

    @Test
    fun `影片没有 nfo id 时退回前缀_番号式就是这样`() {
        assertEquals("ABC-301", mediaKeyOf("ABC-301", null, isEpisodeLike = false))
        assertEquals("tmdb-8078", mediaKeyOf("示例影片4 (1997) {tmdbid-8078}", null, isEpisodeLike = false))
    }
}
