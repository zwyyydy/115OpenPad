package com.open115.pad.data.media.scrape

/** 刮的是电影还是剧集：决定查 TMDB 的 /movie 还是 /tv，以及写 movie.nfo 还是 tvshow.nfo */
enum class ScrapeKind { MOVIE, TV }

/** 用户在 App 内建/导入的一个 JS 元数据源（存 DataStore）。[enabled]=false 时不进注册表。 */
@kotlinx.serialization.Serializable
data class JsSource(
    val id: String,
    val name: String,
    val code: String,
    val enabled: Boolean = true,
)

/**
 * 元数据刮削：**宿主拥有全部 IO，源只输出纯数据**。
 *
 * 这一层是插件化的地基（第一版只内置 TMDB）：界面/落库/写回云盘/重扫都由宿主做，
 * [MetadataSource] 的实现只负责"给我检索词、还我结构化数据"。以后加"用户自写的源"
 * 时，换的是这一层的实现，上下的链路一行都不用动。
 *
 * 协议里从 v1 就带上的几条（都是别处踩出来的教训，见各字段注释）：
 *  - [MetadataSource.apiVersion] 放最前，宿主按版本拒绝不认识的源；
 *  - 候选**必须带自己回报的编号**（番号类源），宿主拿它跟请求的编号**回读校验**；
 *  - 失败分 [ScrapeFailure.NotFound] / [ScrapeFailure.RequestError] 两层：
 *    只有 NotFound 才允许自动换下一个源，RequestError（源挂了/网络不通）要停下来报错。
 */
interface MetadataSource {
    /** 稳定 id（`tmdb`）；以后写进记录用 */
    val id: String

    /** 界面上显示的名字 */
    val displayName: String

    /** 插件协议版本。第一版恒 1；宿主按它决定认不认这个源 */
    val apiVersion: Int

    /** 检索词 → 候选列表（空列表 = 这个源里没有，算 NotFound 语义） */
    suspend fun search(query: ScrapeQuery): List<ScrapeCandidate>

    /** 候选 → 完整元数据 */
    suspend fun fetch(candidate: ScrapeCandidate): ScrapedMetadata

    /**
     * 剧照（可选能力）：给"按赞数取前 [max] 张"的背景图 URL 列表。
     *
     * 默认空 = 这个源没有剧照可刮 —— 编排层据此跳过，宿主不关心底下是谁。
     */
    suspend fun fetchBackdropUrls(candidate: ScrapeCandidate, max: Int): List<String> = emptyList()

    /**
     * 某一季的分集（可选能力，剧集源用）：季号 → 该集全部分集。没有返回 null。
     *
     * 电影源不实现（默认 null）；宿主拿本地文件名解析出的季/集号来要对应季，再逐集配。
     */
    suspend fun fetchSeason(candidate: ScrapeCandidate, season: Int): ScrapedSeason? = null
}

/** 一集（写 `episodedetails` nfo 的数据源） */
data class ScrapedEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String? = null,
    /** `yyyy-MM-dd` */
    val aired: String? = null,
    val tmdbId: String? = null,
)

/** 一季 */
data class ScrapedSeason(val season: Int, val name: String?, val episodes: List<ScrapedEpisode>)

/** 一次刮削检索的输入。番号类源只用 [number]，片名类源（TMDB）用 [title]/[year] */
data class ScrapeQuery(
    val title: String = "",
    val year: Int? = null,
    /** 番号（如 `ABC-301`）：番号类源的主检索键，TMDB 这类不用 */
    val number: String? = null,
    /** 查电影还是剧集库（TMDB 两套） */
    val kind: ScrapeKind = ScrapeKind.MOVIE,
)

/** 候选：够铺一张卡片的信息（海报是缩略图 URL） */
data class ScrapeCandidate(
    val sourceId: String,
    /** 源内的 id（TMDB 是 movie id）；fetch 时原样回传 */
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val rating: Double? = null,
    /** 这条候选是电影还是剧集（fetch 时原样回传给源，决定查 /movie 还是 /tv） */
    val kind: ScrapeKind = ScrapeKind.MOVIE,
    /**
     * 源自己回报的编号。**宿主会拿它跟请求的编号回读校验** —— 防"插件返回错配数据"
     * （搜 A 番号却回了 B 的元数据，自动模式下会静默写错片）。
     */
    val number: String? = null,
)

/** 刮到的元数据。字段与 [com.open115.pad.data.media.NfoMeta] 对齐（写 nfo 就是照这份映射） */
data class ScrapedMetadata(
    val sourceId: String,
    /** 源内主键（如 `tmdb-603`），用于将来去重/记录 */
    val sourceKey: String,
    /** 电影还是剧集（决定写 movie.nfo 还是 tvshow.nfo） */
    val kind: ScrapeKind = ScrapeKind.MOVIE,
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String? = null,
    val tagline: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    /** `yyyy-MM-dd` */
    val premiered: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    /** 合集名（写 `<set><name>`） */
    val set: String? = null,
    val trailer: String? = null,
    val actors: List<ScrapedActor> = emptyList(),
    /** 外部 id：写进 `<uniqueid type="tmdb">` / `<tmdbid>`，也让扫描器的 mediaKey 变成 `tmdb-<id>` */
    val tmdbId: String? = null,
    val imdbId: String? = null,
    /** 竖版海报原图 URL（w500 档即可） */
    val posterUrl: String? = null,
    /** 横版背景图 URL（w1280 档，写回为 `<前缀>-fanart.jpg`） */
    val fanartUrl: String? = null,
)

data class ScrapedActor(val name: String, val role: String? = null)

/**
 * 一次"要刮哪部片"的准备结果：写回需要的全部信息 + 检索词预填。
 *
 * 两个入口都构造它，共用同一套对话框与写回逻辑：
 *  - **媒体库详情页**：从 `MovieEntity` 映射（[scrapePlanOfMovie]）；
 *  - **文件页**：给一个云盘目录（或目录里的一个视频文件），先列目录找出视频再构造
 *    （[MetadataScraper.planFromDirectory]）—— 那边没有 DB 行，不知道有没有 nfo，
 *    所以"已有 nfo"的判据要现读目录列表。
 */
data class ScrapePlan(
    /** 只用于界面匹配写入状态：媒体库 = mediaKey；文件页 = `dir:<cid>` 这类合成键 */
    val key: String,
    /** 检索词预填（清洗前） */
    val title: String,
    val year: Int? = null,
    val videoName: String,
    val dirCid: String,
    /** 媒体库写法（从库根起的相对路径）；不在任何库里也要填 —— 写回只需要它来判"要不要排重扫" */
    val dirPath: String,
    val isEpisodeLike: Boolean = false,
    /** 覆盖模式要一并回收的旧 nfo file_id */
    val oldNfoFid: String? = null,
    /** 已有 nfo 时给界面看的覆盖提示（null = 没有） */
    val overwriteNote: String? = null,
    /** 这个目录在某个媒体库里吗（不在 = 写回后不会自动重扫入库，界面要说明） */
    val inLibrary: Boolean = true,
    /** 电影还是剧集（文件页按目录内容自动判；剧集写 tvshow.nfo 到该目录、不归位） */
    val kind: ScrapeKind = ScrapeKind.MOVIE,
)

/** 失败语义分层：只有 [NotFound] 才 fallback 到下一个源 */
sealed interface ScrapeFailure {
    data class NotFound(val message: String) : ScrapeFailure
    data class RequestError(val message: String) : ScrapeFailure
}

/**
 * 把异常变成给用户看的一句话。
 *
 * ★ **没有 message 的异常必须露出类型名**：`NetworkOnMainThreadException`、部分
 *   `ConnectException`/`SocketTimeoutException` 的 message 都是 null —— 早先统一显示
 *   "搜索失败"，等于把唯一线索丢了（2026-10-08 真机上就卡在这里）。
 */
fun scrapeErrorText(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

/**
 * 解析「代理地址」设置（设置 → 实验室）：给 TMDB 这条路单独走代理用 ——
 * `api.themoviedb.org` / `image.tmdb.org` 在国内直连不通（DNS 污染 + SNI），
 * 用户手上多半已经有能出去的代理（例如服务器上的 `host:20171`）。
 *
 * 接受这些写法：`host:port`、`http://host:port`、`socks5://host:port`、`socks://host:port`。
 * 返回 null = 直连或无法识别（**不抛异常**：设置项是自由文本，填错只该退回直连，
 * 别把整个刮削功能弄挂）。
 */
fun parseScrapeProxy(raw: String): java.net.Proxy? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    var type = java.net.Proxy.Type.HTTP
    when {
        s.startsWith("socks5://", ignoreCase = true) -> { type = java.net.Proxy.Type.SOCKS; s = s.substring(9) }
        s.startsWith("socks://", ignoreCase = true) -> { type = java.net.Proxy.Type.SOCKS; s = s.substring(8) }
        s.startsWith("http://", ignoreCase = true) -> s = s.substring(7)
        s.startsWith("https://", ignoreCase = true) -> s = s.substring(8)
    }
    s = s.trim().trimEnd('/')
    val host = s.substringBeforeLast(':', missingDelimiterValue = "")
    val port = s.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    if (host.isBlank() || port == null || port !in 1..65535) return null
    return runCatching { java.net.Proxy(type, java.net.InetSocketAddress(host, port)) }.getOrNull()
}

class ScrapeException(val failure: ScrapeFailure) : Exception(
    when (failure) {
        is ScrapeFailure.NotFound -> failure.message
        is ScrapeFailure.RequestError -> failure.message
    },
)
