package com.open115.pad.ui.media

import com.open115.pad.data.media.TagSignals
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 标签球的**排布与投影**：纯函数、零 Compose 依赖，单独提出来是为了能直接跑单测
 * （球面撒点是不是真的铺匀了、"近大远小"有没有反，靠眼睛看是看不出来的）。
 *
 * 球面撒点用**斐波那契螺旋**（黄金角 2.399963…）：按 `i * 黄金角` 转一圈，
 * 高度 `y` 从 1 均匀降到 -1，剩下的半径由 `sqrt(1 - y²)` 补齐 —— 点自动落在单位球面上，
 * 而且看不出条带（经纬度网格那种"两极挤成一坨"的毛病它没有）。
 *
 * 投影是**透视**而不是正交：正交投影下球面上的点只是被压扁，前后一样大，看着像一张贴纸；
 * 透视才有"球在转"的立体感（`focal = 2.8 × 半径` 是试出来的，再小近处会撑破画面、
 * 再大又退回正交）。
 *
 * 文字本身**不跟着旋转**（不倾斜、不倒立），只按深度缩放与淡出 —— 这是"能读"和"好看"
 * 的折中：真按 3D 旋转文字，转到背面的字会倒过来，那它就没法看了。
 */
object TagBallGeometry {

    /** 球面上的一点（单位向量）。z 为正 = 靠近观察者 */
    data class Point(val x: Float, val y: Float, val z: Float)

    /** 一个点投影到屏幕上之后的结果 */
    data class Projected(
        /** 相对容器中心的像素偏移 */
        val x: Float,
        val y: Float,
        /** 透视缩放（1 = 球心所在的深度） */
        val scale: Float,
        /** 深度：-1 最远、1 最近。透明度与绘制顺序都用它 */
        val depth: Float,
    )

    /** 黄金角：`π(3-√5)`，相邻两点在球面上绕开的角 */
    private const val GOLDEN_ANGLE = 2.399963229728653

    /**
     * [n] 个点在单位球面上均匀铺开。
     *
     * `i + 0.5` 而不是 `i`：不偏半格的话第一个点正好落在北极、最后一个点落在南极，
     * 两极各堆一条（虽然只有一条，但看起来就是"上下各挂了一个"）。
     */
    fun spherePoints(n: Int): List<Point> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(Point(0f, 0f, 1f))
        return List(n) { i ->
            val y = 1.0 - (i + 0.5) * 2.0 / n
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = GOLDEN_ANGLE * i
            Point((cos(theta) * r).toFloat(), y.toFloat(), (sin(theta) * r).toFloat())
        }
    }

    /** 先绕 X 轴俯仰（[pitch]）、再绕 Y 轴自转（[yaw]），单位弧度 */
    fun rotate(p: Point, yaw: Float, pitch: Float): Point {
        val cp = cos(pitch)
        val sp = sin(pitch)
        val y1 = p.y * cp - p.z * sp
        val z1 = p.y * sp + p.z * cp
        val cy = cos(yaw)
        val sy = sin(yaw)
        return Point(
            x = p.x * cy + z1 * sy,
            y = y1,
            z = -p.x * sy + z1 * cy,
        )
    }

    /**
     * 把球面上的点投到屏幕上：`translation = p.xy × radius × persp`、
     * `persp = focal / (focal - z × radius)`。
     *
     * 分母在 `z→1` 时趋向 `focal - radius`：`focal` 一旦取到跟半径同量级，近处的字就会
     * 被放大到糊住整屏（`focal == radius` 更是直接除零）。所以这里夹一道下限 ——
     * 界面上用的是 `2.8 × 半径`，这道保险平时永远不生效。
     */
    fun project(p: Point, radius: Float, focal: Float): Projected {
        val f = focal.coerceAtLeast(radius * 1.2f)
        val persp = f / (f - p.z * radius)
        return Projected(
            x = p.x * radius * persp,
            y = p.y * radius * persp,
            scale = persp,
            depth = p.z,
        )
    }

    /** 深度 → 不透明度：背面淡、正面实（0.22 起步，背面还不至于看不见） */
    fun alphaOf(depth: Float): Float = 0.22f + 0.78f * ((depth + 1f) / 2f)

    /**
     * 计数 → 0~1 的权重。
     *
     * 开方压一道：库里最大的标签可能盖着 1500 部、第十名才 200 部，直接线性映射会把
     * 除头名以外的全部压成一样小 —— 开方之后 1500→1.0、200→0.37，个头还能分出层次。
     */
    fun weight(count: Int, maxCount: Int): Float {
        if (maxCount <= 0) return 0f
        return sqrt((count.toFloat() / maxCount).coerceIn(0f, 1f))
    }

    /** 最小 / 最大字号（sp）：小到能铺满球、大到一眼是"库里的头名" */
    private const val MIN_SP = 11.5f
    private const val MAX_SP = 26f

    /**
     * 词条字号（sp）。窄屏整体缩一档 —— 手机上球本来就小，同样字号会互相压住。
     * 返回值进 `TextStyle`，所以这里只给数不做单位。
     */
    fun fontSizeSp(count: Int, maxCount: Int, compact: Boolean): Float {
        val base = MIN_SP + (MAX_SP - MIN_SP) * weight(count, maxCount)
        return if (compact) base * 0.86f else base
    }

    /**
     * 按名字挑一个配色下标（`0 until paletteSize`）：**同一个名字永远同一个颜色**。
     *
     * 用 `hashCode` 而不是遍历顺序 —— 球的成员会随库里新增影片变化，按顺序上色的话
     * 加一部片就会让整颗球换色。绝对值是因为 `hashCode` 可能是负的（`Int.MIN_VALUE`
     * 取绝对值还是负，所以取长整型再取模）。
     */
    fun colorIndex(name: String, paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        return (abs(name.hashCode().toLong()) % paletteSize).toInt()
    }
}

/** 进球的一个候选：查询用的原名 + 是哪一族 + 盖住多少张卡片 */
data class BallCandidate(val name: String, val kind: WorksKind, val count: Int)

/**
 * 从整个库里**挑谁进球**。与排布分开：那边管"字摆在哪儿"，这边管"谁上场"。
 *
 * 候选由 DAO 按计数排好（`tagCloudCounts` / `actorCloudCounts`），这里只做三件事：
 * 保底、限量、打散 —— 都是纯函数，所以"球上该有谁"能直接跑单测钉住。
 */
object TagBallPick {

    /**
     * 挑 [limit] 条。
     *
     * 两个族各**保底 1/4**，剩下的名额按计数从两族的剩余里挑。
     * 为什么要有保底：演员表动辄上千行、计数还都不低，纯按计数取前 N 会把标签整个挤出球外
     * （反过来在演员少的库里也一样）。保底只管"进场"，**头名照样是头名** ——
     * 剩余名额仍然按计数排，所以库里最大的那个标签不会因为保底被砍掉。
     */
    fun pick(all: List<BallCandidate>, limit: Int): List<BallCandidate> {
        if (limit <= 0) return emptyList()
        if (all.size <= limit) return all
        val floor = maxOf(1, limit / 4)
        fun topOf(kind: WorksKind): List<BallCandidate> = all.asSequence()
            .filter { it.kind == kind }
            .sortedByDescending { it.count }
            .take(floor)
            .toList()
        val actors = topOf(WorksKind.Actor)
        val tags = topOf(WorksKind.Tag)
        // 按 (名字, 族) 去重而不是按对象：同名的标签和演员是两条不同的人/物，不能互相顶掉
        val taken = HashSet<Pair<String, WorksKind>>()
        (actors + tags).forEach { taken += it.name to it.kind }
        val rest = all.asSequence()
            .filter { (it.name to it.kind) !in taken }
            .sortedByDescending { it.count }
            .take(limit - actors.size - tags.size)
            .toList()
        return actors + tags + rest
    }

    /**
     * 确定性地打散顺序。
     *
     * 用 [pick] 交出来的列表是"演员一批、标签一批、剩下的按计数"——直接按这个顺序往
     * 斐波那契螺旋上摆，球面就会**按族分成两条带子**（相邻下标在球面上本来就挨着），
     * 一眼看出来是"这一半是人、那一半是标签"。Fisher-Yates 洗一遍就没这毛病了。
     *
     * 种子固定（`size` 决定）：每次进页面同一个排布 —— 用 `Random()` 的话每次回来
     * 整颗球的字都会换位置，看着像闪烁。
     */
    fun scatter(items: List<BallCandidate>): List<BallCandidate> {
        val out = items.toMutableList()
        var seed = 0x2545F4914F6CDD1DL xor out.size.toLong()
        for (i in out.indices.reversed()) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val j = ((seed ushr 33) % (i + 1)).toInt()
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }

    /**
     * 球上显示的文本：系列标签去掉 `系列: ` 前缀（[TagSignals.seriesLabel]）——球上一条条
     * 都顶着"系列: "很占地方，而"xxx"本身才是要看的东西。**查询仍用原名**，别拿这个去查。
     * 去掉前缀后空了的（标签就叫"系列"）退回原名，总得有点字可显示。
     */
    fun labelOf(name: String): String {
        if (!TagSignals.isSeries(name)) return name
        return TagSignals.seriesLabel(name).ifEmpty { name }
    }
}
