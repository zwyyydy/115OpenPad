package com.open115.pad.data.media

import kotlinx.coroutines.flow.first
import kotlin.math.exp
import kotlin.random.Random

/**
 * 推荐影片：**本地四路信号**的打分与抽样（纯逻辑，好单测）。
 *
 *  1. 同演员（最强）—— 这类片库里演员就是主线
 *  2. 同标签 —— 扫描期写的是 `nfo 的 genre + tag`，所以"类型"和"标签"是同一张表；
 *     按**覆盖率**降权（[tagFactor]：盖住半个库的标签没有区分度），其中 `系列: xxx`
 *     单独吃更高的权重（比普通标签精确得多，见 [TagSignals]）
 *  3. 近期观影口味 —— 最近看过的片按"多近 + 看没看完"加权，聚成一份画像
 *  4. 随机扰动 —— 每次进详情页/点「换一批」给出不同的组合
 *
 * 标签的**两道过滤**：画质/编码（`1080P` / `AVC1` / `4K`…，[TagSignals.isNoise]）一律不计入；
 * 太宽的标签（盖住九成片的那些）按覆盖率把权重降到 0。两者都要在进 SQL 之前做完 ——
 * 候选查询只按名字匹配，自己分不出谁是噪声、谁太宽，它们一旦进了名单大半个库都会"命中"。
 *
 * 分工：[MediaDao.recommendCandidates] 负责"挑出有共同点的候选并粗排"，这里负责精确打分、
 * 加权抽样和"为什么推它"的文案。SQL 里做不了"加权随机"，而 Kotlin 里做不了几千行的索引查找。
 *
 * ★ 随机必须**带种子且稳定**：同一个页面停留期间种子固定，否则滚动一下推荐就在跳，比没有还糟。
 *   只有「换一批」或重新进详情页才会换种子（见 [RecommendationSeed]）。
 */
object MovieRecommender {

    // ---------------- 权重（调"推荐口味"就改这里） ----------------

    /** 同演员：最强信号。不封顶会让"合作过 8 个演员"的片霸榜，所以记满 [MAX_SELF_ACTORS] 个为止 */
    private const val W_SELF_ACTOR = 3.0
    private const val MAX_SELF_ACTORS = 3

    /** 同标签：标签很宽（一条热门标签能盖几百部），权重压到 1/3。计数里**不含**系列标签 */
    private const val W_SELF_TAG = 1.0
    private const val MAX_SELF_TAGS = 4

    /**
     * 同系列（`系列: xxx`）：介于演员与普通标签之间。系列比标签精确得多 ——
     * 实测整库最大的系列也只有 23 部片（相比之下最宽的那条标签有 459 部），同一个系列基本就是
     * "同一批片"，所以给它 2.5（演员是 3.0），封顶 2 个防止堆叠。
     */
    private const val W_SELF_SERIES_TAG = 2.5
    private const val MAX_SELF_SERIES_TAGS = 2

    /** 口味画像命中的权重：比"和当前这部片相关"弱一档 */
    private const val W_TASTE_ACTOR = 1.5
    private const val W_TASTE_TAG = 0.4

    /** 口味里的系列标签：同样比普通标签高（0.4 → 1.2），是"一直在追这个系列"的意思 */
    private const val W_TASTE_SERIES_TAG = 1.2

    private const val W_RATING = 1.2
    private const val W_FRESH = 0.6
    private const val FRESH_MS = 90L * 24 * 3600 * 1000

    /** 同库（dirPath 前缀相同）：这类库是按演员分的，实际是"同演员"的补充 */
    private const val W_SAME_LIB = 0.5

    /** 看过就降权、看完了沉底 —— **不剔除**（想重看是合理需求） */
    private const val PENALTY_SEEN = 2.0
    private const val PENALTY_FINISHED = 3.0

    /** 随机扰动的幅度：小于最小权重差（1.0），够换组合、不至于把弱候选顶到最前 */
    private const val JITTER = 0.9

    /**
     * 加权抽样的温度（softmax）。**不能省**：权重取线性的 `1 + 分数` 时，候选一多，
     * 最强的那条也只有约一半概率上榜（实测 30 个弱候选、抽 5 条时强候选只出现 10/20 次）——
     * 表现为"最相关的片经常看不到"，而弱候选轮番出现。温度越小越"赢家通吃"：
     * 2.5 下最强的几乎总在（约 93%），但也不是永远同 12 条，换一批仍然能换出东西。
     */
    private const val TEMPERATURE = 2.5

    private const val TASTE_TOP_ACTORS = 15
    private const val TASTE_TOP_TAGS = 20

    /** 精确排序只在粗排后的前 [POOL] 条里做加权抽样（避免"永远那 12 条"） */
    private const val POOL = 40

    /** 与观影历史同一个口径：差这么点就算看完（见 ui/history 的 NEAR_END_MS） */
    private const val NEAR_END_MS = 15_000L

    /**
     * 空集合的**哨兵**：Room 会把空 List 参数拼成 `IN ()`，SQLite 直接语法错。
     * 传一个不可能存在的名字进去，语义正好是"这一路没有信号"。
     */
    val NoName: List<String> = listOf("\u0000__none__")

    /** 口味画像：近段时间反复出现的演员/标签名，按权重从高到低 */
    data class Taste(val actors: List<String>, val tags: List<String>) {
        val isEmpty: Boolean get() = actors.isEmpty() && tags.isEmpty()

        companion object {
            val Empty = Taste(emptyList(), emptyList())
        }
    }

    /** 界面要的一条推荐 */
    data class Recommendation(val card: MovieCard, val reason: String?)

    // ---------------- 标签的覆盖率权重 ----------------

    /**
     * 覆盖率的"归零点"：一个标签盖住全库这么大比例的片，就当它没有区分度（因子 0）。
     *
     * 0.5 = 覆盖 0% 满权、25% 半权、**50% 及以上归零**。实测：最宽的几条内容标签分别盖 87%（归零）、
     * 47%（0.05）、32%（0.36）、28%（0.45）、17%（0.67），
     * 而系列标签、冷门癖好（1%）基本满权 —— 调"多宽算宽"就改这一个数。
     */
    private const val TAG_ZERO_COVERAGE = 0.5

    /**
     * 覆盖率因子：**一个标签盖住全库多少比例的片，就掉多少话语权**。
     *
     * 为什么要有它：库里最宽的那几条内容标签能盖住三到九成的片，给它们满权等于给大半个库
     * 免费加分，还会霸占推荐理由（一屏卡片都写着同一条"人人都有"的标签）。判据只能按**数据**来 ——
     * 哪些标签算宽，随库而变，写死一张名单迟早跟不上刮削器的花样。
     *
     * 分母取全库**顶层卡片数**（不是某一个库）：候选本来就是跨库挑的，判别力也该按全库算。
     * 数不出总数（空库/刚建库）时不惩罚，返回 1.0 —— 宁可不降权，也别把信号全抹平。
     */
    fun tagFactor(cardsWithTag: Int, totalCards: Int): Double {
        if (totalCards <= 0) return 1.0
        return (1.0 - cardsWithTag.toDouble() / totalCards / TAG_ZERO_COVERAGE).coerceIn(0.0, 1.0)
    }

    /**
     * 一张卡命中的标签的**加权和**：四个桶分开（自己/口味的 × 普通/系列），每一项是那个标签的覆盖率因子。
     *
     * 不在这里乘 `W_*`：系列与普通、自己与口味的权重不同，而封顶要封在"个数"上 —— 因子 ≤1，
     * 于是"因子之和"正好就是"折算成几个满权标签"，[score] 里直接封顶即可（一个标签一条都不命中时是 0）。
     */
    data class TagHits(
        val selfPlain: Double = 0.0,
        val selfSeries: Double = 0.0,
        val tastePlain: Double = 0.0,
        val tasteSeries: Double = 0.0,
    ) {
        companion object {
            val None = TagHits()
        }
    }

    /**
     * 把"命中的标签名"折成 [TagHits]。`factors` 是 名字 → 覆盖率因子，**已经滤掉画质/编码与归零的宽标签**
     * （不在表里的名字就是"不算信号"，所以调用方不用再判一次）。
     */
    fun tagHits(
        names: Collection<String>,
        selfFactors: Map<String, Double>,
        tasteFactors: Map<String, Double>,
    ): TagHits {
        var selfPlain = 0.0
        var selfSeries = 0.0
        var tastePlain = 0.0
        var tasteSeries = 0.0
        names.forEach { n ->
            selfFactors[n]?.let { f -> if (TagSignals.isSeries(n)) selfSeries += f else selfPlain += f }
            tasteFactors[n]?.let { f -> if (TagSignals.isSeries(n)) tasteSeries += f else tastePlain += f }
        }
        return TagHits(selfPlain, selfSeries, tastePlain, tasteSeries)
    }

    /** 按卡分组：[MediaDao.matchedTagsForCards] 的结果 → 每张卡一份 [TagHits] */
    fun tagHitsByCard(
        matched: List<MovieNameRow>,
        selfFactors: Map<String, Double>,
        tasteFactors: Map<String, Double>,
    ): Map<String, TagHits> = matched.groupBy({ it.mediaKey }, { it.name })
        .mapValues { (_, names) -> tagHits(names, selfFactors, tasteFactors) }

    // ---------------- 打分 ----------------

    /**
     * 一条候选的得分。`jitter` 由调用方按种子给出（同样的输入必须给同样的结果）；
     * `now` 显式传入而不是内部取系统时钟 —— 否则"近期入库"那一项没法单测。
     *
     * `hits` 见 [TagHits]：宽标签因子小，"大家都有"的标签堆再多也换不来分。
     */
    fun score(
        cand: MovieRecommendation,
        selfDirPath: String?,
        seen: Set<String>,
        finished: Set<String>,
        hits: TagHits,
        jitter: Double,
        now: Long = System.currentTimeMillis(),
    ): Double {
        var s = W_SELF_ACTOR * minOf(cand.selfActorHits, MAX_SELF_ACTORS) +
            W_SELF_TAG * minOf(hits.selfPlain, MAX_SELF_TAGS.toDouble()) +
            W_SELF_SERIES_TAG * minOf(hits.selfSeries, MAX_SELF_SERIES_TAGS.toDouble()) +
            W_TASTE_ACTOR * cand.tasteActorHits +
            W_TASTE_TAG * hits.tastePlain +
            W_TASTE_SERIES_TAG * hits.tasteSeries
        cand.rating?.let { s += W_RATING * (it / 10.0).coerceIn(0.0, 1.0) }
        // 入库时间在未来（时钟偏移/刮削器写错）不算"新片"，所以下界取 1 而不是 0
        cand.addedAt?.let { if (now - it in 1..FRESH_MS) s += W_FRESH }
        val dir = cand.dirPath
        if (!selfDirPath.isNullOrBlank() && !dir.isNullOrBlank() && sameLibrary(selfDirPath, dir)) {
            s += W_SAME_LIB
        }
        // 未命中的那一项（比如没有评分）不加不减，符合"信号缺失就不表态"
        if (cand.mediaKey in finished) s -= PENALTY_FINISHED else if (cand.mediaKey in seen) s -= PENALTY_SEEN
        return s + jitter
    }

    /** 同库 = 同一个目录，或者一方在另一方的子树里（媒体库的根是目录不是文件） */
    private fun sameLibrary(a: String, b: String): Boolean =
        a == b || a.startsWith("$b/") || b.startsWith("$a/")

    /**
     * 从粗排结果里挑 [count] 条：**按分加权抽样**，不是"取前 N 再打乱"。
     *
     * 取前 N 打乱的话换一批只是在同几个人里换顺序，抽 N 次才会真的换组合；但完全按权重抽又会让
     * 弱候选频繁冒头，所以只在粗排后的前 [POOL] 条里抽，并且权重用 `1 + 分数` 保证高分仍然更容易中。
     */
    fun pick(
        candidates: List<MovieRecommendation>,
        count: Int,
        seed: Long,
        selfDirPath: String?,
        seen: Set<String>,
        finished: Set<String>,
        hitsByCard: Map<String, TagHits>,
        now: Long = System.currentTimeMillis(),
    ): List<MovieRecommendation> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        // 两个独立种子：扰动与抽样各自确定，改一个不会连带改另一个
        val jitterRng = Random(seed)
        val scored = candidates
            .map { c ->
                c to score(
                    c, selfDirPath, seen, finished,
                    hitsByCard[c.mediaKey] ?: TagHits.None, jitterRng.nextDouble() * JITTER, now,
                )
            }
            .sortedByDescending { it.second }
        val scoreOf = scored.associate { it.first.mediaKey to it.second }

        val pool = scored.take(POOL).toMutableList()
        val sampleRng = Random(seed * 31 + 7)
        val picked = ArrayList<MovieRecommendation>(count)
        // softmax 的基准取池子里最高分，指数才不会溢出
        val top = pool.first().second
        while (picked.size < count && pool.isNotEmpty()) {
            val weights = pool.map { exp((it.second - top) / TEMPERATURE) }
            val total = weights.sum()
            var r = sampleRng.nextDouble() * total
            var idx = 0
            while (idx < weights.size - 1 && r >= weights[idx]) {
                r -= weights[idx]
                idx++
            }
            picked += pool.removeAt(idx).first
        }
        // 抽完再按分数排一次：组合是随机的，顺序不该看起来也是乱的
        return picked.sortedByDescending { scoreOf[it.mediaKey] ?: 0.0 }
    }

    // ---------------- 口味画像 ----------------

    /**
     * 近期观影口味：第 i 条（0 起）权重 `1/(1+i/10)`，看完的算满分、没看完的打 6 折。
     *
     * 为什么降权而不是"只看最近 5 条"：看片的连续性比"时间窗"稳 —— 一晚上连看 3 部会把
     * 窗口冲掉，而降权能把最近十几部都纳进来。看完的比弃掉的可信：后者可能是点错了或不好看。
     *
     * 画质/编码标签（`1080P` / `AVC1`…）在这里就滤掉 —— 它们几乎每部片都有，
     * 不滤的话口味画像会被这类标签占满，真正的口味反而挤不进前 20（见 [TagSignals.isNoise]）。
     */
    fun taste(
        watched: List<WatchedCardRow>,
        actorsByCard: Map<String, List<String>>,
        tagsByCard: Map<String, List<String>>,
    ): Taste {
        if (watched.isEmpty()) return Taste.Empty
        val actorW = HashMap<String, Double>()
        val tagW = HashMap<String, Double>()
        watched.forEachIndexed { i, row ->
            val fresh = 1.0 / (1.0 + i / 10.0)
            val done = if (isFinished(row)) 1.0 else 0.6
            val w = fresh * done
            actorsByCard[row.cardKey].orEmpty().forEach { actorW[it] = (actorW[it] ?: 0.0) + w }
            tagsByCard[row.cardKey].orEmpty().filterNot(TagSignals::isNoise)
                .forEach { tagW[it] = (tagW[it] ?: 0.0) + w }
        }
        return Taste(topN(actorW, TASTE_TOP_ACTORS), topN(tagW, TASTE_TOP_TAGS))
    }

    private fun isFinished(row: WatchedCardRow): Boolean =
        row.durationMs > 0L && row.positionMs >= row.durationMs - NEAR_END_MS

    /** 权重降序取前 n 个；同权重按名字排，保证结果与遍历顺序无关（HashMap 顺序不保证） */
    private fun topN(weights: Map<String, Double>, n: Int): List<String> =
        weights.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(n).map { it.key }

    // ---------------- 推荐理由 ----------------

    /**
     * 命中的标签里"最值得当理由"的那条：按 `权重 × 覆盖率因子` 比（系列权重高，自然更容易胜出）。
     *
     * 用最强的而不是"列表里第一条"：卡片的标签顺序是数据库给的，不排一次就会写出
     * "一条人人都有的标签"这种没信息量的理由。同分的按名字定序，保证同一次推荐反复算结果一样。
     */
    private fun strongestTag(
        cardTags: Collection<String>,
        factors: Map<String, Double>,
        seriesWeight: Double,
        plainWeight: Double,
    ): String? = cardTags.mapNotNull { t ->
        // 因子 0（覆盖率归零的宽标签）连"最弱"都算不上：不出现在理由里
        factors[t]?.takeIf { it > 0.0 }?.let { f -> t to f * (if (TagSignals.isSeries(t)) seriesWeight else plainWeight) }
    }.maxWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenByDescending { it.first })?.first

    /**
     * 「为什么推它」：按信号强弱取第一条命中的，取不到就给"高分"、再取不到就不写。
     *
     * `selfTags` / `tasteTags` 是 **名字 → 覆盖率因子**（已滤掉画质/编码与归零的宽标签），
     * 所以宽标签既不会加分、也不会被写成理由；`cardTags` 传卡片自己的标签名即可，不认识的自然忽略。
     *
     * 没理由的推荐条**照样显示**（只是没有那行小字）—— 缺理由不是缺影片。
     */
    fun reasonOf(
        selfActors: Set<String>,
        selfTags: Map<String, Double>,
        tasteActors: Set<String>,
        tasteTags: Map<String, Double>,
        cardActors: List<String>,
        cardTags: List<String>,
        rating: Double?,
    ): String? {
        cardActors.firstOrNull { it in selfActors }?.let { return "同演员 · $it" }
        strongestTag(cardTags, selfTags, W_SELF_SERIES_TAG, W_SELF_TAG)?.let {
            return if (TagSignals.isSeries(it)) "同系列 · ${TagSignals.seriesLabel(it)}" else "同标签 · $it"
        }
        cardActors.firstOrNull { it in tasteActors }?.let { return "你最近看过 · $it" }
        strongestTag(cardTags, tasteTags, W_TASTE_SERIES_TAG, W_TASTE_TAG)?.let {
            return if (TagSignals.isSeries(it)) "常看系列 · ${TagSignals.seriesLabel(it)}" else "口味相近 · $it"
        }
        return if ((rating ?: 0.0) >= 7.5) "高分" else null
    }
}

/**
 * 推荐种子：同一个页面停留期间**必须固定**，否则滚动/重组一次推荐就跳一次。
 *
 * 用"影片键 + 换一批次数"派生，和会话无关 —— 同一部片第二次进来看到的推荐是一样的
 * （想换就点「换一批」）。升级换算法时把 [VERSION] +1，免得新旧结果混着看。
 */
object RecommendationSeed {
    /** 3：标签按覆盖率降权（宽标签归零）—— 旧种子下的结果会变，混着看会以为推荐在乱跳 */
    private const val VERSION = 3

    fun of(mediaKey: String, shuffleTick: Int): Long =
        mediaKey.hashCode().toLong() * 1_000_003L + shuffleTick * 7919L + VERSION
}

/**
 * 取一部片的推荐列表（界面唯一入口）。
 *
 * 顺序：自身画像 → 口味画像 → 标签覆盖率 → 候选 → 打分抽样 → 给最终那几条补"理由"。
 * 全程本地 Room 查询、**零网络**；海报交给界面按老规矩取（命中缓存即零请求）。
 *
 * ★ 标签的**两道过滤都得在进 SQL 之前做完**（画质/编码 + 覆盖率归零）：候选查询只按名字匹配，
 * 自己分不出谁是 `1080P`、谁是"人人都有"的宽标签 —— 它们一旦进了名单，大半个库都会"命中"。
 *
 * 兜底：这部片**既没有演员也没有标签**（老数据/无 nfo）时四路信号全空，候选为空 ——
 * 这时退回"同库评分最高的几部"，别让区块整段消失。
 */
suspend fun loadRecommendations(
    dao: MediaDao,
    selfKey: String,
    count: Int = 12,
    seed: Long,
): List<MovieRecommender.Recommendation> {
    val selfActors = dao.actorNamesForCards(listOf(selfKey)).map { it.name }.distinct()
    val rawSelfTags = dao.tagNamesForCards(listOf(selfKey)).map { it.name }.distinct()
        .filterNot(TagSignals::isNoise)

    val watched = dao.recentWatchedCards(30)
    val watchedKeys = watched.map { it.cardKey }.distinct()
    val taste = if (watchedKeys.isEmpty()) {
        MovieRecommender.Taste.Empty
    } else {
        MovieRecommender.taste(
            watched = watched,
            actorsByCard = dao.actorNamesForCards(watchedKeys).groupBy({ it.mediaKey }, { it.name }),
            tagsByCard = dao.tagNamesForCards(watchedKeys).groupBy({ it.mediaKey }, { it.name }),
        )
    }

    // 覆盖率：这批标签各盖住全库多少片 → 因子；归零的（太宽）**直接剔出名单**，既不当候选来源也不当理由
    val allTagNames = (rawSelfTags + taste.tags).distinct()
    val totalCards = dao.topLevelCardCount()
    val factors = if (allTagNames.isEmpty()) {
        emptyMap()
    } else {
        dao.tagCardCounts(allTagNames)
            .associate { it.name to MovieRecommender.tagFactor(it.tagCount, totalCards) }
            .filterValues { it > 0.0 }
    }
    fun kept(names: List<String>): Map<String, Double> =
        names.mapNotNull { n -> factors[n]?.let { n to it } }.toMap()
    val selfFactors = kept(rawSelfTags)
    val tasteFactors = kept(taste.tags)

    val selfDirPath = dao.movie(selfKey)?.dirPath
    val seen = watchedKeys.toSet()
    val finished = watched.filter { it.durationMs > 0L && it.positionMs >= it.durationMs - 15_000L }
        .map { it.cardKey }.toSet()

    val cands = dao.recommendCandidates(
        self = selfKey,
        selfActors = selfActors.ifEmpty { MovieRecommender.NoName },
        selfTags = selfFactors.keys.toList().ifEmpty { MovieRecommender.NoName },
        tasteActors = taste.actors.ifEmpty { MovieRecommender.NoName },
        tasteTags = tasteFactors.keys.toList().ifEmpty { MovieRecommender.NoName },
        seriesLike = TagSignals.SERIES_LIKE,
    )
    if (cands.isEmpty() && selfDirPath.isNullOrBlank()) return emptyList()

    // 命中的是"哪几条"标签：打分要按各自的覆盖率加权，理由也要从这里挑最强的 —— 一次查询取回
    val matched = if (cands.isEmpty() || allTagNames.isEmpty()) {
        emptyList()
    } else {
        dao.matchedTagsForCards(cands.map { it.mediaKey }, allTagNames)
    }
    val matchedByCard = matched.groupBy({ it.mediaKey }, { it.name })
    val hitsByCard = MovieRecommender.tagHitsByCard(matched, selfFactors, tasteFactors)

    val picked = if (cands.isNotEmpty()) {
        MovieRecommender.pick(cands, count, seed, selfDirPath, seen, finished, hitsByCard)
    } else {
        emptyList()
    }
    // 兜底：一条候选都没有（这部片没有演员/标签、口味也空）→ 同库评分最高的几部
    val finalCards = if (picked.isNotEmpty()) {
        picked
    } else {
        val dir = selfDirPath ?: return emptyList()
        dao.byLibraryPath(dir, count + 1, WorksSort.Rating.sql).first()
            .filter { it.mediaKey != selfKey }
            .take(count)
            .map { it.toRecommendation() }
    }
    if (finalCards.isEmpty()) return emptyList()

    val keys = finalCards.map { it.mediaKey }
    val cardActors = dao.actorNamesForCards(keys).groupBy({ it.mediaKey }, { it.name })
    // 理由用的标签：正常路径直接用上面那份"命中的"；兜底那一路没查过，按老办法补一次
    val cardTags = if (picked.isNotEmpty()) matchedByCard
    else dao.tagNamesForCards(keys).groupBy({ it.mediaKey }, { it.name })
    val selfActorSet = selfActors.toSet()
    val tasteActorSet = taste.actors.toSet()
    return finalCards.map { c ->
        MovieRecommender.Recommendation(
            card = c.toCard(),
            reason = MovieRecommender.reasonOf(
                selfActors = selfActorSet,
                selfTags = selfFactors,
                tasteActors = tasteActorSet,
                tasteTags = tasteFactors,
                cardActors = cardActors[c.mediaKey].orEmpty(),
                cardTags = cardTags[c.mediaKey].orEmpty(),
                rating = c.rating,
            ),
        )
    }
}

/** 兜底那一路拿到的是 [MovieCard]（`byLibraryPath` 的投影），补齐成推荐用的形状 */
private fun MovieCard.toRecommendation(): MovieRecommendation = MovieRecommendation(
    mediaKey = mediaKey,
    title = title,
    year = year,
    rating = rating,
    posterPickCode = posterPickCode,
    fanartPickCode = fanartPickCode,
    extraFanartPickCodes = extraFanartPickCodes,
    isEpisodeLike = isEpisodeLike,
    videoPickCode = videoPickCode,
    genre = genre,
    videoName = videoName,
)
