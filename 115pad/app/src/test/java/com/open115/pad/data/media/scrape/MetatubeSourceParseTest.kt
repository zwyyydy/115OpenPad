package com.open115.pad.data.media.scrape

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HTTP 服务源的 JSON 解析单测：喂真实形状的响应（snake_case、日期两种写法、
 * 空值/缺字段），验证到 [ScrapeCandidate] / [ScrapedMetadata] 的映射。
 * 不发网络请求 —— 构造函数给的 OkHttpClient 不会被用到。
 */
class MetatubeSourceParseTest {

    private val source = MetatubeSource(OkHttpClient(), baseUrl = { "http://192.0.2.5:8090" })

    @Test
    fun search_mapsSnakeCaseFields() {
        val root = """
            [
              {"id":"abc-123","number":"ABC-123","title":"サンプル作品","provider":"fanza",
               "homepage":"https://www.dmm.co.jp/x","thumb_url":"https://pics.dmm.co.jp/t.jpg",
               "cover_url":"https://pics.dmm.co.jp/c.jpg","score":4.2,
               "actors":["演員甲","演員乙"],"release_date":"2024-06-07"},
              {"id":"","title":"缺 id 的脏数据应当被滤掉","provider":"javbus"}
            ]
        """.trimIndent()
        val list = source.parseSearchResponse(root)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("metatube", c.sourceId)
        assertEquals("fanza:abc-123", c.id)
        assertEquals("サンプル作品", c.title)
        assertEquals("ABC-123", c.number)
        assertEquals(2024, c.year)
        assertEquals("https://pics.dmm.co.jp/t.jpg", c.posterUrl)
        assertEquals(4.2, c.rating!!, 0.0001)
    }

    @Test
    fun search_emptyArray_isEmpty() {
        assertEquals(0, source.parseSearchResponse("[]").size)
    }

    @Test
    fun info_mapsAllWritableFields() {
        val root = """
            {"id":"abc-123","number":"ABC-123","title":"サンプル作品",
             "summary":"あらすじ","provider":"fanza","homepage":"https://h",
             "director":"監督A、監督B","actors":["演員甲","演員乙"],
             "thumb_url":"https://t.jpg","big_thumb_url":"https://bt.jpg",
             "cover_url":"https://c.jpg","big_cover_url":"https://bc.jpg",
             "preview_video_url":"https://pv.mp4",
             "preview_images":["https://p1.jpg","https://p2.jpg"],
             "maker":"メーカー","series":"シリーズ","genres":["ジャンル1","ジャンル2"],
             "score":4.5,"runtime":150,"release_date":"2024-06-07T00:00:00+09:00"}
        """.trimIndent()
        val m = source.parseInfoResponse(root, "fanza", "abc-123", "回退标题", ScrapeKind.MOVIE)
        assertEquals("metatube-fanza-abc-123", m.sourceKey)
        assertEquals("サンプル作品", m.title)
        assertEquals("あらすじ", m.plot)
        assertEquals("2024-06-07", m.premiered)
        assertEquals(2024, m.year)
        assertEquals(150, m.runtimeMinutes)
        assertEquals(4.5, m.rating!!, 0.0001)
        assertEquals(listOf("ジャンル1", "ジャンル2"), m.genres)
        assertEquals(listOf("メーカー"), m.studios)
        // 导演串里的中文顿号/逗号都拆
        assertEquals(listOf("監督A", "監督B"), m.directors)
        assertEquals("シリーズ", m.set)
        assertEquals("https://pv.mp4", m.trailer)
        assertEquals(listOf(ScrapedActor("演員甲"), ScrapedActor("演員乙")), m.actors)
        assertEquals("https://bc.jpg", m.posterUrl)
        assertEquals("https://bt.jpg", m.fanartUrl)
    }

    @Test
    fun info_missingOptionalFields_stayNull() {
        val root = """{"id":"x","number":"X-1","title":"T","provider":"fanza","homepage":"https://h"}"""
        val m = source.parseInfoResponse(root, "fanza", "x", "回退", ScrapeKind.MOVIE)
        assertNull(m.plot)
        assertNull(m.premiered)
        assertNull(m.year)
        assertNull(m.runtimeMinutes)
        assertNull(m.rating)
        assertNull(m.set)
        assertNull(m.trailer)
        assertNull(m.posterUrl)
        assertNull(m.fanartUrl)
        assertEquals(0, m.genres.size)
        assertEquals(0, m.actors.size)
    }

    @Test
    fun info_emptyStringBigCover_fallsBackToCover() {
        // FANZA 数字版实测：big_cover_url 是 "" 不是 null，elvis 挡不住空串
        val root = """
            {"id":"abc00123","number":"ABC-123","title":"T","provider":"FANZA","homepage":"https://h",
             "big_cover_url":"","cover_url":"https://c.jpg","big_thumb_url":"","thumb_url":"https://t.jpg"}
        """.trimIndent()
        val m = source.parseInfoResponse(root, "FANZA", "abc00123", "回退", ScrapeKind.MOVIE)
        assertEquals("https://c.jpg", m.posterUrl)
        assertEquals("https://t.jpg", m.fanartUrl)
    }

    @Test
    fun search_envelopeShape_fromRealServer() {
        // 真服务器的形状：gin 层包 {"data": [...]} 信封（上报过"无法解析"就是漏了它）
        val root = """
            {"data":[{"id":"abc00123","number":"ABC-123","title":"サンプル作品","provider":"FANZA",
               "homepage":"https://h","thumb_url":"https://t.jpg","score":4.76,
               "actors":["演員甲"],"release_date":"2024-06-07T00:00:00Z"}]}
        """.trimIndent()
        val list = source.parseSearchResponse(root)
        assertEquals(1, list.size)
        assertEquals("FANZA:abc00123", list[0].id)
        assertEquals(2024, list[0].year)
        assertEquals("ABC-123", list[0].number)
    }

    @Test
    fun info_envelopeShape_fromRealServer() {
        val root = """
            {"data":{"id":"abc00123","number":"ABC-123","title":"T","summary":"あらすじ",
              "provider":"FANZA","homepage":"https://h","actors":["演員甲"],
              "big_cover_url":"","cover_url":"https://c.jpg","preview_images":["https://p1.jpg"],
              "maker":"メーカー","genres":["ジャンル3"],"score":4.76,"runtime":163,
              "release_date":"2024-06-07T01:00:00Z"}}
        """.trimIndent()
        val m = source.parseInfoResponse(root, "FANZA", "abc00123", "回退", ScrapeKind.MOVIE)
        assertEquals("あらすじ", m.plot)
        assertEquals("2024-06-07", m.premiered)
        assertEquals(163, m.runtimeMinutes)
        assertEquals("https://c.jpg", m.posterUrl)
        assertEquals(listOf("メーカー"), m.studios)
    }

    @Test
    fun backdrops_takesPreviewImagesUpToMax() {
        val root = """{"preview_images":["https://p1.jpg","https://p2.jpg","https://p3.jpg"]}"""
        assertEquals(listOf("https://p1.jpg", "https://p2.jpg"), source.parseBackdropUrls(root, 2))
        assertEquals(3, source.parseBackdropUrls(root, 10).size)
        assertEquals(0, source.parseBackdropUrls("{}", 5).size)
        assertEquals(0, source.parseBackdropUrls("not json", 5).size)
    }
}
