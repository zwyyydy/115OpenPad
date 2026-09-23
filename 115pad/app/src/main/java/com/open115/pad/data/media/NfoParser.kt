package com.open115.pad.data.media

import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/** Emby .nfo 解析结果：只提取海报墙和详情页需要的字段 */
@Serializable
data class NfoMeta(
    val title: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val contentRating: String? = null,
    val uniqueTmdbid: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val showTitle: String? = null,
) {
    /**
     * 到底解析出东西了吗。
     *
     * 用来把"这次没拉到"和"这个 nfo 本来就是空的"分开：两者都返回默认值 NfoMeta()，
     * 但前者**不能**当成功缓存下来（写进去等于把"拉不到"缓存住，详情页的自愈重试
     * 会一路命中这个空结果、再也不重试）。缓存按本判据给不同 TTL，见 MediaScanner。
     */
    val hasContent: Boolean
        get() = title != null || plot != null || rating != null ||
            genres.isNotEmpty() || actors.isNotEmpty() || uniqueTmdbid != null
}

/**
 * 轻量 .nfo XML 解析（平台自带 XmlPullParser，零新依赖）。
 *
 * 规则对齐 Archos MediaScraper（aos-MediaLib NfoParser + NfoMovieHandler/NfoEpisodeHandler/NfoShowHandler）：
 * - 根节点 <movie> / <episodedetails> / <tvshow>，统一按字段名读取（Archos 按根节点分三个 handler，本项目场景字段集合并即可）
 * - movie.nfo / 文件名.nfo / tvshow.nfo 的文件定位规则在 MediaScanner 的 Cluster 阶段完成，这里只管解析
 * - actor 子节点取 <name>/<role>；genre 按 | , / 分隔拆分（Archos STRING_SPLITTERS）
 * - ID 取 <id>（imdb）与 <tmdbid>；<uniqueid type="tmdb"> 是 Emby 新格式，一并认
 * - 字段降级容错：任何一处缺失/类型异常都跳过该字段而不是整个解析失败
 */
object NfoParser {
    /** Archos NfoParser.STRING_SPLITTERS */
    private val STRING_SPLITTERS = charArrayOf('|', ',', '/')

    fun parse(reader: Reader): NfoMeta {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(reader)
        var title: String? = null
        var year: Int? = null
        var rating: Double? = null
        var plot: String? = null
        val genres = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val directors = mutableListOf<String>()
        val studios = mutableListOf<String>()
        var contentRating: String? = null
        var tmdbid: String? = null
        var season: Int? = null
        var episode: Int? = null
        var showTitle: String? = null

        var inActor = false
        var currentActorName: String? = null
        // 深度控制：Archos 的 NfoMovieHandler 只在 movie 根下取 <thumb>，
        // fanart 里的 <thumb> 是背景图；tvshow 根下同理。用栈区分 thumb 归属
        var inFanart = false

        var event = p.eventType
        // ★★ 结构性畸形**必须容忍**，不能整部片都没有元数据。
        //
        // 实测（2026-09-22，真实库）：刮削器写出的 nfo 会畸形 —— 根节点 </movie> 出现在
        // 文件中途、后面还接着 <tmdbid>/<director>/整块重复的 <thumb>，actor 开 9 个闭 10 个。
        // 这时 p.next() 会抛 XmlPullParserException，而字段是一个个收在局部变量里的：
        // 不接住的话，前面 115 行已经收好的 title/plot/year/rating/演员/类型**全丢**，
        // 整部片变成没有简介没有评分（调用方拿到的是默认值 NfoMeta()）。
        // 接住并返回已收到的部分，正是本类注释承诺的"跳过该字段而不是整个解析失败"。
        try {
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "actor" -> {
                            inActor = true
                            currentActorName = null
                        }
                        "fanart" -> inFanart = true
                        "title" -> if (!inActor) title = title ?: p.nextTextSafe()
                        "showtitle" -> showTitle = showTitle ?: p.nextTextSafe()
                        "year" -> year = year ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                        "season" -> season = season ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                        "episode" -> episode = episode ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                        "premiered", "releasedate", "aired" ->
                            if (year == null) year = p.nextTextSafe()?.trim()?.take(4)?.toIntOrNull()
                        "rating" -> {
                            // Emby 的 rating 可能带属性（name/type/default），只取正文首个数值
                            if (rating == null) rating = p.nextTextSafe()?.trim()?.toDoubleOrNull()
                        }
                        "plot" -> if (plot == null) plot = p.nextTextSafe()
                        "outline" -> if (plot == null) plot = p.nextTextSafe()
                        "genre" -> p.nextTextSafe()?.let { g ->
                            // 有的刮削器一个 genre 里塞多个（Archos addGenreIfAbsent 同款分隔符）
                            g.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }.forEach(genres::add)
                        }
                        "director" -> p.nextTextSafe()?.let { d ->
                            d.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }.forEach(directors::add)
                        }
                        "studio" -> p.nextTextSafe()?.let { s ->
                            s.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }.forEach(studios::add)
                        }
                        "mpaa" -> if (contentRating == null) contentRating = p.nextTextSafe()
                        "name" -> if (inActor && currentActorName == null) currentActorName = p.nextTextSafe()
                        "id" -> {
                            // Archos：<id> 是 imdb；值为 tt 开头才算 imdb id，别把 tmdb 数字 id 误收
                            val v = p.nextTextSafe()?.trim()
                            if (v?.startsWith("tt") == true && tmdbid == null) {
                                // imdb id 不用于聚类键，仅占位：这里不写 tmdbid
                            }
                        }
                        "tmdbid" -> {
                            val v = p.nextTextSafe()?.trim()
                            if (!v.isNullOrBlank() && v != "0") tmdbid = v
                        }
                        "uniqueid" -> {
                            val type = p.getAttributeValue(null, "type")?.lowercase()
                            val value = p.nextTextSafe()?.trim()
                            if (type == "tmdb" && !value.isNullOrBlank() && value != "0") tmdbid = value
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "actor" -> {
                            inActor = false
                            currentActorName?.takeIf { it.isNotBlank() }?.let(actors::add)
                        }
                        "fanart" -> inFanart = false
                    }
                }
                event = p.next()
            }
        } catch (e: Exception) {
            // 见上：畸形处之后的字段读不到了，但已经收到的照常返回。
            //
            // ★ 这条日志不能省：**只有它能把"这个 nfo 本来就是空的"和"解析在中途断了"分开** ——
            //   两者返回的 NfoMeta 长得一模一样（都可能是全 null），没有日志就只能靠猜。
            //   2026-09-22 排查"元数据全是 null"时正是靠它才没走偏。
            android.util.Log.w("NfoParser", "nfo 结构畸形，已收字段照常返回：${e.message}")
        }
        return NfoMeta(
            title = title,
            year = year,
            rating = rating,
            plot = plot,
            genres = genres.distinct(),
            actors = actors.distinct(),
            directors = directors.distinct(),
            studios = studios.distinct(),
            contentRating = contentRating,
            uniqueTmdbid = tmdbid,
            season = season,
            episode = episode,
            showTitle = showTitle,
        )
    }

    /** nextText() 在空节点/异常时可能抛错；用容错版本读正文，读完保证停在 END_TAG */
    private fun XmlPullParser.nextTextSafe(): String? = try {
        val text = nextText()
        // nextText 后已消费 END_TAG，推进到下一个事件
        next()
        text
    } catch (_: Exception) {
        null
    }
}
