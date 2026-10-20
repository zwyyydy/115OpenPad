package com.open115.pad.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 标签球的排布与投影。
 *
 * 这些判据全靠眼睛看是看不出来的："撒点是不是真的铺匀了""近大远小有没有反"在静态截图上
 * 都像是对的，只有把它钉成数字才拦得住（尤其是改了焦距或投影公式之后）。
 */
class TagBallGeometryTest {

    private fun len(p: TagBallGeometry.Point) = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

    @Test
    fun `撒点全部落在单位球面上`() {
        listOf(1, 2, 7, 64, 110, 500).forEach { n ->
            TagBallGeometry.spherePoints(n).forEach {
                assertEquals("n=$n 时有点不在球面上", 1f, len(it), 1e-4f)
            }
        }
    }

    @Test
    fun `两极不各挂一条`() {
        // i + 0.5 的用意：不偏半格的话首尾两点正好落在 (0,±1,0)，看起来是"上下各挂了一个"
        val pts = TagBallGeometry.spherePoints(20)
        assertTrue(pts.none { abs(abs(it.y) - 1f) < 1e-3f })
        // 但上下半球都要有点，不然球是半边的
        assertTrue(pts.any { it.y > 0.6f })
        assertTrue(pts.any { it.y < -0.6f })
    }

    @Test
    fun `撒点铺得开 不挤在一起`() {
        // 最近的邻居距离：斐波那契螺旋在 n 个点时约 2/sqrt(n)，留一半余量就算"铺开"
        val n = 110
        val pts = TagBallGeometry.spherePoints(n)
        var min = Float.MAX_VALUE
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val d = sqrt(
                    (pts[i].x - pts[j].x) * (pts[i].x - pts[j].x) +
                        (pts[i].y - pts[j].y) * (pts[i].y - pts[j].y) +
                        (pts[i].z - pts[j].z) * (pts[i].z - pts[j].z),
                )
                if (d < min) min = d
            }
        }
        assertTrue("最近两点只差 $min，撒点没铺开", min > 1f / sqrt(n.toFloat()))
    }

    @Test
    fun `不转与整圈之后回到原位`() {
        val p = TagBallGeometry.spherePoints(30).first()
        val same = TagBallGeometry.rotate(p, yaw = 0f, pitch = 0f)
        assertEquals(p.x, same.x, 1e-5f)
        assertEquals(p.y, same.y, 1e-5f)
        assertEquals(p.z, same.z, 1e-5f)

        val turn = TagBallGeometry.rotate(p, yaw = (2 * Math.PI).toFloat(), pitch = 0f)
        assertEquals(p.x, turn.x, 1e-4f)
        assertEquals(p.z, turn.z, 1e-4f)
    }

    @Test
    fun `旋转保持单位长度`() {
        TagBallGeometry.spherePoints(40).forEach { p ->
            val r = TagBallGeometry.rotate(p, yaw = 1.1f, pitch = -0.4f)
            assertEquals(1f, len(r), 1e-4f)
        }
    }

    @Test
    fun `近大远小`() {
        val radius = 400f
        val focal = radius * 2.8f
        val near = TagBallGeometry.project(
            TagBallGeometry.Point(0f, 0f, 1f), radius, focal,
        )
        val far = TagBallGeometry.project(
            TagBallGeometry.Point(0f, 0f, -1f), radius, focal,
        )
        assertTrue("正面的字没有比背面大", near.scale > far.scale)
        assertTrue("背面应该在球心偏上（y 不变、只有缩放）", far.scale > 0.5f)
        // 球心那个点缩放恰为 1（不放大也不缩小）
        assertEquals(1f, TagBallGeometry.project(
            TagBallGeometry.Point(1f, 0f, 0f), radius, focal,
        ).scale, 1e-4f)
    }

    @Test
    fun `焦距小于半径也不会把近点甩到无穷远`() {
        val radius = 400f
        TagBallGeometry.spherePoints(64).forEach { p ->
            val q = TagBallGeometry.project(p, radius, focal = radius * 0.5f)
            assertTrue("缩放翻了：${q.scale}", q.scale > 0f && q.scale < 7f)
        }
    }

    @Test
    fun `深度越大越不透明 且始终看得见`() {
        assertTrue(TagBallGeometry.alphaOf(1f) > TagBallGeometry.alphaOf(0f))
        assertTrue(TagBallGeometry.alphaOf(0f) > TagBallGeometry.alphaOf(-1f))
        assertTrue(TagBallGeometry.alphaOf(-1f) >= 0.2f)
        assertTrue(TagBallGeometry.alphaOf(1f) <= 1f)
    }

    @Test
    fun `权重随计数单调 且开方压过一道`() {
        assertEquals(1f, TagBallGeometry.weight(1500, 1500), 1e-4f)
        assertEquals(0f, TagBallGeometry.weight(0, 1500), 1e-4f)
        assertTrue(TagBallGeometry.weight(200, 1500) > 200f / 1500f) // 线性映射会把小词压没了
        assertTrue(TagBallGeometry.weight(200, 1500) < 1f)
    }

    @Test
    fun `字号在范围内 且窄屏整体缩一档`() {
        val big = TagBallGeometry.fontSizeSp(1500, 1500, compact = false)
        val small = TagBallGeometry.fontSizeSp(0, 1500, compact = false)
        assertEquals(26f, big, 1e-3f)
        assertEquals(11.5f, small, 1e-3f)
        assertTrue(TagBallGeometry.fontSizeSp(1500, 1500, compact = true) < big)
        // 计数为 0 / maxCount 为 0 这类退化输入不能炸，也不该给出负数
        assertTrue(TagBallGeometry.fontSizeSp(0, 0, compact = false) >= 11.5f)
    }

    @Test
    fun `同一个名字永远同一个颜色 且下标不越界`() {
        val names = listOf("A", "示例标签甲", "", "x".repeat(50), "008")
        names.forEach { name ->
            val a = TagBallGeometry.colorIndex(name, 6)
            assertEquals(a, TagBallGeometry.colorIndex(name, 6))
            assertTrue("[$name] 下标越界：$a", a in 0 until 6)
        }
        assertNotEquals(
            TagBallGeometry.colorIndex("示例标签甲", 6),
            TagBallGeometry.colorIndex("示例标签乙", 6),
        )
    }
}

/**
 * 谁进球（保底 / 限量 / 打散）。
 *
 * 这几条规矩都是"不写下来就会忘"的类型：保底是为了不让演员把标签挤光，
 * 但保底一旦写成"两族各占一半"又会把头名砍掉 —— 用例把两头都钉住。
 */
class TagBallPickTest {

    private fun cand(name: String, kind: WorksKind, count: Int) = BallCandidate(name, kind, count)

    /** 造一个"演员全是大词、标签全是小词"的库（真实库里最常见的那种） */
    private fun actorHeavy(actors: Int, tags: Int): List<BallCandidate> =
        (0 until actors).map { cand("演员$it", WorksKind.Actor, 900 - it) } +
            (0 until tags).map { cand("标签$it", WorksKind.Tag, 90 - it) }

    @Test
    fun `不超过限量`() {
        val all = actorHeavy(500, 500)
        assertEquals(100, TagBallPick.pick(all, 100).size)
    }

    @Test
    fun `候选不够时全给`() {
        val all = actorHeavy(3, 4)
        assertEquals(7, TagBallPick.pick(all, 100).size)
    }

    @Test
    fun `一族压倒另一族时 另一族也进得来`() {
        // 纯按计数取前 100 会全是演员（演员计数 900 起、标签最高才 90）——
        // 保底就是防这个：标签至少占 1/4 的名额
        val picked = TagBallPick.pick(actorHeavy(500, 500), 100)
        val tags = picked.count { it.kind == WorksKind.Tag }
        assertTrue("标签只进了 $tags 条", tags >= 25)
        assertTrue(picked.any { it.kind == WorksKind.Actor })
    }

    @Test
    fun `保底不会把头名砍掉`() {
        // 库里最大的标签（1800 部）必须在球上，哪怕演员那边词更大
        val all = actorHeavy(500, 500) + cand("最大的标签", WorksKind.Tag, 1800)
        val picked = TagBallPick.pick(all, 100)
        assertTrue(picked.any { it.name == "最大的标签" })
    }

    @Test
    fun `同名但不同族的不会互相顶掉`() {
        val all = (0 until 400).map { cand("同一个名字", WorksKind.Actor, 500 - it) } +
            (0 until 400).map { cand("同一个名字", WorksKind.Tag, 500 - it) }
        val picked = TagBallPick.pick(all, 100)
        assertTrue(picked.any { it.kind == WorksKind.Actor })
        assertTrue(picked.any { it.kind == WorksKind.Tag })
    }

    @Test
    fun `限量不为正时给空表`() {
        assertTrue(TagBallPick.pick(actorHeavy(10, 10), 0).isEmpty())
    }

    @Test
    fun `打散是同一个多重集 且每次一样`() {
        val all = actorHeavy(30, 30)
        val once = TagBallPick.scatter(all)
        assertEquals(all.size, once.size)
        assertEquals(all.sortedBy { it.name }.map { it.name }, once.sortedBy { it.name }.map { it.name })
        assertEquals(once.map { it.name }, TagBallPick.scatter(all).map { it.name })
    }

    @Test
    fun `打散之后同族不再连成一片`() {
        // pick 交出来的是"演员一批 + 标签一批"，直接摆上球面会按族分成两条带子
        val blocked = (0 until 20).map { cand("演员$it", WorksKind.Actor, 100 - it) } +
            (0 until 20).map { cand("标签$it", WorksKind.Tag, 100 - it) }
        fun sameFamilyNeighbors(list: List<BallCandidate>) =
            list.zipWithNext().count { it.first.kind == it.second.kind }
        assertEquals(38, sameFamilyNeighbors(blocked))
        assertTrue(
            "打散后同族邻居还有 ${sameFamilyNeighbors(TagBallPick.scatter(blocked))} 对",
            sameFamilyNeighbors(TagBallPick.scatter(blocked)) < 30,
        )
    }

    @Test
    fun `系列标签显示时去掉前缀 查询仍用原名`() {
        assertEquals("示例系列", TagBallPick.labelOf("系列: 示例系列"))
        assertEquals("示例系列", TagBallPick.labelOf("系列：示例系列"))
        assertEquals("普通标签", TagBallPick.labelOf("普通标签"))
        // 名字就叫"系列"的：去完前缀是空的，退回原名（球上总得有字）
        assertEquals("系列", TagBallPick.labelOf("系列"))
    }
}
