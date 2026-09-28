package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类整理的纯逻辑：扩展名 → 分类下标。分类文件夹名与顺序都由这张表决定，
 * 标错了就是"整理完文件进了错误的文件夹"，所以单独测。
 */
class FileClassifierTest {

    @Test
    fun categoriesIsSixPlusOther() {
        assertEquals(7, FileClassifier.CATEGORIES.size)
        assertEquals("其他", FileClassifier.CATEGORIES.last())
        assertEquals(
            listOf("视频", "音频", "文本", "应用程序", "图片", "压缩包"),
            FileClassifier.CATEGORIES.dropLast(1),
        )
    }

    @Test
    fun commonExtensionsLandInRightBucket() {
        assertEquals(0, FileClassifier.indexOf("电影.mp4"))
        assertEquals(0, FileClassifier.indexOf("某某.mkv"))
        assertEquals(1, FileClassifier.indexOf("track01.flac"))
        assertEquals(1, FileClassifier.indexOf("有声书.m4a"))
        assertEquals(2, FileClassifier.indexOf("某片.nfo"))
        assertEquals(2, FileClassifier.indexOf("subtitle.chs.ass"))
        assertEquals(2, FileClassifier.indexOf("论文.pdf"))
        assertEquals(3, FileClassifier.indexOf("setup.apk"))
        assertEquals(3, FileClassifier.indexOf("tool.exe"))
        assertEquals(4, FileClassifier.indexOf("poster.jpg"))
        assertEquals(4, FileClassifier.indexOf("截图.PNG"))
        assertEquals(5, FileClassifier.indexOf("backup.zip"))
        assertEquals(5, FileClassifier.indexOf("资源.rar"))
        assertEquals(5, FileClassifier.indexOf("pack.7z"))
        assertEquals(5, FileClassifier.indexOf("backup.tar.gz"))
    }

    @Test
    fun edgeCaseNamesFallBackToOther() {
        // 无扩展名 / 隐藏文件 / 以点结尾 / 光盘镜像种子等认不出的 → 其他（下标 6）
        assertEquals(6, FileClassifier.indexOf("README"))
        assertEquals(6, FileClassifier.indexOf(".gitignore"))
        assertEquals(6, FileClassifier.indexOf("file."))
        assertEquals(6, FileClassifier.indexOf("resource.torrent"))
        assertEquals(6, FileClassifier.indexOf("盘片.iso"))
    }
}
