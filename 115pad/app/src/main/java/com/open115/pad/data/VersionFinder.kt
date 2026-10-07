package com.open115.pad.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "VersionFinder"

/** 分级限速（与查重/整理同一口径） */
private const val THROTTLE_FAST_MS = 300L
private const val THROTTLE_MEDIUM_MS = 1000L
private const val THROTTLE_SLOW_MS = 2000L

/** 时长容差：同一部片的不同清晰度，时长几乎分秒不差（±3 秒足够松，也不会把相邻的"两集"并进来） */
internal const val VERSION_DURATION_TOLERANCE_SEC = 3L

/**
 * 画质/编码类 token —— **不能**当"片名共同点"的证据（不然两个 1080p 就被算成"名字很像"）。
 * 只用于打分，不用于过滤。
 */
private val QUALITY_TOKENS = setOf(
    "4K", "8K", "2K", "UHD", "HD", "SD", "FHD", "QHD",
    "480P", "576P", "720P", "1080P", "1080I", "2160P", "4320P",
    "H264", "H265", "X264", "X265", "AVC", "HEVC", "MPEG", "DIVX", "XVID",
    "8BIT", "10BIT", "12BIT", "HDR", "HDR10", "HDR10PLUS", "DV", "DOLBY", "VISION", "SDR",
    "AAC", "AC3", "AC", "EAC3", "DDP", "DD", "DTS", "DTSHD", "TRUEHD", "ATMOS", "FLAC", "MP3",
    "WEB", "WEBDL", "DL", "WEBRIP", "BLURAY", "BDRIP", "BDR", "BRRIP", "HDTV", "HDRIP", "DVDRIP", "REMUX", "RIP",
    "PROPER", "REPACK", "EXTENDED", "UNCUT", "UNRATED", "REMASTERED", "IMAX",
    "CHS", "CHT", "ENG", "JPN", "GB", "BIG5",
)

/** 剧集/分P标记：这些**必须**参与"是不是同胞"的判断（在这里相同 = 不同集，不是不同版本） */
private val EPISODE_MARKERS = listOf(
    Regex("""(?i)s\d{1,2}\s*e\d{1,3}"""),          // S01E05
    Regex("""(?i)\bep?\s*\d{1,3}\b"""),            // EP05 / E5
    Regex("""第\s*\d{1,3}\s*[集话回]"""),            // 第5集
    Regex("""(?i)\b(part|pt|cd|disc|disk|dvd|vol)\s*\d{1,2}\b"""), // Part 2 / CD1（分片的另一部分）
)

/** 一个"版本候选"：一个视频文件 + 它在云端的位置 */
data class VideoVersion(
    val item: FileItem,
    val dirCid: String,
    val dirPath: String,
) {
    val fid: String get() = item.fid.orEmpty()
    val name: String get() = item.fn
    val size: Long get() = item.fs
    /** 时长（秒），列表响应自带 */
    val duration: Long get() = item.play_long
    val sha1: String? get() = item.sha1
}

/**
 * 一组"疑似同一部片的不同版本"。
 *
 * ★ 这只是**候选**：A 段只用得出"时长几乎一样、且不是剧集同胞"这一层，
 * 真正"是不是同一部片"要靠后面深挖（真实分辨率/码率 → 抽帧哈希）和用户确认。
 */
data class VersionGroup(
    /** 组内成员，按"建议保留项在前"排（A 段只能先按体积降序，B 段会用真实分辨率校正） */
    val members: List<VideoVersion>,
    /** 组内是否存在**内容完全相同**的一份（那种其实该用「内容相同」那档直接清理） */
    val sameContent: Boolean,
    /** 组内共有的"番号式"token（`ABC101` / `2024` 这种），没有就是 null */
    val sharedToken: String?,
    /** 清洗后片名的最长公共片段长度（字符数）——"名字不一样但有一截一样"的证据 */
    val commonSubstring: Int,
    /** 最大/最小体积比（同一部片换清晰度常 2~4 倍；同规格不同压制接近 1） */
    val sizeRatio: Double,
    /** 组内时长极差（秒） */
    val durationSpan: Long,
) {
    val duration: Long get() = members.firstOrNull()?.duration ?: 0L
}

// ---------------- 纯逻辑（单测直接打这些） ----------------

/** 片名去掉扩展名、统一小写、把**画质/编码 token 与集号分P号**抹掉，只留下"标题本体"（用于算名字相似度） */
internal fun cleanName(name: String): String {
    var s = name.substringBeforeLast('.').lowercase()
    s = s.replace(Regex("""[\[\](){}【】（）]"""), " ")
    s = s.replace(Regex("""[._\-+]"""), " ")
    EPISODE_MARKERS.forEach { s = it.replace(s, " ") }
    val kept = s.split(Regex("""\s+""")).filter { tok ->
        tok.isNotBlank() && !QUALITY_TOKENS.contains(tok.uppercase().replace(Regex("""[^A-Z0-9]"""), ""))
    }
    return kept.joinToString(" ").trim()
}

/**
 * "同胞身份"：只把**集号 / 分P号**抹成占位符，**画质 token 必须留着**。
 *
 * 两个文件如果除了集号/分P号以外完全一样，那它们是一部剧的两集（或一部片的两张盘），
 * **不是**"同一部片的不同清晰度" —— 这正是实测里最容易把候选撑爆的一类
 * （一部剧 50 集时长几乎都一样），所以在 A 段就按这个判据排掉。
 *
 * ★ 画质 token 千万不能抹（这就是为什么本函数**不**复用 [cleanName]）：抹了的话
 * `Movie 2160p` 与 `Movie 1080p` 会算成同一个同胞身份，**真正的目标会被整组丢掉** ——
 * 写这版时踩过，多亏单测里那条"画质不同则不同"才发现。
 */
internal fun siblingKey(name: String): String {
    var s = name.substringBeforeLast('.').lowercase()
    s = s.replace(Regex("""[\[\](){}【】（）]"""), " ")
    s = s.replace(Regex("""[._\-+]"""), " ")
    EPISODE_MARKERS.forEach { s = it.replace(s, " # ") }
    return s.replace(Regex("""\s+"""), " ").trim()
}

/** 含数字、且不是画质/编码词的长 token —— 番号（`ABC101`）、年份（`2024`）、季集号（`S01E05`）都在内 */
internal fun significantTokens(name: String): Set<String> = name
    .substringBeforeLast('.')
    .split(Regex("""[^A-Za-z0-9]+"""))
    .filter { it.length >= 3 && it.any { c -> c.isDigit() } }
    .map { it.uppercase() }
    .filterNot { QUALITY_TOKENS.contains(it) }
    .toSet()

/** 最长公共子串长度（名字不长，直接 DP；够用） */
internal fun longestCommonSubstring(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var best = 0
    val prev = IntArray(b.length + 1)
    for (i in 1..a.length) {
        var diag = 0
        for (j in 1..b.length) {
            val tmp = prev[j]
            prev[j] = if (a[i - 1] == b[j - 1]) diag + 1 else 0
            if (prev[j] > best) best = prev[j]
            diag = tmp
        }
    }
    return best
}

/**
 * A 段归组：**时长几乎相同**（±[toleranceSec]，按组内最小值为锚，不做链式漂移）
 * 且**不是剧集同胞**的成组，组内 ≥2 个。
 *
 * 大小比、名字相似度都**只用于打分排序**，不参与过滤 —— 实测"同规格不同压制"的两版体积就是很接近，
 * 用它当门槛会把该找的漏掉。
 */
internal fun buildVersionGroups(
    files: List<VideoVersion>,
    toleranceSec: Long = VERSION_DURATION_TOLERANCE_SEC,
): List<VersionGroup> {
    val videos = files
        .filter { !it.item.isDir && it.duration > 0 && it.size > 0 }
        .sortedBy { it.duration }

    val raw = mutableListOf<List<VideoVersion>>()
    var i = 0
    while (i < videos.size) {
        val anchor = videos[i]
        val bucket = mutableListOf(anchor)
        var j = i + 1
        while (j < videos.size && videos[j].duration - anchor.duration <= toleranceSec) {
            bucket += videos[j]
            j++
        }
        if (bucket.size >= 2) raw += bucket
        i += bucket.size
    }

    return raw
        .mapNotNull { bucket ->
            // 排掉"清一色同胞"的桶（剧集各集 / 分P）：只在它们彼此都算同胞时整体丢掉；
            // 只要有一个不是同胞（比如混了一版 4K），就保留下来交给用户看
            val keys = bucket.map { siblingKey(it.name) }
            if (keys.toSet().size == 1) return@mapNotNull null
            val members = bucket.sortedWith(compareByDescending<VideoVersion> { it.size }.thenBy { it.name })
            val shared = sharedSignificantToken(bucket)
            val sub = longestCommonSubstringIn(bucket)
            val sizes = bucket.map { it.size }
            VersionGroup(
                members = members,
                sameContent = bucket.groupBy { it.sha1 }.any { (k, v) -> k != null && v.size > 1 },
                sharedToken = shared,
                commonSubstring = sub,
                sizeRatio = sizes.max().toDouble() / sizes.min().toDouble(),
                durationSpan = (bucket.maxOf { it.duration } - bucket.minOf { it.duration }),
            )
        }
        .sortedWith(
            compareBy<VersionGroup> { it.durationSpan }
                .thenByDescending { it.sharedToken != null }
                .thenByDescending { it.commonSubstring }
                .thenByDescending { it.sizeRatio },
        )
}

/** 组内两两共同出现的"番号式"token，取出现次数最多的那个 */
private fun sharedSignificantToken(bucket: List<VideoVersion>): String? {
    val counts = HashMap<String, Int>()
    for (a in bucket.indices) {
        for (b in a + 1 until bucket.size) {
            val ta = significantTokens(bucket[a].name)
            val tb = significantTokens(bucket[b].name)
            (ta intersect tb).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
    }
    return counts.maxByOrNull { it.value }?.key
}

/** 组内两两（清洗后）片名的最长公共片段，取最大的那对 */
private fun longestCommonSubstringIn(bucket: List<VideoVersion>): Int {
    var best = 0
    for (a in bucket.indices) {
        for (b in a + 1 until bucket.size) {
            val r = longestCommonSubstring(cleanName(bucket[a].name), cleanName(bucket[b].name))
            if (r > best) best = r
        }
    }
    return best
}

/**
 * "同片多版本"查找（**A 段**：只做零成本的时长预筛）。
 *
 * 判据与排序：
 *  - **过滤**：时长 ±3 秒 + 组内不能清一色是剧集同胞（实测一部剧 50 集时长几乎一样，不排掉会撑爆候选）；
 *  - **打分排序**（只影响先后，不影响出入）：时长极差小的在前（时间差越小越像同一部片的不同版本）
 *    → 极差相同时再看：名字里有没有共同"番号式"token → 最长公共片段 → 体积比。
 *
 * 不做的事：不判断"到底是不是同一部片"（那要深挖真实分辨率/码率、必要时抽帧），
 * 所以结果只当**候选清单**看，界面不提供删除。零额外请求：时长/体积都是列表响应自带的。
 */
class VersionFinder(
    private val api: OpenApi,
    private val scope: CoroutineScope,
) {

    data class Progress(
        val running: Boolean = false,
        val rootCid: String = "",
        val rootPath: String = "",
        val recursive: Boolean = false,
        val scannedDirs: Int = 0,
        val seenVideos: Int = 0,
        val groups: List<VersionGroup> = emptyList(),
        val summary: String? = null,
        val stopped: Boolean = false,
        val finishedAt: Long = 0,
        // ---- B 段：深挖（读真实宽高/码率/时长）----
        /** fid → 真实参数；没深挖过的文件不在这里 */
        val meta: Map<String, VideoMeta> = emptyMap(),
        val probing: Boolean = false,
        val probeDone: Int = 0,
        val probeTotal: Int = 0,
    ) {
        fun metaOf(m: VideoVersion): VideoMeta? = meta[m.fid]
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var stopRequested = false

    private var job: Job? = null

    private class StopSignal : Exception()

    fun start(rootCid: String, rootPath: String, recursive: Boolean) {
        if (_progress.value.running) return
        stopRequested = false
        _progress.value = Progress(running = true, rootCid = rootCid, rootPath = rootPath, recursive = recursive)
        job = scope.launch { run(rootCid, rootPath, recursive) }
    }

    fun stop() {
        stopRequested = true
    }

    fun clearResult() {
        if (_progress.value.running) return
        _progress.value = Progress()
    }

    /**
     * **B 段：深挖** —— 给这些文件读真实宽高/码率/时长（每个 1~2 秒，只读头部）。
     *
     * 只在用户点名的那几组上花这个钱：A 段的候选天生带误报，全量深挖会白跑半天。
     * 已经深挖过的文件跳过（所以对同一组再点一次只补新的）。
     */
    fun deepen(targets: List<VideoVersion>) {
        if (_progress.value.probing || _progress.value.running) return
        val todo = targets.filter { it.fid.isNotBlank() && it.fid !in _progress.value.meta }
        if (todo.isEmpty()) return
        stopRequested = false
        _progress.value = _progress.value.copy(probing = true, probeDone = 0, probeTotal = todo.size)
        job = scope.launch {
            val found = HashMap<String, VideoMeta>()
            for (v in todo) {
                if (stopRequested) break
                delay(PROBE_GAP_MS)
                val meta = probeVideoMeta(api, v.item.pc ?: continue)
                if (meta != null) found[v.fid] = meta
                _progress.value = _progress.value.copy(
                    probeDone = _progress.value.probeDone + 1,
                    meta = _progress.value.meta + found, // 边探边显示，用户不用等整组跑完
                )
            }
            _progress.value = _progress.value.copy(probing = false, meta = _progress.value.meta + found)
        }
    }

    /** 删完之后调：把删掉的文件从候选里摘掉（连带它的真实参数），并丢掉不足两个的组 */
    fun markDeleted(fids: Collection<String>) {
        if (fids.isEmpty()) return
        val gone = fids.toSet()
        val kept = _progress.value.groups
            .map { g -> g.copy(members = g.members.filterNot { it.fid in gone }) }
            .filter { it.members.size >= 2 }
        _progress.value = _progress.value.copy(
            groups = kept,
            meta = _progress.value.meta.filterKeys { it !in gone },
            summary = if (kept.isEmpty()) "已清理完候选里的多余副本" else "还剩 ${kept.size} 组候选",
        )
    }

    private suspend fun run(rootCid: String, rootPath: String, recursive: Boolean) {
        val found = mutableListOf<VideoVersion>()
        try {
            walkDirs(api, rootCid, rootPath, recursive, beforeDir = { throttle() }) { cid, path, items ->
                checkStop()
                var n = 0
                for (item in items) {
                    if (item.isDir) continue
                    if (!isVideoName(item.fn)) continue
                    if (item.fs <= 0) continue
                    found += VideoVersion(item, cid, path)
                    n++
                }
                _progress.value = _progress.value.copy(
                    scannedDirs = _progress.value.scannedDirs + 1,
                    seenVideos = _progress.value.seenVideos + n,
                )
            }
            finish("时长预筛：从 ${found.size} 个视频里得到", buildVersionGroups(found))
        } catch (e: StopSignal) {
            finish("已停止 · 从已扫到的 ${found.size} 个视频里得到", buildVersionGroups(found), stopped = true)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "同片多版本预筛失败：${e.message}")
            finish("出错了（${e.message ?: e.toString()}）· 已扫到的结果仍在下面", buildVersionGroups(found), stopped = true)
        }
    }

    private fun isVideoName(name: String): Boolean {
        val n = name.lowercase()
        return VIDEO_EXTS.any { n.endsWith(it) }
    }

    private fun finish(prefix: String, groups: List<VersionGroup>, stopped: Boolean = false) {
        val summary = if (groups.isEmpty()) {
            "$prefix 0 组候选（这个范围里没有时长几乎一致的片子）"
        } else {
            val files = groups.sumOf { it.members.size }
            "$prefix ${groups.size} 组候选（共 $files 个文件）"
        }
        _progress.value = _progress.value.copy(
            running = false,
            groups = groups,
            summary = summary,
            stopped = stopped,
            finishedAt = System.currentTimeMillis(),
        )
    }

    private fun checkStop() {
        if (stopRequested) throw StopSignal()
    }

    private suspend fun throttle() {
        val scanned = _progress.value.scannedDirs
        delay(
            when {
                scanned >= 20 -> THROTTLE_SLOW_MS
                scanned >= 10 -> THROTTLE_MEDIUM_MS
                else -> THROTTLE_FAST_MS
            },
        )
    }

    private companion object {
        val VIDEO_EXTS = listOf(".mp4", ".mkv", ".ts", ".mov", ".avi", ".wmv", ".flv", ".m2ts", ".rmvb", ".iso")

        /** 深挖时每个文件之间的间隔（读头部也要打 115 的资源接口取直链，别让它太密） */
        const val PROBE_GAP_MS = 300L
    }
}
