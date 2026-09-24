package com.open115.pad.data.media

/**
 * 目录嗅探：把一个目录里的文件按「公共前缀」聚成影视条目。
 * 覆盖两种实测结构：
 *  1. Emby 影片目录：`示例影片 (1979) {tmdbid-348}.nfo/.jpg/-fanart.jpg/-logo.png` + `folder.jpg` + 视频 .iso
 *  2. 演员目录（演员目录）：`ABC-301.mp4/-poster.jpg/-fanart.jpg/-thumb.jpg/.zh-CN.srt` + `ABC-301.nfo`
 *
 * 聚类规则：文件名去掉 {tmdbid-…}、去掉 -poster/-fanart/-thumb/-logo 等装饰后缀和
 * 语言后缀（.zh-CN）与扩展名，剩下的公共前缀即一组；视频扩展名是主文件。
 */

private val VIDEO_EXTS = setOf("mkv", "mp4", "avi", "iso", "ts", "mov", "wmv", "flv", "m2ts", "webm")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")

/** 装饰后缀：命名规范里跟在前缀后面的角色标记 */
private val DECOR_SUFFIXES = listOf("-fanart", "-poster", "-thumb", "-logo", "-banner", "-disc", "-backdrop", "-keyart", "-landscape", "-clearart")

/**
 * **无前缀**的"目录级"标准文件名（Kodi/Emby 约定）：它们属于整个目录，不参与前缀分组。
 *
 * ★ 必须在前缀分组之前认掉。`fanart.jpg` 早先会聚出一个前缀为 `fanart` 的组 ——
 * 装饰后缀表里是**带连字符**的 `-fanart`，而文件名就是裸的 `fanart`，`endsWith` 匹配不上。
 * `season01-poster.jpg` 同理聚出 `season01`。这两个多出来的组会把组数顶高，
 * 直接害死下面那个海报兜底（见 [clusterFiles] 里 anchor 的注释）。
 */
private val DIR_POSTER_NAMES = setOf("poster", "folder")
private val DIR_FANART_NAMES = setOf("fanart", "backdrop", "background")
private val DIR_THUMB_NAMES = setOf("thumb")
private val DIR_OTHER_NAMES = setOf("logo", "clearlogo", "banner", "disc", "landscape")

/**
 * 季描述文件的前缀：`season.nfo` / `season01.nfo` / `specials.nfo`。
 *
 * 它们**有 nfo 没视频**，能通过"有视频或有 nfo"那道过滤 → 凭空生成一条标题为「季 1」的
 * 垃圾卡片（实测 2026-09-23：示例剧集 那个库多出一行 mediaKey=`season`）。
 * 它描述的是"季"而不是影视条目，不该出现在海报墙上。
 *
 * `tvshow.nfo` **不在这里**：剧集根目录通常没有视频文件，系列条目正是靠它建起来的。
 */
private val SEASON_DESC_PREFIX = Regex("""(?i)^(season|specials)\d*$""")

/** 一组文件：同公共前缀的 .nfo/.jpg/视频 归一类 */
data class Cluster(
    val prefix: String,
    val video: FileRef? = null,
    val nfo: FileRef? = null,
    val poster: FileRef? = null,
    val fanart: FileRef? = null,
    val thumb: FileRef? = null,
)

/**
 * 一个云盘文件。
 *
 * [fid] 是 115 的 file_id —— **删云端文件时只能用它**（`ufile/delete` 收的是 file_ids，
 * pick_code 不认）。扫描时列表响应里就带着，顺手存下来，否则删的时候还得再列一次目录。
 */
data class FileRef(
    val name: String,
    val pickCode: String,
    val sizeBytes: Long,
    val upt: Long,
    val fid: String = "",
)

/** 从文件名前缀提取 {tmdbid-348} 里的 348（右括号必须转义，否则 ICU 正则编译崩） */
val TMDB_ID = Regex("""\{tmdbid-(\d+)\}""")

/**
 * 目录指纹：把目录里**所有条目**（含子目录项）的身份压成一个定长哈希。
 *
 * 增量扫描靠它判断"这个目录变没变"。原来的判据是"目录内 upt 的最大值"，只反映
 * "有没有出现比它更新的东西"，**删除、改名、把文件移进来都不会让它变** ——
 * 实测「示例影片（系列）」的 upt 从建库那天起就没动过，而里面先后加过片子、删过文件、还原过文件。
 * 115 里目录项自己的 upt 就是创建时间，内容变化根本不顶它。
 *
 * 取四项：
 *  - `name` / `sizeBytes` / `upt` —— 云盘上这个东西本身变了吗
 *  - `pickCode` —— **我们存下来的那个字段**变了吗（重传/替换后 pickCode 会换，索引得跟着更新）
 *
 * ★ 必须**排序后再哈希**：115 的列表顺序不稳定（按 upt 倒序返回），顺序进了指纹
 *   就会天天判成"变了"，把增量扫描退化成全量。
 * ★ 分隔符用 NUL：文件名里不可能出现它，拼接不会歧义（用 `|` 之类就可能）。
 * ★ 用 MD5 而不是 `hashCode()`：32 位哈希在几千个目录上有可观的碰撞概率，
 *   一次碰撞就是"某个目录永远被跳过"。
 */
fun dirFingerprintOf(files: List<FileRef>): String {
    if (files.isEmpty()) return ""
    val joined = files
        .map { "${it.name}\u0000${it.sizeBytes}\u0000${it.upt}\u0000${it.pickCode}" }
        .sorted()
        .joinToString("\u0001")
    val digest = java.security.MessageDigest.getInstance("MD5").digest(joined.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 纯逻辑聚类（对文件名列表）。folder.jpg 是无前缀海报的兜底，归到唯一视频组。
 *
 * [minVideoBytes] > 0 时做**体积过滤**：小于它的视频当它不存在（预告/花絮/样本不该占卡片位）。
 * 过滤必须在这里、而不是入库那一步 —— 否则"1 个正片 + 1 个预告"的目录会因为看到 2 个视频
 * 被判成多集目录（`isMultiVideo`），一部电影被拆成两条。
 */
fun clusterFiles(files: List<FileRef>, minVideoBytes: Long = 0L): List<Cluster> {
    data class MutableCluster(
        val prefix: String,
        var video: FileRef? = null,
        var nfo: FileRef? = null,
        var poster: FileRef? = null,
        var fanart: FileRef? = null,
        var thumb: FileRef? = null,
        /** 这个组的视频存在、但因为太小被滤掉了 */
        var videoTooSmall: Boolean = false,
    )

    val byPrefix = LinkedHashMap<String, MutableCluster>()
    // 目录级素材：不属于任何前缀组，最后挂给"锚点簇"
    var dirPoster: FileRef? = null
    var dirFanart: FileRef? = null
    var dirThumb: FileRef? = null

    fun basePrefix(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1).lowercase()
        if (ext !in VIDEO_EXTS && ext !in IMAGE_EXTS && ext != "nfo" && ext != "srt") return null
        var stem = name.substring(0, dot)
        // 去掉语言后缀（.zh-CN 在去扩展名后残留）
        stem = stem.replace(Regex("""\.[a-zA-Z]{2}(-[a-zA-Z]{2})?$"""), "")
        // 去掉装饰后缀
        for (s in DECOR_SUFFIXES) {
            if (stem.lowercase().endsWith(s)) {
                stem = stem.substring(0, stem.length - s.length)
                break
            }
        }
        return stem.trim()
    }

    for (f in files) {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val stem = f.name.substringBeforeLast('.')
        // 目录级标准文件名先认掉（必须在分组之前，否则会各自成组）
        if (ext in IMAGE_EXTS) {
            when (stem.lowercase()) {
                in DIR_POSTER_NAMES -> { if (dirPoster == null) dirPoster = f; continue }
                in DIR_FANART_NAMES -> { if (dirFanart == null) dirFanart = f; continue }
                in DIR_THUMB_NAMES -> { if (dirThumb == null) dirThumb = f; continue }
                in DIR_OTHER_NAMES -> continue
            }
        }
        val prefix = basePrefix(f.name) ?: continue
        val decor = DECOR_SUFFIXES.firstOrNull { stem.lowercase().endsWith(it) }
        val c = byPrefix.getOrPut(prefix) { MutableCluster(prefix) }
        when {
            ext in VIDEO_EXTS -> when {
                c.video != null -> Unit // 一组只要一个主视频
                minVideoBytes > 0 && f.sizeBytes < minVideoBytes -> c.videoTooSmall = true
                else -> c.video = f
            }
            ext == "nfo" -> if (c.nfo == null) c.nfo = f
            ext in IMAGE_EXTS -> when (decor) {
                "-poster" -> if (c.poster == null) c.poster = f
                "-fanart", "-backdrop" -> if (c.fanart == null) c.fanart = f
                "-thumb", "-landscape" -> if (c.thumb == null) c.thumb = f
                else -> if (c.poster == null) c.poster = f // 无角色标记的图当海报
            }
        }
    }

    // 条目 = 有视频或有 nfo 的组；季描述文件（season.nfo）除外
    val entries = byPrefix.values
        .filter { it.video != null || it.nfo != null }
        .filterNot { it.video == null && SEASON_DESC_PREFIX.matches(it.prefix) }
        // 视频被体积滤掉的组**整条丢掉**：留着会变成"有海报有简介、点播放却说没有可播放文件"的卡片。
        // 只丢"曾经有视频且被滤掉"的组 —— 纯 nfo 组（tvshow.nfo）照旧保留，剧集条目靠它建起来。
        .filterNot { it.video == null && it.videoTooSmall }

    // 目录级海报/背景挂给哪个簇（锚点）：
    //   ① 本目录**唯一**带视频的簇 —— 单影片目录（示例影片那种）
    //   ② 带 nfo 但没有视频的簇 —— 剧集根目录的 tvshow.nfo 就是"系列本身"，海报就该挂它
    //   ③ 都没有就不挂（例如季目录里 9 集 + 一张季海报：挂给哪一集都是张冠李戴）
    //
    // ☠ 早先的条件是 `byPrefix.size == 1`，那对剧集根目录**永远不成立**：目录里
    //    fanart.jpg / season01-poster.jpg 各自成组就够把组数顶到 3，于是整个兜底失效 ——
    //    实测 2026-09-23「示例剧集」那个库：目录里明明有 poster.jpg(651KB) + fanart.jpg(390KB)，
    //    索引里两个字段却都是 null，海报墙上系列卡是空白。
    //
    // ① 在 ② 之前：电影目录里混进一个无关的 xxx.nfo 时，海报不该挂到那个 nfo 组上。
    val anchor = entries.singleOrNull { it.video != null }
        ?: entries.firstOrNull { it.nfo != null && it.video == null }

    return entries.map { c ->
        val onAnchor = c === anchor
        Cluster(
            prefix = c.prefix,
            video = c.video,
            nfo = c.nfo,
            poster = c.poster ?: dirPoster.takeIf { onAnchor },
            fanart = c.fanart ?: dirFanart.takeIf { onAnchor },
            thumb = c.thumb ?: dirThumb.takeIf { onAnchor },
        )
    }
}

/**
 * 剧集判定：一个目录里有多个视频组（季集目录），每组是一条集。
 * 影片判定：一个目录只聚出一组。
 *
 * [minVideoBytes] 见 [clusterFiles] —— 它在聚类阶段就滤掉了小视频，所以这里的计数
 * 天然只算"留下的那些"，不会把预告片算进集数。
 */
data class DirScan(
    val clusters: List<Cluster>,
    val isMultiVideo: Boolean,
)

fun sniffDirectory(files: List<FileRef>, minVideoBytes: Long = 0L): DirScan {
    val clusters = clusterFiles(files, minVideoBytes)
    return DirScan(clusters = clusters, isMultiVideo = clusters.count { it.video != null } > 1)
}

/** 剧集键：从文件名前缀提取 S01E02 / 第X集；没有则用前缀本身 */
fun episodeKeyOf(prefix: String): String =
    Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(prefix)?.value
        ?: Regex("""[Ee][Pp]?(\d{1,3})""").find(prefix)?.value
        ?: prefix

/**
 * 入库主键（`movies.mediaKey` 是主键，撞了就是互相覆盖）。
 *
 * - **影片**：优先用 nfo 里的 tmdb id —— 同一部片换个目录、换个文件名还是同一条，不会重复入库；
 *   没有 id 才退回前缀（番号式的 `ABC-301` 就是这样）。
 * - **分集**：一律用**文件前缀**，**绝不用 nfo 的 id**。
 *
 * ★ 分集这条是踩出来的：实测「示例剧集三 (2021)」36 集的 nfo 里 `uniqueid` 全是同一个值
 *   （刮削器把整部剧的 id 写进了每一集），拿它当主键 → 36 集全写进同一行，
 *   最后写的那一集把其余 35 集覆盖掉，海报墙上就只剩一张卡、点进去只有一集。
 *   前缀是每个文件自己的（`示例剧集三 - S01E07 - 第7集`），天然一集一个。
 *
 * 前缀也不带 tmdb id：有些包会把 `{tmdbid-118759}` 写进**每个**文件名里，那同样会撞。
 *
 * 影片那条统一成 `tmdb-<id>`（id 来自 nfo 或文件名都算）：早先两条路会产出 `tmdb-8078`
 * 和 `8078` 两个不同形状的键，同一部片于是可能入库两次。
 */
fun mediaKeyOf(prefix: String, nfoTmdbId: String?, isEpisodeLike: Boolean): String {
    if (isEpisodeLike) return prefix
    val id = nfoTmdbId ?: TMDB_ID.find(prefix)?.groupValues?.get(1)
    return if (id != null) "tmdb-$id" else prefix
}

/**
 * 集号排序键：`...S01E02...` → `1*10000 + 2 = 10002`；解析不出返回 Int.MAX_VALUE（沉底）。
 *
 * 返回单个 Int 而不是 Pair：`kotlin.Pair` **不实现 Comparable**，`compareBy` 用不了它。
 *
 * **不能按标题字符串排**：`S01E10` > `S01E09` 按字典序碰巧对，但 `S01E2` > `S01E10`
 * 也成立 —— 集数一上两位数顺序就乱。集号一定得按数值比。
 */
fun episodeSortKey(title: String): Int {
    val m = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(title) ?: return Int.MAX_VALUE
    return m.groupValues[1].toInt() * 10_000 + m.groupValues[2].toInt()
}

/**
 * 演员名从哪来（按优先级）——**nfo 是权威**，目录名只是最后手段。
 *
 *  ① nfo 的 `<actor><name>`：刮削器写的演员表，顺序也是演职员表顺序
 *  ② `.actors/` 目录里的文件名：同样出自刮削器，而且**正是头像的键** ——
 *     名字从这里取，头像就一定配得上（实测 `ABC-101-U` 那种没 nfo 的分集，
 *     目录名启发式会猜出 `-4K-C 示例演员` 这种脏名字，而 `.actors/示例演员.jpg`
 *     给的是干净的真名）
 *  ③ [actorsFromDirName]：番号式目录名里带演员（`ABC-301 示例演员二,示例演员三`）。
 *     两个来源都没有时才用，属于"猜"，所以排在最后。
 */
fun actorsOf(
    nfoActors: List<String>,
    actorAvatarNames: List<String>,
    dirPath: String,
): List<String> = nfoActors.ifEmpty {
    actorAvatarNames.distinct().takeIf { it.isNotEmpty() } ?: actorsFromDirName(dirPath)
}

/**
 * 番号式目录名提演员：`ABC-301 示例演员二,示例演员三` → ["示例演员二", "示例演员三"]。
 *
 * 只在影片自己的目录名以「字母+数字的番号段」开头时才认（ABC-301 / ABC-303）：
 * Emby 影片目录（`示例影片 (1979)` / `Sample Movie (1979)`）不以番号开头，天然不匹配，不会误提。
 * 目录名只是番号时（`/演员目录/示例演员二,示例演员三/ABC-301/`）往上一层目录名找。
 */
fun actorsFromDirName(dirPath: String): List<String> {
    val segments = dirPath.trimEnd('/').split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return emptyList()
    val own = segments.last()
    val code = CODE_PREFIX.find(own) ?: return emptyList()
    val body = own.substring(code.value.length).trim()
    if (body.isNotEmpty()) return splitActors(body)
    // 上一层是演员目录：要求带分隔符或含 CJK，避免把 "演员目录" 这类普通目录名当演员
    val parent = segments.getOrNull(segments.size - 2) ?: return emptyList()
    val looksLikeActors = parent.any { it in ",，、;；" } || parent.any { it.code in 0x4E00..0x9FFF }
    if (!looksLikeActors) return emptyList()
    return splitActors(parent)
}

private fun splitActors(body: String): List<String> =
    body.split(Regex("""[,，、;；]+"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= 30 }
        .distinct()

/** 番号段：字母开头、可带数字，以 -/_ 接 2-6 位数字（ABC-301 / ABC-303） */
private val CODE_PREFIX = Regex("""^[A-Za-z][A-Za-z0-9]{0,14}[-_]\d{2,6}\s*""")

// ---------------- 分集继承（海报/背景图） ----------------

/** 季级海报/背景：`season01-poster.jpg` / `Season 01 - Poster.jpg` / `season-specials-fanart.jpg` */
val SEASON_ART_POSTER = Regex("""(?i)^season[-_ ]?(\d+|specials)[-_ ]*poster\.(jpg|jpeg|png|webp)$""")
val SEASON_ART_FANART = Regex("""(?i)^season[-_ ]?(\d+|specials)[-_ ]*(fanart|backdrop|background)\.(jpg|jpeg|png|webp)$""")

/** 往上找祖先目录时最多爬几层（`Series/Season 1/Disc 1/` 这种更深的结构也够用） */
const val MAX_INHERIT_HOPS = 3

/**
 * 这个文件是不是媒体库的素材图（海报 / 背景 / 缩略图 / logo…）。
 *
 * **只按扩展名认，不按文件名。** 真实库里最常见的海报就叫
 * `示例影片4 (1997) {tmdbid-8078}.jpg`（跟视频同名、没有任何角色后缀），按名字认会漏掉它，
 * 用户看到的就还是"删完目录里还剩一张图"。名字那套词汇表
 * （[DIR_POSTER_NAMES] / [DECOR_SUFFIXES] / 季图正则）回答的是另一个问题 ——
 * **这张图属于哪个条目**；这里只需要回答"这个文件是不是图"。
 *
 * 用在「彻底删除」的收尾上（见 MovieDeleter.sweepOrphanArt）：
 * **目录里已经没有任何本库条目**时才清，所以不怕删到别人的图 —— 那里已经没有"别人的"了。
 * 扩展名清单跟扫描器认图的那一份是同一个常量，不会走岔。
 */
fun isArtImageFile(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return false
    return name.substring(dot + 1).lowercase() in IMAGE_EXTS
}

// ---------------- 侧挂素材：剧照（extrafanart）与演员头像（.actors） ----------------
//
// 刮削好的影片目录里常见两个子目录（实测结构，见用户库 ABC-103-C）：
//   `extrafanart/fanart1.jpg … fanart15.jpg`  剧照，十几张
//   `.actors/<演员名>.jpg`                     演员头像（TinyMediaManager 那套）
// 它们**不是**独立条目：文件本身没有视频也没有 nfo，聚类天然聚不出东西来，
// 所以是"挂在影片目录上的附属素材"。这两个目录的清单在扫描期**本来就会列到**
// （collectDirs 递归列全部子目录），取它们零额外请求。

/** 剧照目录名（不区分大小写匹配目录项） */
val EXTRAFANART_DIR_NAMES = listOf("extrafanart")

/** 演员头像目录名：TMM 写 `.actors`，也有刮削器写不带点的 `actors` */
val ACTORS_DIR_NAMES = listOf(".actors", "actors")

/**
 * 剧照目录里的图，按**自然序**排。
 *
 * 自然序不是锦上添花：`fanart10.jpg` 按字符串排会跑到 `fanart2.jpg` 前面，
 * 详情页里剧照顺序看着就是乱的。
 * 非图片（`Thumbs.db`、`desktop.ini`、字幕）一律不算剧照。
 */
fun extraFanartFilesOf(files: List<FileRef>): List<FileRef> =
    files.filter { isArtImageFile(it.name) }.sortedWith { a, b -> compareNatural(a.name, b.name) }

/**
 * 演员头像目录：**归一化后的演员名 → 头像文件**。
 *
 * 文件名就是演员名（`示例演员.jpg`）。用归一化的名字做键，是因为同一部片的演员名
 * 可能来自 nfo、也可能来自目录名，两处的大小写/空格不会完全一致（见 [normalizeActorName]）。
 *
 * 同名多份（`张三.jpg` 与 `张三.png`）只留先到的那份：头像只需要一张。
 */
fun actorAvatarFilesOf(files: List<FileRef>): Map<String, FileRef> {
    val out = LinkedHashMap<String, FileRef>()
    for (f in files) {
        if (!isArtImageFile(f.name)) continue
        val name = f.name.substringBeforeLast('.')
        val key = normalizeActorName(name)
        if (key.isNotEmpty()) out.putIfAbsent(key, f)
    }
    return out
}

/**
 * 演员名归一化：只用于**配对**（`.actors` 里的文件名 ↔ nfo/目录名里的演员名）。
 *
 * 去掉全部空白（含全角空格）并转小写：`"John Doe"` / `"john doe"` / `"John  Doe"` 归一成同一个键。
 * 日文汉字与假名不做转换 —— 那种差异（`示例演员假名` vs `示例演员`）本质是两个不同的名字写法，
 * 猜错了会给演员配错头像，不如不配。
 */
fun normalizeActorName(name: String): String =
    name.filterNot { it.isWhitespace() }.lowercase()

/**
 * 自然序比较：数字段按**数值**比，其余按字符比（不区分大小写）。
 * 让 `fanart2.jpg` 排在 `fanart10.jpg` 前面，也让 `S01E2` 排在 `S01E10` 前面。
 */
fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var i2 = i
            while (i2 < a.length && a[i2].isDigit()) i2++
            var j2 = j
            while (j2 < b.length && b[j2].isDigit()) j2++
            // 去前导零后再比：先比长度（长的数值大），同长再比字典序
            val na = a.substring(i, i2).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, j2).trimStart('0').ifEmpty { "0" }
            val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (c != 0) return c
            i = i2
            j = j2
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    // 一个是另一个的前缀：短的排前面（fanart1.jpg 在 fanart1a.jpg 前）
    return (a.length - i) - (b.length - j)
}

/**
 * 这个目录项是不是"侧挂素材目录"（剧照 / 演员头像）。
 *
 * 扫描循环靠它把这两个目录**从条目的文件来源里排除**：它们的内容已经按附属素材
 * 处理过了，再当普通文件参与聚类只会白跑一遍（虽然聚不出东西）。
 */
fun isSideArtDirName(name: String): Boolean {
    val n = name.lowercase()
    return n in EXTRAFANART_DIR_NAMES || n in ACTORS_DIR_NAMES
}

// ---------------- 屏蔽的子目录：花絮 / 预告 / 访谈 ----------------
//
// Kodi / Emby 约定的"附加内容"目录名。它们**既不当条目、也不当素材**：整棵子树都不扫。
// 不屏蔽的话，里面的视频会各自聚成条目——实测 `ABC-201 示例演员/behind the scenes/`
// 里每一段花絮都变成海报墙上的一张卡，还带自己的 nfo/海报，混在正片里根本分不出来。
//
// ★ 不含 `Specials`：剧集包里那是"特别篇"季（S00），是真的分集。
// ★ 也不含 `Sample`：小样该由**每库的体积过滤**（minVideoSizeMb）挡，按目录名一刀切
//   容易误伤（有的库把正片放在叫 sample 的目录里？不至于，但体积过滤已经够用）。
private val EXTRA_DIR_NAMES = setOf(
    "behind the scenes", "behindthescenes", "behind_the_scenes", "behind scenes",
    "extras", "extra", "featurettes", "featurette",
    "trailers", "trailer", "interviews", "interview",
    "deleted scenes", "deletedscenes", "deleted_scenes",
    "shorts", "clips", "bonus",
)

/** 是不是"附加内容"目录（花絮/预告/访谈…）——整棵子树不扫 */
fun isExtraDirName(name: String): Boolean = name.trim().lowercase() in EXTRA_DIR_NAMES

/**
 * 扫描时要**整棵跳过**的目录名：侧挂素材（内容归父目录）+ 附加内容（丢弃）。
 *
 * 两者处理方式不同（见 [sideArtOf]），但"不进递归、不当条目"是一样的 ——
 * 放在一起是为了不漏：新增一类要屏蔽的目录时只改这一个判断。
 */
fun isIgnoredDirName(name: String): Boolean = isSideArtDirName(name) || isExtraDirName(name)

// ---------------- 分 CD / 分片的资源：合成一条 ----------------

/**
 * CD/分片后缀：`-cd1` `_CD2` `.part3` `-disc1` `-dvd1` `-vol2` `X - disc 1`。
 *
 * 两边的分隔符用 `*` 而不是 `?`：`X - disc 1` 里关键字前面是「空格 减号 空格」三个字符，
 * 只吃一个的话基名会剩下 `X -`（实测踩到，标题尾巴上挂个减号很难看）。
 *
 * 只认这几个关键词：`xxx-1`/`xxx-2` 这种纯数字编号太容易误伤（`1080p`/`720p` 也是数字结尾）。
 */
private val CD_SUFFIX = Regex("""(?i)[-_. ]*(cd|disc|disk|dvd|part|pt|vol)[-_. ]*\d{1,2}$""")

/**
 * 这一组簇是不是**同一部片的多个 CD/分片**（`ABC-201-cd1` + `ABC-201-cd2`）。
 *
 * 是就返回**合并后那条的基名**（`ABC-201`），不是返回 null。
 *
 * 为什么要合并：分 CD 存的资源每张盘都是一套完整的视频 + nfo + 海报，
 * 扫描时会被当成 N 部独立的片，海报墙上就是 N 张几乎一样的卡（实测 `ABC-201 示例演员`
 * 那个目录出 2 张）。它们本来就是一部片，应该是一张卡 + 里面列 CD1/CD2 两集。
 *
 * 判据（三条都要满足，宁可不合并也别乱合并）：
 *  ① 至少两个簇；
 *  ② **至少有一个**前缀真的带 CD 后缀（全都不带就是普通的多视频目录：一季的集、不同清晰度）；
 *  ③ 去掉后缀后是同一个基名 —— 注意 `X` 与 `X-cd2` 算同一个基名，
 *     实测库里一半的分盘就是这种"第一张盘不带后缀"的命名（`ABC-202` + `ABC-202-cd2`）。
 *
 * 所以 `S01E01/S01E02`（剧集）、`xxx-1080p/xxx-720p`（同一部片的不同清晰度）都不会被合并 ——
 * 前者一条都不带 CD 后缀，后者的基名各不相同。
 */
fun cdGroupBaseOf(clusters: List<Cluster>): String? {
    if (clusters.size < 2) return null
    if (clusters.none { CD_SUFFIX.containsMatchIn(it.prefix) }) return null
    val bases = clusters.map { it.prefix.replace(CD_SUFFIX, "").trim() }
    val base = bases.first()
    if (base.isEmpty() || bases.any { it != base }) return null
    return base
}

/**
 * CD 组的**代表**：合并后那条的元数据与图取它 —— 按自然序最小的那个前缀，
 * 也就是第一张盘（`ABC-202` 或 `ABC-201-cd1`）。
 *
 * 为什么不能直接取 `first()`：115 的列表顺序是 upt 倒序、不稳定，
 * 取到 cd2 的话合并卡的标题/海报就成了第二张盘的（标题去掉 CD 标记后一样，海报不一定）。
 */
fun cdGroupLead(clusters: List<Cluster>): Cluster? =
    clusters.minWithOrNull { a, b -> compareNatural(a.prefix, b.prefix) }

/**
 * 各 CD 的入库键（`mediaKey`）。
 *
 * ★ **第一张盘不能直接用基名当键**：实测库里一半的分盘第一张就叫基名
 *   （`ABC-202.mp4` + `ABC-202-cd2.mp4`），而合成行用的正是基名 —— 撞了的话
 *   合成行会被第一张盘那条覆盖，变成 `seriesKey = 自己` 的自引用行，
 *   海报墙上直接消失（顶层只出 seriesKey IS NULL 的）。所以第一张盘改名成 `基名-cd1`。
 *
 * 顺带把"真有一个叫 `X-cd1` 的文件"这种撞车也躲开（往后顺延成 -cd2/-cd3…，
 * 名字只是内部键，界面上的标签来自各自的标题）。
 */
fun cdChildKeys(base: String, prefixes: List<String>): List<String> {
    val used = mutableSetOf(base)
    val out = mutableListOf<String>()
    for (p in prefixes) {
        var n = 1
        var key = if (p == base) "$base-cd1" else p
        while (!used.add(key)) {
            n++
            key = "$base-cd$n"
        }
        out += key
    }
    return out
}

/** 去掉标题尾巴上的 CD 标记：`… 8 小时 BEST CD1` → `… 8 小时 BEST`（合并后的卡片标题用） */
fun stripCdMarker(title: String): String = title.replace(CD_SUFFIX, "").trim()

/**
 * 从 [dirPath] 往上列出**要依次查询**的祖先路径（不含自己），到 [rootPath] 为止。
 *
 * 分集继承用：剧集包通常只在**系列根目录**放 `poster.jpg`/`fanart.jpg`（`Season 1/` 里什么都没有），
 * 于是整季的卡片全是空白。往上找第一个"有图"的祖先目录，就是系列根。
 *
 * 两条边界：
 * - **不能越过库根**（`cur.length >= stop.length`）：越过就可能借到隔壁库的图
 * - 层数封顶 [MAX_INHERIT_HOPS]：正常结构一层就命中，多留几层是给 Disc 子目录那种用的
 */
fun ancestorPaths(dirPath: String, rootPath: String, maxHops: Int = MAX_INHERIT_HOPS): List<String> {
    val stop = rootPath.trimEnd('/')
    val out = mutableListOf<String>()
    var cur = dirPath.trimEnd('/').substringBeforeLast('/', "")
    while (out.size < maxHops && cur.isNotEmpty() && cur.length >= stop.length) {
        out += cur
        if (cur == stop) break
        cur = cur.substringBeforeLast('/', "")
    }
    return out
}
