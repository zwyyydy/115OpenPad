package com.open115.pad.data.media

/**
 * 作品列表的筛选条件（多选；全空 = 不筛）。
 *
 * 各维度的**选项**都从"当前页面已经拿到的那批作品"里现算（见 [facetsOf]）——
 * 只列这个页面里真的有的年份/类型/标签，勾了必有结果，也不会出现空选项。
 *
 * 维度之间是"与"（年份 2024 **且** 类型含科幻），同一维度内是"或"（2024 或 2023 都算命中）。
 */
data class WorksFilter(
    val years: Set<Int> = emptySet(),
    /**
     * 类型 / 标签 —— **合成一个维度**。
     *
     * 为什么不分两份：nfo 里 `<genre>` 与 `<tag>` 常常是同一批词（实测某库两边完全一样），
     * 分成两个维度要么列两遍一样的选项、要么其中一个永远空着。
     * 命中判据是"类型里有 **或** 标签里有"（[genreListOf] + `movie_tags`）。
     */
    val kinds: Set<String> = emptySet(),
    /** 演员（多选）：命中判据是"这部片有其中任意一位" */
    val actors: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = years.isEmpty() && kinds.isEmpty() && actors.isEmpty()

    /** 一共勾了几项（表头那个按钮上显示角标） */
    val selectedCount: Int get() = years.size + kinds.size + actors.size

    /**
     * 这部作品命中吗。
     *
     * ★ [extra] 是**单独查出来**的（标签与演员都在关联表里，`MovieCard` 上没有）——
     *   调用方一次查完整批、按 mediaKey 建好映射再传进来（见 [cardFacetsOf]），别在判断里现查。
     */
    fun matches(card: MovieCard, extra: CardFacets): Boolean {
        if (years.isNotEmpty() && (card.year == null || card.year !in years)) return false
        if (kinds.isNotEmpty()) {
            val hit = genreListOf(card.genre).any { it in kinds } || extra.tags.any { it in kinds }
            if (!hit) return false
        }
        if (actors.isNotEmpty() && extra.actors.none { it in actors }) return false
        return true
    }
}

/**
 * 一张卡片上**查不到**的那些维度：标签与演员都在关联表（`movie_tags` / `movie_actors`）里。
 *
 * 打包成一个对象而不是给 [WorksFilter.matches] 加两个 Set 参数：维度以后还会多，
 * 而调用方永远是"按 mediaKey 取一份"——多一个维度只改这里和构造的地方。
 */
data class CardFacets(
    val tags: Set<String> = emptySet(),
    val actors: Set<String> = emptySet(),
) {
    companion object {
        val Empty = CardFacets()
    }
}

/**
 * 一次查回这批作品的标签与演员，按 mediaKey 建好映射（筛选用）。
 *
 * 两条查询而不是每部片两条：列表最多几百条，逐条查就是上千次查询。
 */
suspend fun cardFacetsOf(dao: MediaDao, keys: List<String>): Map<String, CardFacets> {
    if (keys.isEmpty()) return emptyMap()
    val out = HashMap<String, CardFacets>(keys.size)
    fun merge(rows: List<MovieNameRow>, tag: Boolean) {
        for (row in rows) {
            val old = out[row.mediaKey] ?: CardFacets.Empty
            out[row.mediaKey] = if (tag) old.copy(tags = old.tags + row.name) else old.copy(actors = old.actors + row.name)
        }
    }
    runCatching { dao.tagNamesOf(keys) }.getOrDefault(emptyList()).let { merge(it, tag = true) }
    runCatching { dao.actorNamesOf(keys) }.getOrDefault(emptyList()).let { merge(it, tag = false) }
    return out
}

/**
 * `movies.genre` 这一列存的是 `动作 / 冒险 / 科幻`（扫描时用 " / " 拼的），拆开才能比。
 *
 * 顺带认 `|` 与 `,`：nfo 里一个 genre 标签塞多个时，扫描器也按这些分隔符拆过，
 * 落到列上只剩 " / " —— 这里多认两种只是为了老数据/手改数据不至于整串匹配不上。
 */
fun genreListOf(genre: String?): List<String> =
    genre?.split('/', '|', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** 这批作品里实际出现的选项 */
data class FilterFacets(
    val years: List<Int>,
    /** 类型与标签的并集（去重后按出现次数排） */
    val kinds: List<String>,
    /** 演员（按出现次数排） */
    val actors: List<String>,
) {
    val isEmpty: Boolean get() = years.isEmpty() && kinds.isEmpty() && actors.isEmpty()

    /** 选项被截断时的提示（演员可能上百个，只列最常见的那些） */
    val actorsTruncated: Boolean get() = actors.size > MAX_ACTOR_CHOICES
}

/** 演员选项最多列多少个（按出现次数取前 N）：一部片五个演员，几百部就是一个长长的名单 */
const val MAX_ACTOR_CHOICES = 60

/**
 * 现算选项：年份新的在前；类型/标签按**出现次数多的在前**（同次数按字典序，保证顺序稳定 ——
 * 顺序会变的话，用户勾完一项、列表重排、chip 位置跟着跳）。
 */
fun facetsOf(list: List<MovieCard>, extraOf: (MovieCard) -> CardFacets): FilterFacets {
    fun byCount(values: List<String>): List<String> = values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    return FilterFacets(
        years = list.mapNotNull { it.year }.distinct().sortedDescending(),
        // byCount 的 key 天然去重：类型与标签里重复的词只出现一次
        kinds = byCount(list.flatMap { genreListOf(it.genre) + extraOf(it).tags }),
        actors = byCount(list.flatMap { extraOf(it).actors }).take(MAX_ACTOR_CHOICES),
    )
}
