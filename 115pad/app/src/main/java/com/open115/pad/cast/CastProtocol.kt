package com.open115.pad.cast

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 投屏协议层里**纯计算**的那部分：SSDP 报文、UPnP 设备描述、DIDL/SOAP 拼装、时间与 Range 解析。
 *
 * 全部不碰 Android API，单测直接喂字符串即可。真正的设备兼容性只能真电视上验，
 * 但"标题里有 &amp; 会不会把 SOAP 拼坏"" seek 时间格式对不对""Range 边界差一"这些
 * 一旦错了就是黑屏/不能拖进度，必须先在这里盯住。
 */

/**
 * 投屏目标的两条通道：
 * - [Dlna]：通用兼容通道。任何 DLNA 渲染器（老电视、别人家的电视）都能投，代价是
 *   必须本机中转补 UA、平板进程要一直活着；
 * - [PadTv]：装了「115 openpad电视端」的设备。电视端自己带正确 UA 拉直链，
 *   平板只发一条播放指令，发完即可退出、零中转。
 */
internal enum class CastTarget { Dlna, PadTv }

/** 一台可投屏的 DLNA 渲染器（MediaRenderer） */
internal data class CastDevice(
    val name: String,
    val model: String?,
    /** 设备描述 XML 的地址（排重用） */
    val location: String,
    val udn: String,
    val avTransportUrl: String,
    /** 设备自己声明的服务类型（有的电视是 AVTransport:2） */
    val avTransportType: String,
    val renderingControlUrl: String?,
    val renderingControlType: String?,
    /** 投屏通道；DLNA 设备构造时不用带这个参数 */
    val target: CastTarget = CastTarget.Dlna,
)

/** 投屏会话状态（位置/时长单独走 [CastSession.progress]，避免每秒重组整个播放页） */
internal sealed interface CastUiState {
    object Idle : CastUiState
    data class Connecting(val device: CastDevice) : CastUiState
    data class Casting(val device: CastDevice, val playing: Boolean) : CastUiState
}

/** 一档可选画质（电视端 /status 上报的）：def 编号 + 显示名 */
internal data class CastQualityOption(
    val def: Int,
    val label: String,
)

/** 电视侧进度（轮询得到） */
internal data class CastProgress(
    val positionMs: Long,
    val durationMs: Long,
    /** 电视侧倍速（1.0 = 正常）；DLNA 通道恒为 1（那边没有远端倍速能力） */
    val speed: Float = 1f,
    /** 电视侧媒体音量与上限；-1 = 该通道读不到（DLNA 没有 RenderingControl 实现） */
    val volume: Int = -1,
    val volumeMax: Int = -1,
    /** 电视侧解码方式（hw/sw）与播放器（builtin/system）；DLNA 通道恒为默认值 */
    val decode: String = PadTv.DECODE_HW,
    val player: String = PadTv.PLAYER_BUILTIN,
    /** 电视侧当前画质档（def 编号）与可选档列表：遥控面板 chips 高亮/绘制用 */
    val quality: Int = 0,
    val qualityOptions: List<CastQualityOption> = emptyList(),
    /** 电视侧字幕开关：遥控 chips 高亮用 */
    val subtitleOn: Boolean = true,
    /** true = 电视端把流交给了它自带的播放器：进度与遥控都不可用（面板只留"停止投屏"） */
    val external: Boolean = false,
)

/** 解析一条 SSDP M-SEARCH 响应，返回小写头名 → 值 */
internal fun parseSsdpHeaders(text: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (line in text.lineSequence()) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim().lowercase(Locale.US)
        val value = line.substring(idx + 1).trim()
        if (key.isNotEmpty() && value.isNotEmpty()) out.putIfAbsent(key, value)
    }
    return out
}

/**
 * 解析 UPnP 设备描述，抽出可投屏所需的两条控制地址。
 *
 * 找不到 AVTransport 就返回 null —— 路由器、打印机、纯 MediaServer 都会回 SSDP，
 * 只有带 AVTransport 的才是能"被推视频"的渲染器。
 *
 * 控制地址可能是相对路径（`/upnp/control/AVTransport1`），按 LOCATION 的基址解析。
 */
internal fun parseDeviceDescription(xml: ByteArray, location: String): CastDevice? {
    val doc = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 设备描述是我们主动去拉的可信局域网设备，但禁掉 DTD 仍然是好习惯；
            // Android 的实现不一定认这个 feature，认不得就照常解析。
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }.getOrNull() ?: return null
    val root = doc.documentElement ?: return null
    val device = childElements(root, "device").firstOrNull() ?: return null

    fun text(el: Element, name: String): String? =
        childElements(el, name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    val friendly = text(device, "friendlyName") ?: return null
    val model = text(device, "modelName")
    val udn = text(device, "UDN") ?: location

    var avUrl: String? = null
    var avType: String? = null
    var rcUrl: String? = null
    var rcType: String? = null
    val services = childElements(device, "serviceList")
        .flatMap { childElements(it, "service") }
    for (s in services) {
        val type = text(s, "serviceType") ?: continue
        val control = text(s, "controlURL") ?: continue
        when {
            type.contains(":service:AVTransport:") -> {
                avType = type
                avUrl = runCatching { URI(location).resolve(control).toString() }.getOrNull()
            }
            type.contains(":service:RenderingControl:") -> {
                rcType = type
                rcUrl = runCatching { URI(location).resolve(control).toString() }.getOrNull()
            }
        }
    }
    if (avUrl.isNullOrBlank() || avType.isNullOrBlank()) return null
    return CastDevice(
        name = friendly,
        model = model,
        location = location,
        udn = udn,
        avTransportUrl = avUrl,
        avTransportType = avType,
        renderingControlUrl = rcUrl,
        renderingControlType = rcType,
    )
}

/** DOM 里按 localName 找直接子元素（忽略命名空间前缀，Android 与 JVM 一致） */
internal fun childElements(parent: Element, localName: String): List<Element> {
    val nodes = parent.childNodes
    val out = ArrayList<Element>()
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType != Node.ELEMENT_NODE) continue
        val el = n as Element
        val ln = el.localName ?: el.tagName.substringAfter(':')
        if (ln.equals(localName, ignoreCase = true)) out.add(el)
    }
    return out
}

/** XML 文本转义（属性与元素文本共用；`'` 与 `"` 都转，省得记哪个位置该用哪套） */
internal fun xmlEscape(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (ch in value) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** DIDL-Lite 元数据：电视靠它知道"这是一段视频"，标题也显示在电视的播放信息里 */
internal fun didlLite(title: String, url: String, mime: String): String =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
        "<dc:title>${xmlEscape(title)}</dc:title>" +
        "<upnp:class>object.item.videoItem</upnp:class>" +
        "<res protocolInfo=\"http-get:*:${xmlEscape(mime)}:*\">${xmlEscape(url)}</res>" +
        "</item></DIDL-Lite>"

/**
 * SOAP 信封。参数值统一转义 —— `CurrentURIMetaData` 里装的是整段 DIDL，
 * 标题带 `&` 时少转一层就会被电视判成 500（实测最常见的"投屏失败"来源之一）。
 */
internal fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>): String {
    val body = buildString {
        append("<u:").append(action).append(" xmlns:u=\"").append(xmlEscape(serviceType)).append("\">")
        for ((k, v) in args) {
            append('<').append(k).append('>').append(xmlEscape(v))
            append("</").append(k).append('>')
        }
        append("</u:").append(action).append('>')
    }
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" " +
        "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$body</s:Body></s:Envelope>"
}

/** DLNA 的 REL_TIME 格式：HH:MM:SS */
internal fun formatDlnaTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        total / 3600,
        (total % 3600) / 60,
        total % 60,
    )
}

/** 解析电视返回的 HH:MM:SS（有的带毫秒，按秒截断）；不是时间返回 null */
internal fun parseDlnaTime(value: String?): Long? {
    val s = value?.trim()?.substringBefore('.') ?: return null
    if (s.isEmpty()) return null
    val parts = s.split(':')
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    // 分/秒必须 < 60：把 "99:99" 这类脏数据当时间会让 seek 静默失效
    if (nums.size == 3) {
        if (nums[1] > 59 || nums[2] > 59) return null
        return (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000
    }
    if (nums[1] > 59) return null
    return (nums[0] * 60 + nums[1]) * 1000
}

/** 从 SOAP 响应里抠一个元素文本（命名空间无关） */
internal fun xmlTextTag(xml: String, tag: String): String? =
    Regex("<(?:[\\w.-]+:)?${Regex.escape(tag)}>([^<]*)</", RegexOption.IGNORE_CASE)
        .find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

/** 电视拒绝请求时带上它自己的错误码/描述，比"投屏失败"有用得多 */
internal fun parseUpnpError(xml: String): String? {
    val desc = xmlTextTag(xml, "errorDescription")
    val code = xmlTextTag(xml, "errorCode")
    return when {
        desc != null && code != null -> "$desc（$code）"
        desc != null -> desc
        code != null -> "错误码 $code"
        else -> null
    }
}

/** 字节范围（只支持单段；`bytes=start-` 的 end 为 null） */
internal data class ByteRange(val start: Long, val end: Long?) {
    /** HTTP Content-Range 里的 `start-end`（end 缺省时用文件总长补；总长也不知道时用开放范围） */
    fun headerValue(total: Long?): String {
        val last = end ?: total?.minus(1)
        return if (last != null) "bytes $start-$last" else "bytes $start-"
    }
}

/**
 * 解析 `Range: bytes=start-end` / `bytes=start-`。
 *
 * 刻意不支持 `bytes=-N`（末尾 N 字节）：那要先知道文件总长，中转层得为此多发一次请求；
 * 实测电视/盒子基本只用前两种。返回 null = 当整段处理（200）。
 */
internal fun parseByteRange(header: String?): ByteRange? {
    val v = header?.trim() ?: return null
    if (!v.startsWith("bytes=", ignoreCase = true)) return null
    val spec = v.substringAfter('=').trim()
    if (spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash <= 0) return null
    val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
    if (start < 0) return null
    val endText = spec.substring(dash + 1).trim()
    val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
    if (end != null && end < start) return null
    return ByteRange(start, end)
}

/** 从 `Content-Range: bytes 0-0/123456` 取总长（HEAD 探测用） */
internal fun contentRangeTotal(value: String?): Long? {
    val v = value ?: return null
    val total = v.substringAfterLast('/').trim()
    return total.toLongOrNull()
}

/** 按扩展名给 Content-Type：电视对 MIME 比浏览器挑剔，mp4/mkv 至少要报对 */
internal fun castMimeOf(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv", "asf" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}

/** URL 路径段编码：空格必须 `%20`（`+` 在路径里不是空格），保留扩展名的点 */
internal fun pathEncode(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if ((ch.isLetterOrDigit() && c < 128) || ch == '.' || ch == '-' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()
