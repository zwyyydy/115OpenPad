package com.open115.pad.data.media

/**
 * 推荐信号里"一个标签名该怎么读"。
 *
 * 库里存的标签是扫描期 `nfo 的 genre + tag` 原样落库的，里面混着两类不适合当信号的东西：
 *
 *  1. **画质 / 编码**（`1080P` / `AVC1` / `4K` / `HD高画质` / `フルハイビジョン(FHD)` …）。
 *     实测一个 2468 个标签的库里有 24 个是这类，却盖住大半库（光 `1080P` 就 1363 部）——
 *     拿它当"共同点"等于给大半个库加分，还会顶掉真正的标签出现在推荐理由里（"同标签 · 1080P"）。
 *  2. 空名、纯符号 —— 没有信息量。
 *
 * 另一头是**系列**：刮削器写在 `tag` 里的 `系列: xxx`，比普通标签精确得多（实测最"大"的系列
 * 也只有 23 部片），推荐里要单独吃更高的权重。判据集中在这一个对象里，SQL 与打分共用一份规则 ——
 * 两边各写一套（Kotlin 判前缀、SQL 判 LIKE）迟早会走岔。
 */
object TagSignals {

    /** 系列标签的前缀：库里的写法是 `系列: xxx`（半角冒号 + 空格，也有全角的） */
    const val SERIES_PREFIX = "系列"

    /** [SERIES_PREFIX] 的 SQL 写法（给 `tags.name LIKE :seriesLike` 传这个，别在 DAO 里另写字面量） */
    const val SERIES_LIKE = "$SERIES_PREFIX%"

    fun isSeries(name: String): Boolean = name.trim().startsWith(SERIES_PREFIX)

    /** 系列标签去掉前缀后的名字（推荐理由里显示"同系列 · xxx"，不要那个 `系列: `） */
    fun seriesLabel(name: String): String =
        name.removePrefix(SERIES_PREFIX).trimStart(':', '：', ' ', '\u3000').trim()

    /**
     * 这条标签该不该**计入推荐统计**。
     *
     * 判据是"整条名字拆开之后每一段都是画质/编码 token"：`1080P`、`FHD 60FPS`、`AVC1`
     * 都会被清掉，而 `示例标签甲`、`示例标签乙`、`3D、4D`（只有一位数，不是分辨率）、`12小时+`、
     * 以及看着像编号前缀的短码（`SDXX` / `300XXX`）都不会被误伤。
     */
    fun isNoise(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return true
        // 日文的"高画质"类整词：フルハイビジョン(FHD) / 全高清 (FHD) / HD高画质 —— 拆不出 token，单独认
        if (CJK_QUALITY_ANY.any { n.contains(it) }) return true
        // 先归一化再拆：全角的空格/斜杠（`　`、`／`）只有转成 ASCII 之后才认得出来
        val parts = normalizeToken(n).split(SPLIT).map { it.replace(STRIP, "") }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        return parts.all { QUALITY.matches(it) }
    }

    /** 分隔符：`1080P 60FPS`、`AVC1/1080P` 这类拼起来的标签要拆开逐段判 */
    private val SPLIT = Regex("[\\s,，、/·・+＋×]+")

    /** 段内还要去掉的点线与括号：`H.264`、`AVC-1`、`全高清(FHD)` */
    private val STRIP = Regex("[.\\-_/（）()\\[\\]【】:：]+")

    private val CJK_QUALITY_ANY = listOf("ハイビジョン", "高画质", "高画質", "全高清")

    /** 单段 = 一个画质/编码 token 的**完整**匹配（不是前缀，`SDXX` 这种编号前缀不会中） */
    private val QUALITY = Regex(
        "(?:" +
            "[0-9]{3,4}[PI]|" +          // 480P ~ 2160P / 1080I
            "[0-9]{1,2}K|" +             // 4K / 8K
            "[0-9]{1,3}FPS|" +           // 60FPS
            "[0-9]{1,2}BIT|" +           // 8BIT / 10BIT
            "UHD|FHD|QHD|HD|SD|" +
            "HDR[0-9]*\\+?|" +
            "H\\.?26[45]|X26[45]|HEVC|HEV1|HVC1|AVC1|AVC|VP0?9|AV1|" +
            "MPEG[24]|MP4V|WMV[0-9]|WVC1|DIVX|XVID|VC1" +
            ")",
    )

    /** 全角转半角 + 大写：`１０８０Ｐ` / `1080p` 都是同一个 token */
    private fun normalizeToken(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            when {
                ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                ch == '\u3000' -> append(' ')
                else -> append(ch)
            }
        }
    }.uppercase()
}
