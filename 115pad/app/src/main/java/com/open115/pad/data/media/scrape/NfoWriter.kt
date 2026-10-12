package com.open115.pad.data.media.scrape

import java.util.Locale

/**
 * 把 [ScrapedMetadata] 写成 Emby / Kodi / TMM 范式的 movie nfo。
 *
 * 对齐关系是**单向的硬约束**：本写出的文件必须能被
 * [com.open115.pad.data.media.NfoParser] 原样读回来（单测做 write→parse round-trip），
 * 否则"写回云盘 → 重扫入库"这条链路会静默丢字段。
 *
 * 有意**不写**的字段：
 *  - `<dateadded>`：工程里"入库时间"的语义是"本机索引时刻"（MDCx 写的 nfo 也没有这个标签），
 *    写一个刮削时间进去会把「入库时间 ↑/↓」排序的含义搅乱；
 *  - `<fileinfo><streamdetails>`：技术参数由本地文件分析得出，不该由刮削源编；
 *  - `<thumb aspect>` 之类的图床 URL：海报是**文件**（`<前缀>-poster.jpg`），不写 URL 引用。
 *
 * 标签值里的 `|` `,` `/` 会被解析器当分隔符拆开（Archos 约定），
 * 所以每个值都单独出一个元素、不在一个元素里塞多项 —— 名字里带分隔符的极少，接受这点损耗。
 */
object NfoWriter {

    fun build(meta: ScrapedMetadata): String = buildString(1024) {
        // 剧集写 <tvshow> 根（扫描器据此建系列条目），电影写 <movie>。NfoParser 按字段名读，两者共用一套字段
        val root = if (meta.kind == ScrapeKind.TV) "tvshow" else "movie"
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<$root>\n")

        el("title", meta.title)
        // 原名与片名相同时不重复写（Kodi 的约定是"不同才写"）
        meta.originalTitle?.takeIf { it.isNotBlank() && it != meta.title }?.let { el("originaltitle", it) }
        meta.sortTitle?.takeIf { it.isNotBlank() }?.let { el("sorttitle", it) }
        meta.tagline?.takeIf { it.isNotBlank() }?.let { el("tagline", it) }

        meta.rating?.let { el("rating", formatRating(it)) }
        meta.votes?.takeIf { it > 0 }?.let { el("votes", it.toString()) }
        meta.plot?.takeIf { it.isNotBlank() }?.let { el("plot", it) }
        meta.runtimeMinutes?.takeIf { it > 0 }?.let { el("runtime", it.toString()) }
        meta.contentRating?.takeIf { it.isNotBlank() }?.let { el("mpaa", it) }
        meta.premiered?.takeIf { it.isNotBlank() }?.let { el("premiered", it) }
        meta.year?.takeIf { it > 0 }?.let { el("year", it.toString()) }

        meta.genres.filter { it.isNotBlank() }.forEach { el("genre", it) }
        meta.countries.filter { it.isNotBlank() }.forEach { el("country", it) }
        meta.languages.filter { it.isNotBlank() }.forEach { el("language", it) }
        meta.studios.filter { it.isNotBlank() }.forEach { el("studio", it) }
        meta.directors.filter { it.isNotBlank() }.forEach { el("director", it) }
        // Kodi 把编剧写在 <credits>，解析器 writer/credits 都认
        meta.writers.filter { it.isNotBlank() }.forEach { el("credits", it) }
        meta.set?.takeIf { it.isNotBlank() }?.let { name ->
            append("  <set>\n")
            el("name", name, indent = "    ")
            append("  </set>\n")
        }
        meta.trailer?.takeIf { it.isNotBlank() }?.let { el("trailer", it) }

        meta.actors.forEachIndexed { i, a ->
            if (a.name.isBlank()) return@forEachIndexed
            append("  <actor>\n")
            el("name", a.name, indent = "    ")
            a.role?.takeIf { it.isNotBlank() }?.let { el("role", it, indent = "    ") }
            el("order", i.toString(), indent = "    ")
            append("  </actor>\n")
        }

        // 外部 id：扫描器的 mediaKey 会据此变成 tmdb-<id>（判据见 DirectorySniffer.mediaKeyOf）
        meta.tmdbId?.takeIf { it.isNotBlank() && it != "0" }?.let { id ->
            append("  <uniqueid type=\"tmdb\" default=\"true\">${esc(id)}</uniqueid>\n")
            el("tmdbid", id)
        }
        meta.imdbId?.takeIf { it.isNotBlank() }?.let { el("imdbid", it) }

        append("</$root>\n")
    }

    /**
     * 分集 nfo（`<episodedetails>`）：写在各集视频同名文件里，让扫描器给这一集填真名/简介/首播/季集号。
     *
     * `<uniqueid>` 用**这一集自己的** tmdb id（Kodi 约定），剧的关联靠 `<showtitle>`；
     * 本工程的 mediaKey 对分集一律用文件前缀（不吃 nfo 的 id），所以这个 id 只作信息、不影响入库主键。
     */
    fun buildEpisode(showTitle: String, ep: ScrapedEpisode): String = buildString(512) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<episodedetails>\n")
        el("title", ep.title)
        el("showtitle", showTitle)
        el("season", ep.season.toString())
        el("episode", ep.episode.toString())
        ep.plot?.takeIf { it.isNotBlank() }?.let { el("plot", it) }
        ep.aired?.takeIf { it.isNotBlank() }?.let { el("firstaired", it) }
        ep.tmdbId?.takeIf { it.isNotBlank() && it != "0" }?.let { id ->
            append("  <uniqueid type=\"tmdb\" default=\"true\">${esc(id)}</uniqueid>\n")
        }
        append("</episodedetails>\n")
    }

    /** 一个元素连同缩进；[value] 为空/空白直接不写（"只写有值的字段"是刻意的） */
    private fun StringBuilder.el(tag: String, value: String, indent: String = "  ") {
        if (value.isBlank()) return
        append(indent).append('<').append(tag).append('>')
        append(esc(value))
        append("</").append(tag).append(">\n")
    }

    /**
     * 评分格式：一位小数、**固定 `.` 小数点**。
     * Locale 不指定的话在中文/德语等环境会写成 `8,2`，解析器读回就成了 null。
     */
    private fun formatRating(v: Double): String = String.format(Locale.US, "%.1f", v.coerceIn(0.0, 10.0))

    /** XML 转义。控制字符（除 \n \t）直接丢掉 —— 塞进 XML 会让整个文件解析失败 */
    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
            }
        }
    }
}
