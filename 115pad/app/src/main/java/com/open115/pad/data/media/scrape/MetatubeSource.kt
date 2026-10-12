package com.open115.pad.data.media.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP 服务源：一个跑在服务端的元数据聚合服务，App 只当一个 HTTP 客户端。
 *
 * 插件的另一种形态 —— 代码跑在服务器上（多源聚合：fanza/javbus/caribbean… 20+ 站点），
 * App 只按它的 REST 协议当 HTTP 客户端：`/v1/movies/search?q=` 搜候选、
 * `/v1/movies/{provider}/{id}` 取详情、详情里的 `preview_images` 当剧照。
 * 字段与 [ScrapedMetadata] 的对齐关系：summary→plot / maker→studios / series→set /
 * release_date→premiered / score→rating / cover→poster / thumb→fanart / preview_video→trailer。
 *
 * 服务端设了 `-token` 时走 `Authorization: Bearer`（图片端点不受鉴权管，直接下）。
 * 没配基址 = 这个源不存在（[configured] 为 false，注册表不会把它列进去）。
 */
class MetatubeSource(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val token: () -> String = { "" },
) : MetadataSource {
    override val id: String = SOURCE_ID
    override val displayName: String = "HTTP 服务源"
    override val apiVersion: Int = 1

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun configured(): Boolean = baseUrl().isNotBlank()

    /** 检索词用番号优先（番号类源的主键就是它），没有番号退回片名 */
    override suspend fun search(query: ScrapeQuery): List<ScrapeCandidate> {
        val q = query.number?.takeIf { it.isNotBlank() } ?: query.title
        val root = getJson("/v1/movies/search", mapOf("q" to q, "fallback" to "true"))
        return parseSearchResponse(root)
    }

    override suspend fun fetch(candidate: ScrapeCandidate): ScrapedMetadata {
        val (provider, mid) = splitCandidateId(candidate.id)
        val root = getJson("/v1/movies/$provider/$mid", emptyMap())
        return parseInfoResponse(root, provider, mid, candidate.title, candidate.kind)
    }

    /** 剧照 = 详情里的 preview_images（站点截图序列）。多一次详情请求，服务端有库缓存 */
    override suspend fun fetchBackdropUrls(candidate: ScrapeCandidate, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val (provider, mid) = splitCandidateId(candidate.id)
        val root = runCatching { getJson("/v1/movies/$provider/$mid", emptyMap()) }.getOrNull() ?: return emptyList()
        return parseBackdropUrls(root, max)
    }

    /**
     * 服务端公共节流：这个源背后是"真实站点"（会被频控封 IP），跟 TMDB 的 pace 同理串行 + 间隔。
     * 搜索/详情/剧照都走它；图片字节不走（服务端已经聚合好 URL）。
     */
    private val paceLock = Mutex()
    private var lastRequestAt = 0L

    private suspend fun pace() {
        paceLock.withLock {
            val wait = lastRequestAt + PACE_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private suspend fun getJson(path: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        pace()
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) throw ScrapeException(ScrapeFailure.RequestError("服务源地址是空的（设置 → 实验室）"))
        // path 段（provider/id）都是字母数字与连字符，直接拼；查询参数走 addQueryParameter 转义
        val url = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw ScrapeException(ScrapeFailure.RequestError("服务源地址不合法：$base"))

        val t = token().trim()
        val req = Request.Builder().url(url).get().apply {
            if (t.isNotEmpty()) header("Authorization", "Bearer $t")
        }.build()

        val (code, body) = client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string().orEmpty())
        }
        when {
            // 服务端把"查无此片"表达成 404；只有它可以换下一个源
            code == 404 -> throw ScrapeException(ScrapeFailure.NotFound("服务源没有这条记录（404）"))
            code == 401 -> throw ScrapeException(ScrapeFailure.RequestError("服务源拒绝了 token（401）"))
            code !in 200..299 -> throw ScrapeException(
                ScrapeFailure.RequestError("服务源返回 HTTP $code${body.take(160).ifBlank { "" }}"),
            )
        }
        if (body.isBlank()) throw ScrapeException(ScrapeFailure.RequestError("服务源返回了空响应"))
        body
    }

    private fun splitCandidateId(raw: String): Pair<String, String> {
        val p = raw.substringBefore(':', missingDelimiterValue = "")
        val mid = raw.substringAfter(':', missingDelimiterValue = "")
        if (p.isBlank() || mid.isBlank()) {
            throw ScrapeException(ScrapeFailure.RequestError("服务源候选 id 不合法：$raw"))
        }
        return p to mid
    }

    // ---------------- 解析（internal 纯函数，单测直接喂 JSON，不走网络） ----------------

    /**
     * 服务端 gin 层把所有响应包成 `{"data": ...}` 信封（实测 curl 如此），这里优先按信封解、
     * 解不动再退回裸数组/裸对象（兼容直接反代裸协议的实现）。两层都失败才算"无法解析"，
     * 报错里带上响应开头，别再把唯一线索吞掉。
     */
    internal fun parseSearchResponse(root: String): List<ScrapeCandidate> {
        val resp = runCatching { json.decodeFromString<MtSearchEnvelope>(root).data }
            .getOrElse { runCatching { json.decodeFromString<MtSearchList>(root) }.getOrElse {
                throw ScrapeException(ScrapeFailure.RequestError("服务源返回无法解析：${it.message}｜响应开头：${root.take(160)}"))
            } }
        return resp.filter { it.provider.isNotBlank() && it.id.isNotBlank() && it.title.isNotBlank() }.map { m ->
            ScrapeCandidate(
                sourceId = id,
                // 拉详情要 provider + id 两段，拼在一起当源内主键（两边都不会含冒号）
                id = m.provider + ":" + m.id,
                title = m.title,
                year = m.release_date?.takeIfDate()?.take(4)?.toIntOrNull(),
                posterUrl = m.thumb_url?.takeIf { it.isNotBlank() },
                rating = m.score?.takeIf { it > 0.0 },
                number = m.number?.takeIf { it.isNotBlank() },
            )
        }
    }

    internal fun parseInfoResponse(
        root: String,
        provider: String,
        mid: String,
        fallbackTitle: String,
        kind: ScrapeKind,
    ): ScrapedMetadata {
        val m = runCatching { json.decodeFromString<MtMovieEnvelope>(root).data }
            .getOrElse { runCatching { json.decodeFromString<MtMovie>(root) }.getOrElse {
                throw ScrapeException(ScrapeFailure.RequestError("服务源返回无法解析：${it.message}｜响应开头：${root.take(160)}"))
            } }
        return ScrapedMetadata(
            sourceId = id,
            sourceKey = "$SOURCE_ID-$provider-$mid",
            kind = kind,
            title = m.title.ifBlank { fallbackTitle },
            plot = m.summary?.takeIf { it.isNotBlank() },
            year = m.release_date?.takeIfDate()?.take(4)?.toIntOrNull(),
            premiered = m.release_date?.takeIfDate(),
            runtimeMinutes = m.runtime?.takeIf { it > 0 },
            rating = m.score?.takeIf { it > 0.0 },
            genres = m.genres.orEmpty().filter { it.isNotBlank() },
            studios = listOfNotNull(m.maker?.takeIf { it.isNotBlank() }),
            directors = (m.director ?: "").split(',', '、').map { it.trim() }.filter { it.isNotBlank() },
            set = m.series?.takeIf { it.isNotBlank() },
            // 站点的预览视频当预告片（nfo 的 <trailer> 认 URL）
            trailer = m.preview_video_url?.takeIf { it.isNotBlank() },
            actors = m.actors.orEmpty().filter { it.isNotBlank() }.map { ScrapedActor(it) },
            // FANZA 数字版 big_cover_url 常是空串（不是 null），elvis 挡不住空串——按"第一个非空"取
            posterUrl = firstNotBlank(m.big_cover_url, m.cover_url),
            fanartUrl = firstNotBlank(m.big_thumb_url, m.thumb_url),
        )
    }

    internal fun parseBackdropUrls(root: String, max: Int): List<String> {
        val m = runCatching { json.decodeFromString<MtMovieEnvelope>(root).data }
            .getOrElse { runCatching { json.decodeFromString<MtMovie>(root) }.getOrNull() } ?: return emptyList()
        return m.preview_images.orEmpty().filter { it.isNotBlank() }.take(max)
    }

    private fun firstNotBlank(vararg urls: String?): String? =
        urls.firstOrNull { !it.isNullOrBlank() }

    /** 日期字段容忍两种写法：`2006-01-02` 与 RFC3339（`2006-01-02T…`）；别的形状一律当没有 */
    private fun String.takeIfDate(): String? =
        takeIf { it.length >= 10 && it[4] == '-' && it[7] == '-' }?.take(10)

    companion object {
        const val SOURCE_ID = "metatube"

        /** 服务端背后是真实站点，保守节流（防站点侧封 IP，与全局 115 限速无关） */
        private const val PACE_INTERVAL_MS = 500L
    }
}

// ---------------- JSON 模型（服务端字段是 snake_case，只收用得上的） ----------------

/** 服务端把所有响应包在 `{"data": ...}` 里（gin 层统一信封，实测确认） */
@Serializable
private data class MtSearchEnvelope(val data: List<MtSearchResult> = emptyList())

@Serializable
private data class MtMovieEnvelope(val data: MtMovie)

private typealias MtSearchList = List<MtSearchResult>

@Serializable
private data class MtSearchResult(
    val id: String = "",
    val number: String? = null,
    val title: String = "",
    val provider: String = "",
    val thumb_url: String? = null,
    val cover_url: String? = null,
    val score: Double? = null,
    val release_date: String? = null,
)

@Serializable
private data class MtMovie(
    val id: String = "",
    val number: String? = null,
    val title: String = "",
    val summary: String? = null,
    val provider: String = "",
    val director: String? = null,
    val actors: List<String>? = null,
    val thumb_url: String? = null,
    val big_thumb_url: String? = null,
    val cover_url: String? = null,
    val big_cover_url: String? = null,
    val preview_video_url: String? = null,
    val preview_images: List<String>? = null,
    val maker: String? = null,
    val series: String? = null,
    val genres: List<String>? = null,
    val score: Double? = null,
    val runtime: Int? = null,
    val release_date: String? = null,
)
