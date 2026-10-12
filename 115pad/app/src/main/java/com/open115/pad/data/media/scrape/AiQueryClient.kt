package com.open115.pad.data.media.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI 检索词：把一个可能满是噪声的视频文件名/目录名，交给 OpenAI 兼容的大模型洗成干净的片名，
 * 喂给 TMDB 搜索。**只产出检索词，绝不改文件名**（改名要动云盘、作废 scan_state，是另一回事）。
 *
 * 与 [TmdbClient] 同样的取舍：
 *  - 端点 / key / 模型 / 提示词都是**读时现取**的 lambda，设置里改完立刻生效；
 *  - 用**独立的 OkHttpClient**（不带 115 鉴权，也不走 TMDB 那条代理——AI 多半是另一家服务）；
 *  - 一切失败都退回 null（调用方按"AI 没帮上忙、保留原检索词"处理），绝不抛。
 */
class AiQueryClient(
    private val client: OkHttpClient,
    private val endpoint: () -> String,
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /** 三项都填了才算配好；没配好界面上不出现「用 AI 优化」 */
    fun configured(): Boolean =
        endpoint().isNotBlank() && apiKey().isNotBlank() && model().isNotBlank()

    /**
     * 让模型给 [rawName] 产出一行结果（用 [prompt] 这套提示词）。失败/没配/空返回 null。
     *
     * 抛不抛都无所谓——调用方 runCatching 兜住，这里也全程不抛，只回 null。
     */
    suspend fun suggest(rawName: String, prompt: String): String? {
        val name = rawName.trim()
        if (name.isEmpty() || !configured()) return null
        val url = completionsUrl(endpoint()) ?: return null
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model().trim(),
                temperature = 0.0,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", name),
                ),
            ),
        )
        return runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${apiKey().trim()}")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body?.string().orEmpty()
                    val content = json.decodeFromString<ChatResponse>(text).choices.firstOrNull()?.message?.content
                    normalize(content)
                }
            }
        }.getOrNull()
    }

    /** `{基址}/chat/completions`；基址已含该路径就用原样 */
    internal fun completionsUrl(base: String): String? {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return null
        val full = if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        return full.takeIf { it.toHttpUrlOrNull() != null }
    }

    /**
     * 收一收模型的输出：取第一行、去引号/常见前缀（"片名：" "Title:"）与首尾标点。
     * 模型偶尔会啰嗦，这里尽量把它拉回"就一个片名"。
     */
    internal fun normalize(raw: String?): String? {
        val line = raw?.lines()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        var s = line.trim().trim('"', '“', '”', '\'', '「', '」')
        // 去掉 "片名：xxx" / "Title: xxx" 这类前缀
        Regex("""(?i)^\s*(?:片名|标题|title|name)\s*[:：]\s*(.+)$""").find(s)?.let { s = it.groupValues[1].trim() }
        s = s.trimEnd('.', '。', '，', ',').trim()
        return s.takeIf { it.isNotBlank() && it.length <= 120 }
    }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val temperature: Double,
        val messages: List<ChatMessage>,
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Msg? = null)

    @Serializable
    private data class Msg(val content: String? = null)
}
