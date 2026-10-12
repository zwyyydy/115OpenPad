package com.open115.pad.data.media.scrape

/**
 * 检索词清洗与候选匹配的**纯逻辑**（无 IO、无 Android 依赖），单独拎出来是为了可单测。
 *
 * 这一层是"AI 检索词"那一步的落点：以后脏名字交给 AI 时，AI 的输出也走同一个
 * [cleanQueryTitle] + [autoPick] 校验管，不会绕过这里的规则。
 */
object ScrapeMatch {

    /**
     * 检索词的轻清洗：把点/下划线换空格、去掉方括号段与画质/编码/语言等发布标记。
     *
     * **刻意不做**的事：不从标题里猜年份（"1917"、"Blade Runner 2049" 这类片名会被误伤），
     * 年份只信扫描器已经解析出来的那个。
     */
    fun cleanQueryTitle(raw: String): String {
        var s = raw
        // 先整段删掉"连在一起"的技术标记 —— 点号会被后面的替换拆开，
        // `H.264` 拆成 `H`+`264`、`AC3 2.0` 拆成 `AC3`+`2`+`0`，单个词表就管不住了
        s = Regex("""(?i)\b[hd]\.?26[45]\b""").replace(s, " ")
        s = Regex("""(?i)\bx\.?26[45]\b""").replace(s, " ")
        s = Regex("""(?i)\b\d{1,3}\s?fps\b""").replace(s, " ")
        s = Regex("""(?i)\b(ac3|eac3|aac|dts|ddp?|truehd|atmos)[\s\-.]*hd[\s\-.]*ma[\s\-.]*\d\.\d\b""").replace(s, " ")
        s = Regex("""(?i)\b(ac3|eac3|aac|dts|ddp?|truehd|atmos)[\s\-.]*\d\.\d\b""").replace(s, " ")
        s = s.replace('_', ' ').replace('.', ' ')
        s = Regex("""\[[^\]]*]""").replace(s, " ")
        s = Regex("""【[^】]*】""").replace(s, " ")
        val tokens = s.split(' ', '-', '(', ')', '（', '）').map { it.trim() }.filter { it.isNotEmpty() }
        var kept = tokens.filterNot { it.lowercase() in NOISE_TOKENS }
        // 发布组尾巴（`...-GROUP` 形状）才丢：必须带连字符、全大写 ASCII、且不只有数字。
        // 不做"任何全大写尾巴都丢"—— "THE MATRIX" 这类片名会被误伤。
        val tail = Regex("""-([A-Za-z0-9]{2,12})$""").find(raw.trim())?.groupValues?.get(1)
        if (tail != null && tail.any { it.isLetter() } && tail == tail.uppercase() &&
            tail.lowercase() !in NOISE_TOKENS
        ) {
            val idx = kept.indexOfLast { it.equals(tail, ignoreCase = true) }
            if (idx >= 0) kept = kept.toMutableList().also { it.removeAt(idx) }
        }
        return kept.joinToString(" ").trim().ifBlank { raw.trim() }
    }

    /**
     * 自动预选：只在**片名完全同名**（归一化后逐字相等）且**没有并列**时勾一条，
     * 其余一律留给用户手点 —— 宁可不勾，也别替用户勾错。
     *
     * ★ 比对前先 [cleanQueryTitle] 一遍：文件页拿到的"本地标题"常常是**带发布标记的原始前缀**
     *   （`片名 (2026) - 1080p.SDR.H.264…`），不先洗掉这些尾巴，跟 TMDB 的干净标题永远对不上，
     *   自动预选就永远不生效（2026-10-08 实测踩到）。
     *
     * 返回候选下标；null = 没有可预选的。
     */
    fun autoPick(cands: List<ScrapeCandidate>, localTitle: String, year: Int?): Int? {
        if (cands.isEmpty()) return null
        // 先摘掉 `(YYYY)`、再洗发布标记：文件页的"本地标题"常常两样都带
        val norm = normTitle(cleanQueryTitle(splitYearFromTitle(localTitle).first))
        if (norm.isEmpty()) return null
        var bestIdx = -1
        var bestScore = 0
        var tie = false
        cands.forEachIndexed { i, c ->
            var s = 0
            val ct = normTitle(c.title)
            val co = normTitle(c.originalTitle.orEmpty())
            when {
                ct == norm || co == norm -> s = 4
                norm.length >= 4 && (ct.contains(norm) || co.contains(norm)) -> s = 2
            }
            if (s > 0 && year != null && c.year == year) s += 1
            if (s > bestScore) {
                bestScore = s
                bestIdx = i
                tie = false
            } else if (s == bestScore && s > 0) {
                tie = true
            }
        }
        return if (bestScore >= 4 && !tie) bestIdx else null
    }

    /** 归一到"只留字母数字"（CJK 算字母）：忽略大小写、空格与标点差异 */
    fun normTitle(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 从目录/文件名里抽 `(YYYY)` 形式的年份，并返回**去掉那个括号段之后的标题**。
     *
     * 只认括号里的 4 位年份（Emby 命名约定）：不这么做的话 "Blade Runner 2049" 这种
     * 片名会被当成 2049 年的片、搜索时套错年份过滤条件（详见 [cleanQueryTitle] 的说明）。
     * 返回的标题为空时退回原文。
     */
    fun splitYearFromTitle(raw: String): Pair<String, Int?> {
        val m = Regex("""[（(]\s*(\d{4})\s*[)）]""").find(raw) ?: return raw.trim() to null
        val year = m.groupValues[1].toIntOrNull()?.takeIf { it in 1900..2099 } ?: return raw.trim() to null
        val cleaned = (raw.removeRange(m.range)).replace(Regex("""\s{2,}"""), " ").trim()
        return (cleaned.ifBlank { raw.trim() }) to year
    }

    /**
     * 检索词里要丢掉的发布标记。**故意不含任何 4 位数字** —— "1917" 这类片名会被误伤。
     */
    private val NOISE_TOKENS = setOf(
        "1080p", "720p", "2160p", "480p", "4k", "uhd", "hdr", "hdr10", "sdr", "dv",
        "bluray", "blu-ray", "bdrip", "brrip", "web", "webrip", "web-dl", "webdl",
        "hdtv", "dvdrip", "hdrip", "remux", "fullhd", "hd", "dl",
        "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit",
        "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "flac", "mp3",
        "proper", "repack", "internal", "extended", "uncut", "remastered", "imax",
        "chs", "cht", "gb", "big5", "chinese", "中字", "中英", "国语", "粤语", "双语",
        "简中", "繁中", "简繁", "内封", "内嵌", "外挂", "字幕", "首发",
    )
}

/**
 * 用匹配到的片名生成新文件名：片名做主体、**保留原文件的扩展名**（改名不该动扩展名）。
 * 片名为空、或含路径分隔符会被替换成全角（115 文件名不允许 `/`）。返回 null = 不该改。
 */
fun matchedFileName(title: String, originalName: String): String? {
    val t = title.trim().replace('/', '／').replace('\\', '＼').replace(Regex("""\s+"""), " ").trim()
    if (t.isEmpty()) return null
    val dot = originalName.lastIndexOf('.')
    val ext = if (dot > 0 && dot < originalName.length - 1) originalName.substring(dot) else ""
    return t + ext
}
data class BackdropRef(val path: String, val vote: Double = 0.0, val votes: Int = 0)

/**
 * 「刮剧照」的挑选规则（纯函数）：按**赞数**排（同数再按平均分），去掉空路径与重复，截前 [max] 张。
 *
 * 放这里而不是 TmdbClient 里：客户端只做 HTTP + JSON 映射，"取哪几张"是要单测的规则。
 */
fun pickStills(refs: List<BackdropRef>, max: Int): List<String> =
    refs.filter { it.path.isNotBlank() }
        .distinctBy { it.path }
        .sortedWith(compareByDescending<BackdropRef> { it.votes }.thenByDescending { it.vote })
        .take(max.coerceAtLeast(0))
        .map { it.path }
