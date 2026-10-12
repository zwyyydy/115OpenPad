package com.open115.pad.data.media.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TMDB 客户端：只做"HTTP + JSON 映射"，不做编排（编排在 [MetadataScraper]）。
 *
 * 几条刻意的取舍：
 *  - **基址、图片基址、key 都是"读的时候现取"的 lambda**：设置里改完立刻生效，
 *    不用重启，也和工程里其他"设置→内存镜像"的做法一致；
 *  - **认证两种写法都认**：v3 的 32 位 key 走 `api_key` 查询参数，v4 的 JWT（`eyJ` 开头）
 *    走 `Authorization: Bearer`。反代/中转站两种 key 都有人用；
 *  - **自己带节流**（默认 300ms 一次）：这个接口可能走用户自建反代，别把它打崩；
 *  - 用的必须是**独立于 115 的 OkHttpClient**（见 App115 里 newThirdPartyClient 的注释）。
 */
class TmdbClient(
    private val client: OkHttpClient,
    private val apiBase: () -> String,
    private val imageBase: () -> String,
    private val apiKey: () -> String,
    private val language: String = "zh-CN",
    private val intervalMs: Long = 300,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val paceLock = Mutex()
    private var lastRequestAt = 0L

    fun configured(): Boolean = apiKey().isNotBlank()

    /** 接口一次请求的公共节流：串行 + 间隔。图片走 [getBytes] 不占这里的额度 */
    private suspend fun pace() {
        paceLock.withLock {
            val wait = lastRequestAt + intervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /**
     * 按候选/详情里的 path 拼图片 URL。[size] 用 TMDB 的档位名（`w154` / `w500` / `w1280`）。
     * 图片基址可配（与接口地址同一个反代），path 以 `/` 开头。
     */
    fun imageUrl(path: String?, size: String = "w500"): String? {
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return null
        val base = imageBase().trim().trimEnd('/')
        if (base.isEmpty()) return null
        return "$base/t/p/$size${if (p.startsWith("/")) p else "/$p"}"
    }

    suspend fun searchMovies(title: String, year: Int? = null): List<ScrapeCandidate> {
        val root = getJson(
            path = "/3/search/movie",
            params = buildMap {
                put("query", title)
                if (year != null) put("year", year.toString())
                put("language", language)
                put("include_adult", "false")
            },
        )
        val resp = runCatching { json.decodeFromString<TmdbSearchResponse>(root) }.getOrElse { return emptyList() }
        return resp.results.map { m ->
            ScrapeCandidate(
                sourceId = TmdbMetadataSource.SOURCE_ID,
                id = m.id.toString(),
                title = m.title.ifBlank { m.original_title.orEmpty() },
                originalTitle = m.original_title,
                year = m.release_date?.take(4)?.toIntOrNull(),
                overview = m.overview,
                posterUrl = imageUrl(m.poster_path, "w154"),
                rating = m.vote_average,
            )
        }
    }

    /** 剧集搜索：`/search/tv`（字段名与电影不同：name/original_name/first_air_date） */
    suspend fun searchShows(title: String, year: Int? = null): List<ScrapeCandidate> {
        val root = getJson(
            path = "/3/search/tv",
            params = buildMap {
                put("query", title)
                if (year != null) put("first_air_date_year", year.toString())
                put("language", language)
                put("include_adult", "false")
            },
        )
        val resp = runCatching { json.decodeFromString<TmdbTvSearchResponse>(root) }.getOrElse { return emptyList() }
        return resp.results.map { s ->
            ScrapeCandidate(
                sourceId = TmdbMetadataSource.SOURCE_ID,
                id = s.id.toString(),
                title = s.name.ifBlank { s.original_name.orEmpty() },
                originalTitle = s.original_name,
                year = s.first_air_date?.take(4)?.toIntOrNull(),
                overview = s.overview,
                posterUrl = imageUrl(s.poster_path, "w154"),
                rating = s.vote_average,
                kind = ScrapeKind.TV,
            )
        }
    }

    suspend fun fetchDetail(id: String): ScrapedMetadata {
        val root = getJson(
            path = "/3/movie/$id",
            params = mapOf(
                "language" to language,
                "append_to_response" to "credits,videos",
            ),
        )
        val d = runCatching { json.decodeFromString<TmdbDetail>(root) }.getOrElse {
            throw ScrapeException(ScrapeFailure.RequestError("TMDB 返回无法解析：${it.message}"))
        }
        val directorJobs = setOf("Director")
        val writerJobs = setOf("Writer", "Screenplay", "Story")
        return ScrapedMetadata(
            sourceId = TmdbMetadataSource.SOURCE_ID,
            sourceKey = "tmdb-${d.id}",
            title = d.title.ifBlank { d.original_title.orEmpty() },
            originalTitle = d.original_title,
            tagline = d.tagline?.takeIf { it.isNotBlank() },
            plot = d.overview?.takeIf { it.isNotBlank() },
            year = d.release_date?.take(4)?.toIntOrNull(),
            premiered = d.release_date?.takeIf { it.length >= 10 },
            runtimeMinutes = d.runtime?.takeIf { it > 0 },
            rating = d.vote_average,
            votes = d.vote_count,
            genres = d.genres.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
            countries = d.production_countries.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
            languages = d.spoken_languages.mapNotNull {
                (it.english_name ?: it.name)?.takeIf { n -> n.isNotBlank() }
            },
            studios = d.production_companies.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.take(8),
            directors = d.credits?.crew.orEmpty()
                .filter { it.job in directorJobs }.map { it.name.orEmpty() }
                .filter { it.isNotBlank() }.distinct().take(4),
            writers = d.credits?.crew.orEmpty()
                .filter { it.job in writerJobs }.map { it.name.orEmpty() }
                .filter { it.isNotBlank() }.distinct().take(4),
            set = d.belongs_to_collection?.name?.takeIf { it.isNotBlank() },
            trailer = d.videos?.results.orEmpty()
                .firstOrNull { it.site == "YouTube" && it.type == "Trailer" && !it.key.isNullOrBlank() }
                ?.key?.let { "https://www.youtube.com/watch?v=$it" },
            actors = d.credits?.cast.orEmpty().take(20).mapNotNull { c ->
                c.name?.takeIf { it.isNotBlank() }?.let { ScrapedActor(it, c.character?.takeIf { r -> r.isNotBlank() }) }
            },
            tmdbId = d.id.takeIf { it > 0 }?.toString(),
            imdbId = d.imdb_id?.takeIf { it.isNotBlank() },
            posterUrl = imageUrl(d.poster_path, "w500"),
            fanartUrl = imageUrl(d.backdrop_path, "w1280"),
        )
    }

    /** 剧集详情：`/tv/{id}` + credits。字段名与电影不同（name/first_air_date/episode_run_time/networks） */
    suspend fun fetchTvDetail(id: String): ScrapedMetadata {
        val root = getJson(
            path = "/3/tv/$id",
            params = mapOf("language" to language, "append_to_response" to "credits"),
        )
        val d = runCatching { json.decodeFromString<TmdbTvDetail>(root) }.getOrElse {
            throw ScrapeException(ScrapeFailure.RequestError("TMDB 返回无法解析：${it.message}"))
        }
        return ScrapedMetadata(
            sourceId = TmdbMetadataSource.SOURCE_ID,
            sourceKey = "tmdb-${d.id}",
            kind = ScrapeKind.TV,
            title = d.name.ifBlank { d.original_name.orEmpty() },
            originalTitle = d.original_name,
            plot = d.overview?.takeIf { it.isNotBlank() },
            year = d.first_air_date?.take(4)?.toIntOrNull(),
            premiered = d.first_air_date?.takeIf { it.length >= 10 },
            runtimeMinutes = d.episode_run_time.orEmpty().firstOrNull()?.takeIf { it > 0 },
            rating = d.vote_average,
            votes = d.vote_count,
            genres = d.genres.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
            countries = d.origin_country.mapNotNull { it.takeIf { c -> c.isNotBlank() } },
            languages = d.spoken_languages.mapNotNull {
                (it.english_name ?: it.name)?.takeIf { n -> n.isNotBlank() }
            },
            studios = (d.production_companies.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } } +
                d.networks.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }).distinct().take(8),
            directors = d.credits?.cast.orEmpty()
                .filter { it.job == "Creator" }.map { it.name.orEmpty() }
                .filter { it.isNotBlank() }.distinct().take(4),
            actors = d.credits?.cast.orEmpty().take(20).mapNotNull { c ->
                c.name?.takeIf { it.isNotBlank() }?.let { ScrapedActor(it, c.character?.takeIf { r -> r.isNotBlank() }) }
            },
            tmdbId = d.id.takeIf { it > 0 }?.toString(),
            imdbId = d.imdb_id?.takeIf { it.isNotBlank() },
            posterUrl = imageUrl(d.poster_path, "w500"),
            fanartUrl = imageUrl(d.backdrop_path, "w1280"),
        )
    }

    /** 某一季的分集：`/tv/{id}/season/{n}`。解析失败按"没有这一季"处理（返回 null），不抛。 */
    suspend fun fetchTvSeason(id: String, season: Int): ScrapedSeason? {
        val root = getJson(path = "/3/tv/$id/season/$season", params = mapOf("language" to language))
        val d = runCatching { json.decodeFromString<TmdbSeason>(root) }.getOrNull() ?: return null
        return ScrapedSeason(
            season = d.season_number ?: season,
            name = d.name?.takeIf { it.isNotBlank() },
            episodes = d.episodes.mapNotNull { e ->
                val num = e.episode_number ?: return@mapNotNull null
                ScrapedEpisode(
                    season = e.season_number ?: season,
                    episode = num,
                    title = e.name.orEmpty(),
                    plot = e.overview?.takeIf { it.isNotBlank() },
                    aired = e.air_date?.takeIf { it.length >= 10 },
                    tmdbId = e.id.takeIf { it > 0 }?.toString(),
                )
            },
        )
    }

    /**
     * 剧照（背景图）列表：`/{movie|tv}/{id}/images` 的 `backdrops[]`，按赞数取前 [max] 张的 URL。
     *
     * 不带 `language` 参数 —— backdrop 本身不分语言，带了反而可能把图过滤掉。
     * 解析失败按"没有剧照"处理（空列表），不抛。
     */
    suspend fun fetchBackdropUrls(id: String, max: Int, kind: ScrapeKind = ScrapeKind.MOVIE): List<String> {
        val seg = if (kind == ScrapeKind.TV) "tv" else "movie"
        val root = getJson(path = "/3/$seg/$id/images", params = emptyMap())
        val resp = runCatching { json.decodeFromString<TmdbImages>(root) }.getOrElse { return emptyList() }
        return pickStills(
            resp.backdrops.map { BackdropRef(it.file_path.orEmpty(), it.vote_average ?: 0.0, it.vote_count ?: 0) },
            max,
        ).mapNotNull { imageUrl(it, "w1280") }
    }

    /** 任意图片字节（候选缩略图 / 海报原图）。失败返回 null —— 调用方按"没有这张图"处理 */
    suspend fun getBytes(url: String): ByteArray? = runCatching {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        }
    }.getOrNull()

    /**
     * 公共 GET：拼 URL、挂认证、判错分类，最后原样返回响应体文本（JSON 由调用方解）。
     *
     * ★ 整段必须跑在 [Dispatchers.IO] 上：`execute()` 是**阻塞**调用，而调用方常常是
     *   界面上的 `rememberCoroutineScope()`（主线程）—— 直接在那边发就会抛
     *   `NetworkOnMainThreadException`，而且这个异常**不带 message**，界面上只会显示一句
     *   没有缘由的"搜索失败"（2026-10-08 真机上就是这么表现，排查了半天）。
     */
    private suspend fun getJson(path: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        pace()
        val base = apiBase().trim().trimEnd('/')
        if (base.isEmpty()) throw ScrapeException(ScrapeFailure.RequestError("TMDB 接口地址是空的（设置 → 实验室）"))
        val key = apiKey().trim()
        if (key.isEmpty()) throw ScrapeException(ScrapeFailure.RequestError("还没填 TMDB API Key（设置 → 实验室）"))

        val url = (base + path).toHttpUrlOrNull()
            ?: throw ScrapeException(ScrapeFailure.RequestError("TMDB 接口地址不合法：$base"))
        val builder = url.newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        // v3 的 key 走查询参数；v4 的 JWT 走 Bearer。放 build 之前，顺序无关紧要
        val useBearer = key.startsWith("eyJ")
        if (!useBearer) builder.addQueryParameter("api_key", key)

        val req = Request.Builder().url(builder.build()).get().apply {
            if (useBearer) header("Authorization", "Bearer $key")
        }.build()

        val (code, body) = client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string().orEmpty())
        }
        when {
            // 404 才叫"这个源里没有这部片"（可以换源）；其余非 2xx 都是源/网络问题，要停下来报
            code == 404 -> throw ScrapeException(ScrapeFailure.NotFound("TMDB 没有这条记录（404）"))
            code == 401 -> throw ScrapeException(ScrapeFailure.RequestError("TMDB 拒绝了这个 key（401）"))
            code !in 200..299 -> throw ScrapeException(ScrapeFailure.RequestError("TMDB 返回 HTTP $code"))
        }
        if (body.isBlank()) throw ScrapeException(ScrapeFailure.RequestError("TMDB 返回了空响应"))
        body
    }

    // ---------------- JSON 模型（只收用得上的字段，未知字段忽略） ----------------

    @Serializable
    private data class TmdbSearchResponse(val results: List<TmdbBrief> = emptyList())

    @Serializable
    private data class TmdbBrief(
        val id: Int = 0,
        val title: String = "",
        val original_title: String? = null,
        val release_date: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val vote_average: Double? = null,
    )

    @Serializable
    private data class TmdbDetail(
        val id: Int = 0,
        val title: String = "",
        val original_title: String? = null,
        val tagline: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val runtime: Int? = null,
        val vote_average: Double? = null,
        val vote_count: Int? = null,
        val imdb_id: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val genres: List<TmdbNamed> = emptyList(),
        val production_companies: List<TmdbNamed> = emptyList(),
        val production_countries: List<TmdbNamed> = emptyList(),
        val spoken_languages: List<TmdbLang> = emptyList(),
        val belongs_to_collection: TmdbCollection? = null,
        val credits: TmdbCredits? = null,
        val videos: TmdbVideos? = null,
    )

    @Serializable
    private data class TmdbNamed(val name: String? = null)

    @Serializable
    private data class TmdbLang(val name: String? = null, val english_name: String? = null)

    @Serializable
    private data class TmdbCollection(val name: String? = null)

    @Serializable
    private data class TmdbCredits(
        val cast: List<TmdbCast> = emptyList(),
        val crew: List<TmdbCrew> = emptyList(),
    )

    @Serializable
    private data class TmdbCast(val name: String? = null, val character: String? = null)

    @Serializable
    private data class TmdbCrew(val name: String? = null, val job: String? = null)

    @Serializable
    private data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

    @Serializable
    private data class TmdbVideo(val key: String? = null, val site: String? = null, val type: String? = null)

    @Serializable
    private data class TmdbImages(val backdrops: List<TmdbImage> = emptyList())

    @Serializable
    private data class TmdbImage(
        val file_path: String? = null,
        val vote_average: Double? = null,
        val vote_count: Int? = null,
    )

    @Serializable
    private data class TmdbTvSearchResponse(val results: List<TmdbTvBrief> = emptyList())

    @Serializable
    private data class TmdbTvBrief(
        val id: Int = 0,
        val name: String = "",
        val original_name: String? = null,
        val first_air_date: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val vote_average: Double? = null,
    )

    @Serializable
    private data class TmdbTvDetail(
        val id: Int = 0,
        val name: String = "",
        val original_name: String? = null,
        val overview: String? = null,
        val first_air_date: String? = null,
        val episode_run_time: List<Int> = emptyList(),
        val vote_average: Double? = null,
        val vote_count: Int? = null,
        val imdb_id: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val genres: List<TmdbNamed> = emptyList(),
        val production_companies: List<TmdbNamed> = emptyList(),
        val networks: List<TmdbNamed> = emptyList(),
        val origin_country: List<String> = emptyList(),
        val spoken_languages: List<TmdbLang> = emptyList(),
        val credits: TmdbTvCredits? = null,
    )

    @Serializable
    private data class TmdbTvCredits(val cast: List<TmdbTvCast> = emptyList())

    @Serializable
    private data class TmdbTvCast(
        val name: String? = null,
        val character: String? = null,
        val job: String? = null,
    )

    @Serializable
    private data class TmdbSeason(
        val season_number: Int? = null,
        val name: String? = null,
        val episodes: List<TmdbSeasonEpisode> = emptyList(),
    )

    @Serializable
    private data class TmdbSeasonEpisode(
        val id: Int = 0,
        val episode_number: Int? = null,
        val season_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val air_date: String? = null,
    )
}
