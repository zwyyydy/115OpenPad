package com.open115.pad.data.media

import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/**
 * Emby / Kodi / TinyMediaManager 的 .nfo 解析结果 —— **尽量全**：
 * 海报墙、详情页、检索用得上的字段都收，其余也一并留着（存成 JSON，见 MovieEntity.nfoJson）。
 *
 * 字段名与含义对齐 Kodi 的 movie.nfo / episodedetails / tvshow 三套约定（同一个标签在三种
 * 根节点下含义相同，所以这里不分根节点，统一按标签名收）。
 */
@Serializable
data class NfoMeta(
    // ---------------- 标题 ----------------
    val title: String? = null,
    /** 原名（`<originaltitle>`） */
    val originalTitle: String? = null,
    /** 排序名（`<sorttitle>`），海报墙排序用得上 */
    val sortTitle: String? = null,
    /** 分集所属剧名（`<showtitle>`） */
    val showTitle: String? = null,
    /** 标语（`<tagline>`） */
    val tagline: String? = null,

    // ---------------- 时间与评分 ----------------
    val year: Int? = null,
    /** 首播/上映日期原样（`<premiered>` / `<releasedate>` / `<aired>`，形如 `2024-05-17`） */
    val premiered: String? = null,
    /** 主评分（顶层 `<rating>`，或 `<ratings>` 块里的 imdb/tmdb 那条） */
    val rating: Double? = null,
    /** 评分人数（`<votes>`） */
    val votes: Int? = null,
    /** 媒体/影评人评分（`<criticrating>` 或 `<ratings>` 里的 critic/metacritic） */
    val criticRating: Double? = null,
    /** 个人评分（`<userrating>`） */
    val userRating: Double? = null,
    /** 榜单排名（`<top250>`） */
    val top250: Int? = null,
    /** 时长（分钟）：`<runtime>` 的 `90` / `90 min` / `01:32:00` 三种写法都认 */
    val runtimeMinutes: Int? = null,

    // ---------------- 文本 ----------------
    val plot: String? = null,
    val outline: String? = null,
    /** 分级（`<mpaa>`） */
    val contentRating: String? = null,

    // ---------------- 分类与归属 ----------------
    val genres: List<String> = emptyList(),
    /** Kodi 的 `<tag>`：与 genre 分开收（刮削器常把"系列/片商/发行"塞这儿，检索更细） */
    val tags: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /** 编剧（`<writer>` 与 Kodi 的 `<credits>`） */
    val writers: List<String> = emptyList(),
    /** 合集名（`<set><name>`），如「示例系列」 */
    val set: String? = null,
    val setOverview: String? = null,
    val trailer: String? = null,
    /** 媒体来源（`<source>`：bluray / webdl / hdtv…） */
    val source: String? = null,
    /** 加入媒体库时间（`<dateadded>`） */
    val dateAdded: String? = null,

    // ---------------- 演员 ----------------
    /** 演员名，按 nfo 里的顺序 */
    val actors: List<String> = emptyList(),
    /**
     * 角色名（`<actor><role>`），**与 [actors] 同下标一一对应**（没有的角色为空串）。
     * 拆成两个平行列表而不是一个对象列表：`actors` 已经被扫描器/DAO/测试用了一大片，
     * 换成对象要动一圈，而角色名只用在展示上。
     */
    val actorRoles: List<String> = emptyList(),

    // ---------------- 剧集 ----------------
    val season: Int? = null,
    val episode: Int? = null,
    /** 剧集状态（`<status>`：Continuing / Ended） */
    val status: String? = null,
    /** 电视台 / 播出方（`<network>`） */
    val network: String? = null,
    /** 季名（`<namedseason number="1">季名</namedseason>`） */
    val namedSeasons: Map<Int, String> = emptyMap(),

    // ---------------- 技术参数（`<fileinfo><streamdetails>`） ----------------
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoDurationSeconds: Int? = null,
    /** 画面比例（`<aspect>`） */
    val videoAspect: String? = null,
    /** 音轨编码（第一条） */
    val audioCodec: String? = null,
    /** 声道（第一条） */
    val audioChannels: String? = null,
    /** 全部音轨语言（去重） */
    val audioLanguages: List<String> = emptyList(),
    /** 全部字幕语言（去重） */
    val subtitleLanguages: List<String> = emptyList(),

    // ---------------- 外部 id ----------------
    val uniqueTmdbid: String? = null,
    val imdbId: String? = null,
    val tvdbId: String? = null,

    /**
     * 写这份结果时用的**解析器版本**（[NfoParser.PARSER_VERSION]）。
     *
     * ★ 默认 0 = "老数据/不知道"，由 [NfoParser.parse] 显式写成当前版本 —— 不能给成当前版本，
     *   否则旧 JSON 反序列化出来会自称是最新的，版本检查就废了。
     *   用途：解析字段集一变，存量数据靠它被认出来"需要重拉"（见 MediaScanner.needsNfoRefetch）。
     */
    val parserVersion: Int = 0,
) {
    /**
     * 到底解析出东西了吗。
     *
     * 用来把"这次没拉到"和"这个 nfo 本来就是空的"分开：两者都返回默认值 NfoMeta()，
     * 但前者**不能**当成功缓存下来（写进去等于把"拉不到"缓存住，详情页的自愈重试
     * 会一路命中这个空结果、再也不重试）。缓存按本判据给不同 TTL，见 MediaScanner。
     */
    val hasContent: Boolean
        get() = title != null || plot != null || rating != null || runtimeMinutes != null ||
            genres.isNotEmpty() || actors.isNotEmpty() || uniqueTmdbid != null ||
            countries.isNotEmpty() || tagline != null || videoCodec != null

    /** 演员 + 角色（给详情页展示用）：`[("示例演员", ""), ("John Doe", "示例角色")]` */
    val actorPairs: List<Pair<String, String>>
        get() = actors.mapIndexed { i, name -> name to actorRoles.getOrElse(i) { "" } }

    /** 时长文案：`128` → `2 小时 8 分`；不足一小时只写分钟 */
    val runtimeText: String?
        get() = runtimeMinutes?.takeIf { it > 0 }?.let { m ->
            if (m >= 60) "${m / 60} 小时${if (m % 60 > 0) " ${m % 60} 分" else ""}" else "$m 分钟"
        }

    /** 分辨率文案：`1920x1080` */
    val resolutionText: String?
        get() = if (videoWidth != null && videoHeight != null) "${videoWidth}×${videoHeight}" else null
}

/**
 * 轻量 .nfo XML 解析（平台自带 XmlPullParser，零新依赖）。
 *
 * 规则对齐 Archos MediaScraper（aos-MediaLib NfoParser + NfoMovieHandler/NfoEpisodeHandler/NfoShowHandler）
 * 与 Kodi 的 nfo 约定：
 * - 根节点 `<movie>` / `<episodedetails>` / `<tvshow>`，统一按字段名读取（Archos 按根节点分三个
 *   handler，本项目场景字段集合并即可）
 * - movie.nfo / 文件名.nfo / tvshow.nfo 的文件定位规则在 MediaScanner 的 Cluster 阶段完成，这里只管解析
 * - `genre` / `director` / `studio` / `country` / `tag` / `writer` 按 `|` `,` `/` 分隔拆开
 *   （Archos STRING_SPLITTERS：有的刮削器一个标签里塞多个）
 * - **分区块读**：`<ratings>`（Emby 的分项评分）、`<set>`（合集）、`<fileinfo><streamdetails>`
 *   （技术参数）、`<actor>`（演员）各自有嵌套的同名子标签（比如到处都是 `<name>`），
 *   靠进入/离开标记区分归属 —— 只看标签名会把合集名当成演员名
 * - 字段降级容错：任何一处缺失/类型异常都跳过该字段而不是整个解析失败
 */
object NfoParser {
    /**
     * 解析器版本：**字段集/取值规则一变就 +1**。
     *
     * 一个数字管两件事：
     *  - nfo 落盘缓存的 key（`nfo|v<版本>|…`）—— 旧缓存里的结果字段不全，不能继续命中；
     *  - 存量数据的重拉判据（[NfoMeta.parserVersion] 比它小 = 该重拉）。
     *
     * v2：从 13 个字段补到 47 个（原名/标语/时长/国家/语言/编剧/合集/技术参数/角色名…）
     * v3：修"读完文本多推一次事件"（紧凑 XML 隔一个丢一个）+ 评分归一化（百分制折算、票数脏值丢弃）
     * v4：解析字段没变，但**落库的形状变了** —— 首映日期/入库时间从 JSON 提到独立列（DB v12），
     *     存量数据要靠版本比较被认出来重拉一次才填得上
     */
    const val PARSER_VERSION = 4

    /** Archos NfoParser.STRING_SPLITTERS */
    private val STRING_SPLITTERS = charArrayOf('|', ',', '/')

    fun parse(reader: Reader): NfoMeta {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(reader)

        var title: String? = null
        var originalTitle: String? = null
        var sortTitle: String? = null
        var showTitle: String? = null
        var tagline: String? = null
        var year: Int? = null
        var premiered: String? = null
        var rating: Double? = null
        var votes: Int? = null
        var criticRating: Double? = null
        var userRating: Double? = null
        var top250: Int? = null
        var runtimeMinutes: Int? = null
        var plot: String? = null
        var outline: String? = null
        var contentRating: String? = null
        val genres = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val countries = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val studios = mutableListOf<String>()
        val directors = mutableListOf<String>()
        val writers = mutableListOf<String>()
        var set: String? = null
        var setOverview: String? = null
        var trailer: String? = null
        var source: String? = null
        var dateAdded: String? = null
        val actorNames = mutableListOf<String>()
        val actorRoles = mutableListOf<String>()
        var season: Int? = null
        var episode: Int? = null
        var status: String? = null
        var network: String? = null
        val namedSeasons = linkedMapOf<Int, String>()
        var videoCodec: String? = null
        var videoWidth: Int? = null
        var videoHeight: Int? = null
        var videoDurationSeconds: Int? = null
        var videoAspect: String? = null
        var audioCodec: String? = null
        var audioChannels: String? = null
        val audioLanguages = mutableListOf<String>()
        val subtitleLanguages = mutableListOf<String>()
        var tmdbid: String? = null
        var imdbId: String? = null
        var tvdbId: String? = null

        // 分区块标记：同名子标签靠它们区分归属（<name> 出现在 actor/set/studio 三种地方）
        var inFanart = false
        var inActor = false
        var inSet = false
        var inRatings = false
        var inStreams = false
        var inVideo = false
        var inAudio = false
        var inSubtitle = false
        var currentActorName: String? = null
        var currentActorRole: String? = null

        /** 离开某个区块：复位标记、收尾演员条目。循环与 [readText] 都要调（见下） */
        fun endTag(name: String) {
            when (name) {
                "fanart" -> inFanart = false
                "ratings" -> inRatings = false
                "set" -> inSet = false
                "streamdetails" -> inStreams = false
                "video" -> inVideo = false
                "audio" -> inAudio = false
                "subtitle" -> inSubtitle = false
                "actor" -> {
                    inActor = false
                    currentActorName?.takeIf { it.isNotBlank() }?.let { n ->
                        actorNames += n
                        actorRoles += currentActorRole.orEmpty().trim()
                    }
                    currentActorName = null
                    currentActorRole = null
                }
            }
        }

        /**
         * 读一个元素的正文。
         *
         * ★★ `nextText()` 读完**停在 END_TAG**，所以这里必须**立刻**把这个 END_TAG 处理掉 ——
         *   不能像早先那样再 `next()` 一次：元素之间**有换行**时多推的那次恰好吃掉空白文本事件、
         *   看不出问题；而**紧凑 XML（`<a>1</a><b>2</b>` 没有空白）会隔一个丢一个**
         *   （实测：`<video>` 里 width 读到了、height 是 null，演员的角色名全丢）。
         *   真机上的 nfo 都是带换行的，所以这个坑一直没暴露 —— 单测里用紧凑 XML 才现形。
         */
        fun readText(): String? = try {
            val text = p.nextText()
            endTag(p.name.lowercase())
            text
        } catch (_: Exception) {
            null
        }

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
                    XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                        "fanart" -> inFanart = true
                        "ratings" -> inRatings = true
                        "set" -> inSet = true
                        "streamdetails" -> inStreams = true
                        "video" -> inVideo = inStreams
                        "audio" -> inAudio = inStreams
                        "subtitle" -> inSubtitle = inStreams
                        "actor" -> {
                            inActor = true
                            currentActorName = null
                            currentActorRole = null
                        }
                        "title" -> if (!inActor && !inSet && title == null) title = readText()
                        "originaltitle" -> originalTitle = originalTitle ?: readText()
                        "sorttitle" -> sortTitle = sortTitle ?: readText()
                        "showtitle" -> showTitle = showTitle ?: readText()
                        "tagline" -> tagline = tagline ?: readText()
                        "year" -> year = year ?: readText()?.trim()?.toIntOrNull()
                        "season" -> season = season ?: readText()?.trim()?.toIntOrNull()
                        "episode" -> episode = episode ?: readText()?.trim()?.toIntOrNull()
                        "status" -> status = status ?: readText()
                        "network" -> network = network ?: readText()
                        "namedseason" -> {
                            val num = p.getAttributeValue(null, "number")?.trim()?.toIntOrNull()
                            val text = readText()?.trim()
                            if (num != null && !text.isNullOrBlank()) namedSeasons[num] = text
                        }
                        "premiered", "releasedate", "aired" -> {
                            val v = readText()?.trim()
                            if (v != null) {
                                if (premiered == null) premiered = v
                                if (year == null) year = v.take(4).toIntOrNull()
                            }
                        }
                        "dateadded" -> dateAdded = dateAdded ?: readText()
                        "rating" -> {
                            // 顶层 <rating> 是主评分（可能带 name/type/default 属性）；
                            // <ratings> 块里的是分项，按 name 分派（Emby 新格式）
                            val name = p.getAttributeValue(null, "name")?.lowercase()
                            val v = normRating(readText(), p.getAttributeValue(null, "max"))
                            if (inRatings) {
                                when (name) {
                                    "critic", "metacritic", "rottentomatoes" ->
                                        criticRating = criticRating ?: v
                                    "user", "audience" -> userRating = userRating ?: v
                                    // imdb/tmdb 是主评分的另一种写法：顶层没有才用
                                    else -> if (rating == null) rating = v
                                }
                            } else if (rating == null) {
                                rating = v
                            }
                        }
                        "criticrating" -> criticRating = criticRating ?: normRating(readText(), null)
                        "userrating" -> userRating = userRating ?: normRating(readText(), null)
                        "votes" -> votes = votes ?: readText()?.trim()?.toIntOrNull()
                        "top250" -> top250 = top250 ?: readText()?.trim()?.toIntOrNull()
                        "runtime" -> if (runtimeMinutes == null) {
                            runtimeMinutes = parseRuntimeMinutes(readText())
                        }
                        "plot" -> if (plot == null) plot = readText()
                        "outline" -> if (plot == null) plot = readText()
                        "mpaa" -> if (contentRating == null) contentRating = readText()
                        "genre" -> readText()?.let { g ->
                            // 有的刮削器一个 genre 里塞多个（Archos addGenreIfAbsent 同款分隔符）
                            g.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(genres::add)
                        }
                        "tag" -> readText()?.let { t ->
                            t.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(tags::add)
                        }
                        "country" -> readText()?.let { c ->
                            c.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(countries::add)
                        }
                        "language" -> {
                            // 含义随所在区块变：音轨语言 / 字幕语言 / 影片语言
                            val v = readText()?.trim()
                            when {
                                inAudio -> if (!v.isNullOrBlank()) audioLanguages += v
                                inSubtitle -> if (!v.isNullOrBlank()) subtitleLanguages += v
                                !inStreams -> if (!v.isNullOrBlank()) {
                                    v.split(*STRING_SPLITTERS).map { it.trim() }
                                        .filter { it.isNotEmpty() }.forEach(languages::add)
                                }
                            }
                        }
                        "studio" -> readText()?.let { s ->
                            s.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(studios::add)
                        }
                        "director" -> readText()?.let { d ->
                            d.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(directors::add)
                        }
                        // Kodi 把编剧写成 <credits>，Emby 写 <writer>
                        "writer", "credits" -> readText()?.let { w ->
                            w.split(*STRING_SPLITTERS).map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(writers::add)
                        }
                        "trailer" -> trailer = trailer ?: readText()
                        "source" -> source = source ?: readText()
                        "codec" -> when {
                            inVideo -> videoCodec = videoCodec ?: readText()?.trim()
                            inAudio -> audioCodec = audioCodec ?: readText()?.trim()
                            else -> readText()
                        }
                        "width" -> if (inVideo) videoWidth = readText()?.trim()?.toIntOrNull() else readText()
                        "height" -> if (inVideo) videoHeight = readText()?.trim()?.toIntOrNull() else readText()
                        "aspect" -> if (inVideo) videoAspect = readText()?.trim() else readText()
                        "durationinseconds" -> if (inVideo) {
                            videoDurationSeconds = readText()?.trim()?.toIntOrNull()
                        } else {
                            readText()
                        }
                        "channels" -> if (inAudio) audioChannels = audioChannels ?: readText()?.trim() else readText()
                        "id" -> {
                            // Archos：<id> 是 imdb（值为 tt 开头才算）
                            val v = readText()?.trim()
                            if (v?.startsWith("tt") == true) imdbId = imdbId ?: v
                        }
                        "tmdbid" -> {
                            val v = readText()?.trim()
                            if (!v.isNullOrBlank() && v != "0") tmdbid = v
                        }
                        "imdbid" -> imdbId = imdbId ?: readText()?.trim()
                        "tvdbid" -> tvdbId = tvdbId ?: readText()?.trim()
                        "uniqueid" -> {
                            val type = p.getAttributeValue(null, "type")?.lowercase()
                            val value = readText()?.trim()
                            if (!value.isNullOrBlank() && value != "0") when (type) {
                                "tmdb" -> tmdbid = value
                                "imdb" -> imdbId = imdbId ?: value
                                "tvdb" -> tvdbId = tvdbId ?: value
                            }
                        }
                        // 这三个标签的含义随所在区块变：演员名 / 合集名 / 音轨语言
                        "name" -> when {
                            inActor && currentActorName == null -> currentActorName = readText()
                            inSet -> {
                                set = set ?: readText()
                            }
                            else -> readText()
                        }
                        "role" -> if (inActor) {
                            currentActorRole = currentActorRole ?: readText()
                        } else {
                            readText()
                        }
                        "overview" -> if (inSet) setOverview = setOverview ?: readText() else readText()
                    }
                    XmlPullParser.END_TAG -> endTag(p.name.lowercase())
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

        // 音轨/字幕语言是在 <audio>/<subtitle> 块里收的，出块时才知道归哪类
        return NfoMeta(
            title = title,
            originalTitle = originalTitle,
            sortTitle = sortTitle,
            showTitle = showTitle,
            tagline = tagline,
            year = year,
            premiered = premiered,
            rating = rating,
            votes = votes,
            criticRating = criticRating,
            userRating = userRating,
            top250 = top250,
            runtimeMinutes = runtimeMinutes,
            plot = plot,
            outline = outline,
            contentRating = contentRating,
            genres = genres.distinct(),
            tags = tags.distinct(),
            countries = countries.distinct(),
            languages = languages.distinct(),
            studios = studios.distinct(),
            directors = directors.distinct(),
            writers = writers.distinct(),
            set = set,
            setOverview = setOverview,
            trailer = trailer,
            source = source,
            dateAdded = dateAdded,
            actors = actorNames.distinct(),
            actorRoles = actorRoles,
            season = season,
            episode = episode,
            status = status,
            network = network,
            namedSeasons = namedSeasons,
            videoCodec = videoCodec,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoDurationSeconds = videoDurationSeconds,
            videoAspect = videoAspect,
            audioCodec = audioCodec,
            audioChannels = audioChannels,
            audioLanguages = audioLanguages.distinct(),
            subtitleLanguages = subtitleLanguages.distinct(),
            uniqueTmdbid = tmdbid,
            imdbId = imdbId,
            tvdbId = tvdbId,
            parserVersion = PARSER_VERSION,
        )
    }

    /**
     * 评分归一化到 **0~10** 并做合理性判断。
     *
     * 两个实测坑：
     *  - 满分数不一定是 10：有的刮削器按 100 分制写（`<rating max="100">78</rating>`），
     *    直接当 10 分制显示就是 "78.0" —— 按 `max` 折算回来；
     *  - **票数被写进评分槽**：实测某库的 `<rating name="critic">7029</rating>`，
     *    折算后仍然超范围 → 当成"这不是评分"丢掉（宁可没有，也别在详情页写"媒体评分 7029"）。
     */
    internal fun normRating(raw: String?, maxAttr: String?): Double? {
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        val max = maxAttr?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
        return (v * 10.0 / max).takeIf { it in 0.1..10.0 }
    }

    /**
     * 时长文案 → 分钟。三种实测写法：
     * `90`（Kodi，就是分钟）、`90 min` / `90 mins`、`01:32:00`（时分秒）。
     * 认不出来返回 null（不猜）。
     */
    internal fun parseRuntimeMinutes(raw: String?): Int? {
        val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // HH:MM:SS / MM:SS
        if (s.contains(':')) {
            val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
            if (parts.isEmpty()) return null
            return when (parts.size) {
                3 -> parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
                2 -> parts[0] + if (parts[1] >= 30) 1 else 0
                else -> null
            }
        }
        // "1h 32m" / "1h" / "32m"
        if (s.any { it.isLetter() }) {
            val h = Regex("""(\d+)\s*h""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("""(\d+)\s*m""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = h * 60 + m
            return total.takeIf { it > 0 }
        }
        return s.toIntOrNull()?.takeIf { it > 0 }
    }
}
