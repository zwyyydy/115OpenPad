package com.open115.pad.data.media

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/** Emby .nfo 解析结果：只提取海报墙和详情页需要的字段 */
data class NfoMeta(
    val title: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val uniqueTmdbid: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * 轻量 .nfo XML 解析（平台自带 XmlPullParser，零新依赖）。
 * 字段降级容错：任何一处缺失/类型异常都跳过该字段而不是整个解析失败；
 * nfo 根节点可能是 <movie> / <episodedetails> / <tvshow>，统一按字段名读取。
 */
object NfoParser {
    fun parse(reader: Reader): NfoMeta {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(reader)
        var title: String? = null
        var year: Int? = null
        var rating: Double? = null
        var plot: String? = null
        val genres = mutableListOf<String>()
        val actors = mutableListOf<String>()
        var tmdbid: String? = null
        var season: Int? = null
        var episode: Int? = null

        var inActor = false
        var currentActorName: String? = null

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "actor" -> {
                        inActor = true
                        currentActorName = null
                    }
                    "title" -> if (!inActor) title = title ?: p.nextTextSafe()
                    "year" -> year = year ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                    "season" -> season = season ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                    "episode" -> episode = episode ?: p.nextTextSafe()?.trim()?.toIntOrNull()
                    "premiered", "releasedate" ->
                        if (year == null) year = p.nextTextSafe()?.trim()?.take(4)?.toIntOrNull()
                    "rating" -> {
                        // Emby 的 rating 可能带属性（name/type/default），只取正文首个数值
                        if (rating == null) rating = p.nextTextSafe()?.trim()?.toDoubleOrNull()
                    }
                    "plot" -> if (plot == null) plot = p.nextTextSafe()
                    "genre" -> p.nextTextSafe()?.let { g ->
                        // 有的刮削器一个 genre 里塞多个（| 或 / 分隔）
                        g.split('|', '/').map { it.trim() }.filter { it.isNotEmpty() }.forEach(genres::add)
                    }
                    "name" -> if (inActor && currentActorName == null) currentActorName = p.nextTextSafe()
                    "uniqueid" -> {
                        val type = p.getAttributeValue(null, "type")?.lowercase()
                        val value = p.nextTextSafe()?.trim()
                        if (type == "tmdb" && !value.isNullOrBlank()) tmdbid = value
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "actor") {
                    inActor = false
                    currentActorName?.takeIf { it.isNotBlank() }?.let(actors::add)
                }
            }
            event = p.next()
        }
        return NfoMeta(
            title = title,
            year = year,
            rating = rating,
            plot = plot,
            genres = genres.distinct(),
            actors = actors.distinct(),
            uniqueTmdbid = tmdbid,
            season = season,
            episode = episode,
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
