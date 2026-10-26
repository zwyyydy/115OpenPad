package com.open115.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 原盘档位编号（与平板端同一套定义）：走 ufile/downurl，直接播原始文件 */
internal const val DEF_ORIGINAL_FILE = 999

/** 原画档（转码接口里的 100）：标签兜底用 */
internal const val DEF_ORIGINAL = 100

/** 115 open 接口主机（与平板端的 Retrofit baseUrl 一致） */
private const val API_BASE = "https://proapi.115.com"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/**
 * 一档清晰度：电视台自己解析出来的直链。
 *
 * [hls] = 115 的转码切片（地址没有 .m3u8 后缀）：media3 必须显式按 HLS 解析，
 * 否则会当普通容器解、直接 PARSING_CONTAINER_UNSUPPORTED。
 */
internal data class TvTier(
    val def: Int,
    val label: String,
    val url: String,
    val hls: Boolean,
)

/** 接口失败：消息直接能显示给用户 */
internal class Tv115Exception(message: String) : Exception(message)

/**
 * 电视端自己取 115 直链的小客户端（二期：可选清晰度 + 直链过期自愈）。
 *
 * 只用 HttpURLConnection + kotlinx.serialization：整个 App 就这两个端点，
 * 为它俩引 Retrofit 不值当（电视端刻意保持小依赖）。
 *
 * [token] 是平板随投屏指令带来的 access token：**只放内存、不落盘、不写日志**。
 * 协议本身是局域网明文，与整个投屏能力同一份信任模型；电视上没有账号体系，丢了就丢一次授权。
 */
internal class Tv115Api(
    private val token: String,
    private val userAgent: String?,
    private val baseUrl: String = API_BASE,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 这一集能选的全部档位：原盘 + 转码档，原盘排最前 */
    suspend fun tiers(pickCode: String): List<TvTier> = withContext(Dispatchers.IO) {
        val out = ArrayList<TvTier>(6)
        // 原盘取不到不算失败：转码档还能播
        runCatching { originalUrl(pickCode) }.getOrNull()?.let {
            out += TvTier(DEF_ORIGINAL_FILE, "原盘", it, hls = false)
        }
        runCatching { videoTiers(pickCode) }.getOrNull()?.let { out += it }
        if (out.isEmpty()) throw Tv115Exception("没能取到这一集的播放地址")
        out
    }

    /** 只取一档（直链过期自愈时用）：原盘走 downurl，转码档走 videoPlay */
    suspend fun tier(pickCode: String, def: Int): TvTier? = withContext(Dispatchers.IO) {
        if (def == DEF_ORIGINAL_FILE) {
            originalUrl(pickCode)?.let { TvTier(DEF_ORIGINAL_FILE, "原盘", it, hls = false) }
        } else {
            videoTiers(pickCode).firstOrNull { it.def == def }
        }
    }

    private suspend fun originalUrl(pickCode: String): String? = withContext(Dispatchers.IO) {
        val text = request("/open/ufile/downurl", form = "pick_code=" + enc(pickCode))
        val root = parseRoot(text) ?: return@withContext null
        if (!stateOk(root)) return@withContext null
        parseDownUrl(root)
    }

    private suspend fun videoTiers(pickCode: String): List<TvTier> = withContext(Dispatchers.IO) {
        val text = request("/open/video/play?pick_code=" + enc(pickCode), form = null)
        val root = parseRoot(text) ?: throw Tv115Exception("清晰度接口返回无法解析")
        if (!stateOk(root)) throw Tv115Exception(messageOf(root) ?: "获取清晰度失败")
        parseVideoTiers(root)
    }

    private fun parseRoot(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun request(path: String, form: String?): String {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (form != null) "POST" else "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            // 直链绑定申请时的 UA：必须与播放器拉流用的一模一样（平板随投屏带来的那个）
            if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (form != null) conn.outputStream.use { it.write(form.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw Tv115Exception("115 凭据已失效，请在平板上重新投屏")
            if (code !in 200..299) throw Tv115Exception("115 接口返回 HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * 接口主机：**debug 包**允许用 `tv_debug` 偏好里的 api_base 指向本地假服务（二期联调用），
 * release 恒为官方主机。这个开关不在设置页，普通用户碰不到。
 */
internal fun apiBaseFor(context: android.content.Context): String {
    if (!com.open115.tv.BuildConfig.DEBUG) return API_BASE
    return context.getSharedPreferences("tv_debug", android.content.Context.MODE_PRIVATE)
        .getString("api_base", null)
        ?.trim()
        ?.takeIf { it.startsWith("http") }
        ?: API_BASE
}

// ---------------- 纯解析（单测覆盖） ----------------

/** 转码档列表：`video_url` 既可能是数组也可能是 def→对象 的映射，两种都认 */
internal fun parseVideoTiers(root: JsonObject): List<TvTier> {
    val d = root["data"] as? JsonObject ?: return emptyList()
    val labels = parseDefinitionLabels(d)
    val entries = when (val vu = d["video_url"]) {
        is JsonArray -> vu.mapNotNull { (it as? JsonObject)?.toTier(null) }
        is JsonObject -> vu.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.toTier(k.toIntOrNull()) }
        else -> emptyList()
    }
    return entries
        .map { tier -> labels[tier.def]?.takeIf { it.isNotBlank() }?.let { tier.copy(label = it) } ?: tier }
        .sortedByDescending { it.def }
}

private fun JsonObject.toTier(keyDef: Int?): TvTier? {
    val url = (this["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val def = keyDef
        ?: (this["definition"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: return null
    val height = (this["height"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    return TvTier(
        def = def,
        label = if (height > 0) "${height}P" else "清晰度 $def",
        url = url,
        hls = !isDirectVideoUrl(url),
    )
}

private fun parseDefinitionLabels(d: JsonObject): Map<Int, String> =
    ((d["definition_list_new"] as? JsonObject) ?: (d["definition_list"] as? JsonObject))
        ?.entries
        ?.mapNotNull { (k, v) ->
            k.toIntOrNull()?.let { def -> def to ((v as? JsonPrimitive)?.contentOrNull ?: "") }
        }
        ?.toMap()
        ?: emptyMap()

/** downurl 的 data 是"一份或多份地址"的映射，取第一条（与平板端同口径） */
internal fun parseDownUrl(root: JsonObject): String? {
    val data = root["data"] as? JsonObject ?: return null
    val entry = data.values.firstOrNull() as? JsonObject ?: return null
    return when (val el = entry["url"]) {
        is JsonObject -> (el["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }
}

/** 115 的 state 既可能是 true/1（布尔）也可能是 "true"/"1"（字符串） */
internal fun stateOk(root: JsonObject): Boolean =
    (root["state"] as? JsonPrimitive)?.contentOrNull?.let {
        it == "1" || it.equals("true", ignoreCase = true)
    } ?: false

internal fun messageOf(root: JsonObject): String? =
    (root["message"] as? JsonPrimitive)?.contentOrNull
        ?: (root["msg"] as? JsonPrimitive)?.contentOrNull

/** 直链判定与平板端同口径：有这些后缀就是原始文件，其余（无后缀）按 HLS */
internal fun isDirectVideoUrl(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    return listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".m2ts", ".rmvb", ".webm", ".mpg", ".mpeg")
        .any { path.endsWith(it) }
}

/** 首选档位不在这一集里：退回原盘；原盘也没有就取最高的一档 */
internal fun pickTier(tiers: List<TvTier>, preferredDef: Int): TvTier? =
    tiers.firstOrNull { it.def == preferredDef }
        ?: tiers.firstOrNull { it.def == DEF_ORIGINAL_FILE }
        ?: tiers.maxByOrNull { it.def }
