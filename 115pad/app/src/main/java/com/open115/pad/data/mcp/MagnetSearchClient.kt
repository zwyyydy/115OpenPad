package com.open115.pad.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 「纸鸢磁力」（magnet.kiteyuan.info）的 **MCP 客户端**——最小实现，只覆盖本 App 用到的工具。
 *
 * 协议：MCP Streamable HTTP —— JSON-RPC over HTTPS POST。握手一次（initialize）从响应头拿
 * `mcp-session-id`，之后每次调用带上；响应可能是 `application/json`，也可能是
 * `text/event-stream`（按行读 `data:` 帧），两种都认。鉴权：`Authorization: Bearer <MCP Token>`
 * （网站 /mcp 页生成，明文只显示一次，可轮换）。
 *
 * 工具（站方文档）：`magnet_search`（query/limit，**仅站内聚合索引库**）、`magnet_files`
 * （解析磁力文件结构）、`magnet_preview`。本类只接前两个——预览的信息文件结构里都有。
 *
 * 工具返回的 content[].text 的 JSON 形状站方没有文档承诺，解析全部走**宽匹配**
 * （字段名候选数组）+ 磁力链正则兜底，解析不出时保留原文，界面展示原文而不是报错。
 */
class MagnetSearchClient(
    private val endpoint: String,
    private val token: String,
    private val http: OkHttpClient,
) {
    class MagnetException(message: String) : Exception(message)

    private var sessionId: String? = null
    private var nextId = 1

    companion object {
        const val DEFAULT_ENDPOINT = "https://magnet.kiteyuan.info/api/v1/mcp"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** 磁力搜索的一条结果。磁力链一定以 magnet: 开头（缺链的行直接丢弃） */
    data class MagnetItem(
        val name: String,
        val magnet: String,
        val size: String?,
        val date: String?,
    )

    /** 磁力文件结构解析结果；[raw] 是工具原文（解析失败时界面展示它） */
    data class FilesInfo(
        val files: List<FileEntry>,
        val raw: String,
    ) {
        data class FileEntry(val name: String, val size: String?)
    }

    /** 磁力搜索：返回结果列表（可能为空 = 没搜到） */
    suspend fun search(query: String, limit: Int = 20): List<MagnetItem> = withContext(Dispatchers.IO) {
        val text = callTool(
            "magnet_search",
            buildJsonObject {
                put("query", query)
                put("limit", limit)
            },
        )
        parseSearchPayload(text)
    }

    /** 解析磁力里的文件结构（推离线下载前确认内容用） */
    suspend fun files(magnetLink: String): FilesInfo = withContext(Dispatchers.IO) {
        val text = callTool(
            "magnet_files",
            buildJsonObject { put("magnet_link", magnetLink) },
        )
        parseFilesPayload(text)
    }

    /** 设置页「测试连接」：握手 + 列工具，返回一句话摘要（异常直接抛给调用方展示） */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val names = listTools()
        "连接正常，${names.size} 个工具：" + names.take(4).joinToString("、") + if (names.size > 4) " 等" else ""
    }

    // ---------------- MCP 协议层 ----------------

    private suspend fun listTools(): List<String> {
        val result = rpc("tools/list", buildJsonObject { put("limit", 50) })
            ?: throw MagnetException("服务无响应")
        val tools = result["tools"] as? JsonArray ?: throw MagnetException("响应里没有工具列表")
        return tools.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
    }

    private suspend fun callTool(name: String, args: JsonObject): String {
        val result = rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        ) ?: throw MagnetException("服务无响应")
        val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
        val text = (result["content"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")
            .orEmpty()
        if (isError) throw MagnetException(text.ifBlank { "工具执行失败" })
        return text
    }

    /**
     * 发一次 JSON-RPC（自动完成握手；会话失效自动重握手重试一次）。
     * @return result 对象；notification（无 id 的请求）返回 null
     */
    private suspend fun rpc(method: String, params: JsonObject): JsonObject? {
        val id = nextId++
        try {
            ensureSession()
            val resp = exchange(rpcBody(id, method, params))
            return readResponse(resp, id)
        } catch (e: SessionGone) {
            // 会话过期（服务端重启/过期）：重握手再试一次，仅一次
            sessionId = null
            ensureSession()
            val resp = exchange(rpcBody(id, method, params))
            return readResponse(resp, id)
        }
    }

    private suspend fun ensureSession() {
        if (sessionId != null) return
        val id = nextId++
        val resp = exchange(
            rpcBody(
                id, "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    put("capabilities", buildJsonObject { })
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "115OpenPad")
                            put("version", "1.0")
                        },
                    )
                },
            ),
        )
        sessionId = resp.header("mcp-session-id")
        readResponse(resp, id) // 校验握手的 JSON-RPC 错误
        // initialized 通知：无 id、不期待响应（202 空）
        exchange(
            rpcBody(null, "notifications/initialized", buildJsonObject { }),
        ).close()
    }

    private class SessionGone : Exception()

    private fun rpcBody(id: Int?, method: String, params: JsonObject): Request {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }
        val builder = Request.Builder()
            .url(endpoint)
            .post(obj.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer $token")
        sessionId?.let { builder.header("mcp-session-id", it) }
        return builder.build()
    }

    private fun exchange(request: Request): okhttp3.Response {
        val resp = http.newCall(request).execute()
        if (resp.code == 401 || resp.code == 403) {
            resp.close()
            throw MagnetException("Token 未填或已失效（在网站 /mcp 页生成后填进设置）")
        }
        if (resp.code == 404) {
            resp.close()
            throw SessionGone()
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw MagnetException("服务返回 HTTP $code")
        }
        return resp
    }

    /** 从 JSON 或 SSE 响应里取出 id 匹配的 JSON-RPC 响应，返回 result（error 则抛给人话） */
    private fun readResponse(resp: okhttp3.Response, wantId: Int): JsonObject? {
        val contentType = resp.header("Content-Type").orEmpty()
        val body = resp.body?.string().orEmpty()
        resp.close()
        val messages = if (contentType.contains("text/event-stream")) {
            sseDataFrames(body)
        } else {
            listOf(body)
        }.mapNotNull { frame ->
            runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
        }
        val own = messages.firstOrNull {
            (it["id"] as? JsonPrimitive)?.content?.toIntOrNull() == wantId
        } ?: if (messages.size == 1) messages[0] else return null
        (own["error"] as? JsonObject)?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            throw MagnetException("服务端错误：$msg")
        }
        return own["result"] as? JsonObject
    }

    /** SSE 帧拆解：每个空行分隔的块里，所有 data: 行拼成一个 JSON 文本 */
    internal fun sseDataFrames(body: String): List<String> =
        body.split("\n\n", "\r\n\r\n").mapNotNull { chunk ->
            val data = chunk.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
            data.ifBlank { null }
        }

    // ---------------- 结果解析（宽匹配，供单测） ----------------

    /**
     * magnet_search 的 content 文本 → 结果列表。
     * 先按结构化 JSON 宽匹配（items/results/data/list 任一数组字段，或对象本身就是一条）；
     * 都不行就正则抓磁力链兜底（名字用 dn 参数或磁力链本身），保证"至少能推离线"。
     */
    internal fun parseSearchPayload(text: String): List<MagnetItem> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val items = root?.let { structuredItems(it) }.orEmpty()
        if (items.isNotEmpty()) return items
        return extractMagnets(text).map { MagnetItem(it.second, it.first, null, null) }
    }

    private fun structuredItems(root: JsonElement): List<MagnetItem> {
        val arr = firstArray(root) ?: return emptyList()
        val out = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.firstStr("name", "title", "filename", "t") ?: return@mapNotNull null
            var magnet = o.firstStr("magnet", "magnet_link", "magnetUrl", "magnet_url", "uri", "link", "url").orEmpty()
            if (!magnet.startsWith("magnet:")) {
                val hash = o.firstStr("info_hash", "infoHash", "hash", "btih")
                if (!hash.isNullOrBlank()) magnet = magnetFromHash(hash, name)
            }
            if (!magnet.startsWith("magnet:")) return@mapNotNull null
            MagnetItem(
                name = name,
                magnet = magnet,
                size = o.sizeText(),
                date = o.firstStr("date", "time", "pubdate", "pubDate", "created", "created_at"),
            )
        }
        return out
    }

    internal fun parseFilesPayload(text: String): FilesInfo {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return FilesInfo(emptyList(), text)
        val arr = firstArray(root) ?: return FilesInfo(emptyList(), text)
        val files = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.firstStr("name", "path", "filename", "title", "file") ?: return@mapNotNull null
            FilesInfo.FileEntry(name, o.sizeText())
        }
        if (files.isEmpty()) return FilesInfo(emptyList(), text)
        return FilesInfo(files, text)
    }

    /** 对象/数组里找到第一个"像结果列表"的数组：数组本身，或对象下 items/results/data/list/rows */
    private fun firstArray(root: JsonElement): JsonArray? {
        when (root) {
            is JsonArray -> return root
            is JsonObject -> {
                // 对象本身就是一条结果（有 name + magnet 类字段）
                if (root.firstStr("name", "title") != null) return JsonArray(listOf(root))
                for (key in listOf("items", "results", "data", "list", "rows", "files")) {
                    (root[key] as? JsonArray)?.let { if (it.isNotEmpty()) return it }
                    // data/files 可能是 {list: [...]} 的再包一层
                    (root[key] as? JsonObject)?.let { inner ->
                        for (key2 in listOf("items", "results", "list", "files")) {
                            (inner[key2] as? JsonArray)?.let { if (it.isNotEmpty()) return it }
                        }
                    }
                }
            }
            else -> return null
        }
        return null
    }

    private fun JsonObject.firstStr(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }

    /** 尺寸字段：数字当字节数格式化，字符串原样（站方可能直接给"1.4 GB"） */
    private fun JsonObject.sizeText(): String? {
        val v = this["size"] ?: this["length"] ?: this["filesize"] ?: this["total_size"] ?: return null
        return when (v) {
            is JsonPrimitive -> if (v.content.toLongOrNull() != null) humanize(v.content.toLong()) else v.content.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun humanize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024; u++
        }
        return (if (v >= 100) "%.0f %s" else "%.1f %s").format(v, units[u])
    }

    /** info_hash → 磁力链（hash 可能带 urn:btih: 前缀） */
    private fun magnetFromHash(hash: String, name: String): String {
        val h = hash.removePrefix("urn:btih:")
        val dn = URLEncoder.encode(name, "UTF-8")
        return "magnet:?xt=urn:btih:$h&dn=$dn"
    }

    /** 从任意文本里抓磁力链（返回 磁力链 to 展示名） */
    internal fun extractMagnets(text: String): List<Pair<String, String>> {
        val re = Regex("magnet:\\?xt=urn:btih:[A-Za-z0-9]{26,42}[^\\s\"'<>]*")
        return re.findAll(text).map { m ->
            val link = m.value
            val dn = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            link to (dn?.takeIf { it.isNotBlank() } ?: link.substringAfter("dn=", "").ifBlank { link })
        }.distinctBy { it.first }.toList()
    }
}
