package com.open115.tv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 115 接口解析的纯逻辑：响应里 `video_url` / `definition_list` / `state` 都有多种写法，
 * 解析错一处就是"电视上看不到清晰度列表"或"切换无效"。
 */
class Tv115ApiTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun parse(text: String): List<TvTier> = parseVideoTiers(obj(text))

    @Test
    fun videoUrl_array_parsesTiersSortedByDefinition() {
        val json = """
            {"state":true,"data":{
              "video_url":[
                {"url":"https://cdn/video-720p/index.m3u8","definition":2,"height":720},
                {"url":"https://cdn/video-1080p/index.m3u8","definition":3,"height":1080},
                {"url":"https://cdn/a-origin.mp4","definition":100,"height":1080}
              ],
              "definition_list_new":{"2":"720P","3":"1080P","100":"原画"}
            }}
        """.trimIndent()
        val tiers = parse(json)
        // 高码率在前；HLS 无后缀按切片，直链按原始文件
        assertEquals(listOf(100, 3, 2), tiers.map { it.def })
        assertEquals("原画", tiers[0].label)
        assertEquals("1080P", tiers[1].label)
        assertFalse(tiers[0].hls)
        assertTrue(tiers[1].hls)
        assertTrue(tiers[2].hls)
    }

    @Test
    fun videoUrl_object_parsesTiers() {
        val json = """
            {"state":true,"data":{
              "video_url":{"1":{"url":"https://cdn/360/index.m3u8","height":360}}
            }}
        """.trimIndent()
        val tiers = parse(json)
        assertEquals(1, tiers.size)
        assertEquals(1, tiers[0].def)
        assertEquals("360P", tiers[0].label)
    }

    @Test
    fun labelsFallBackToHeightOrDefinition() {
        val json = """
            {"state":true,"data":{"video_url":[
              {"url":"https://cdn/a/index.m3u8","definition":4,"height":1080},
              {"url":"https://cdn/b/index.m3u8","definition":5}
            ]}}
        """.trimIndent()
        val tiers = parse(json)
        assertEquals("1080P", tiers.first { it.def == 4 }.label)
        assertEquals("清晰度 5", tiers.first { it.def == 5 }.label)
    }

    @Test
    fun malformedState_isNotOk() {
        assertFalse(stateOk(obj("""{"state":false}""")))
        assertTrue(stateOk(obj("""{"state":"true"}""")))
        assertTrue(stateOk(obj("""{"state":1}""")))
        assertFalse(stateOk(obj("""{"state":0}""")))
        assertFalse(stateOk(obj("""{"code":40140125,"message":"token 过期"}""")))
    }

    @Test
    fun downUrl_parsesBothShapes() {
        assertEquals(
            "https://cdn/x.mp4?sign=1",
            parseDownUrl(obj("""{"state":true,"data":{"123":{"url":{"url":"https://cdn/x.mp4?sign=1"}}}}""")),
        )
        assertEquals(
            "https://cdn/y.mp4",
            parseDownUrl(obj("""{"state":true,"data":{"123":{"url":"https://cdn/y.mp4"}}}""")),
        )
        assertNull(parseDownUrl(obj("""{"state":true,"data":[]}""")))
    }

    @Test
    fun directUrlDetection_matchesTabletRules() {
        assertTrue(isDirectVideoUrl("https://cdn/a.mkv?sign=1"))
        assertTrue(isDirectVideoUrl("https://cdn/a.MP4"))
        assertFalse(isDirectVideoUrl("https://cdn/video-1080p/index.m3u8"))
        // 115 的转码地址没有后缀
        assertFalse(isDirectVideoUrl("https://cdn/xxxx/video"))
    }

    @Test
    fun pickTier_prefersRequestedThenOriginalThenHighest() {
        val tiers = listOf(
            TvTier(999, "原盘", "https://x/origin.mp4", hls = false),
            TvTier(4, "1080P", "https://x/4.m3u8", hls = true),
            TvTier(2, "720P", "https://x/2.m3u8", hls = true),
        )
        assertEquals(2, pickTier(tiers, 2)?.def)
        // 选的档这一集没有 → 原盘
        assertEquals(999, pickTier(tiers, 5)?.def)
        // 原盘也没有（downurl 失败）→ 最高的一档
        assertEquals(4, pickTier(tiers.filter { it.def != 999 }, 999)?.def)
        assertNull(pickTier(emptyList(), 999))
    }
}
