package com.open115.pad.util

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** 识别到的链接类型 */
enum class LinkType(val label: String) {
    MAGNET("磁力链接"),
    ED2K("电驴链接"),
    HTTP("HTTP 直链"),
    FTP("FTP 直链"),
    PAN_SHARE("网盘分享链接"),
}

/**
 * 从任意文本中识别出的下载链接。
 * @param url  规范化后的完整链接（磁力链接保留全部参数）
 * @param preview 给用户看的缩略文本：磁力优先取 dn= 里的文件名，否则截断链接
 */
data class DetectedLink(
    val type: LinkType,
    val url: String,
    val preview: String,
)

/**
 * 剪贴板 / 外部唤起的链接解析。
 *
 * 设计取舍：
 * - 用 `[^\s"'<>]` 而不是规格里的 `.*` 收尾 —— 剪贴板里常是"看这个 magnet:?xt=... 挺好"这类整句，
 *   `.*` 会把后面的中文一起吞进来，导致提交给云下载的链接非法。
 * - 判定 HTTP 直链只看 URL 的 **path** 部分是否以已知后缀结尾，忽略 query，
 *   否则 `.../a.mkv?sign=xxx` 这类带签名的直链会被判成"无后缀"而漏掉。
 */
object LinkParser {

    private val MAGNET = Regex(
        """magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^\s"'<>]*""",
        RegexOption.IGNORE_CASE,
    )
    private val ED2K = Regex("""ed2k://\|file\|[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val HTTP = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val FTP = Regex("""ftp://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** 常见归档 / 媒体 / 种子后缀（及少数常见可执行包） */
    private val KNOWN_SUFFIXES = setOf(
        // 视频
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "m4v", "ts", "m2ts", "rmvb", "rm", "webm",
        "mpg", "mpeg", "vob", "3gp", "iso",
        // 音频
        "mp3", "flac", "ape", "wav", "aac", "m4a", "dts",
        // 归档与种子
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "torrent",
        // 常见安装包
        "apk", "exe", "dmg", "pkg",
    )

    /** 常见网盘分享链接（host + 路径前缀） */
    private val PAN_SHARE_PATTERNS = listOf(
        Regex("""^115\.com/(s|lb)/""", RegexOption.IGNORE_CASE),
        Regex("""^(www\.)?(anxia|115cdn)\.com/(s|lb)/""", RegexOption.IGNORE_CASE),
        Regex("""^pan\.baidu\.com/(s|share)/""", RegexOption.IGNORE_CASE),
        Regex("""^(www\.)?aliyundrive\.com/s/""", RegexOption.IGNORE_CASE),
        Regex("""^(www\.)?alipan\.com/s/""", RegexOption.IGNORE_CASE),
        Regex("""^cloud\.189\.cn/""", RegexOption.IGNORE_CASE),
        Regex("""^(pan\.)?quark\.cn/s/""", RegexOption.IGNORE_CASE),
        Regex("""^(www\.)?123pan\.com/s/""", RegexOption.IGNORE_CASE),
        Regex("""^drive\.uc\.cn/s/""", RegexOption.IGNORE_CASE),
    )

    /**
     * 115 自家域名。
     * 这些域名下的直链即使后缀不常见（例如多段上传产生的 `xxx.apk.1`）也照收：
     * 对 115 客户端来说，"从别处复制到 115 直链" 才是最典型的粘贴场景。
     */
    private val TRUSTED_FILE_HOSTS = listOf("115.com", "115cdn.com", "115cdn.net", "anxia.com")

    /**
     * 从文本里挑出一个可提交的下载链接；识别不到返回 null。
     * 优先级：磁力 > 电驴 > FTP > HTTP（磁力/电驴的信息量最大，直链次之）。
     */
    fun parse(text: String?): DetectedLink? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null

        MAGNET.find(raw)?.let { m ->
            val url = m.value
            val name = magnetDisplayName(url) ?: url
            return DetectedLink(LinkType.MAGNET, url, name)
        }
        ED2K.find(raw)?.let { m ->
            val url = m.value
            return DetectedLink(LinkType.ED2K, url, ed2kDisplayName(url) ?: truncate(url))
        }

        // 直链：FTP 优先，其次 HTTP(S)
        FTP.find(raw)?.let { m ->
            val url = m.value.trimEnd('.', ',', '，', '。', ')', '）')
            if (looksLikeFile(url)) return DetectedLink(LinkType.FTP, url, truncate(url))
        }
        HTTP.find(raw)?.let { m ->
            val url = m.value.trimEnd('.', ',', '，', '。', ')', '）')
            val hostPath = hostAndPath(url) ?: return@let
            if (isPanShare(hostPath)) {
                return DetectedLink(LinkType.PAN_SHARE, url, truncate(url))
            }
            val host = hostPath.substringBefore('/')
            if (looksLikeFile(url) || isTrustedFileHost(host)) {
                return DetectedLink(LinkType.HTTP, url, truncate(url))
            }
        }
        return null
    }

    /** 去掉 http(s):// 前缀后的 host+path（用于网盘分享匹配） */
    private fun hostAndPath(url: String): String? {
        val noScheme = url.substringAfter("://", "")
        if (noScheme.isEmpty()) return null
        return noScheme.substringBefore('?').substringBefore('#')
    }

    /** 只检查 URL 的 path 是否以已知后缀结尾，忽略 query / fragment */
    private fun looksLikeFile(url: String): Boolean {
        val path = hostAndPath(url) ?: return false
        val last = path.substringAfterLast('/', "")
        val ext = last.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.isNotEmpty() && ext in KNOWN_SUFFIXES
    }

    private fun isPanShare(hostPath: String): Boolean =
        PAN_SHARE_PATTERNS.any { it.containsMatchIn(hostPath) }

    /** host 是否为受信任的 115 域名（含子域），用于放宽后缀要求 */
    private fun isTrustedFileHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).substringBefore(':')
        return TRUSTED_FILE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** 磁力链接里的 dn= 通常是文件名，作为预览比一长串 hash 友好得多 */
    private fun magnetDisplayName(url: String): String? {
        val dn = Regex("""[?&]dn=([^&]+)""").find(url)?.groupValues?.get(1) ?: return null
        val decoded = urlDecode(dn) ?: dn
        return decoded.replace('+', ' ').ifBlank { null }
    }

    /** ed2k 的文件名在第 2 段：ed2k://|file|<name>|<size>|<hash>|/ */
    private fun ed2kDisplayName(url: String): String? {
        val parts = url.removePrefix("ed2k://").split('|')
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return urlDecode(name)?.replace('+', ' ') ?: name
    }

    fun truncate(url: String, max: Int = 72): String =
        if (url.length <= max) url else url.take(max - 1) + "…"

    /** URLDecoder 遇到非法转义会抛异常，这里统一兜底为 null */
    fun urlDecode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        null
    }

    /**
     * 剪贴板内容指纹（MD5）。用于"上次已解析过的文本不要再提示"。
     * 对整段文本取哈希而不是对链接取，这样同一链接出现在不同句子里也只按文本判重。
     */
    fun contentKey(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(text.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
