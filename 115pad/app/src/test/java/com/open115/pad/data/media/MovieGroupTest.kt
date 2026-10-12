package com.open115.pad.data.media

import com.open115.pad.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一部一目录」归位与「多片目录让你选」的分组判据：一个条目属于哪一部片。
 *
 * 关键约束：分 CD 的一部片要归成一组（搬的时候一起走、不被拆散），
 * 而目录级共享素材（folder.jpg、.actors）谁都不属于（不能被某一部片搬走）。
 */
class MovieGroupTest {

    private fun file(name: String) = FileItem(fid = "x", fn = name, fc = 1)
    private fun dir(name: String) = FileItem(fid = "d", fn = name, fc = 0)

    @Test
    fun `正片与其同名图与 nfo 归一部`() {
        assertEquals("蜘蛛侠", movieGroupOf(file("蜘蛛侠.mp4")))
        assertEquals("蜘蛛侠", movieGroupOf(file("蜘蛛侠.nfo")))
        assertEquals("蜘蛛侠", movieGroupOf(file("蜘蛛侠-poster.jpg")))
        assertEquals("蜘蛛侠", movieGroupOf(file("蜘蛛侠-fanart.jpg")))
        assertEquals("蜘蛛侠", movieGroupOf(dir("蜘蛛侠-extrafanart")))
    }

    @Test
    fun `分 CD 的两张盘归同一部`() {
        val a = movieGroupOf(file("ABC-201-cd1.mp4"))
        val b = movieGroupOf(file("ABC-201-cd2.mp4"))
        assertEquals(a, b)
        assertEquals("ABC-201", a)
    }

    @Test
    fun `不同片归不同组`() {
        assertEquals("蜘蛛侠", movieGroupOf(file("蜘蛛侠.mp4")))
        assertEquals("金刚", movieGroupOf(file("金刚.mp4")))
    }

    @Test
    fun `目录级共享素材不属于任何一部`() {
        // folder.jpg 基名是 folder、.actors 不是专属剧照目录 → 都不该被某一部片搬走
        assertEquals("folder", movieGroupOf(file("folder.jpg")))
        assertNull(movieGroupOf(dir(".actors")))
        assertNull(movieGroupOf(dir("extrafanart")))
    }

    // ---------------- 剧集目录识别 ----------------

    @Test
    fun `一堆分集文件认成剧集`() {
        val items = listOf(
            file("Game.of.Thrones.S01E01.mkv"),
            file("Game.of.Thrones.S01E02.mkv"),
            file("Game.of.Thrones.S01E03.mkv"),
        )
        assertTrue(looksLikeTvDirectory(items))
    }

    @Test
    fun `季x集命名也认成分集`() {
        assertTrue(isEpisodeFileName("show 1x05 1080p.mkv"))
        // 关键反例：分辨率里的 1080x720 不能被当成"10x5"式分集
        assertFalse(isEpisodeFileName("Movie.1080x720.mkv"))
        // Se7en 不含 s+数字+e，不误判
        assertFalse(isEpisodeFileName("Se7en.2007.mkv"))
    }

    @Test
    fun `本级没视频但有季目录认成剧集根`() {
        assertTrue(looksLikeTvDirectory(listOf(dir("Season 1"), dir("Season 2"))))
        assertTrue(looksLikeTvDirectory(listOf(dir("第2季"))))
    }

    @Test
    fun `两部不同电影混目录不算剧集`() {
        val items = listOf(file("蜘蛛侠.mp4"), file("金刚.mp4"))
        assertFalse(looksLikeTvDirectory(items))
    }

    @Test
    fun `抽季集号 SxxExx 与 季x集`() {
        assertEquals(1 to 5, parseEpisodeNumber("Show.S01E05.1080p.mkv"))
        assertEquals(2 to 12, parseEpisodeNumber("show s2e12.mkv"))
        assertEquals(1 to 5, parseEpisodeNumber("show 1x05 HDTV.mkv"))
        // 分辨率里的 1080x720 不能被当成季x集（前后是数字）
        assertNull(parseEpisodeNumber("Movie.1080x720.mkv"))
        // 认不出季集号
        assertNull(parseEpisodeNumber("蜘蛛侠.mp4"))
        // SxxExx 优先于数字串
        assertEquals(1 to 2, parseEpisodeNumber("Show.S01E02.720p.x264.1080x720.mkv"))
    }
}
