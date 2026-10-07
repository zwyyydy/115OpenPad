package com.open115.pad.ui.files

import com.open115.pad.ui.files.FilesViewModel.DirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目录栈存档（"退出播放器回到原目录"那件事的存档格式）。
 *
 * 只测纯编解码：ViewModel 本身构造要 OpenApi / 主线程调度器，不合适在 JVM 单测里建；
 * 存档的全部逻辑就是"栈 ⇄ JSON 字符串"这一对函数，脏存档必须安静地退化成"没有存档"。
 */
class DirStackCodecTest {

    private val deep = listOf(
        DirEntry("0", "全部文件"),
        DirEntry("123", "电影"),
        DirEntry("456", "名字里带\"引号\"、逗号和\\反斜杠 的目录"),
    )

    @Test
    fun `往返：cid 与名字都原样保留`() {
        assertEquals(deep, FilesViewModel.decodeStack(FilesViewModel.encodeStack(deep)))
    }

    @Test
    fun `坏存档与空存档一律当没有存档（退回根目录）`() {
        assertNull(FilesViewModel.decodeStack(null))
        assertNull(FilesViewModel.decodeStack(""))
        assertNull(FilesViewModel.decodeStack("   "))
        assertNull(FilesViewModel.decodeStack("{"))
        assertNull(FilesViewModel.decodeStack("[]"))
        assertNull(FilesViewModel.decodeStack("""[{"cid":123,"name":"旧格式"}]"""))
    }
}
