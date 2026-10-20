package com.open115.pad.util

import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * .torrent 解析层测试。
 *
 * 两条 fixture 是用 Python 的 bencode 现造的（够小、纯 ASCII，所以能直接写在源码里），
 * 期望的 info_hash 也在那边独立算出来写死在这里——**这样一旦我抄错了字节长度，
 * 测试会立刻失败**（info_hash 对不上），不用靠人眼数 `20:AAAA...` 有几个 A。
 */
class TorrentTest {

    /** 单文件 + 两档 announce-list（第一档和 announce 重复，要去重） */
    private fun singleFileTorrent(): ByteArray = buildString {
        append("d")
        append("8:announce35:http://tracker.example.com/announce")
        append("13:announce-list")
        append("ll35:http://tracker.example.com/announcee")
        append("l36:udp://open.example.org:6969/announce34:http://backup.example.net/announceee")
        append("4:info")
        append("d4:name14:test-movie.mkv6:lengthi1073741824e12:piece lengthi262144e")
        append("6:pieces20:").append("A".repeat(20))
        append("e")
        append("e")
    }.toByteArray(Charsets.US_ASCII)

    /** 多文件 + 只有一条 announce（没有 announce-list） */
    private fun multiFileTorrent(): ByteArray = buildString {
        append("d")
        append("8:announce36:udp://only.example.org:6969/announce")
        append("4:info")
        append("d4:name4:pack5:files")
        append("ld6:lengthi1048576e4:pathl1:a5:b.mkvee")
        append("d6:lengthi2048e4:pathl5:c.mp4ee")
        append("d6:lengthi512e4:pathl3:sub5:d.srtee")
        append("e")
        append("12:piece lengthi262144e6:pieces20:").append("B".repeat(20))
        append("e")
        append("e")
    }.toByteArray(Charsets.US_ASCII)

    @Test
    fun `单文件种子 - info_hash 与文件数总大小`() {
        val info = Torrent.parse(singleFileTorrent())
        assertEquals("dfe8162ff85c9f7709866f793d1912faa4733270", info.infoHash)
        assertEquals("test-movie.mkv", info.name)
        assertEquals(1073741824L, info.totalSize)
        assertEquals(1, info.fileCount)
        assertEquals(1, info.files.size)
        assertEquals(Torrent.TorrentInfo.FileEntry("test-movie.mkv", 1073741824L), info.files[0])
    }

    @Test
    fun `tracker 从 announce 与 announce-list 里都取 - 去重保序`() {
        val info = Torrent.parse(singleFileTorrent())
        assertEquals(
            listOf(
                "http://tracker.example.com/announce",
                "udp://open.example.org:6969/announce",
                "http://backup.example.net/announce",
            ),
            info.trackers,
        )
    }

    @Test
    fun `磁力链带 dn 与 tr - 特殊字符百分号编码 - 空格不能变成加号`() {
        val info = Torrent.parse(singleFileTorrent())
        val magnet = Torrent.buildMagnet(info)
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:dfe8162ff85c9f7709866f793d1912faa4733270"))
        assertTrue(magnet.contains("&dn=test-movie.mkv"))
        // 每条 tracker 一个 tr=，https:// 的冒号斜杠要编码
        assertEquals(3, Regex("&tr=").findAll(magnet).count())
        assertTrue(magnet.contains("&tr=http%3A%2F%2Ftracker.example.com%2Fannounce"))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2Fopen.example.org%3A6969%2Fannounce"))
        assertTrue(!magnet.contains("+"))
    }

    @Test
    fun `多文件种子 - 文件清单按路径拼好 - 大小相加`() {
        val info = Torrent.parse(multiFileTorrent())
        assertEquals("54fd44c080aa0e2bb7499831e8dbdeee23e7ab57", info.infoHash)
        assertEquals(3, info.fileCount)
        assertEquals(1048576L + 2048L + 512L, info.totalSize)
        assertEquals(listOf("a/b.mkv", "c.mp4", "sub/d.srt"), info.files.map { it.path })
        assertEquals(listOf(1048576L, 2048L, 512L), info.files.map { it.size })
        assertEquals(listOf("udp://only.example.org:6969/announce"), info.trackers)
    }

    @Test
    fun `没带 tracker 的种子 - trackers 为空但仍能出磁力链`() {
        val bytes = buildString {
            append("d")
            append("4:info")
            append("d4:name4:bare6:lengthi1024e6:pieces20:").append("C".repeat(20))
            append("e")
            append("e")
        }.toByteArray(Charsets.US_ASCII)
        val info = Torrent.parse(bytes)
        assertTrue(info.trackers.isEmpty())
        assertEquals("bare", info.name)
        assertEquals("magnet:?xt=urn:btih:${info.infoHash}&dn=bare", Torrent.buildMagnet(info))
    }

    @Test
    fun `不是种子、缺 info 字段，都给人话错误`() {
        listOf(
            "hello world, not a torrent".toByteArray(),
            ByteArray(0),
            // d 开头但里面没有 info
            "d8:announce35:http://tracker.example.com/announcee".toByteArray(),
        ).forEach { bytes ->
            try {
                Torrent.parse(bytes)
                fail("应当抛 IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
    }
}
