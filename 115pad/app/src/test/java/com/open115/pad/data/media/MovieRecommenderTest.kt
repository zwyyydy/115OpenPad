package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐的打分 / 抽样 / 理由。
 *
 * 这几条都是"错了很难发现"的地方：权重串了不会崩，只会让推荐悄悄变成一堆无关的片；
 * 随机不稳定则会让列表在滚动时跳。所以把纯逻辑抽出来钉住。
 */
class MovieRecommenderTest {

    private val now = 1_700_000_000_000L

    /** ★ 标签命中不再是"个数"，而是**覆盖率因子之和**（见 [MovieRecommender.TagHits]） */
    private fun hits(
        selfPlain: Double = 0.0,
        selfSeries: Double = 0.0,
        tastePlain: Double = 0.0,
        tasteSeries: Double = 0.0,
    ) = MovieRecommender.TagHits(selfPlain, selfSeries, tastePlain, tasteSeries)

    private fun cand(
        key: String,
        selfActor: Int = 0,
        tasteActor: Int = 0,
        rating: Double? = null,
        addedAt: Long? = null,
        dirPath: String? = null,
    ) = MovieRecommendation(
        mediaKey = key,
        title = key,
        year = null,
        rating = rating,
        posterPickCode = null,
        fanartPickCode = null,
        extraFanartPickCodes = null,
        isEpisodeLike = false,
        videoPickCode = null,
        genre = null,
        videoName = null,
        dirPath = dirPath,
        addedAt = addedAt,
        selfActorHits = selfActor,
        tasteActorHits = tasteActor,
    )

    private fun score(
        c: MovieRecommendation,
        selfDir: String? = null,
        seen: Set<String> = emptySet(),
        finished: Set<String> = emptySet(),
        hits: MovieRecommender.TagHits = MovieRecommender.TagHits.None,
        jitter: Double = 0.0,
    ) = MovieRecommender.score(c, selfDir, seen, finished, hits, jitter, now)

    // ---------------- 打分 ----------------

    @Test
    fun `同演员命中按个数加权`() {
        assertEquals(3.0, score(cand("a", selfActor = 1)), 1e-9)
        assertEquals(9.0, score(cand("b", selfActor = 3)), 1e-9)
    }

    @Test
    fun `同演员命中封顶三个 —— 不让合作过五个演员的片霸榜`() {
        // 和 selfActor=3 同分：这是刻意的，5 个演员并不比 3 个"更相关"
        assertEquals(score(cand("c", selfActor = 3)), score(cand("d", selfActor = 5)), 1e-9)
    }

    @Test
    fun `同标签权重是演员的三分之一，且折算个数封顶四个`() {
        assertEquals(1.0, score(cand("a"), hits = hits(selfPlain = 1.0)), 1e-9)
        assertEquals(4.0, score(cand("b"), hits = hits(selfPlain = 4.0)), 1e-9)
        // 折算个数封顶：因子之和超过 4 的部分不再加分
        assertEquals(
            score(cand("c"), hits = hits(selfPlain = 4.0)),
            score(cand("d"), hits = hits(selfPlain = 9.0)),
            1e-9,
        )
    }

    @Test
    fun `口味命中的权重比和当前这部片相关弱`() {
        assertEquals(1.5, score(cand("a", tasteActor = 1)), 1e-9)
        assertEquals(0.4, score(cand("b"), hits = hits(tastePlain = 1.0)), 1e-9)
        assertTrue(score(cand("c", selfActor = 1)) > score(cand("d", tasteActor = 1)))
    }

    // ---------------- 覆盖率降权 ----------------

    @Test
    fun `覆盖率因子：盖住四分之一时半权、一半及以上归零`() {
        assertEquals(1.0, MovieRecommender.tagFactor(0, 1000), 1e-9)
        assertEquals(0.8, MovieRecommender.tagFactor(100, 1000), 1e-9)
        assertEquals(0.5, MovieRecommender.tagFactor(250, 1000), 1e-9)
        assertEquals(0.0, MovieRecommender.tagFactor(500, 1000), 1e-9)
        assertEquals(0.0, MovieRecommender.tagFactor(870, 1000), 1e-9)   // 覆盖 87% 那个量级
    }

    @Test
    fun `数不出全库卡片数时不惩罚`() {
        // 空库/刚建库：宁可不降权，也别把信号全抹平
        assertEquals(1.0, MovieRecommender.tagFactor(0, 0), 1e-9)
    }

    @Test
    fun `宽标签堆再多也换不来分 —— 人人都有的标签就是这样退场的`() {
        val broad = MovieRecommender.tagFactor(870, 1000)   // 0.0
        val mid = MovieRecommender.tagFactor(280, 1000)     // 0.44
        val rare = MovieRecommender.tagFactor(8, 1000)      // 0.984
        assertEquals(0.0, score(cand("a"), hits = hits(selfPlain = broad * 3)), 1e-9)
        assertEquals(3 * mid, score(cand("b"), hits = hits(selfPlain = mid * 3)), 1e-9)
        assertEquals(3 * rare, score(cand("c"), hits = hits(selfPlain = rare * 3)), 1e-9)
        // 命中 8 条人人都有标签也顶不上 1 条稀有标签
        assertTrue(score(cand("d"), hits = hits(selfPlain = broad * 8)) < score(cand("e"), hits = hits(selfPlain = rare)))
    }

    @Test
    fun `命中标签按因子累加，系列与自己口味分四个桶`() {
        val selfFactors = mapOf("系列: 甲" to 0.9, "乙" to 0.4, "人人都有" to 0.0)
        val tasteFactors = mapOf("丙" to 0.6, "系列: 丁" to 0.8)
        val h = MovieRecommender.tagHits(
            names = listOf("系列: 甲", "乙", "丙", "系列: 丁", "不在这两份表里"),
            selfFactors = selfFactors,
            tasteFactors = tasteFactors,
        )
        assertEquals(0.4, h.selfPlain, 1e-9)
        assertEquals(0.9, h.selfSeries, 1e-9)
        assertEquals(0.6, h.tastePlain, 1e-9)
        assertEquals(0.8, h.tasteSeries, 1e-9)
    }

    @Test
    fun `按卡分组：只认名字表里的标签`() {
        val matched = listOf(
            MovieNameRow("k1", "乙"), MovieNameRow("k1", "甲"),
            MovieNameRow("k2", "丙"), MovieNameRow("k3", "甲"),
        )
        val byCard = MovieRecommender.tagHitsByCard(matched, mapOf("甲" to 0.5, "乙" to 1.0), emptyMap())
        assertEquals(1.5, byCard.getValue("k1").selfPlain, 1e-9)
        assertEquals(0.5, byCard.getValue("k3").selfPlain, 1e-9)
        // k2 命中的"丙"不在自己的表里 → 0（表格没有的 = 不算信号）
        assertEquals(0.0, byCard.getValue("k2").selfPlain, 1e-9)
    }

    // ---------------- 系列标签（比重高于普通标签） ----------------

    @Test
    fun `同系列比普通标签重，且折算个数封顶两个`() {
        assertEquals(2.5, score(cand("a"), hits = hits(selfSeries = 1.0)), 1e-9)
        assertEquals(5.0, score(cand("b"), hits = hits(selfSeries = 2.0)), 1e-9)
        assertEquals(
            score(cand("c"), hits = hits(selfSeries = 2.0)),
            score(cand("d"), hits = hits(selfSeries = 5.0)),
            1e-9,
        )
        // 1 个系列（2.5）重于 2 个普通标签（2.0）—— 系列比标签精确得多
        assertTrue(score(cand("e"), hits = hits(selfSeries = 1.0)) > score(cand("f"), hits = hits(selfPlain = 2.0)))
        assertTrue(score(cand("g"), hits = hits(selfSeries = 1.0)) < score(cand("h", selfActor = 1)))
    }

    @Test
    fun `口味里的系列也比普通标签重`() {
        assertEquals(1.2, score(cand("a"), hits = hits(tasteSeries = 1.0)), 1e-9)
        assertTrue(score(cand("b"), hits = hits(tasteSeries = 1.0)) > score(cand("c"), hits = hits(tastePlain = 2.0)))
        // 但还是弱于口味演员
        assertTrue(score(cand("d"), hits = hits(tasteSeries = 1.0)) < score(cand("e", tasteActor = 1)))
    }

    @Test
    fun `看过的降权、看完的沉底 —— 但不剔除`() {
        val base = score(cand("a", selfActor = 2))
        val seen = score(cand("a", selfActor = 2), seen = setOf("a"))
        val done = score(cand("a", selfActor = 2), finished = setOf("a"))
        assertEquals(base - 2.0, seen, 1e-9)
        assertEquals(base - 3.0, done, 1e-9)
        assertTrue(done < seen)
    }

    @Test
    fun `同库、近期入库、评分各自加成`() {
        assertEquals(0.5, score(cand("a", dirPath = "/lib/x"), selfDir = "/lib"), 1e-9)
        // 同一部片自己（前缀完全相同）也算同库
        assertEquals(0.5, score(cand("a", dirPath = "/lib"), selfDir = "/lib"), 1e-9)
        // 不相关目录不加
        assertEquals(0.0, score(cand("a", dirPath = "/other"), selfDir = "/lib"), 1e-9)
        assertEquals(0.6, score(cand("a", addedAt = now - 24 * 3600_000L)), 1e-9)
        // 超过 90 天不算新片；入库时间在未来（时钟偏移）也不算
        assertEquals(0.0, score(cand("a", addedAt = now - 200L * 24 * 3600_000L)), 1e-9)
        assertEquals(0.0, score(cand("a", addedAt = now + 3600_000L)), 1e-9)
        assertEquals(1.2, score(cand("a", rating = 10.0)), 1e-9)
    }

    @Test
    fun `没有评分不加不减`() {
        assertEquals(0.0, score(cand("a")), 1e-9)
    }

    // ---------------- 抽样 ----------------

    private fun pool(n: Int) = (1..n).map { cand("k$it", selfActor = (it % 4) + 1) }

    @Test
    fun `同一个种子给出完全相同的结果 —— 滚动时不许跳`() {
        val a = MovieRecommender.pick(
            pool(30), count = 6, seed = 42L, selfDirPath = null, seen = emptySet(),
            finished = emptySet(), hitsByCard = emptyMap(), now = now,
        )
        val b = MovieRecommender.pick(
            pool(30), count = 6, seed = 42L, selfDirPath = null, seen = emptySet(),
            finished = emptySet(), hitsByCard = emptyMap(), now = now,
        )
        assertEquals(a.map { it.mediaKey }, b.map { it.mediaKey })
    }

    @Test
    fun `换一批（换种子）会换出不同的组合`() {
        val sets = (1L..6L).map { seed ->
            MovieRecommender.pick(pool(30), 6, seed, null, emptySet(), emptySet(), emptyMap(), now)
                .map { it.mediaKey }.toSet()
        }
        // 极端情况下两个种子可能撞出同一批，但六个种子全是同一批说明抽样没起作用
        assertTrue("六个种子只抽出一批，加权抽样没生效", sets.distinct().size > 1)
    }

    @Test
    fun `抽取条数不超过候选数，空候选给空`() {
        assertEquals(3, MovieRecommender.pick(pool(3), 12, 1L, null, emptySet(), emptySet(), emptyMap(), now).size)
        assertTrue(
            MovieRecommender.pick(emptyList(), 12, 1L, null, emptySet(), emptySet(), emptyMap(), now).isEmpty(),
        )
    }

    @Test
    fun `高分候选在多次抽样里更容易出现`() {
        // 一个明显更强的候选 + 一堆弱候选：强的那条应当几乎每次都上榜
        val cands = listOf(cand("strong", selfActor = 3, rating = 9.5)) + (1..30).map { cand("weak$it") }
        val hitsByCard = (1..30).associate { "weak$it" to hits(selfPlain = 1.0) }
        val hitsCount = (1L..20L).count { seed ->
            MovieRecommender.pick(cands, 5, seed, null, emptySet(), emptySet(), hitsByCard, now)
                .any { it.mediaKey == "strong" }
        }
        assertTrue("强候选只出现 $hitsCount/20 次，加权抽样的权重没起作用", hitsCount >= 18)
    }

    @Test
    fun `没在命中表里的候选按零标签算，不会崩`() {
        val cands = listOf(cand("a", selfActor = 1), cand("b"))
        val picked = MovieRecommender.pick(cands, 2, 7L, null, emptySet(), emptySet(), mapOf("a" to hits()), now)
        assertEquals(2, picked.size)
    }

    // ---------------- 口味画像 ----------------

    private fun watched(key: String, pos: Long = 0L, dur: Long = 0L) = WatchedCardRow(key, pos, dur)

    @Test
    fun `口味画像按最近程度降权，看完的比没看完的更可信`() {
        val watched = listOf(
            watched("recent", pos = 1000, dur = 1000),      // 看完
            watched("old", pos = 0, dur = 1000),            // 没看完
        )
        val taste = MovieRecommender.taste(
            watched = watched,
            actorsByCard = mapOf("recent" to listOf("甲"), "old" to listOf("乙")),
            tagsByCard = emptyMap(),
        )
        assertEquals(listOf("甲", "乙"), taste.actors)
    }

    @Test
    fun `口味画像取头部若干条，且与遍历顺序无关`() {
        val watched = (1..40).map { watched("k$it", pos = 1000, dur = 1000) }
        val actors = (1..40).associate { "k$it" to listOf("演员$it") }
        val taste = MovieRecommender.taste(watched, actors, emptyMap())
        assertEquals(15, taste.actors.size)
        assertEquals("演员1", taste.actors.first())
    }

    @Test
    fun `没有观看记录时口味为空`() {
        assertTrue(MovieRecommender.taste(emptyList(), emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun `口味画像不收画质编码标签 —— 它们几乎每部片都有`() {
        val taste = MovieRecommender.taste(
            watched = listOf(watched("k", pos = 1000, dur = 1000)),
            actorsByCard = emptyMap(),
            tagsByCard = mapOf("k" to listOf("1080P", "AVC1", "4K", "示例标签")),
        )
        assertEquals(listOf("示例标签"), taste.tags)
    }

    @Test
    fun `空集合哨兵不是空列表 —— Room 会把空 List 拼成 IN () 直接报错`() {
        assertTrue(MovieRecommender.NoName.isNotEmpty())
        assertTrue(MovieRecommender.NoName.single().isNotBlank())
    }

    // ---------------- 推荐理由 ----------------

    @Test
    fun `理由按信号强弱取第一条命中的`() {
        assertEquals(
            "同演员 · 甲",
            MovieRecommender.reasonOf(
                selfActors = setOf("甲"), selfTags = mapOf("乙" to 1.0),
                tasteActors = setOf("丙"), tasteTags = mapOf("丁" to 1.0),
                cardActors = listOf("甲"), cardTags = listOf("乙"), rating = 9.0,
            ),
        )
        assertEquals(
            "同标签 · 乙",
            MovieRecommender.reasonOf(
                selfActors = setOf("甲"), selfTags = mapOf("乙" to 1.0),
                tasteActors = emptySet(), tasteTags = emptyMap(),
                cardActors = listOf("戊"), cardTags = listOf("乙"), rating = 9.0,
            ),
        )
        assertEquals(
            "你最近看过 · 丙",
            MovieRecommender.reasonOf(
                selfActors = setOf("甲"), selfTags = emptyMap(),
                tasteActors = setOf("丙"), tasteTags = emptyMap(),
                cardActors = listOf("丙"), cardTags = emptyList(), rating = null,
            ),
        )
    }

    @Test
    fun `多条标签同时命中时挑最值钱的那条 —— 不按数据库给的顺序`() {
        assertEquals(
            "同标签 · 人无我有",
            MovieRecommender.reasonOf(
                selfActors = emptySet(), selfTags = mapOf("人人都有" to 0.0, "人无我有" to 0.98),
                tasteActors = emptySet(), tasteTags = emptyMap(),
                cardActors = emptyList(), cardTags = listOf("人人都有", "人无我有"), rating = null,
            ),
        )
    }

    @Test
    fun `系列命中说「同系列」，排在普通标签前面，且不带 系列： 前缀`() {
        // 系列权重 2.5，因子略低也压过普通标签
        assertEquals(
            "同系列 · 示例系列",
            MovieRecommender.reasonOf(
                selfActors = setOf("甲"), selfTags = mapOf("系列: 示例系列" to 0.8, "乙" to 0.98),
                tasteActors = emptySet(), tasteTags = emptyMap(),
                cardActors = emptyList(), cardTags = listOf("乙", "系列: 示例系列"), rating = null,
            ),
        )
        assertEquals(
            "常看系列 · 示例系列",
            MovieRecommender.reasonOf(
                selfActors = emptySet(), selfTags = emptyMap(),
                tasteActors = emptySet(), tasteTags = mapOf("系列: 示例系列" to 0.9),
                cardActors = emptyList(), cardTags = listOf("系列: 示例系列"), rating = null,
            ),
        )
        // 没有共同点时还是老样子
        assertEquals(
            "同标签 · 乙",
            MovieRecommender.reasonOf(
                emptySet(), mapOf("乙" to 1.0), emptySet(), emptyMap(),
                emptyList(), listOf("系列: 别的系列", "乙"), rating = null,
            ),
        )
    }

    @Test
    fun `归零的宽标签当不了理由 —— 只剩它命中时宁可写高分`() {
        assertEquals(
            "高分",
            MovieRecommender.reasonOf(
                selfActors = emptySet(), selfTags = mapOf("人人都有" to 0.0),
                tasteActors = emptySet(), tasteTags = emptyMap(),
                cardActors = emptyList(), cardTags = listOf("人人都有"), rating = 8.0,
            ),
        )
        assertNull(
            MovieRecommender.reasonOf(
                selfActors = emptySet(), selfTags = mapOf("人人都有" to 0.0),
                tasteActors = emptySet(), tasteTags = emptyMap(),
                cardActors = emptyList(), cardTags = listOf("人人都有"), rating = 5.0,
            ),
        )
    }

    @Test
    fun `没有共同点但评分高时说高分，连高分都没有就不写理由`() {
        assertEquals(
            "高分",
            MovieRecommender.reasonOf(
                emptySet(), emptyMap(), emptySet(), emptyMap(),
                listOf("甲"), emptyList(), rating = 8.0,
            ),
        )
        assertNull(
            MovieRecommender.reasonOf(
                emptySet(), emptyMap(), emptySet(), emptyMap(),
                listOf("甲"), emptyList(), rating = 5.0,
            ),
        )
        assertNotNull(
            MovieRecommender.reasonOf(
                emptySet(), emptyMap(), emptySet(), emptyMap(),
                listOf("甲"), emptyList(), rating = 7.5,
            ),
        )
    }

    // ---------------- 种子 ----------------

    @Test
    fun `种子同片同批稳定，换一批或换片才变`() {
        val a = RecommendationSeed.of("demo-a", 0)
        assertEquals(a, RecommendationSeed.of("demo-a", 0))
        assertTrue(a != RecommendationSeed.of("demo-a", 1))
        assertTrue(a != RecommendationSeed.of("demo-b", 0))
    }
}
