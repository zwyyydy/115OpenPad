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

/** 一组文件：同公共前缀的 .nfo/.jpg/视频 归一类 */
data class Cluster(
    val prefix: String,
    val video: FileRef? = null,
    val nfo: FileRef? = null,
    val poster: FileRef? = null,
    val fanart: FileRef? = null,
    val thumb: FileRef? = null,
) {
    /** 键：{tmdbid-348} 优先，否则取前缀本体（如 ABC-301 / 示例影片 (1979)） */
    fun mediaKey(): String = TMDB_ID.find(prefix)?.groupValues?.get(1) ?: prefix
}

data class FileRef(val name: String, val pickCode: String, val sizeBytes: Long, val upt: Long)

/** 从文件名前缀提取 {tmdbid-348} 里的 348（右括号必须转义，否则 ICU 正则编译崩） */
val TMDB_ID = Regex("""\{tmdbid-(\d+)\}""")

/**
 * 纯逻辑聚类（对文件名列表）。folder.jpg 是无前缀海报的兜底，归到唯一视频组。
 */
fun clusterFiles(files: List<FileRef>): List<Cluster> {
    data class MutableCluster(
        val prefix: String,
        var video: FileRef? = null,
        var nfo: FileRef? = null,
        var poster: FileRef? = null,
        var fanart: FileRef? = null,
        var thumb: FileRef? = null,
    )

    val byPrefix = LinkedHashMap<String, MutableCluster>()
    var folderJpg: FileRef? = null

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
        if (f.name.equals("folder.jpg", ignoreCase = true) || f.name.equals("poster.jpg", ignoreCase = true)) {
            folderJpg = f
            continue
        }
        val prefix = basePrefix(f.name) ?: continue
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val stem = f.name.substringBeforeLast('.')
        val decor = DECOR_SUFFIXES.firstOrNull { stem.lowercase().endsWith(it) }
        val c = byPrefix.getOrPut(prefix) { MutableCluster(prefix) }
        when {
            ext in VIDEO_EXTS -> if (c.video == null) c.video = f
            ext == "nfo" -> if (c.nfo == null) c.nfo = f
            ext in IMAGE_EXTS -> when (decor) {
                "-poster" -> if (c.poster == null) c.poster = f
                "-fanart", "-backdrop" -> if (c.fanart == null) c.fanart = f
                "-thumb", "-landscape" -> if (c.thumb == null) c.thumb = f
                else -> if (c.poster == null) c.poster = f // 无角色标记的图当海报
            }
        }
    }

    return byPrefix.values
        .filter { it.video != null || it.nfo != null }
        .map { c ->
            Cluster(
                prefix = c.prefix,
                video = c.video,
                nfo = c.nfo,
                poster = c.poster ?: folderJpg.takeIf { byPrefix.size == 1 },
                fanart = c.fanart,
                thumb = c.thumb,
            )
        }
}

/**
 * 剧集判定：一个目录里有多个视频组（季集目录），每组是一条集。
 * 影片判定：一个目录只聚出一组。
 */
data class DirScan(
    val clusters: List<Cluster>,
    val isMultiVideo: Boolean,
)

fun sniffDirectory(files: List<FileRef>): DirScan {
    val clusters = clusterFiles(files)
    return DirScan(clusters = clusters, isMultiVideo = clusters.count { it.video != null } > 1)
}

/** 剧集键：从文件名前缀提取 S01E02 / 第X集；没有则用前缀本身 */
fun episodeKeyOf(prefix: String): String =
    Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(prefix)?.value
        ?: Regex("""[Ee][Pp]?(\d{1,3})""").find(prefix)?.value
        ?: prefix

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
