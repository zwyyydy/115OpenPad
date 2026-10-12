package com.open115.pad.data.mcp

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁力搜索客户端的**纯解析层**测试。工具返回的 JSON 形状站方没有文档，
 * 解析必须宽匹配 + 正则兜底 —— 这里把形状钉住，防止以后改宽匹配时把兜底改丢。
 */
class MagnetSearchClientTest {

    private val client = MagnetSearchClient(
        endpoint = "https://example.invalid/api/v1/mcp",
        token = "t",
        http = OkHttpClient(),
    )

    @Test
    fun `结构化搜索结果 - items 数组 - 缺磁力链的行丢弃 - 字节数格式化`() {
        val text = """
            {"items":[
                {"name":"某资源 2024 1080p","magnet":"magnet:?xt=urn:btih:${"a".repeat(40)}&dn=x","size":1073741824,"date":"2024-05-01"},
                {"name":"这条没有磁力链","size":5},
                {"name":"hash 型","info_hash":"${"b".repeat(40)}","size":2048}
            ]}
        """.trimIndent()
        val items = client.parseSearchPayload(text)
        assertEquals(2, items.size)
        assertEquals("某资源 2024 1080p", items[0].name)
        assertEquals("1.0 GB", items[0].size)
        assertEquals("2024-05-01", items[0].date)
        // info_hash 字段要补造成完整磁力链
        assertEquals("magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=hash+%E5%9E%8B", items[1].magnet)
        assertEquals("hash 型", items[1].name)
    }

    @Test
    fun `results 数组与字符串尺寸也认`() {
        val text = """{"results":[{"title":"另一部","magnet":"magnet:?xt=urn:btih:${"c".repeat(40)}","size":"1.4 GB"}]}"""
        val items = client.parseSearchPayload(text)
        assertEquals(1, items.size)
        assertEquals("另一部", items[0].name)
        assertEquals("1.4 GB", items[0].size)
    }

    @Test
    fun `非 JSON 文本 - 正则抓磁力链兜底 - dn 解码当名字`() {
        val text = "搜索结果页原文…… <a href='magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=%E6%9F%90%E7%89%87'>链</a>"
        val items = client.parseSearchPayload(text)
        assertEquals(1, items.size)
        assertTrue(items[0].magnet.startsWith("magnet:?xt=urn:btih:" + "d".repeat(40)))
        assertEquals("某片", items[0].name)
    }

    @Test
    fun `空文本返回空列表不抛异常`() {
        assertTrue(client.parseSearchPayload("").isEmpty())
        assertTrue(client.parseSearchPayload("服务繁忙，请稍后再试").isEmpty())
    }

    @Test
    fun `SSE 帧拆解 - 空行分隔的 data 行`() {
        val body = "event: message\ndata: {\"id\":1,\"result\":{}}\n\nevent: message\ndata: {\"id\":2}\r\n\r\n注释块无data"
        val frames = client.sseDataFrames(body)
        assertEquals(2, frames.size)
        assertTrue(frames[0].contains("\"id\":1"))
        assertTrue(frames[1].contains("\"id\":2"))
    }

    @Test
    fun `文件结构解析 - files 数组`() {
        val text = """{"files":[{"path":"Dir/Sub/a.mkv","size":734003200},{"path":"Dir/readme.txt","size":"1.4 GB"}]}"""
        val info = client.parseFilesPayload(text)
        assertEquals(2, info.files.size)
        assertEquals("Dir/Sub/a.mkv", info.files[0].name)
        assertEquals("700 MB", info.files[0].size)
        assertEquals("1.4 GB", info.files[1].size)
    }

    @Test
    fun `文件结构解析失败 - 保留原文供界面兜底展示`() {
        val info = client.parseFilesPayload("服务繁忙，请稍后再试")
        assertTrue(info.files.isEmpty())
        assertEquals("服务繁忙，请稍后再试", info.raw)
    }

    @Test
    fun `重复磁力链去重`() {
        val link = "magnet:?xt=urn:btih:" + "e".repeat(40)
        val text = "$link $link $link"
        assertEquals(1, client.parseSearchPayload(text).size)
    }
}
