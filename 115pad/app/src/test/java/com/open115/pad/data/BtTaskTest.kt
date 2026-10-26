package com.open115.pad.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BtTaskTest {

    private fun json(s: String): JsonObject =
        Json.parseToJsonElement(s) as JsonObject

    /** 正常响应：size 有数字也有字符串，wanted 是 0/1 */
    @Test
    fun parseKeepsFileOrderAndWantedFlags() {
        val root = json(
            """
            {"state":true,"code":0,"message":"","data":{
              "file_size":3000,"torrent_name":"Some.Show","file_count":3,
              "info_hash":"abc123",
              "torrent_filelist":[
                {"size":1000,"path":"Some.Show/E01.mkv","wanted":1},
                {"size":"1500","path":"Some.Show/E02.mkv","wanted":0},
                {"size":500,"path":"Some.Show/readme.txt","wanted":1}
              ]
            }}
            """.trimIndent()
        )
        val p = BtTask.parse(root)
        assertEquals("abc123", p.infoHash)
        assertEquals("Some.Show", p.name)
        assertEquals(3000, p.totalSize)
        assertEquals(3, p.fileCount)
        assertEquals(listOf(0, 1, 2), p.files.map { it.index })
        assertEquals(listOf(1000L, 1500L, 500L), p.files.map { it.size })
        assertTrue(p.files[0].wanted)
        assertFalse(p.files[1].wanted)
        // 默认只选服务端标了 wanted 的
        assertEquals(setOf(0, 2), p.defaultSelection)
        assertEquals(1500L, p.selectedSize(setOf(0, 2)))
    }

    /** 老种子一个 wanted 都没有 → 默认全选，避免用户以为"不用选"结果一个都没下 */
    @Test
    fun parseSelectsAllWhenNoWantedFlag() {
        val root = json(
            """
            {"state":true,"data":{"info_hash":"h","torrent_filelist":[
              {"size":1,"path":"a","wanted":0},
              {"size":2,"path":"b","wanted":0}
            ]}}
            """.trimIndent()
        )
        val p = BtTask.parse(root)
        assertEquals(setOf(0, 1), p.defaultSelection)
    }

    /** file_size 缺失/为 0 时用文件清单求和兜底 */
    @Test
    fun parseFallsBackToSumWhenFileSizeMissing() {
        val root = json(
            """
            {"state":true,"data":{"info_hash":"h","torrent_filelist":[
              {"size":10,"path":"a","wanted":1},
              {"size":20,"path":"b","wanted":1}
            ]}}
            """.trimIndent()
        )
        assertEquals(30L, BtTask.parse(root).totalSize)
    }

    @Test
    fun parseThrowsOnMissingInfoHash() {
        val root = json("""{"state":true,"data":{"torrent_filelist":[]}}""")
        try {
            BtTask.parse(root)
            fail("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("info_hash"))
        }
    }

    @Test
    fun parseThrowsOnServerError() {
        val root = json("""{"state":false,"code":20018,"message":"文件不存在"}""")
        try {
            BtTask.parse(root)
            fail("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("种子文件不存在"))
        }
    }

    /** 保存子路径：斜杠被替换（否则会在选定目录里凭空多一层），空名/点名字走兜底 */
    @Test
    fun savePathIsSanitized() {
        assertEquals("A_B", BtTask.savePathFor("A/B"))
        assertEquals("A_B", BtTask.savePathFor("A\\B"))
        assertEquals("Some.Show.S01", BtTask.savePathFor("  Some.Show.S01.  "))
        assertEquals("云下载", BtTask.savePathFor("   "))
        assertEquals("云下载", BtTask.savePathFor(".."))
        assertEquals(80, BtTask.savePathFor("x".repeat(200)).length)
    }

    @Test
    fun sha1MatchesKnownValue() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            BtTask.sha1Hex("abc".toByteArray()),
        )
    }
}
