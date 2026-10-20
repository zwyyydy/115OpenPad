package com.open115.pad.data.magnet

import com.open115.pad.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 磁力预览：把一条磁力链交给第三方预览服务（默认 whatslink.info），换回类型、总大小、
 * 文件数，以及该种子视频内容的几张抽样截图。
 *
 * 与纸鸢磁力 MCP 的 `magnet_files` 是**互补**关系，不是替代：
 * - `magnet_files` 给**文件结构**（有哪些文件、各多大）；预览服务给不了文件列表
 *   （实测 `&name=` 参数被忽略、`POST /api/v1/link` 是 404）。
 * - 预览服务给**画面**：最多 5 张 640px 抽样帧，外加一行类型/大小/文件数；
 *   而且它对**任意磁力**都认，不限于某个索引库。
 *
 * 接口形状（实测 2026-10-14）：`GET {base}/api/v1/link?url=<URL 编码的磁力链>`，
 * 无鉴权、不校验 Referer、不挑 UA，返回
 * `{error,type,file_type,name,size,count,screenshots:[{time,screenshot}]}`；
 * `type` 为 `FILE` / `FOLDER` / `UNKNOWN`，**解析不了也是 HTTP 200**，靠 `type` 区分。
 * 站方没有文档承诺、随时可能改，所以调用方一律按「拿不到就少显示一块」处理。
 */
class WhatsLinkClient(
    private val base: String,
    private val http: OkHttpClient,
) {
    /** 服务在响应里写明 `error` 时抛出（实测只在畸形请求上出现） */
    class PreviewException(message: String) : Exception(message)

    /**
     * 预览结果。[resolved] 为 false = 服务解析不了这条种子（`type:UNKNOWN`），
     * 此时除 [name] 外都没有值。
     */
    data class Preview(
        val name: String?,
        val resolved: Boolean,
        val kindLabel: String?,
        val sizeText: String?,
        val count: Int?,
        val screenshots: List<String>,
    )

    companion object {
        const val DEFAULT_BASE = "https://whatslink.info"

        private val json = Json { ignoreUnknownKeys = true }

        /** file_type → 人话；认不出的原样透传（站方加新值时至少不是空白） */
        private val KIND_LABELS = mapOf(
            "video" to "视频",
            "folder" to "目录",
            "archive" to "压缩包",
            "audio" to "音频",
            "image" to "图片",
            "document" to "文档",
            "subtitle" to "字幕",
        )
    }

    suspend fun preview(magnet: String): Preview = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/api/v1/link?url=" + encode(magnet))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw PreviewException("服务返回 HTTP ${resp.code}")
            parsePreview(body)
        }
    }

    /** 磁力链里有 `&`、中文（dn= 常是片名）和 `+`，整条百分号编码——别让 `+` 变成空格 */
    internal fun encode(magnet: String): String =
        URLEncoder.encode(magnet, "UTF-8").replace("+", "%20")

    internal fun parsePreview(text: String): Preview {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw PreviewException("响应不是 JSON")
        root.str("error")?.let { if (it.isNotBlank()) throw PreviewException(it) }
        val type = root.str("type").orEmpty()
        val resolved = type.isNotBlank() && !type.equals("UNKNOWN", ignoreCase = true)
        val fileType = root.str("file_type").orEmpty()
        val size = root.long("size")
        return Preview(
            name = root.str("name")?.takeIf { it.isNotBlank() },
            resolved = resolved,
            kindLabel = if (!resolved) null
            else KIND_LABELS[fileType] ?: fileType.takeIf { it.isNotBlank() },
            sizeText = size?.takeIf { it > 0 }?.let { Format.size(it) },
            count = root.long("count")?.takeIf { it > 0 }?.toInt(),
            screenshots = (root["screenshots"] as? JsonArray).orEmpty().mapNotNull { el ->
                ((el as? JsonObject)?.get("screenshot") as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.startsWith("http") }
            },
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
