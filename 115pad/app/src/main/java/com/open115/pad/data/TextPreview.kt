package com.open115.pad.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本类文件的预览支持：扩展名判定、内容抓取（带大小上限）、编码兜底与分页。
 *
 * 取内容走"直链 + HTTP Range"：只要前 [TEXT_PREVIEW_MAX_BYTES] 字节，4GB 的文本也不会
 * 把内存吃光；服务端不支持 Range 时退化为"读够上限就断开"，同样不会拉满整个文件。
 */

/** 支持的编码（顺序即 UI 展示顺序，第一个为解码时自动检测的结果） */
val TEXT_ENCODINGS = listOf("UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE", "ISO-8859-1")

/** 可当文本预览的扩展名（小写、不含点） */
private val TEXT_EXTENSIONS = setOf(
    // 纯文本 / 说明
    "txt", "text", "md", "markdown", "log", "csv", "tsv", "rtf",
    // 配置
    "ini", "conf", "cfg", "properties", "env", "json", "json5", "xml", "yaml", "yml",
    "toml", "lock", "gradle", "pro", "editorconfig",
    // 代码
    "py", "pyi", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
    "java", "kt", "kts", "groovy", "scala", "c", "h", "cc", "cpp", "cxx", "hpp", "hh",
    "cs", "go", "rs", "php", "rb", "swift", "m", "mm", "lua", "pl", "r", "dart",
    "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1", "sql",
    // 前端
    "css", "scss", "sass", "less", "html", "htm", "svg", "vue",
    // 字幕 / 其它
    "srt", "vtt", "ass", "ssa", "sub", "nfo", "diff", "patch", "reg", "url",
)

/** 无扩展名但确定是纯文本的常见文件名 */
private val TEXT_FILENAMES = setOf(
    "makefile", "dockerfile", "readme", "license", "licence", "changelog",
    "gitignore", "gitattributes", "editorconfig", "authors", "notice", "todo",
)

/** 单次预览最多拉取的字节数：再大就不是"看一眼"的场景，也避免预览把内存吃光 */
const val TEXT_PREVIEW_MAX_BYTES = 4 * 1024 * 1024

/** 每页行数：分页粒度，配合页面内滚动（超长行、窄屏都不会被截断） */
const val TEXT_LINES_PER_PAGE = 60

/** 文件名是否像纯文本（决定点击时进预览还是弹下载框） */
fun isTextFile(name: String): Boolean {
    val n = name.substringAfterLast('/').trim().lowercase()
    if (n.isEmpty()) return false
    val dot = n.lastIndexOf('.')
    if (dot <= 0 || dot == n.length - 1) return n in TEXT_FILENAMES
    val ext = n.substring(dot + 1)
    return ext in TEXT_EXTENSIONS
}

/**
 * 预览内容：整篇按行切好后按页取用，避免每页各存一份字符串。
 *
 * 保留原始字节，编码可切换——初次自动判定一种，之后用户可在 [TEXT_ENCODINGS] 里手选
 * 重解（常见场景：GBK 中文 txt 被误判成别的编码，选 "GB18030"/"GBK" 即可修复）。
 */
data class TextPreview(
    /** 最近一次解码得到的行 */
    val lines: List<String>,
    /** 当前生效的编码名 */
    val encoding: String,
    val rawBytes: ByteArray,
    /** 是否因超过上限被截断 */
    val truncated: Boolean,
    val bytes: Long,
) {
    val pageCount: Int
        get() = ((lines.size - 1).coerceAtLeast(0) / TEXT_LINES_PER_PAGE) + 1

    /** 第 [index] 页（0 基）的行；越界自动夹到有效范围 */
    fun page(index: Int): List<String> {
        val from = index.coerceIn(0, pageCount - 1) * TEXT_LINES_PER_PAGE
        val to = (from + TEXT_LINES_PER_PAGE).coerceAtMost(lines.size)
        return lines.subList(from, to)
    }

    /** 按 [enc] 重新解码（供编码切换）。失败的编码返回 null，保留现有内容。 */
    fun reDecode(enc: String): TextPreview? {
        val text = decodeAs(rawBytes, enc) ?: return null
        return copy(
            lines = splitLines(text),
            encoding = enc,
            bytes = rawBytes.size.toLong(),
        )
    }
}

/** 把已解码文本按行切开（兼容 \r\n） */
private fun splitLines(text: String): List<String> =
    text.split('\n').map { it.trimEnd('\r') }

sealed interface TextPreviewResult {
    data class Ok(val preview: TextPreview) : TextPreviewResult
    data class Fail(val message: String) : TextPreviewResult
}

/**
 * 拉取文本内容并切好行。
 *
 * @param url 115 直链（调用方负责解析）
 */
suspend fun fetchTextPreview(
    client: OkHttpClient,
    url: String,
    maxBytes: Int = TEXT_PREVIEW_MAX_BYTES,
): TextPreviewResult = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${maxBytes - 1}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@withContext TextPreviewResult.Fail("读取失败（HTTP ${resp.code}）")
            }
            val body = resp.body ?: return@withContext TextPreviewResult.Fail("响应为空")
            // 多读 1 字节用于判断"是否还有后续内容"（服务端忽略 Range 时也能截住）
            val buf = ByteArray(maxBytes + 1)
            var read = 0
            body.byteStream().use { ins ->
                while (read < buf.size) {
                    val n = ins.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            val truncated = read > maxBytes
            val data = if (truncated) buf.copyOf(maxBytes) else buf.copyOf(read)
            // 初次解码：自动检测编码并切好行；原始字节一并保留，供之后切换编码重解
            val (encoding, lines) = detectAndDecode(data)
            TextPreviewResult.Ok(
                TextPreview(
                    lines = lines,
                    encoding = encoding,
                    rawBytes = data,
                    truncated = truncated,
                    bytes = data.size.toLong(),
                ),
            )
        }
    } catch (e: Exception) {
        TextPreviewResult.Fail("读取失败：${e.message ?: e::class.java.simpleName}")
    }
}

/**
 * 解码文本：先严格按 UTF-8 试，失败再退 GB18030。
 *
 * 必须用严格模式（REPORT）——宽松模式会把非法字节换成 U+FFFD，任何 GBK 文本都能"解成"
 * 一堆乱码字符而不报错，那样永远轮不到 GB18030 兜底。中文 txt/字幕大量是 GBK/GB18030。
 * 返回（编码名, 行）。
 */
private fun detectAndDecode(bytes: ByteArray): Pair<String, List<String>> {
    val utf8 = decodeAs(bytes, "UTF-8")
    if (utf8 != null) return "UTF-8" to splitLines(utf8)
    val gb = decodeAs(bytes, "GB18030")
    if (gb != null) return "GB18030" to splitLines(gb)
    val latin = decodeAs(bytes, "ISO-8859-1")
    if (latin != null) return "ISO-8859-1" to splitLines(latin)
    // 都失败（极端情况）：宽松 UTF-8 兜底，用户至少能看到可读的部分
    return "UTF-8（有无法解码的字节）" to splitLines(String(bytes, Charsets.UTF_8).removePrefix(BOM))
}

/**
 * 用 [encName] 严格解码字节；失败（非法字节）返回 null。
 * 支持 BOM：带 BOM 的 UTF-* 会自动去头，其余编码不受影响。
 */
private fun decodeAs(bytes: ByteArray, encName: String): String? {
    if (bytes.isEmpty()) return ""
    val charset: Charset = when {
        encName in listOf("UTF-8", "UTF-8（有无法解码的字节）") -> Charsets.UTF_8
        encName == "GB18030" -> Charset.forName("GB18030")
        encName == "GBK" -> Charset.forName("GBK")
        encName == "UTF-16LE" -> Charset.forName("UTF-16LE")
        encName == "UTF-16BE" -> Charset.forName("UTF-16BE")
        encName == "ISO-8859-1" -> Charset.forName("ISO-8859-1")
        else -> runCatching { Charset.forName(encName) }.getOrNull() ?: Charsets.UTF_8
    }
    return runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix(BOM)
    }.getOrNull()
}

private const val BOM = "\uFEFF"
