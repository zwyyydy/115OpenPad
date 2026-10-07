package com.open115.pad.ui.files

import com.open115.pad.data.PathNode
import com.open115.pad.ui.files.FilesViewModel.Companion.normalizePathChain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录栈归一化：把 115 列目录响应里的 `path[]` 变成面包屑要的那条链。
 *
 * 云端那条链**含不含根、含不含被请求的目录自身都不保证**，所以这里把四种组合、
 * 脏节点、连续重复层都打一遍 —— 这层兜住之后，上层就不必关心 path[] 的确切形状。
 */
class PathChainTest {

    private fun node(name: String, cid: String?) = PathNode(name = name, cid = cid)

    private val root = node("全部文件", "0")

    private fun stack(vararg pairs: Pair<String, String>) = pairs.map { FilesViewModel.DirEntry(it.first, it.second) }

    @Test
    fun `含根且含自身：原样就是完整链`() {
        val got = normalizePathChain(
            listOf(root, node("A", "1"), node("B", "2")),
            targetCid = "2",
            targetName = "B",
        )
        assertEquals(stack("0" to "全部文件", "1" to "A", "2" to "B"), got)
    }

    @Test
    fun `不含根时补上全部文件那一层`() {
        val got = normalizePathChain(
            listOf(node("A", "1"), node("B", "2")),
            targetCid = "2",
            targetName = "B",
        )
        assertEquals(stack("0" to "全部文件", "1" to "A", "2" to "B"), got)
    }

    @Test
    fun `不含自身时末尾补目标（名字用本地已知的）`() {
        val got = normalizePathChain(
            listOf(root, node("A", "1")),
            targetCid = "2",
            targetName = "B",
        )
        assertEquals(stack("0" to "全部文件", "1" to "A", "2" to "B"), got)
    }

    @Test
    fun `空链退化成两级：全部文件 + 目标`() {
        val got = normalizePathChain(emptyList(), targetCid = "9", targetName = "X")
        assertEquals(stack("0" to "全部文件", "9" to "X"), got)
    }

    @Test
    fun `目标就是根：只留一层，不拼出全部文件 到 全部文件`() {
        val got = normalizePathChain(listOf(root), targetCid = "0", targetName = "某个根目录下的文件.mp4")
        assertEquals(stack("0" to "全部文件"), got)
    }

    @Test
    fun `丢掉没有 cid 的脏节点`() {
        val got = normalizePathChain(
            listOf(root, node("脏", null), node("脏2", "  "), node("A", "1"), node("B", "2")),
            targetCid = "2",
            targetName = "B",
        )
        assertEquals(stack("0" to "全部文件", "1" to "A", "2" to "B"), got)
    }

    @Test
    fun `根那一层统一叫全部文件（云端给的名字是"文件"）`() {
        val got = normalizePathChain(
            listOf(node("文件", "0"), node("一级目录", "1"), node("二级目录", "2")),
            targetCid = "2",
            targetName = "二级目录",
        )
        assertEquals(stack("0" to "全部文件", "1" to "一级目录", "2" to "二级目录"), got)
    }

    @Test
    fun `连续重复的同一层只留一个`() {
        val got = normalizePathChain(
            listOf(root, node("全部文件", "0"), node("A", "1"), node("A", "1")),
            targetCid = "1",
            targetName = "A",
        )
        assertEquals(stack("0" to "全部文件", "1" to "A"), got)
    }

    @Test
    fun `节点没名字时退回用 cid，不留空白行`() {
        val got = normalizePathChain(
            listOf(root, node("", "1")),
            targetCid = "1",
            targetName = "A",
        )
        assertEquals(stack("0" to "全部文件", "1" to "1"), got)
    }
}
