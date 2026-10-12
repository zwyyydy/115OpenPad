package com.open115.pad.data.media.scrape

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一个 JS 源（[JsRuntime]）适配成宿主的 [MetadataSource]。
 *
 * 所有方法都是：`runtime.call(名字, 参数JSON)` → 解出 `value` 的 JSON → 手动映射成数据类。
 * 字段名与内置 TMDB 源保持一致（title/year/plot/genres/actors:[{name,role}]/posterUrl/fanartUrl/tmdbId…），
 * 这样写回 nfo、海报、剧照那条链路完全不用区分源。缺字段就留空，解析器/写回器本就"只写有值的"。
 */
class JsMetadataSource(
    private val runtime: JsRuntime,
    private val http: okhttp3.OkHttpClient,
) : MetadataSource {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val id: String get() = runtime.metaId
    override val displayName: String get() = runtime.metaName
    override val apiVersion: Int = 1

    /** 预热：提前建引擎读回 SOURCE.id/name。[id] 是懒加载的，注册表按它匹配选中源——
     *  refresh 时若不预热，引擎未建时 id 还是 dir_/js_ 构造值，与已存的 SOURCE.id 对不上会误回退 TMDB。 */
    suspend fun ensureLoaded(): String? = runtime.loadError()

    override suspend fun search(query: ScrapeQuery): List<ScrapeCandidate> {
        val q = json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(query.title))
        val y = query.year?.toString() ?: "null"
        val k = json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(query.kind.name))
        val value = runtime.unwrap(runtime.call("search", "[{\"title\":$q,\"year\":$y,\"kind\":$k}]"))
        val arr = json.parseToJsonElement(value).let { if (it is JsonArray) it else JsonArray(emptyList()) }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val itemId = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ScrapeCandidate(
                sourceId = id,
                id = itemId,
                title = o["title"]?.jsonPrimitive?.content ?: "",
                originalTitle = o["originalTitle"]?.jsonPrimitive?.content,
                year = o["year"]?.jsonPrimitive?.content?.toIntOrNull(),
                overview = o["overview"]?.jsonPrimitive?.content,
                posterUrl = o["posterUrl"]?.jsonPrimitive?.content,
                rating = o["rating"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                kind = if (o["kind"]?.jsonPrimitive?.content == "TV") ScrapeKind.TV else query.kind,
            )
        }
    }

    override suspend fun fetch(candidate: ScrapeCandidate): ScrapedMetadata {
        val value = runtime.unwrap(runtime.call("fetch", "[${str(candidate.id)}]"))
        val o = json.parseToJsonElement(value).jsonObject
        return ScrapedMetadata(
            sourceId = id,
            sourceKey = candidate.sourceId + "-" + candidate.id,
            kind = candidate.kind,
            title = o["title"]?.jsonPrimitive?.content ?: candidate.title,
            originalTitle = o["originalTitle"]?.jsonPrimitive?.content,
            tagline = o["tagline"]?.jsonPrimitive?.content,
            plot = o["plot"]?.jsonPrimitive?.content,
            year = o["year"]?.jsonPrimitive?.content?.toIntOrNull(),
            premiered = o["premiered"]?.jsonPrimitive?.content,
            runtimeMinutes = o["runtimeMinutes"]?.jsonPrimitive?.content?.toIntOrNull(),
            rating = o["rating"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            votes = o["votes"]?.jsonPrimitive?.content?.toIntOrNull(),
            genres = strList(o["genres"]),
            countries = strList(o["countries"]),
            languages = strList(o["languages"]),
            studios = strList(o["studios"]),
            directors = strList(o["directors"]),
            writers = strList(o["writers"]),
            set = o["set"]?.jsonPrimitive?.content,
            trailer = o["trailer"]?.jsonPrimitive?.content,
            actors = actorList(o["actors"]),
            tmdbId = o["tmdbId"]?.jsonPrimitive?.content,
            imdbId = o["imdbId"]?.jsonPrimitive?.content,
            posterUrl = o["posterUrl"]?.jsonPrimitive?.content,
            fanartUrl = o["fanartUrl"]?.jsonPrimitive?.content,
        )
    }

    override suspend fun fetchBackdropUrls(candidate: ScrapeCandidate, max: Int): List<String> {
        // 把张数上限一并传进脚本（不限制时是 Int.MAX_VALUE）：脚本按它取，别自己写死挡位
        val value = runCatching { runtime.unwrap(runtime.call("backdrops", "[${str(candidate.id)},$max]")) }.getOrNull() ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(value).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }.take(max)
    }

    override suspend fun fetchSeason(candidate: ScrapeCandidate, season: Int): ScrapedSeason? {
        val value = runCatching { runtime.unwrap(runtime.call("season", "[${str(candidate.id)},$season]")) }.getOrNull() ?: return null
        val o = runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
        val eps = (o["episodes"] as? JsonArray)?.mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            val num = eo["episode"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            ScrapedEpisode(
                season = eo["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: season,
                episode = num,
                title = eo["title"]?.jsonPrimitive?.content ?: "",
                plot = eo["plot"]?.jsonPrimitive?.content,
                aired = eo["aired"]?.jsonPrimitive?.content,
                tmdbId = eo["tmdbId"]?.jsonPrimitive?.content,
            )
        }.orEmpty()
        return ScrapedSeason(season, o["name"]?.jsonPrimitive?.content, eps)
    }

    private fun str(s: String): String = json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(s))
    private fun strList(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }.orEmpty()
    private fun actorList(el: JsonElement?): List<ScrapedActor> =
        (el as? JsonArray)?.mapNotNull { e ->
            when (e) {
                is JsonObject -> e["name"]?.jsonPrimitive?.contentOrNullSafe()?.let { ScrapedActor(it, e["role"]?.jsonPrimitive?.contentOrNullSafe()) }
                else -> e.jsonPrimitive.contentOrNullSafe()?.let { ScrapedActor(it, null) }
            }
        }.orEmpty()

    fun close() = runtime.close()
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
