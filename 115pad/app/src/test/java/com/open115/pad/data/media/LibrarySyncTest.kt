package com.open115.pad.data.media

import com.open115.pad.data.PathNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件页联动里唯一能纯测的一步：**从列表响应的 `path[]` 认出这个目录的完整路径**
 * （见 `resolveDirPath` 的路径三级取值）。
 *
 * 这一步错了的后果是静默的：路径认歪 → `libraryForPath` 匹配不上 → 文件页移进来/删掉的片
 * 在媒体库里一点动静都没有，"库里没更新"这个 bug 原地复活，而日志之外没有任何报错。
 */
class LibrarySyncTest {

    private fun node(name: String, cid: String? = "c1") = PathNode(name = name, cid = cid)

    @Test
    fun `根 + 两级目录_拼出库根认得的路径`() {
        // 实测形状：链首那个 cid=0 的节点是**云盘根**本身（名字是界面叫法），不是目录名
        val nodes = listOf(node("根目录", cid = "0"), node("下载目录", "c1"), node("某片 1080p", "c2"))
        assertEquals("下载目录/某片 1080p", pathOfAncestors(nodes))
    }

    @Test
    fun `没有根节点时原样拼`() {
        assertEquals("test/刮削测试", pathOfAncestors(listOf(node("test"), node("刮削测试"))))
    }

    @Test
    fun `库根自己就是一级`() {
        assertEquals("下载目录", pathOfAncestors(listOf(node("根目录", cid = "0"), node("下载目录"))))
    }

    @Test
    fun `名字里带斜杠也归一化掉`() {
        assertEquals("a/b", pathOfAncestors(listOf(node("/a/"), node("/b/"))))
    }

    @Test
    fun `只有根节点或空链_认不出来`() {
        // 宁可不认（调用方跳过），也不要拼出一条"根目录/…"的假路径去撞库根
        assertNull(pathOfAncestors(listOf(node("根目录", cid = "0"))))
        assertNull(pathOfAncestors(emptyList()))
    }

    @Test
    fun `节点没名字_不拼出空段`() {
        assertEquals("下载目录/某片", pathOfAncestors(listOf(node("根目录", cid = "0"), node("下载目录"), node(""), node("某片"))))
    }
}
