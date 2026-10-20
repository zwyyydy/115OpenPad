package com.open115.pad.data.media.scrape

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** 补充刮削的 nfo 合并：只补缺的、已有字段与未知标签原样保留 */
class NfoMergerTest {

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun texts(doc: Document, tag: String): List<String> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }

    private fun actorNames(doc: Document): List<String> {
        val actors = doc.getElementsByTagName("actor")
        return (0 until actors.length).map { i ->
            val names = (actors.item(i) as Element).getElementsByTagName("name")
            if (names.length == 0) "" else names.item(0).textContent.trim()
        }
    }

    private fun uniqueId(doc: Document, type: String): String? {
        val nodes = doc.getElementsByTagName("uniqueid")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("type").equals(type, ignoreCase = true)) return el.textContent.trim()
        }
        return null
    }

    @Test
    fun keepsExistingScalarsAndFillsMissing() {
        val existing = """<?xml version="1.0" encoding="UTF-8"?>
            <movie>
              <title>旧标题</title>
              <plot></plot>
              <rating>7.0</rating>
              <genre>剧情</genre>
              <actor><name>演员甲</name><role>角色甲</role></actor>
            </movie>"""
        val incoming = """<movie>
              <title>新标题</title>
              <plot>新简介</plot>
              <rating>8.5</rating>
              <tagline>标语</tagline>
              <genre>剧情</genre><genre>爱情</genre>
              <actor><name>演员甲</name></actor>
              <actor><name>演员乙</name><role>角色乙</role></actor>
              <uniqueid type="tmdb" default="true">999</uniqueid>
            </movie>"""

        val r = NfoMerger.merge(existing, incoming)!!
        assertTrue(r.added > 0)
        val d = parse(r.xml)
        // 已有非空字段不被覆盖
        assertEquals(listOf("旧标题"), texts(d, "title"))
        assertEquals(listOf("7.0"), texts(d, "rating"))
        // 空字段补上
        assertEquals(listOf("新简介"), texts(d, "plot"))
        // 列表按值去重后追加
        assertEquals(listOf("剧情", "爱情"), texts(d, "genre"))
        // 演员按名字去重
        assertEquals(listOf("演员甲", "演员乙"), actorNames(d))
        // uniqueid 缺失就补
        assertEquals("999", uniqueId(d, "tmdb"))
    }

    @Test
    fun preservesUnknownTagsAndStructure() {
        val existing = """<movie>
              <title>甲</title>
              <fileinfo><streamdetails><video><codec>h264</codec><width>1920</width></video></streamdetails></fileinfo>
              <dateadded>2026-01-02 03:04:05</dateadded>
              <customtag>厂商自定义</customtag>
            </movie>"""
        val incoming = """<movie>
              <title>乙</title>
              <plot>新简介</plot>
            </movie>"""

        val r = NfoMerger.merge(existing, incoming)!!
        val d = parse(r.xml)
        assertEquals(listOf("甲"), texts(d, "title"))
        assertEquals(listOf("新简介"), texts(d, "plot"))
        assertEquals(listOf("h264"), texts(d, "codec"))
        assertEquals(listOf("1920"), texts(d, "width"))
        assertEquals(listOf("2026-01-02 03:04:05"), texts(d, "dateadded"))
        assertEquals(listOf("厂商自定义"), texts(d, "customtag"))
    }

    @Test
    fun noChangeReturnsOriginal() {
        val existing = "<movie><title>甲</title><rating>8.0</rating></movie>"
        val incoming = "<movie><title>乙</title><rating>9.0</rating><plot>简介</plot></movie>"
        // plot 缺失 → 会补；先验证"全部齐全"的情况
        val full = "<movie><title>甲</title><rating>8.0</rating><plot>简介</plot></movie>"
        val r = NfoMerger.merge(full, incoming)!!
        assertEquals(0, r.added)
        assertEquals(full, r.xml)
        // 对照：有缺的就会补
        val r2 = NfoMerger.merge(existing, incoming)!!
        assertEquals(1, r2.added)
    }

    @Test
    fun existingNfoWithSameUniqueIdKeepsTheOldValue() {
        val existing = """<movie><title>甲</title>
            <uniqueid type="tmdb" default="true">111</uniqueid>
            <set><name>旧合集</name></set>
        </movie>"""
        val incoming = """<movie><title>乙</title>
            <uniqueid type="tmdb" default="true">222</uniqueid>
            <uniqueid type="imdb">tt123</uniqueid>
            <set><name>新合集</name></set>
        </movie>"""

        val r = NfoMerger.merge(existing, incoming)!!
        val d = parse(r.xml)
        assertEquals("111", uniqueId(d, "tmdb"))
        assertEquals("tt123", uniqueId(d, "imdb"))
        assertEquals(listOf("旧合集"), texts(d, "name"))
    }

    @Test
    fun invalidExistingNfoReturnsNull() {
        assertNull(NfoMerger.merge("not an xml", "<movie><title>x</title></movie>"))
    }

    @Test
    fun tvRootIsKept() {
        val existing = "<tvshow><title>剧名</title></tvshow>"
        val incoming = "<tvshow><title>别的剧名</title><plot>简介</plot></tvshow>"
        val r = NfoMerger.merge(existing, incoming)!!
        val d = parse(r.xml)
        assertEquals("tvshow", d.documentElement.tagName)
        assertEquals(listOf("剧名"), texts(d, "title"))
        assertEquals(listOf("简介"), texts(d, "plot"))
    }

    // ---------------- 手动编辑：受管字段整体替换，其余标签保留 ----------------

    @Test
    fun manualEditReplacesManagedFieldsAndKeepsUnknown() {
        val existing = """<movie>
              <title>旧标题</title><plot>旧简介</plot><rating>7.0</rating>
              <genre>剧情</genre><genre>爱情</genre>
              <actor><name>甲</name></actor><actor><name>乙</name></actor>
              <votes>1234</votes><sorttitle>排序名</sorttitle><tag>自定义标签</tag>
              <outline>大纲</outline><dateadded>2026-01-01</dateadded>
              <fileinfo><streamdetails><video><codec>h264</codec></video></streamdetails></fileinfo>
            </movie>"""
        val incoming = """<movie>
              <title>新标题</title><plot>新简介</plot><rating>8.5</rating>
              <genre>悬疑</genre>
              <actor><name>丙</name></actor>
            </movie>"""

        val xml = NfoMerger.replaceManaged(existing, incoming)!!
        val d = parse(xml)
        // 表单里的字段按新值整体替换
        assertEquals(listOf("新标题"), texts(d, "title"))
        assertEquals(listOf("新简介"), texts(d, "plot"))
        assertEquals(listOf("8.5"), texts(d, "rating"))
        assertEquals(listOf("悬疑"), texts(d, "genre"))
        assertEquals(listOf("丙"), actorNames(d))
        // 表单没有的标签原样保留
        assertEquals(listOf("1234"), texts(d, "votes"))
        assertEquals(listOf("排序名"), texts(d, "sorttitle"))
        assertEquals(listOf("自定义标签"), texts(d, "tag"))
        assertEquals(listOf("大纲"), texts(d, "outline"))
        assertEquals(listOf("2026-01-01"), texts(d, "dateadded"))
        assertEquals(listOf("h264"), texts(d, "codec"))
    }

    @Test
    fun manualEditRemovesFieldsClearedInForm() {
        val existing = """<movie><title>甲</title>
            <tagline>旧标语</tagline><set><name>旧合集</name></set><trailer>旧预告</trailer>
        </movie>"""
        val incoming = "<movie><title>乙</title></movie>"
        val d = parse(NfoMerger.replaceManaged(existing, incoming)!!)
        assertEquals(listOf("乙"), texts(d, "title"))
        assertTrue(texts(d, "tagline").isEmpty())
        assertTrue(texts(d, "set").isEmpty())
        assertTrue(texts(d, "trailer").isEmpty())
    }

    @Test
    fun manualEditReplacesTmdbButKeepsTvdb() {
        val existing = """<movie><title>甲</title>
            <uniqueid type="tmdb">111</uniqueid><uniqueid type="tvdb">tv9</uniqueid>
            <tmdbid>111</tmdbid><imdbid>tt0</imdbid>
        </movie>"""
        val incoming = """<movie><title>甲</title>
            <uniqueid type="tmdb" default="true">222</uniqueid><tmdbid>222</tmdbid>
        </movie>"""
        val d = parse(NfoMerger.replaceManaged(existing, incoming)!!)
        assertEquals("222", uniqueId(d, "tmdb"))
        // 表单里没有 tvdb：保留原来的
        assertEquals("tv9", uniqueId(d, "tvdb"))
        assertEquals(listOf("222"), texts(d, "tmdbid"))
        // 表单里清空了 IMDb → 老 imdbid 要删掉
        assertTrue(texts(d, "imdbid").isEmpty())
    }

    @Test
    fun manualEditInvalidExistingReturnsNull() {
        assertNull(NfoMerger.replaceManaged("not xml", "<movie><title>x</title></movie>"))
    }
}
