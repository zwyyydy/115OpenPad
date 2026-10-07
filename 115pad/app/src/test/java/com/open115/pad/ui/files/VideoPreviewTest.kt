package com.open115.pad.ui.files

import com.open115.pad.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视频预览图：命名约定 + 列表后处理（隐藏预览图、视频借缩略图）。
 *
 * 用例覆盖配对的边界：配得到才隐藏/借用；配不到（没有对应视频、目录里只有图）一律不动。
 */
class VideoPreviewTest {

    private fun video(name: String, thumb: String? = null, uo: String? = null) =
        FileItem(fn = name, fc = 1, isv = 1, ico = "mp4", thumb = thumb, uo = uo)

    private fun image(name: String, thumb: String? = null, uo: String? = null) =
        FileItem(fn = name, fc = 1, ico = "jpg", thumb = thumb, uo = uo)

    @Test
    fun `命名：基名 + 预览图 + jpg`() {
        assertEquals("1预览图.jpg", videoPreviewFileName("1.mp4"))
        assertEquals("abc.1080p预览图.jpg", videoPreviewFileName("abc.1080p.mp4"))
        assertEquals("无扩展名预览图.jpg", videoPreviewFileName("无扩展名"))
    }

    @Test
    fun `识别：预览图的基名`() {
        assertEquals("1", videoPreviewBaseName("1预览图.jpg"))
        assertEquals("abc.1080p", videoPreviewBaseName("abc.1080p预览图.JPG"))
        assertEquals("1", videoPreviewBaseName("1预览图.png"))
        assertNull(videoPreviewBaseName("1.jpg"))
        assertNull(videoPreviewBaseName("预览图.jpg"))
        assertNull(videoPreviewBaseName("1预览图.gif"))
    }

    @Test
    fun `配对成功：隐藏预览图，视频借到缩略图`() {
        val out = applyVideoPreviews(
            listOf(
                video("1.mp4"),
                image("1预览图.jpg", thumb = "https://t/1.png"),
                video("2.mp4"),
            ),
        )
        assertEquals(listOf("1.mp4", "2.mp4"), out.map { it.fn })
        assertEquals("https://t/1.png", out.first { it.fn == "1.mp4" }.thumb)
        assertNull(out.first { it.fn == "2.mp4" }.thumb)
    }

    @Test
    fun `没有 thumb 时退回原图 uo`() {
        val out = applyVideoPreviews(
            listOf(video("1.mp4"), image("1预览图.jpg", uo = "https://o/1.jpg")),
        )
        assertEquals("https://o/1.jpg", out.first { it.fn == "1.mp4" }.thumb)
    }

    @Test
    fun `配不到视频：预览图照常显示、视频不动`() {
        // 目录里只有预览图，没有 1.mp4
        val out = applyVideoPreviews(listOf(image("1预览图.jpg", thumb = "https://t/1.png")))
        assertEquals(listOf("1预览图.jpg"), out.map { it.fn })

        // 视频名对不上（2.mp4 与 1预览图.jpg）
        val out2 = applyVideoPreviews(listOf(video("2.mp4"), image("1预览图.jpg", thumb = "https://t/1.png")))
        assertEquals(listOf("2.mp4", "1预览图.jpg"), out2.map { it.fn })
        assertNull(out2.first { it.fn == "2.mp4" }.thumb)
    }

    @Test
    fun `目录与文件夹不参与配对`() {
        val dir = FileItem(fn = "1预览图.jpg", fc = 0)
        val out = applyVideoPreviews(listOf(video("1.mp4"), dir))
        assertEquals(listOf("1.mp4", "1预览图.jpg"), out.map { it.fn })
    }

    @Test
    fun `视频自身的 thumb 不被覆盖以外的字段改动`() {
        val out = applyVideoPreviews(
            listOf(video("1.mp4", thumb = "https://old/t.png"), image("1预览图.jpg", thumb = "https://t/1.png")),
        )
        // 配对成功时借用预览图的图；其余字段原样
        assertEquals("https://t/1.png", out[0].thumb)
        assertEquals("1.mp4", out[0].fn)
    }
}
