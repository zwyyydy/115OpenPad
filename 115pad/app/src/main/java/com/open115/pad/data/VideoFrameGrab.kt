package com.open115.pad.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.open115.pad.util.APP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 从 115 的**最小转码档（HLS）**里抓帧当视频预览（封面一张 + 预览条若干张）。
 *
 * 为什么走转码档而不是原始文件（2026-10-07 真机实测，探针跑完即删、结论在下面）：
 * - 115 给视频生成的转码档就是 HLS：`open/video/play` 返回的每个 definition（1~5）
 *   是一条 **master playlist**（`application/x-mpegURL`）→ 一条 variant → media playlist 里
 *   `#EXTINF:10.000000,` 的 **10 秒分片**，分片路径是 `/xxx/video-1080p/video-00001.ts`。
 * - 分片开头就是关键帧，所以"取 30% 处的画面"= 只拉**那一个分片的一小段**，不需要对 GB 级
 *   原文件做远程 seek。实测 `Range: bytes=0-385023` → `206` + 376KB / 159ms。
 * - 只给 `MediaMetadataRetriever` 这 376KB 的 TS 头，就能解出 1920x1080 的首帧（164ms）。
 *   整段 3.3MB 只是多花流量，画面一模一样。
 * - 音频在单独的 `#EXT-X-MEDIA:TYPE=AUDIO` 组里，视频分片不带音轨，解帧更轻。
 * - 最小档实测多是 **def 4 = 1080P / 3Mbps**（115 只生成它有的那几档，不是 360P）。
 *
 * **没有转码档就退到"原画"（def=100）**（2026-10-07 加，用户："很多视频只有原画 原盘两个挡位，
 * 这样的能否抽原画的帧？"）：115 对**原画**同样是以 HLS 提供的——播放器把 def=100 当
 * `MimeTypes.APPLICATION_M3U8` 直接播，URL 形如 `videoplay.115.com/m3u8/<pc>?…&definition=100…`，
 * 所以这一档能原样复用下面这套"playlist → 分片头 → MMR"的办法，只是分片码率高、同样取 376KB
 * 头（高码率下这 376KB 覆盖的时间更短，但**分片开头那个 IDR 一直在**）。优先用最小转码档是因为
 * 它更省、更稳；原画只是**兜底**，不改"有转码档就用转码档"的行为。
 *
 * 硬边界：只有 115 已经转码过、或者**至少给了原画 HLS**的视频才有 m3u8。实测样本 8 个里 6 个有
 * 转码档。两者都没有（比如还在"云端转码中"、或者 def=100 那条其实指向原始文件字节）就返回 null，
 * 由调用方按"暂不可用"处理（不要报成错误）。**原盘（999）不行**：那是原始文件/蓝光目录，
 * 不是 HLS，要另走"远程 Range + 解码器"那套（目前没做）。
 */

/** 抓一帧只拉分片开头这么多字节（TS 包 188 字节 × 2048）。实测 376KB 足够，别改成拉整段 */
private const val SEGMENT_HEAD_BYTES = 188L * 2048

/**
 * 解不出帧时**再给这一片一次更大的额度**（188 × 16384 ≈ 3MB）。
 *
 * 为什么需要：转码档是 3Mbps 上下，376KB 覆盖约 1 秒、肯定含完整关键帧；但**原画**可能是
 * 4K/60Mbps，**一个 I 帧自己就超过 376KB**，那头里根本没有完整关键帧 → 解不出来。
 * 只在第一次失败时才多花这几 MB（能解出来的片不付这份流量）。
 */
private const val SEGMENT_HEAD_BYTES_RETRY = 188L * 16384

/** 115 的"原画"档位号（`defLabel(100) = "原画"`）：不是转码档，但也是 HLS，抓帧当兜底用 */
private const val DEF_ORIGINAL = 100

/**
 * 读 playlist 时最多读这么多字节。
 *
 * 不是为了"省"（playlist 一般几十 KB），是为了**兜住"这条 URL 其实是原始文件"的情况**：
 * 无上限的 `body.string()` 会把整部片子拉下来。同理见 [readAtMost]。
 */
private const val PLAYLIST_MAX_BYTES = 512L * 1024

/** fMP4 的初始化段上限（实测 115 是 TS 没有这一段，留个上限防意外拉整文件） */
private const val INIT_SEGMENT_MAX_BYTES = 2L * 1024 * 1024

/** 封面落盘的最长边：1080P 帧原样存约 130KB，缩到 640 后约 40KB，列表小方块与网格卡都够看 */
const val PREVIEW_MAX_WIDTH = 640

/** 封面 JPEG 质量 */
private const val PREVIEW_JPEG_QUALITY = 80

/**
 * 封面取样位置（时长的比例）。
 *
 * 取 0.3 而不是 0：片头常有黑场/台标/演职员表，抓出来当缩略图很难看。
 * （0.3 落在第 4 个分片上，配 10 秒分片即"从第 30 秒附近开始的那一片"。）
 */
const val PREVIEW_AT = 0.3

/**
 * 预览条（列表里视频行下面那条宽幅横排图）最多几帧。
 * 再多也只是"同一部片的另外几帧"，用户要的是扫一眼看个大概。
 */
const val STRIP_MAX_FRAMES = 10

/** 最少两帧：只给一帧跟封面没区别，看不出片子大概长什么样 */
private const val STRIP_MIN_FRAMES = 2

/** 预览条单帧的最长边与质量：它只需"看得清大概是啥"，320/70 实测约 10~15KB 一张 */
private const val STRIP_MAX_WIDTH = 320
private const val STRIP_JPEG_QUALITY = 70

/** 预览条取样密度：**每约 5 分钟一帧**（数量与片长正相关），再按上下限夹一次 */
private const val STRIP_SECONDS_PER_FRAME = 300.0

/**
 * 这个时长的视频该抽几帧预览条。
 *
 * 正相关 + 上下限：90 秒 → 2 帧；10 分钟 → 2；25 分钟 → 5；50 分钟 → 10（到顶）；
 * 100 分钟 → 也是 10。时长未知（0）按最少算。
 */
fun stripFrameCount(durationSec: Long): Int {
    if (durationSec <= 0) return STRIP_MIN_FRAMES
    val byDuration = Math.round(durationSec / STRIP_SECONDS_PER_FRAME).toInt()
    return byDuration.coerceIn(STRIP_MIN_FRAMES, STRIP_MAX_FRAMES)
}

/**
 * 预览条各帧的取样位置：n 帧就均分 n 份、各取中点（避开首尾各半个格）。
 * 例如 n=2 → 0.25 / 0.75；n=10 → 0.05 / 0.15 / … / 0.95。
 */
fun stripFractions(n: Int): List<Double> {
    val count = n.coerceIn(1, STRIP_MAX_FRAMES)
    return (0 until count).map { (it + 0.5) / count }
}

/**
 * 「同片多版本」做画面比对时用的取样位置。
 * 三个点必须**按比例取**：两个版本时长一致（同一条时间线），按比例取才能采到同一画面。
 */
val COMPARE_ATS = listOf(0.2, 0.5, 0.8)

/** HLS master playlist 里的一个变体（只看码率，用来挑最低那档） */
data class HlsVariant(val bandwidth: Long, val url: String)

/** HLS media playlist 里的一个分片 */
data class HlsSegment(val startSec: Double, val durationSec: Double, val url: String)

data class HlsPlaylist(
    val variants: List<HlsVariant> = emptyList(),
    val segments: List<HlsSegment> = emptyList(),
    /** 有 `#EXT-X-KEY`（非 METHOD=NONE）→ 分片是加密的，抓不了帧 */
    val encrypted: Boolean = false,
    /** `#EXT-X-MAP` 的初始化段（fMP4 才需要；实测 115 是 TS，没有这一段） */
    val initSegment: String? = null,
) {
    val isMaster: Boolean get() = variants.isNotEmpty()
    val totalSec: Double get() = segments.sumOf { it.durationSec }
}

/** 把 playlist 里的相对路径补成绝对 URL（实测 115 的分片是 `/xxx/video-00001.ts` 这种根相对） */
internal fun absUrl(base: String, uri: String): String = when {
    uri.startsWith("http://") || uri.startsWith("https://") -> uri
    uri.startsWith("/") -> (Regex("^(https?://[^/]+)").find(base)?.value ?: "") + uri
    else -> base.substringBeforeLast('/') + "/" + uri
}

/**
 * 解析 m3u8（master 与 media 用同一个函数：谁有内容谁非空）。
 *
 * `#EXT-X-MEDIA`（音频/字幕组）那一行**有意忽略**：它的 URI 是音轨播放列表，
 * 我们要的是视频分片。
 */
internal fun parseHlsPlaylist(text: String, baseUrl: String): HlsPlaylist {
    val variants = ArrayList<HlsVariant>()
    val segments = ArrayList<HlsSegment>()
    var pendingDur = 0.0
    var start = 0.0
    var encrypted = false
    var init: String? = null
    // `#EXT-X-STREAM-INF` 与它的 URI 是成对的两行：记下码率，下一行非注释行就是变体 URI。
    // 不能只按"这行不是注释就算分片"——那样 master 里的变体 URI 会被收成分片（单测抓过）。
    var variantBw = -1L
    text.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith("#EXT-X-STREAM-INF") ->
                variantBw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            line.startsWith("#EXTINF:") -> pendingDur = line.removePrefix("#EXTINF:")
                .substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            line.startsWith("#EXT-X-KEY") -> if (!line.contains("METHOD=NONE")) encrypted = true
            line.startsWith("#EXT-X-MAP") -> init = Regex("URI=\"([^\"]+)\"").find(line)
                ?.groupValues?.get(1)?.let { absUrl(baseUrl, it) }
            line.isNotBlank() && !line.startsWith("#") -> {
                if (variantBw >= 0L) {
                    variants += HlsVariant(variantBw, absUrl(baseUrl, line))
                    variantBw = -1L
                } else {
                    segments += HlsSegment(start, pendingDur, absUrl(baseUrl, line))
                    start += pendingDur
                    pendingDur = 0.0
                }
            }
        }
    }
    return HlsPlaylist(variants, segments, encrypted, init)
}

/** 挑码率最低的变体（"直接抓最小的"） */
internal fun pickLowestVariant(variants: List<HlsVariant>): HlsVariant? = variants.minByOrNull { it.bandwidth }

/**
 * [fraction] 落在第几个分片上。
 *
 * 返回的是**包含该时刻的分片**，实际抓到的是这个分片的**首帧** —— 分片开头是关键帧，
 * 而目标时刻在分片内部要解到中间去，代价高、收益只是"差几秒"，不值得。
 *
 * 抽帧按**分片去重**也要靠它：短视频（90 秒、10 秒一片）取 10 帧会反复落在同几个分片上，
 * 同一个分片只该拉一次字节、只解一次码。
 */
internal fun segmentIndexOf(segments: List<HlsSegment>, fraction: Double): Int? {
    if (segments.isEmpty()) return null
    val total = segments.sumOf { it.durationSec }
    val target = total * fraction.coerceIn(0.0, 1.0)
    val idx = segments.indexOfFirst { it.startSec + it.durationSec >= target }
    return if (idx >= 0) idx else segments.lastIndex
}

/** 取样位置对应的分片；没有分片时返回 null */
internal fun pickSegment(segments: List<HlsSegment>, fraction: Double): HlsSegment? =
    segmentIndexOf(segments, fraction)?.let { segments[it] }

/** 可用档位里**最小**的那一档；100（原画）/999（原盘）不是转码档，排除 */
internal fun lowestTier(urls: List<VideoUrlEntry>): VideoUrlEntry? =
    urls.filter { it.definition != DEF_ORIGINAL && it.definition != 999 }.minByOrNull { it.definition }

/**
 * 抓帧用哪一档：优先最小转码档，**只有转码档一个都没有时才退到原画（def=100）**。
 *
 * 原画也是 HLS（见文件头注释），所以能原样走同一套分片抓帧；它只当兜底 ——
 * 有转码档时仍用转码档（分片小、解帧快）。
 * 返回 null = 这一档都没有（还在转码中，或 def=100 那条其实指向原始文件），调用方按"暂不可用"处理。
 */
internal fun previewTier(urls: List<VideoUrlEntry>): VideoUrlEntry? =
    lowestTier(urls) ?: urls.firstOrNull { it.definition == DEF_ORIGINAL }

/**
 * HLS 抽帧器。
 *
 * 线程：抓帧是阻塞的（HTTP + MediaMetadataRetriever），内部统一切到 IO。
 * 限速：`videoPlay` 走注入的 [api]（openApi → 带全局节拍器）；playlist 与分片是 CDN 字节，
 * 走不限速的 [http]（与图片字节同一条通道），批量侧另有自己的分档间隔。
 */
class VideoFrameGrabber(
    private val api: OpenApi,
    private val http: OkHttpClient,
    /** 分片要落成临时文件才能喂给 MMR；传应用私有缓存目录 */
    private val tempDir: File,
) {

    /**
     * 一次要抓的一帧：取样位置 + 输出尺寸/质量。
     *
     * 同一个分片上可能被要求输出两种尺寸（封面 640 + 预览条 320），
     * 所以尺寸跟着"这一帧"走，而不是跟着整次调用走。
     */
    data class FrameAsk(val fraction: Double, val maxWidth: Int, val quality: Int)

    /** 这个视频能不能生成预览图（有没有可抓帧的档位）；返回 null 表示"暂时拿不到"（未转码/转码中） */
    suspend fun tierOrNull(pickCode: String): VideoUrlEntry? = withContext(Dispatchers.IO) {
        runCatching { previewTier(parseVideoPlayResponse(api.videoPlay(pickCode)).videoUrls) }.getOrNull()
    }

    /**
     * 拉某个档位的 media playlist（返回带分片列表的那一份），失败返回 null。
     *
     * 预下载（PlayerPrefetcher）用它拿"后面还有哪些分片、各自什么时间"。走的是抽帧那条
     * 不限速的 CDN 通道，不占 115 接口的节拍额度。
     * ⚠️ master 里若有多个变体，这里只取码率最低的那份 —— 115 每个清晰度档一个 master，
     * 实测只有一个变体，所以就是播放器实际会用的那一份。
     */
    suspend fun playlistFor(tierUrl: String): HlsPlaylist? = withContext(Dispatchers.IO) {
        fetchPlaylist(tierUrl)
    }

    /** 抓一张封面图（JPEG 字节），失败/无转码档返回 null */
    suspend fun grab(pickCode: String, fraction: Double = PREVIEW_AT): ByteArray? =
        grabFrames(pickCode, listOf(FrameAsk(fraction, PREVIEW_MAX_WIDTH, PREVIEW_JPEG_QUALITY))).firstOrNull()

    /**
     * 抓多个取样点（「同片多版本」画面比对用）。
     * 取样点**必须**对所有版本用同一组 [COMPARE_ATS]；比对只算 9x8 的感知哈希，帧不用大。
     */
    suspend fun grabMany(pickCode: String, fractions: List<Double>): List<ByteArray?> =
        grabFrames(pickCode, fractions.map { FrameAsk(it, STRIP_MAX_WIDTH, STRIP_JPEG_QUALITY) })

    /**
     * 一次拿齐**封面 + 预览条**：返回表里第 0 个是封面，后面按 [stripFractions] 的顺序。
     *
     * 合成一次调用是为了省：playlist 只解析一次，而且封面（30%）常和预览条某帧落在同一个分片上，
     * 那样只拉一次字节、只解一次码。抓不到的位是 null（不抛异常）。
     *
     * [stripCount] ≤ 0 = **只要封面**（媒体库那条播放链路用：卡片自己就有画面，再抓一条预览是重复的）。
     * 注意不能靠 [stripFractions] 传 0 来省这一帧——它内部 `coerceIn(1, …)` 会把它当 1 张。
     */
    suspend fun grabPreviewSet(pickCode: String, stripCount: Int): List<ByteArray?> =
        grabFrames(
            pickCode,
            buildList {
                add(FrameAsk(PREVIEW_AT, PREVIEW_MAX_WIDTH, PREVIEW_JPEG_QUALITY))
                if (stripCount > 0) {
                    stripFractions(stripCount).forEach { add(FrameAsk(it, STRIP_MAX_WIDTH, STRIP_JPEG_QUALITY)) }
                }
            },
        )

    /**
     * 抓帧主流程：`videoPlay` 取最小档 → 解析 playlist → 按比例定位分片
     * → **按分片去重后逐片拉 376KB 头、解一次码、按各自尺寸编码**。
     *
     * 返回与 [asks] 等长的表（抓不到的位是 null）。
     * 线程：整段切 IO —— HTTP 与 MMR 都是阻塞调用（大文件一次好几秒，写临时程序时忘了这点把 App 搞 ANR 过）。
     */
    suspend fun grabFrames(pickCode: String, asks: List<FrameAsk>): List<ByteArray?> = withContext(Dispatchers.IO) {
        if (asks.isEmpty()) return@withContext emptyList()
        if (!tempDir.exists()) tempDir.mkdirs()
        val tier = runCatching { previewTier(parseVideoPlayResponse(api.videoPlay(pickCode)).videoUrls) }
            .getOrNull() ?: return@withContext List(asks.size) { null }
        if (tier.definition == DEF_ORIGINAL) {
            android.util.Log.i("VPreview", "没有转码档，改用原画（def=100）抓帧 pc=$pickCode url=${tier.url.take(140)}")
        }
        val playlist = fetchPlaylist(tier.url) ?: run {
            android.util.Log.i("VPreview", "playlist 拉不到/解析不出 pc=$pickCode 档=${tier.definition}")
            return@withContext List(asks.size) { null }
        }
        if (playlist.encrypted) {
            android.util.Log.i("VPreview", "playlist 带加密（EXT-X-KEY）抓不了 pc=$pickCode")
            return@withContext List(asks.size) { null }
        }
        android.util.Log.i(
            "VPreview",
            "playlist pc=$pickCode 档=${tier.definition} 分片=${playlist.segments.size} " +
                "首片头=${playlist.segments.firstOrNull()?.durationSec}s 总长=${playlist.totalSec.toInt()}s",
        )
        val init = playlist.initSegment?.let { fetchHead(it, INIT_SEGMENT_MAX_BYTES) } ?: ByteArray(0)

        val out = arrayOfNulls<ByteArray>(asks.size)
        // 分片下标 → 要它出哪些帧（LinkedHashMap 保证按首次出现的顺序拉，进度感更自然）
        val bySegment = LinkedHashMap<Int, MutableList<Int>>()
        asks.forEachIndexed { i, ask ->
            segmentIndexOf(playlist.segments, ask.fraction)?.let { seg ->
                bySegment.getOrPut(seg) { mutableListOf() } += i
            }
        }
        for ((segIndex, askIdx) in bySegment) {
            currentCoroutineContext().ensureActive()
            val seg = playlist.segments.getOrNull(segIndex) ?: continue
            val head = fetchHead(seg.url)
            if (head == null) {
                android.util.Log.i("VPreview", "分片头拉不到 seg=$segIndex url=${seg.url.take(120)}")
                continue
            }
            // 这一片要出的最大尺寸：只解一次码到这个尺寸，小的那些再从它缩
            val widest = askIdx.maxOf { asks[it].maxWidth }
            val frame = decodeFrame(seg.url, init + head, widest)
                ?: decodeFrameWider(seg.url, init, head, widest)
            if (frame == null) {
                android.util.Log.i("VPreview", "解帧失败 seg=$segIndex 头=${head.size}B")
                continue
            }
            try {
                for (i in askIdx) {
                    val ask = asks[i]
                    val scaled = scaleTo(frame, ask.maxWidth)
                    try {
                        out[i] = jpegOf(scaled, ask.quality)
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                    }
                }
            } finally {
                frame.recycle()
            }
        }
        out.toList()
    }

    /** 拉 master → 需要就再拉最低码率的 variant，得到带分片列表的那一份 */
    private fun fetchPlaylist(tierUrl: String): HlsPlaylist? {
        val first = httpGet(tierUrl) ?: return null
        val parsed = parseHlsPlaylist(first, tierUrl)
        if (!parsed.isMaster) return parsed
        val lowest = pickLowestVariant(parsed.variants) ?: return null
        val media = httpGet(lowest.url) ?: return null
        return parseHlsPlaylist(media, lowest.url)
    }

    /** 只拉分片开头 [SEGMENT_HEAD_BYTES] 字节；CDN 支持 Range（实测 206） */
    private fun fetchHead(url: String, limit: Long = SEGMENT_HEAD_BYTES): ByteArray? = runCatching {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", APP_USER_AGENT)
                .header("Range", "bytes=0-${limit - 1}")
                .build(),
        ).execute().use { r -> readAtMost(r.body ?: return null, limit) }
    }.getOrNull()

    /**
     * 拉 playlist 文本，**至多 [PLAYLIST_MAX_BYTES]**。
     *
     * 失败时打一行原因（原画档的 URL 形态比较多：可能 302 到别的 host、可能被 CDN 拒 Range），
     * 不然"拉不到"和"解析不出"在日志里长得一样，只能靠猜。
     */
    private fun httpGet(url: String, maxBytes: Long = PLAYLIST_MAX_BYTES, what: String = "playlist"): String? {
        return try {
            http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", APP_USER_AGENT)
                    .header("Range", "bytes=0-${maxBytes - 1}")
                    .build(),
            ).execute().use { r ->
                val body = r.body
                if (body == null) {
                    android.util.Log.i("VPreview", "$what 无响应体 code=${r.code} url=${url.take(110)}")
                    return null
                }
                val text = String(readAtMost(body, maxBytes), Charsets.UTF_8)
                if (!r.isSuccessful || text.isBlank()) {
                    android.util.Log.i(
                        "VPreview",
                        "$what code=${r.code} 长度=${text.length} " +
                            "ct=${r.header("Content-Type")} url=${url.take(110)}",
                    )
                }
                text
            }
        } catch (e: Exception) {
            android.util.Log.i("VPreview", "$what 异常 ${e.javaClass.simpleName}: ${e.message} url=${url.take(110)}")
            null
        }
    }

    /**
     * 读响应体**至多 [maxBytes]**。
     *
     * 为什么要自己封顶：原画档那条 URL 有可能是**原始文件字节**（不是 m3u8），
     * 而 `body.string()` / `body.bytes()` 会把整部片子（几十 GB）拉下来。Range 之外再兜一层：
     * 万一服务端忽略 Range 直接推全量，这里读够 [maxBytes] 就停手。
     *
     * ★**不能用 `readFully`**：115 的 CDN 响应常常没有 `Content-Length`（HTTP/2 / chunked），
     * 那时 `readFully(512KB)` 会一直等到够 512KB 才罢休，几 KB 的 playlist 直接被判成
     * `EOFException`（这个坑把转码档那条路也一起弄坏过，真机上两份日志对照才看出来）。
     */
    private fun readAtMost(body: ResponseBody, maxBytes: Long): ByteArray {
        val src = body.source()
        val buf = okio.Buffer()
        while (buf.size < maxBytes) {
            val n = src.read(buf, maxBytes - buf.size)
            if (n == -1L) break
        }
        return buf.readByteArray()
    }

    /**
     * 376KB 解不出来时，**只对这一片**再拉一次更大的额度（[SEGMENT_HEAD_BYTES_RETRY]）试一次。
     *
     * 高码率原画（4K）一个 I 帧就可能大于 376KB，那头里没有完整关键帧 —— 不是"这片坏了"，
     * 是"字节不够"。拉回来的还不如手上这份多就直接放弃（别做无谓的重复解码）。
     */
    private fun decodeFrameWider(url: String, init: ByteArray, head: ByteArray, maxWidth: Int): Bitmap? {
        if (head.size < SEGMENT_HEAD_BYTES) return null
        val wider = fetchHead(url, SEGMENT_HEAD_BYTES_RETRY) ?: return null
        if (wider.size <= head.size) return null
        android.util.Log.i("VPreview", "原画分片头不够，扩到 ${wider.size / 1024}KB 再试 url=${url.take(80)}")
        return decodeFrame(url, init + wider, maxWidth)
    }

    /**
     * 落临时文件 → MMR 解这一片的**首帧**（缩到 [maxWidth] 以内）。用完即删（TS 头 376KB，不留垃圾）。
     *
     * 首帧＝关键帧：分片开头就是 IDR，所以"这个取样点的画面"取时间 0 就行。
     */
    private fun decodeFrame(url: String, bytes: ByteArray, maxWidth: Int): Bitmap? {
        val tmp = File(tempDir, "vframe_" + url.hashCode() + ".ts")
        return try {
            tmp.writeBytes(bytes)
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(tmp.absolutePath)
                mmrFrame(mmr, maxWidth)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { mmr.release() }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun mmrFrame(mmr: MediaMetadataRetriever, maxWidth: Int): Bitmap? {
        // getScaledFrameAtTime 要 API 27；26 只能先解原尺寸再自己缩（缩在 scaleTo 里做）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            fun meta(key: Int) = runCatching { mmr.extractMetadata(key)?.toIntOrNull() }.getOrNull() ?: 0
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w > 0 && h > 0) {
                val scale = maxWidth.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    val sw = (w * scale).toInt().coerceAtLeast(1)
                    val sh = (h * scale).toInt().coerceAtLeast(1)
                    runCatching {
                        mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sw, sh)
                    }.getOrNull()?.let { return it }
                }
            }
        }
        return runCatching { mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
    }

    /** 缩到最长边 [maxWidth] 以内；本来就不大就原样返回（调用方据此判断要不要 recycle） */
    private fun scaleTo(bmp: Bitmap, maxWidth: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxWidth) return bmp
        val scale = maxWidth.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrDefault(bmp)
    }

    private fun jpegOf(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }.getOrNull()
}
