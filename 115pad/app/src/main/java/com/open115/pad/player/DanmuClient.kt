package com.open115.pad.player

import com.open115.pad.data.media.DanmuCacheEntity
import com.open115.pad.data.media.MediaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一条弹幕。mode：1~3 滚动、4 底部、5 顶部（弹弹play 的语义）；7+ 的高级/代码弹幕不渲染 */
internal data class DanmuComment(
    val timeMs: Long,
    val mode: Int,
    val color: Int,
    val text: String,
)

/** 手动搜索的候选：一部（= 弹弹play 语义的一季）及其全集 */
internal data class DanmuEpisode(val episodeId: String, val title: String)

internal data class DanmuAnime(val title: String, val episodes: List<DanmuEpisode>)

/** 一次自动匹配的结果：弹幕本体 + 匹配到了哪（给弹幕菜单的状态行显示用） */
internal data class DanmuFetchResult(
    val comments: List<DanmuComment>,
    val animeTitle: String,
    val episodeTitle: String,
    val count: Int,
    /** false = 没匹配上（负缓存/空结果/失败） */
    val matched: Boolean,
)

/** 弹幕面板状态行用的匹配状态（条数由界面按**过滤后**的列表现算，见 PlayerScreen） */
internal sealed interface DanmuStatus {
    data object Loading : DanmuStatus
    data class Matched(val animeTitle: String, val episodeTitle: String) : DanmuStatus
    data object NotFound : DanmuStatus
}

/**
 * 弹幕客户端（实验室：弹幕）：对任何**兼容弹弹play 协议**的 HTTP 源做「文件名 → 匹配 → 弹幕」。
 *
 * 为什么只当 HTTP 客户端、不把抓取逻辑搬进 App：聚合十几个平台的适配器是别人（danmu_api）
 * 一直在维护的脏活，App 这边保持"一个地址 + 两个接口"的干净面（见 [[danmu-source-deployed]]）。
 * 源代码是 AGPL，只能调用、不能拷。
 *
 * 匹配用 `/api/v2/search/episodes`（一次拿全集列表，按文件名里的集号对号入座），
 * 弹幕用 `/api/v2/comment/{episodeId}`（默认频控 3 次/分钟，所以缓存是这条链路的命根子：
 * episodeId 永久缓存、弹幕本体短 TTL、匹配失败也记一笔负缓存防反复空查）。
 *
 * ⚠️ 部署版 danmu_api 的 `/api/v2/search/anime` 读单数 `keyword`（规范是复数 `keywords`，
 * 传复数会 500）—— 那个接口这里用不到，但别踩。
 *
 * 弹幕是附属品：任何一步失败都返回空列表，绝不能影响播放。
 */
internal class DanmuClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 匹配 + 拉取主入口，带缓存。返回值带"匹配到了哪部哪集"（弹幕菜单的状态行显示用）。
     * comments 为空可能是没匹配上，也可能是匹配上了但源里没弹幕 —— 看 [DanmuFetchResult.matched]。
     *
     * [cacheDays] = 弹幕本体的缓存时效（天，设置页可调，默认 7）：期内命中缓存零网络；
     * 过期就着 episodeId 重拉一遍补新弹幕。episodeId（匹配结果）本身永久缓存不受它管。
     *
     * ⚠️ 读缓存分两步走（元数据 + 分块取本体），不能图省事写回 `SELECT *`：
     * 一列几 MB 的 TEXT 超过 CursorWindow 时 SQLite 会抛 SQLiteBlobTooBigException，
     * 而它在这里会被 [runCatching] 咽掉 —— 表现就是"缓存了、再次打开却没有弹幕"。
     */
    suspend fun fetchFor(
        pickCode: String,
        fileName: String,
        dao: MediaDao,
        baseUrl: String,
        cacheDays: Int = 7,
    ): DanmuFetchResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl.trim().trimEnd('/')
            if (pickCode.isBlank() || fileName.isBlank() || base.isBlank()) {
                return@runCatching DanmuFetchResult(emptyList(), "", "", 0, false)
            }
            val now = System.currentTimeMillis()
            val commentsTtlMs = cacheDays.coerceIn(1, 365) * 24 * 3_600_000L

            val cached = dao.danmuMeta(pickCode)
            if (cached != null && cached.episodeId.isNotBlank()) {
                val fresh = cached.jsonLength != null && now - cached.commentsAt <= commentsTtlMs
                if (fresh) {
                    val body = decodeComments(dao.danmuJson(pickCode).orEmpty())
                    // 本体没读回来（读不出/串坏了）却记着有弹幕：当没缓存，往下重拉 ——
                    // 这份缓存已经废了，认命的话这部片就永远没有弹幕，用户还看不到原因
                    if (body.isNotEmpty() || cached.commentCount == 0) {
                        return@runCatching DanmuFetchResult(
                            body, cached.animeTitle, cached.episodeTitle, cached.commentCount, true,
                        )
                    }
                }
                // 没拉到过（频控失败）或已过期：就着现成的 episodeId 重拉一次
                val list = fetchComments(base, cached.episodeId)
                if (list == null) {
                    // 拉取失败（频控/网络/串坏了）：**一个字都不写**。把过期的那份端出来接着用，
                    // 没有就空着 —— 关键是不能把"失败"写进缓存当成"这部片没有弹幕"：
                    // 那会让它在整个时效里都不再重试，用户只看到"缓存了也没弹幕"、毫无头绪。
                    val body = if (cached.jsonLength != null) {
                        decodeComments(dao.danmuJson(pickCode).orEmpty())
                    } else {
                        emptyList()
                    }
                    return@runCatching DanmuFetchResult(
                        body, cached.animeTitle, cached.episodeTitle, body.size, true,
                    )
                }
                dao.addDanmu(
                    DanmuCacheEntity(
                        pickCode = pickCode, episodeId = cached.episodeId,
                        animeTitle = cached.animeTitle, episodeTitle = cached.episodeTitle,
                        matchedAt = cached.matchedAt,
                        commentsJson = encodeComments(list), commentsAt = now, commentCount = list.size,
                    ),
                )
                return@runCatching DanmuFetchResult(
                    list, cached.animeTitle, cached.episodeTitle, list.size, true,
                )
            }
            if (cached != null && cached.episodeId.isBlank() && now - cached.matchedAt <= NEG_TTL_MS) {
                return@runCatching DanmuFetchResult(emptyList(), "", "", 0, false) // 负缓存保鲜期内：源里就是没有
            }

            // 没缓存 / 负缓存过期 → 重新匹配
            val match = matchEpisode(base, fileName)
            if (match == null) {
                dao.addDanmu(
                    DanmuCacheEntity(
                        pickCode = pickCode, episodeId = "", animeTitle = "", episodeTitle = "",
                        matchedAt = now, commentsJson = null, commentsAt = now, commentCount = 0,
                    ),
                )
                return@runCatching DanmuFetchResult(emptyList(), "", "", 0, false)
            }
            val (animeTitle, episodeTitle, episodeId) = match
            val list = fetchComments(base, episodeId)
            dao.addDanmu(
                DanmuCacheEntity(
                    pickCode = pickCode, episodeId = episodeId, animeTitle = animeTitle,
                    episodeTitle = episodeTitle, matchedAt = now,
                    // 拉取失败只记"匹配到了哪一集"，本体留空等下次再拉（实体的约定：
                    // commentsJson 为 null = 还没拉到）。写成 "[]" 就把失败钉成了事实。
                    commentsJson = list?.let { encodeComments(it) },
                    commentsAt = now,
                    commentCount = list?.size ?: 0,
                ),
            )
            DanmuFetchResult(list.orEmpty(), animeTitle, episodeTitle, list?.size ?: 0, true)
        }.getOrDefault(DanmuFetchResult(emptyList(), "", "", 0, false))
    }

    /** 手动搜索入口（弹幕匹配对话框用）：关键词由用户编辑，返回全部候选（含各自集列表） */
    suspend fun search(base: String, keyword: String): List<DanmuAnime> = withContext(Dispatchers.IO) {
        runCatching { searchAnimes(base, keyword) }.getOrDefault(emptyList())
    }

    /**
     * 手动绑定：用户在对话框里选定的剧集，写缓存（覆盖自动匹配/负缓存）并拉弹幕。
     * 弹幕拉失败也照样把"绑定哪一集"记下来（用户的手动指定比一次请求失败重要），
     * 本体留空等下次进播放器时按 [fetchFor] 的过期分支补拉。
     */
    suspend fun bindManual(
        pickCode: String,
        dao: MediaDao,
        baseUrl: String,
        animeTitle: String,
        episodeTitle: String,
        episodeId: String,
    ): List<DanmuComment> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val list = fetchComments(baseUrl.trim().trimEnd('/'), episodeId)
            dao.addDanmu(
                DanmuCacheEntity(
                    pickCode = pickCode, episodeId = episodeId, animeTitle = animeTitle,
                    episodeTitle = episodeTitle, matchedAt = now,
                    commentsJson = list?.let { encodeComments(it) },
                    commentsAt = now,
                    commentCount = list?.size ?: 0,
                ),
            )
            list.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 文件名 →（剧名, 集名, episodeId）；没有任何源命中返回 null */
    private fun matchEpisode(base: String, fileName: String): Triple<String, String, String>? {
        val epNum = episodeNumberOf(fileName)
        val title = cleanTitle(fileName)
        if (title.isBlank()) return null
        for (anime in searchAnimes(base, title)) {
            val eps = anime.episodes
            // 集号能解析就按集号对号入座（弹弹play 的 anime = 单季，episodes 按序）；
            // 解析不出来（电影/无集号命名）就取第一集
            val idx = if (epNum != null && eps.size > 1) (epNum - 1).coerceIn(0, eps.size - 1) else 0
            val ep = eps.getOrNull(idx) ?: continue
            if (ep.episodeId.isBlank()) continue
            return Triple(anime.title, ep.title, ep.episodeId)
        }
        return null
    }

    /** 调 search/episodes 拉全部候选（同步版：调用方已在 IO 上下文里） */
    private fun searchAnimes(base: String, keyword: String): List<DanmuAnime> {
        val b = base.trim().trimEnd('/')
        if (keyword.isBlank() || b.isBlank()) return emptyList()
        val body = getJson(
            b.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("api/v2/search/episodes")
                ?.addQueryParameter("anime", keyword.trim())
                ?.build() ?: return emptyList(),
        ) ?: return emptyList()
        val animes = body["animes"] as? JsonArray ?: return emptyList()
        val out = ArrayList<DanmuAnime>()
        for (a in animes) {
            val o = a as? JsonObject ?: continue
            val title = (o["animeTitle"] as? JsonPrimitive)?.content ?: continue
            val eps = (o["episodes"] as? JsonArray)?.mapNotNull { e ->
                val eo = e as? JsonObject ?: return@mapNotNull null
                val id = (eo["episodeId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                DanmuEpisode(id, (eo["episodeTitle"] as? JsonPrimitive)?.content ?: "")
            } ?: emptyList()
            if (eps.isNotEmpty()) out.add(DanmuAnime(title, eps))
        }
        return out
    }

    /**
     * 拉某一集的弹幕。**请求失败返回 null**，与"请求成功但一条都没有"的空列表分开 ——
     * 缓存层要靠这个区别决定写不写：失败写进去就等于把"没有弹幕"钉死一整个时效。
     */
    private fun fetchComments(base: String, episodeId: String): List<DanmuComment>? {
        val body = getJson(
            base.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("api/v2/comment")
                ?.addPathSegment(episodeId)
                ?.addQueryParameter("withRelated", "true")
                ?.addQueryParameter("chConvert", "1")
                ?.build() ?: return null,
        ) ?: return null
        val arr = body["comments"] as? JsonArray ?: return emptyList()
        val out = ArrayList<DanmuComment>(arr.size)
        for (c in arr) {
            val o = c as? JsonObject ?: continue
            val text = (o["m"] as? JsonPrimitive)?.content ?: continue
            if (text.isBlank()) continue
            // p = "秒,模式,颜色[,来源...]"；高级/代码弹幕（7+）解析器画不了，直接丢
            val parts = ((o["p"] as? JsonPrimitive)?.content ?: "").split(',')
            if (parts.size < 3) continue
            val timeMs = (parts[0].toFloatOrNull()?.times(1000))?.toLong() ?: continue
            val mode = parts[1].toIntOrNull() ?: continue
            if (mode !in 1..5) continue
            val color = parts[2].toIntOrNull()?.and(0xFFFFFF) ?: 0xFFFFFF
            out.add(DanmuComment(timeMs, mode, color, text))
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun getJson(url: okhttp3.HttpUrl): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }.getOrNull()

    // ---- 存储：紧凑 JSON（[[timeMs, mode, color, "text"], ...]），比对象数组省一半体积 ----

    private fun encodeComments(list: List<DanmuComment>): String = buildJsonArray {
        list.forEach { c ->
            add(buildJsonArray {
                add(JsonPrimitive(c.timeMs))
                add(JsonPrimitive(c.mode))
                add(JsonPrimitive(c.color))
                add(JsonPrimitive(c.text))
            })
        }
    }.toString()

    private fun decodeComments(raw: String): List<DanmuComment> = runCatching {
        val arr = json.parseToJsonElement(raw).jsonArray
        ArrayList<DanmuComment>(arr.size).also { out ->
            for (e in arr) {
                val f = (e as? JsonArray) ?: continue
                if (f.size < 4) continue
                out.add(
                    DanmuComment(
                        timeMs = f[0].jsonPrimitive.content.toLongOrNull() ?: continue,
                        mode = f[1].jsonPrimitive.content.toIntOrNull() ?: continue,
                        color = f[2].jsonPrimitive.content.toIntOrNull() ?: 0xFFFFFF,
                        text = f[3].jsonPrimitive.content,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        /** 匹配失败负缓存的保鲜期：三天后再给源一个机会（说不定后来有人传了） */
        private const val NEG_TTL_MS = 3 * 24 * 3_600_000L

        /**
         * 屏蔽词解析：逗号/中文逗号/顿号/分号/换行/空格分隔，空段丢弃。
         * 命中任一关键词（子串、忽略大小写）的弹幕不显示。
         */
        internal fun parseBlocklist(raw: String): List<String> =
            raw.split(Regex("[,，、;；\\n\\r\\t ]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /**
         * 从文件名里解析集号（1 基）。命中优先级：S01E02 > EP02 > 独立 E02 > 第02集。
         * 解析不出（电影、无集号命名）返回 null，匹配时取第一集。
         */
        internal fun episodeNumberOf(fn: String): Int? {
            Regex("S\\d{1,2}E(\\d{1,4})", RegexOption.IGNORE_CASE).find(fn)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            Regex("EP(\\d{1,4})", RegexOption.IGNORE_CASE).find(fn)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            // 独立的 E02：前面不能是字母数字（防把 "HEVC" 里的 EVC 当集号），后面不能再跟数字
            Regex("(?:^|[^A-Za-z0-9])E(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE).find(fn)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            Regex("第(\\d{1,4})[集话期]").find(fn)?.let { return it.groupValues[1].toIntOrNull() }
            return null
        }

        /**
         * 文件名 → 提交给弹幕源的搜索词：去扩展名、去括号组（字幕组/说明）、
         * 去画质/编码/音轨/来源这些杂音词。剩下的大概率就是剧名。
         */
        internal fun cleanTitle(fn: String): String = fn
            .substringBeforeLast('.')
            .replace(NOISE, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '_', '.', '·')

        private val NOISE = Regex(
            "\\[[^]]*]|【[^】]*】" +
                "|S\\d{1,2}E\\d{1,3}|EP?\\d{1,4}|第\\d{1,4}[集话期]" +
                "|2160p?|1080p?|720p?|4K|8K|HDR10?\\+?|Dolby\\s*Vision|SDR|10bit|60fps" +
                "|HEVC|H\\.?26[45]|X26[45]|AVC|AV1|VP9" +
                "|WEB-?DL|WEBRip|Blu-?Ray|REMUX|BDMV|UHD|HDTV|REPACK" +
                "|AAC|FLAC|TRUEHD|ATMOS|DDP?\\s*[25]\\.[01]|AC3|MP3|DTS(?:\\s*HD)?|OPUS" +
                "|国语|粤语|中字|简体|繁体|双语|内封|内嵌|外挂|简繁|官方中字|无水印|CHS|CHT|GB|BIG5",
            RegexOption.IGNORE_CASE,
        )
    }
}
