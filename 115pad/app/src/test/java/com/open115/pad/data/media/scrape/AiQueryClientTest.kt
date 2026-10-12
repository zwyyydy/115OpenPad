package com.open115.pad.data.media.scrape

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AI 检索词客户端的两处纯逻辑（不碰网络）：端点拼接 + 模型输出收口。
 */
class AiQueryClientTest {

    private val client = AiQueryClient(
        client = OkHttpClient(),
        endpoint = { "https://api.openai.com/v1" },
        apiKey = { "sk-x" },
        model = { "gpt-4o-mini" },
    )

    @Test
    fun `端点拼 chat-completions`() {
        assertEquals("https://api.openai.com/v1/chat/completions", client.completionsUrl("https://api.openai.com/v1"))
        // 末尾斜杠、已含完整路径都不重复拼
        assertEquals("https://a.b/v1/chat/completions", client.completionsUrl("https://a.b/v1/"))
        assertEquals("https://a.b/v1/chat/completions", client.completionsUrl("https://a.b/v1/chat/completions"))
        assertNull(client.completionsUrl(""))
        assertNull(client.completionsUrl("   "))
    }

    @Test
    fun `收口模型输出`() {
        assertEquals("蜘蛛侠", client.normalize("蜘蛛侠"))
        // 取第一行
        assertEquals("蜘蛛侠 (2002)", client.normalize("蜘蛛侠 (2002)\n额外解释不要"))
        // 去引号
        assertEquals("Big Buck Bunny", client.normalize("\"Big Buck Bunny\""))
        // 去 "片名：" 前缀
        assertEquals("示例影片", client.normalize("片名：示例影片"))
        assertEquals("Sample", client.normalize("Title: Sample"))
        // 去尾部标点
        assertEquals("示例影片", client.normalize("示例影片。"))
        // 空/全空白/超长 → null
        assertNull(client.normalize(null))
        assertNull(client.normalize("   \n  "))
        assertNull(client.normalize("字".repeat(121)))
    }
}
