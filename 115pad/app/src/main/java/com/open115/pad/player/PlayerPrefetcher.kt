package com.open115.pad.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.open115.pad.data.HlsSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 两段之间歇一下：不让预下载把播放和 CDN 连接全占了，也避免撞 115 的量速 */
private const val SEGMENT_PAUSE_MS = 150L

/** 播放器在缓冲时先让路，隔这么久再看一眼 */
private const val YIELD_WHILE_BUFFERING_MS = 500L

/** 连续失败这么多次就收手（地址过期且刷新也救不回来 / 断网）；下次进播放器重新来过 */
private const val MAX_CONSECUTIVE_FAILURES = 3

/** 地址可能中途过期（签名有时效）→ 失败时重新取一次；最多这么多次 */
private const val MAX_REFRESHES = 8

/** 重扫缓存状态时往后多看几段（够覆盖"刚下完还没播到"的那一小截） */
private const val RESCAN_LOOKAHEAD = 24

/** 派下一块之前至少隔这么久：多连接错开启动，别在同一毫秒一起开 */
private const val STAGGER_MS = 80L

/** 并行槽满 / 触发速率上限时，调度器隔多久再看一眼 */
private const val SCHEDULE_POLL_MS = 200L

/** 单块失败后的重试间隔（只重试一次；不放大请求数） */
private const val CHUNK_RETRY_DELAY_MS = 700L

/** 连续失败这么多块才去刷新一次地址（并行下失败会成片出现，别一块一刷） */
private const val FAILURES_BEFORE_REFRESH = 3

/** 两轮地址刷新之间至少隔这么久（防失败风暴里狂刷 playlist） */
private const val REFRESH_COOLDOWN_MS = 20_000L

/**
 * 缓存"满"的水位线（占上限的比例）：到这就先**滚动腾地方**再灌（见 [KEEP_BACK_MS]），绝不直接往里写。
 *
 * 满了还硬灌，media3 的 LRU 会变成"**每写一块先淘汰一块**"，边写边删把吞吐拖到爬
 * （用户实测：清空缓存后同一部片 25MB/s，缓存满了就掉到 ~1MB/s）。留 5% 余量给播放器滚窗。
 */
private const val FULL_RATIO = 0.95

/**
 * 循环缓存的"回退保留"（毫秒）：缓存到水位后以**当前进度**为锚点滚动 ——
 * 锚点之前（保留这小段之外）的旧内容舍弃、腾出地方继续往前灌；这一小段留给小幅回退，
 * 退回来还能立即命中缓存。播放过的不全部舍弃，指的就是它。
 */
private const val KEEP_BACK_MS = 120_000L

/**
 * 多久没有任何一块下完就判"预下载这条连接拿不到带宽"、收手。
 * 实测：115 会给播放器那一路（以及它自己客户端的下载）正常速度，但**额外的连接只慢慢滴**
 * （50MB 一块跑了好几分钟只到 3KB）。这种时候继续占着那一小段区间锁没意义，还可能在播放器
 * 走到那里时挡它，所以直接停手 —— 缓存该灌多少由播放器自己那一路灌（它下的一切都进缓存）。
 */
private const val NO_PROGRESS_GIVE_UP_MS = 90_000L

/** 成批清旧片时清到剩多少（占上限的比例）：一次腾够，之后一路顺写 */
private const val CLEAN_TO_RATIO = 0.5

/** 预下载的速率软上限：一秒最多往缓存里写这么多，超了就等窗口翻页（只是"别失控"的兜底） */
private const val MAX_BYTES_PER_SEC = 16L * 1024 * 1024

/** 起播时等"时长"到位的最长时间（直链计划换算字节区间要用；超时就从第 0 段开始） */
private const val WAIT_DURATION_MAX_MS = 30_000L

/** 直链的默认块大小（拿不到 115 自己的分片参数时用）：25MB，和官方下载通道的切片口径一致 */
private const val FILE_CHUNK_DEFAULT = 25L * 1024 * 1024

/**
 * 从直链 URL 里取 115 自己的**分片大小**（`s=` 参数，实测 26214400 = 25MB）。
 *
 * 为什么要对齐它：115 的下载通道是按这个粒度切片授权的（同 URL 里还有 `us=`/`uc=` = 单元大小/并发数）。
 * 我们之前拿 4MB 不对齐的区间去请求，第二条连接被限到 10~128KB 就停；官方客户端是整片整片要的。
 * 拿不到参数就退回 [FILE_CHUNK_DEFAULT]。
 */
private fun fileChunkBytes(url: String): Long {
    val s = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return FILE_CHUNK_DEFAULT
    // 合理区间兜一下（太小=请求爆炸，太大=一块占锁太久）
    return s.coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024)
}

/**
 * 开始写一块之前，播放器缓冲里至少要留这么多余量。
 *
 * 低于它就先让路：带宽与缓存 key 锁都是共享的，播放优先 —— 它随时可能回来续缓冲，
 * 而写一块要几秒（8MB ≈ 原盘十几秒的内容），占着锁会让它干等。
 */
private const val MIN_BUFFER_AHEAD_MS = 30_000L

private const val TAG = "PlayerPreload"

/** 位置（毫秒）落在第几个分片上（分片表按时间升序） */
internal fun segmentIndexAt(segments: List<HlsSegment>, positionMs: Long): Int {
    if (segments.isEmpty()) return 0
    var idx = 0
    for (i in segments.indices) {
        if (segments[i].startSec * 1000.0 <= positionMs) idx = i else break
    }
    return idx.coerceIn(0, segments.lastIndex)
}

/** 哪些段已完整缓存 → 时间区间（相邻合并），进度条拿它画"已缓存"那层 */
internal fun mergeCachedRanges(startsMs: LongArray, endsMs: LongArray, cached: BooleanArray): List<LongRange> {
    val out = ArrayList<LongRange>()
    var i = 0
    while (i < cached.size) {
        if (!cached[i]) {
            i++
            continue
        }
        val start = startsMs[i]
        var end = endsMs[i]
        var j = i + 1
        while (j < cached.size && cached[j]) {
            end = endsMs[j]
            j++
        }
        if (end > start) out += start..end
        i = j
    }
    return out
}

/**
 * 回退保留的实际时长（毫秒）。
 *
 * 短片（时长不足 6 分钟，两分钟的 1/3 拿不满）压到**片长的 1/3** —— 否则播放永远追不出
 * "锚点之前的旧内容"，循环缓存会一直干等（缓存满、却没有可舍弃的旧部分）。下限 10 秒兜底。
 */
internal fun keepBackMsFor(durationMs: Long): Long =
    if (durationMs <= 0L) KEEP_BACK_MS
    else KEEP_BACK_MS.coerceAtMost(durationMs / 3).coerceAtLeast(10_000L)

/** 完全落在 [beforeMs] 之前的段数（分片表按时间升序，取前缀）：这些段可以整段舍弃 */
internal fun segmentsBefore(segments: List<HlsSegment>, beforeMs: Long): Int {
    var n = 0
    for (s in segments) {
        if ((s.startSec + s.durationSec) * 1000.0 <= beforeMs) n++ else break
    }
    return n
}

/** 直链的一个字节区间是不是**整段**都落在 [byteOffset] 之前（可以舍弃） */
internal fun spanBefore(position: Long, length: Long, byteOffset: Long): Boolean =
    position >= 0L && length > 0L && position + length <= byteOffset

/** 这个地址看起来是不是直链文件（原盘/原画的下载直链）：只看扩展名，不看 query */
internal fun isDirectFileUrl(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    return listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".m2ts", ".rmvb", ".webm", ".mpg", ".mpeg")
        .any { path.endsWith(it) }
}

/**
 * 预下载/缓存状态（UI 只读它）。
 *
 * [rangesMs] 是"已完整落在本机"的时间区间（含**播放器自己下下来的**那些，不只是预下载下的），
 * 进度条画淡蓝那层；[cachedBytes] 是同一口径的字节数，播放器状态栏显示"缓存 N MB"。
 */
internal data class PrefetchState(
    val rangesMs: List<LongRange> = emptyList(),
    val cachedBytes: Long = 0L,
)

/**
 * 一秒窗口内的写入字节数：预下载的速率软上限用。
 * 超了就等到窗口翻页再派新块（不打断正在下的那些块）。
 */
private class RateWindow(private val capBytes: Long) {
    private var windowStart = android.os.SystemClock.elapsedRealtime()
    private var bytes = 0L

    @Synchronized
    fun add(added: Long) {
        bytes += added
    }

    @Synchronized
    fun exceeded(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - windowStart >= 1000L) {
            windowStart = now
            bytes = 0L
            return false
        }
        return bytes >= capBytes
    }
}

/** 一段预下载任务：下 [dataSpec] 这段范围，它对应 [startMs]-[endMs] 这段时间（画进度条用） */
internal class PrefetchChunk(
    val key: String,
    val dataSpec: DataSpec,
    val startMs: Long,
    val endMs: Long,
)

/** 这一段是不是已经完整在缓存里 */
private fun isChunkCached(cache: SimpleCache, chunk: PrefetchChunk): Boolean {
    val len = chunk.dataSpec.length
    if (len != C.LENGTH_UNSET.toLong()) {
        // 直链的字节区间：长度是明确的，直接问缓存覆盖没覆盖这段
        return cache.isCached(chunk.key, chunk.dataSpec.position, len)
    }
    // HLS 分片：长度只能从缓存索引里的 content-length 拿（写完一片时记下的）
    val known = ContentMetadata.getContentLength(cache.getContentMetadata(chunk.key))
    return known != C.LENGTH_UNSET.toLong() && cache.isCached(chunk.key, 0, known)
}

/**
 * 预下载计划：告诉预下载器"下一步下哪一段、它对应哪一段时间"。
 * 两种来源：HLS 分片表（转码档）与直链文件的字节区间（原盘/原画）。
 */
internal interface PrefetchPlan {
    /** 总共多少段 */
    val count: Int

    /** 日志用的一句话 */
    val describe: String

    /** 第 i 段；null = 这段暂时算不出来（跳过它） */
    fun chunkAt(i: Int): PrefetchChunk?

    /** 位置 → 该从第几段开始（用户拖进度后重新对齐） */
    fun indexAt(positionMs: Long): Int

    /**
     * 起点要**越过**播放器缓冲前沿几段。
     *
     * 不越过的话，预下载就是在和播放器抢同一段数据：谁快谁写、另一方发现已缓存就跳过 ——
     * 慢链路上永远抢不过它，于是"已缓存"永远超不过白条（用户看到的就是"只有亮蓝和白"）。
     * 越过去之后，预下载写的是**播放器本来不会马上要**的那部分，进度条上的淡蓝才会长出来。
     */
    val gap: Int

    /** 地址过期时重建（HLS 重拉 playlist / 直链重新取地址）；重建后段数必须不变 */
    suspend fun refresh(): Boolean

    /** 当前这部片的缓存 key 前缀（成批清旧片时不能碰它；null = 判断不出就一个都不删） */
    val keepKeyPrefix: String?

    /**
     * 舍弃这部片里**早于媒体时间 [beforeMs]** 的已缓存内容，返回释放的字节数。
     *
     * 循环缓存的滚动动作：缓存到水位后以当前进度为锚点，把锚点之前的旧部分扔掉、腾地方继续
     * 往前灌（而不是到上限就停手）。只删**完整**落在锚点之前的区间 —— 正在播的那一段绝不碰。
     */
    fun releaseBefore(cache: SimpleCache, beforeMs: Long): Long

    /** 每轮取样时告知（直链计划靠它把字节换算成时间，HLS 用不到） */
    fun onDuration(durationMs: Long) {}
}

/** HLS 分片计划（转码档） */
internal class HlsPrefetchPlan(
    private val segments: List<HlsSegment>,
    /** 重新拉一份 playlist（地址带签名，有时效） */
    private val reload: suspend () -> List<HlsSegment>?,
) : PrefetchPlan {

    private var urls: List<String> = segments.map { it.url }

    override val count: Int get() = segments.size
    override val describe: String get() = "HLS 分片 ${segments.size} 片"

    /** `115hls-v1:<sha1>_<档位>`：同一部片的全部分片 key 都以它开头 */
    override val keepKeyPrefix: String?
        get() = PlayerCache.contentKey(segments.firstOrNull()?.url ?: "")?.substringBeforeLast('-')
    /** 越过 3 片（约 30 秒）*/
    override val gap: Int = 3

    override fun indexAt(positionMs: Long): Int = segmentIndexAt(segments, positionMs)

    override fun chunkAt(i: Int): PrefetchChunk? {
        val url = urls.getOrNull(i) ?: return null
        val key = PlayerCache.contentKey(url) ?: return null
        return PrefetchChunk(
            key = key,
            dataSpec = DataSpec.Builder().setUri(Uri.parse(url)).build(),
            startMs = (segments[i].startSec * 1000).toLong(),
            endMs = ((segments[i].startSec + segments[i].durationSec) * 1000).toLong(),
        )
    }

    override suspend fun refresh(): Boolean {
        val fresh = reload() ?: return false
        if (fresh.size != segments.size) return false
        urls = fresh.map { it.url }
        return true
    }

    /** 整段舍弃：一片一个 key，播完的那些片直接 removeResource */
    override fun releaseBefore(cache: SimpleCache, beforeMs: Long): Long {
        var freed = 0L
        for (i in 0 until segmentsBefore(segments, beforeMs)) {
            val key = PlayerCache.contentKey(urls.getOrNull(i) ?: continue) ?: continue
            val spans = cache.getCachedSpans(key)
            if (spans.isEmpty()) continue
            freed += spans.sumOf { it.length }
            cache.removeResource(key)
        }
        return freed
    }
}

/**
 * 直链文件计划（原盘 / 原画的原始文件 URL）：按字节区间顺序往缓存里灌。
 *
 * 内容标识用 **pick_code**（[PlayerCache.fileKey]）—— 直链本身每次进片都换签名，
 * 按 URL 存下来的分片谁都再也认不出来，只会白占空间。
 */
internal class FilePrefetchPlan(
    private val pickCode: String,
    private val fileSize: Long,
    /** 取一份新的直链（downUrl）；每份地址各自是一条独立流 */
    private val urlProvider: suspend () -> String?,
    private var initialUrl: String,
) : PrefetchPlan {

    private var url: String? = initialUrl
    private var durationMs = 0L

    /**
     * 一块多少字节：**按 115 自己的分片口径**（直链 URL 的 `s=`，实测 25MB）对齐。
     * 不按它对齐时，第二条连接会被限到几十~一百多 KB 就停（实测两次）。
     */
    private val sliceBytes: Long = fileChunkBytes(initialUrl)


    override val count: Int
        get() = if (fileSize <= 0L) 0 else ((fileSize + sliceBytes - 1) / sliceBytes).toInt()

    override val describe: String get() = "直链文件 ${fileSize / 1024 / 1024}MB / $count 段"
    override val keepKeyPrefix: String get() = PlayerCache.fileKey(pickCode)
    /** 越过 2 块；按 115 的 25MB 切片口径 ≈ 50MB ≈ 70 秒内容，刚好超过播放器自己的缓冲窗口 */
    override val gap: Int = 2

    override fun onDuration(durationMs: Long) {
        this.durationMs = durationMs
    }

    override fun indexAt(positionMs: Long): Int {
        if (count <= 0) return 0
        val dur = durationMs
        if (dur <= 0L) return 0
        val bytes = fileSize * positionMs / dur
        return (bytes / sliceBytes).toInt().coerceIn(0, count - 1)
    }

    override fun chunkAt(i: Int): PrefetchChunk? {
        val u = url ?: return null
        val from = i.toLong() * sliceBytes
        val len = minOf(sliceBytes, fileSize - from)
        if (len <= 0L) return null
        val dur = durationMs
        // 时间只有画进度条用：按字节比线性折算（VBR 下略有偏差，能看出"灌到哪了"就够）
        val startMs = if (dur > 0L) dur * from / fileSize else 0L
        val endMs = if (dur > 0L) dur * (from + len) / fileSize else 0L
        val key = PlayerCache.fileKey(pickCode)
        return PrefetchChunk(
            key = key,
            // key 显式挂上：播放器那边是 MediaItem.customCacheKey 挂同一个 key，
            // 这里不挂的话 key 工厂会退回整条签名 URL，两边对不上（下载白下、播放器也读不到）
            dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(u))
                .setKey(key)
                .setPosition(from)
                .setLength(len)
                .build(),
            startMs = startMs,
            endMs = endMs,
        )
    }

    override suspend fun refresh(): Boolean {
        url = urlProvider() ?: return false
        return true
    }

    /**
     * 直链只有一个 key、区间是字节偏移：把时间换算成字节后，删掉**整段都在它之前**的 span。
     * 换算与画进度条同一条线性公式，保证"舍弃的区间"和"进度条上收回的区间"对得上。
     */
    override fun releaseBefore(cache: SimpleCache, beforeMs: Long): Long {
        val dur = durationMs
        if (dur <= 0L || fileSize <= 0L || beforeMs <= 0L) return 0L
        val byte = fileSize * beforeMs / dur
        val key = PlayerCache.fileKey(pickCode)
        var freed = 0L
        for (s in cache.getCachedSpans(key)) {
            if (!spanBefore(s.position, s.length, byte)) continue
            cache.removeSpan(s)
            freed += s.length
        }
        return freed
    }
}

/**
 * 前向预下载：把**后面的内容按顺序灌进播放缓存**，直到片尾；缓存到「缓存上限」后
 * **以当前进度为锚点滚动** —— 舍弃锚点之前的旧内容（保留 [KEEP_BACK_MS] 给小幅回退）、
 * 继续往前灌，而不是到上限就停手。这样一部比缓存还大的片也能一路"边看边往前缓存"。
 *
 * 为什么要单独做：ExoPlayer 的缓冲窗口只按内存算（PlayerActivity 里 BUFFER_MAX_MS = 90 秒 /
 * 192MB），所以"进度条预下载"永远只走一点点就停；而用户设的「缓存上限」说的是**本机磁盘**
 * 能留多少。要真按上限预下载，只能绕开播放器的内存窗口自己往 SimpleCache 里写 —— 写入用的
 * 是与播放器同一套 key（HLS 走内容标识 [PlayerCache.contentKey]，直链走 pick_code），
 * 所以播放器随后读到的是同一份数据，不会重复下载。
 *
 * 只跟播放会话同生命周期：退出播放器 / 切档 / 切集 / 切软硬解都会随协程取消；
 * 地址过期由计划自己 [PrefetchPlan.refresh] 续上，已下好的部分不白费。
 */
internal class PlayerPrefetcher(
    private val cache: SimpleCache,
    /** 与播放器同一套上游 + key 工厂（自己 createDataSource，不共用实例） */
    private val dataSourceFactory: CacheDataSource.Factory,
    /** 磁盘缓存上限（字节） */
    private val maxCacheBytes: Long,
    private val plan: PrefetchPlan,
    /** 「动态」挡位下这部片只留这么多字节（0 = 静态挡位，按整库上限滚动）；见 PlayerCache.dynamicLimitBytes */
    private val dynamicKeepBytes: Long = 0L,
) {

    private val _state = MutableStateFlow(PrefetchState())

    /** 已缓存状态（区间 + 字节数），进度条与状态栏读它 */
    val state: StateFlow<PrefetchState> = _state

    /**
     * 预下载主循环；挂在本播放的 LaunchedEffect 上，随协程取消而结束。
     *
     * ⚠️ 整段必须跑在 IO 上：CacheWriter.cache() 是阻塞网络调用，而且 SimpleCache 的写锁
     * 会让它 `Object.wait()` —— 跑在主线程会直接 NetworkOnMainThreadException，并且把
     * 播放器一起拖死（实测：主线程卡在 startReadWrite，画面停在「缓冲中」不动）。
     */
    suspend fun run(player: Player) = withContext(Dispatchers.IO) {
        val n = plan.count
        if (dynamicKeepBytes > 0L) {
            android.util.Log.i(
                TAG,
                "动态挡位：这部片的目标缓存 ${dynamicKeepBytes / 1024 / 1024}MB" +
                    "（整库上限 ${maxCacheBytes / 1024 / 1024}MB）",
            )
        }
        if (n <= 0) {
            android.util.Log.i(TAG, "没有可预下载的内容（${plan.describe}），跳过")
            return@withContext
        }
        // 起点：位置 → 第几段。直链计划要靠"时长"把位置换算成字节，而起播那一刻时长可能还没到
        // （时长=0 时 indexAt 会退回第 0 段 = 从片头灌，白白下用不到的部分）→ 先等时长到手
        var first = sample(player)
        var waitedMs = 0L
        while (first.durationMs <= 0L && waitedMs < WAIT_DURATION_MAX_MS) {
            delay(500)
            waitedMs += 500
            first = sample(player)
        }
        plan.onDuration(first.durationMs)

        // ⚠️ 初扫必须在"拿到时长"之后：直链计划要靠时长把字节位置换算成时间区间，
        //    时长还是 0 时算出来的区间起止都是 0，会被当成空区间丢掉 —— 进度条上那层淡蓝
        //    就永远画不出来（HLS 分片自带时间，所以这个坑只在直链档位上暴露）。
        val starts = LongArray(n)
        val ends = LongArray(n)
        val cached = BooleanArray(n)
        val sizes = LongArray(n)
        // 本轮"已在本机"的字节数：初扫把已经在的算上，之后每下完/发现一块累加
        var knownBytes = 0L
        for (i in 0 until n) {
            val c = plan.chunkAt(i) ?: continue
            starts[i] = c.startMs
            ends[i] = c.endMs
            if (isChunkCached(cache, c)) {
                cached[i] = true
                sizes[i] = chunkBytes(c)
                knownBytes += sizes[i]
            }
        }
        _state.value = PrefetchState(mergeCachedRanges(starts, ends, cached), knownBytes)
        android.util.Log.i(
            TAG,
            "开始：${plan.describe} 已缓存=${cached.count { it }} 段 上限=${maxCacheBytes / 1024 / 1024}MB",
        )
        val fullAt = (maxCacheBytes * FULL_RATIO).toLong()

        // 缓存到水位时**先成批清掉最久没用到的旧片**，别等写的时候一块一块淘汰（那样吞吐会塌）。
        // 例外：**重进同一部片不清** —— 判据是"这部片自己占了缓存里的一半以上"（初扫的 knownBytes
        // vs 当前占用）：那说明用户是在接着看/重看，一个都不动，直接把之前的缓存接着用。
        // ⚠️ 必须在初扫**之后**判：播放器进片时已经在写它的分片了，拿"最近访问时间"判会被这几个
        //    新分片带偏（实测把"换片"误判成"重进同一部片"）。
        if (cache.cacheSpace >= fullAt) {
            val used = cache.cacheSpace
            if (knownBytes * 2 >= used) {
                android.util.Log.i(
                    TAG,
                    "缓存到水位：这部片自己占了 ${knownBytes / 1024 / 1024}MB（共 ${used / 1024 / 1024}MB），" +
                        "按重进同一部片处理：沿用现有缓存、不清理旧片",
                )
            } else {
                val (n, freed) = PlayerCache.evictOldestExcept(
                    plan.keepKeyPrefix,
                    (maxCacheBytes * CLEAN_TO_RATIO).toLong(),
                )
                android.util.Log.i(
                    TAG,
                    "缓存到水位：清掉 $n 份旧内容、腾出 ${freed / 1024 / 1024}MB" +
                        "（现 ${cache.cacheSpace / 1024 / 1024}MB / 上限 ${maxCacheBytes / 1024 / 1024}MB）",
                )
            }
        }
        var cursor = firstCursor(first)
        var refreshes = 0
        var lastRefreshAt = 0L
        val failures = AtomicInteger(0)
        val addedBytes = AtomicLong(0L)
        val cachedBytes = AtomicLong(knownBytes)
        val rate = RateWindow(MAX_BYTES_PER_SEC)
        val stateLock = Mutex()
        // 固定 1 条：实测 115 的额外连接拿不到带宽（几十 KB 就停），并发在这里没意义；
        // 结构保留（信号量 + worker），万一以后规则变了只要把许可数调大
        val slices = Semaphore(1)
        val doneChunks = AtomicInteger(0)
        // 最后一次"有块下完"的时刻：长时间没进展说明这条连接被饿着（见 NO_PROGRESS_GIVE_UP_MS）
        val lastDoneAt = AtomicLong(android.os.SystemClock.elapsedRealtime())
        /** 让路计数：每让 60 次（约 30 秒）打一行日志 */
        var yieldTicks = 0
        /** 水位等待计数：每 120 次（约 60 秒）打一行日志 */
        var waitTicks = 0

        suspend fun pushState() = stateLock.withLock {
            val next = PrefetchState(mergeCachedRanges(starts, ends, cached), cachedBytes.get())
            if (next != _state.value) _state.value = next
        }

        /** 下完一块（每块最多重试 1 次）；返回是否成功 */
        suspend fun download(key: String, spec: DataSpec): Boolean {
            repeat(2) { attempt ->
                val err = runCatching {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    // 进度回调（每 2MB 记一行，同一块最多记几行）：排"单连接被限速/半死连接"这类问题
                    // 全靠它 —— 能看到每条连接到底在不在走、走多快
                    var lastLogAt = 0L
                    val progress = CacheWriter.ProgressListener { reqLen, cached, newBytes ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLogAt > 8_000L && newBytes > 0L) {
                            lastLogAt = now
                            val rate = if (now > t0) newBytes * 1000 / (now - t0) / 1024 else 0
                            android.util.Log.i(
                                TAG,
                                "块进度：${cached / 1024}KB/${reqLen / 1024}KB 平均 ${rate}KB/s（key=${key.take(32)}）",
                            )
                        }
                    }
                    CacheWriter(dataSourceFactory.createDataSource(), spec, null, progress).cache()
                    android.os.SystemClock.elapsedRealtime() - t0
                }.getOrElse { e ->
                    if (attempt == 0) {
                        delay(CHUNK_RETRY_DELAY_MS)
                        return@repeat
                    }
                    android.util.Log.i(TAG, "下载失败：${e.javaClass.simpleName}: ${e.message}")
                    return false
                }
                if (err > 10_000L) android.util.Log.i(TAG, "一块写了 ${err / 1000}s（key=${key.take(40)}）")
                return true
            }
            return false
        }

        coroutineScope {
            val jobs = ArrayList<Job>()
            while (currentCoroutineContext().isActive) {
                val s = sample(player)
                plan.onDuration(s.durationMs)
                // 每轮顺手把"当前位置往后这一窗"的缓存状态重扫一遍（播放器自己下的也算进去）
                rescan(starts, ends, cached, sizes, s) { cachedBytes.addAndGet(it) }
                // 对齐：只在"播放器自己的缓冲前沿追上来"时把游标往前推。
                // ⚠️ 绝不能反过来在我们走远时把游标拽回来 —— 那等于每轮重置一次，
                //    预下载永远超不出"前沿 + gap"（实测：前面有几百 MB 已缓存时循环空转、一块不下）。
                //    用户往回拖时也不用回填：播放器自己会把它窗口里那段下下来。
                val at = firstCursor(s)
                if (at > cursor) cursor = at
                if (cursor > plan.count - 1) {
                    android.util.Log.i(TAG, "停手：已到片尾")
                    break
                }
                // 循环缓存：到水位 / 超出这部片的预算时，以**当前进度**为锚点滚动 ——
                // 舍弃锚点之前（保留 KEEP_BACK_MS 之外）的旧内容、腾出地方继续往前灌，而不是到上限就停手。
                // 之前播放的不全部舍弃：保留的那一小段给小幅回退，退回来还能立即命中缓存。
                // 裁剪发生在写路径之外 ⇒ 不会触发"边写边淘汰"那种吞吐塌陷。
                if (cache.cacheSpace >= fullAt ||
                    (dynamicKeepBytes > 0L && cachedBytes.get() > dynamicKeepBytes)
                ) {
                    val keepBack = keepBackMsFor(s.durationMs)
                    val before = s.posMs - keepBack
                    val freed = if (before > 0L) plan.releaseBefore(cache, before) else 0L
                    if (freed > 0L) {
                        cachedBytes.addAndGet(-freed)
                        stateLock.withLock {
                            for (i in 0 until n) {
                                if (cached[i] && ends[i] <= before) cached[i] = false
                            }
                        }
                        pushState()
                        android.util.Log.i(
                            TAG,
                            "循环缓存：舍弃进度之前的 ${freed / 1024 / 1024}MB（保留回退 ${keepBack / 1000}s）" +
                                "（现 ${cache.cacheSpace / 1024 / 1024}MB / 上限 ${maxCacheBytes / 1024 / 1024}MB）",
                        )
                    } else {
                        // 锚点之前还没有可舍弃的（刚起播 / 还没播出回退余量之外）：等一会儿再看，别停 ——
                        // 播放器往前走，很快就有旧内容可以滚出去。这期间不是连接被饿，别触发无进展收手
                        lastDoneAt.set(android.os.SystemClock.elapsedRealtime())
                        waitTicks++
                        if (waitTicks % 120 == 1) {
                            android.util.Log.i(
                                TAG,
                                "循环缓存：已到水位、进度之前暂无可舍弃的旧内容，等播放继续推进" +
                                    "（已灌 ${addedBytes.get() / 1024 / 1024}MB / 进度 ${s.posMs / 1000}s）",
                            )
                        }
                        delay(YIELD_WHILE_BUFFERING_MS)
                        continue
                    }
                }

                // 连续一段时间一块都下不完 → 预下载这条连接被饿着，收手（不加锁、不占位）
                if (android.os.SystemClock.elapsedRealtime() - lastDoneAt.get() > NO_PROGRESS_GIVE_UP_MS) {
                    android.util.Log.i(
                        TAG,
                        "停手：预下载连接长时间没有进展（已灌 ${addedBytes.get() / 1024 / 1024}MB），" +
                            "缓存交给播放器自己那一路灌",
                    )
                    break
                }
                // 让路两条（播放优先）：正在缓冲，或缓冲余量不够
                if (s.buffering || s.bufferedEndMs - s.posMs < MIN_BUFFER_AHEAD_MS) {
                    yieldTicks++
                    if (yieldTicks % 60 == 1) {
                        android.util.Log.i(
                            TAG,
                            "让路中（第 $yieldTicks 次）：缓冲余量=${(s.bufferedEndMs - s.posMs) / 1000}s " +
                                "buffering=${s.buffering}",
                        )
                    }
                    delay(YIELD_WHILE_BUFFERING_MS)
                    continue
                }
                yieldTicks = 0
                // 失败成片出现时刷新一次地址（带冷却，不一块一刷）
                if (failures.get() >= FAILURES_BEFORE_REFRESH) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (refreshes < MAX_REFRESHES && now - lastRefreshAt > REFRESH_COOLDOWN_MS) {
                        refreshes++
                        lastRefreshAt = now
                        if (plan.refresh()) {
                            failures.set(0)
                            android.util.Log.i(TAG, "地址已刷新（第 $refreshes 次）")
                        } else {
                            android.util.Log.i(TAG, "刷新地址失败，停手")
                            break
                        }
                    } else {
                        android.util.Log.i(TAG, "停手：连续失败且刷不动了")
                        break
                    }
                }
                // 速率软上限 / 并行槽
                if (rate.exceeded()) {
                    delay(SCHEDULE_POLL_MS)
                    continue
                }
                if (!slices.tryAcquire()) {
                    delay(SCHEDULE_POLL_MS)
                    continue
                }
                val idx = cursor
                val chunk = plan.chunkAt(idx)
                if (chunk == null || cached[idx]) {
                    slices.release()
                    cursor++
                    continue
                }
                cursor++
                jobs += launch {
                    try {
                        val ok = download(chunk.key, chunk.dataSpec)
                        if (ok) {
                            val written = chunkBytes(chunk)
                            failures.set(0)
                            addedBytes.addAndGet(written)
                            rate.add(written)
                            cachedBytes.addAndGet(written)
                            stateLock.withLock {
                                cached[idx] = true
                                sizes[idx] = written
                            }
                            lastDoneAt.set(android.os.SystemClock.elapsedRealtime())
                            val done = doneChunks.incrementAndGet()
                            if (done % 20 == 0) {
                                android.util.Log.i(
                                    TAG,
                                    "预下载：已灌 ${addedBytes.get() / 1024 / 1024}MB（第 $idx 段）",
                                )
                            }
                            pushState()
                        } else {
                            failures.incrementAndGet()
                        }
                    } finally {
                        slices.release()
                    }
                }
                // 错开启动：别在同一毫秒把几条连接一起开出去
                delay(STAGGER_MS)
            }
            jobs.joinAll()
        }
    }

    /**
     * 重扫一窗（当前位置 → 缓冲前沿 + gap 再往后一点），把**播放器自己下的**也算进来，
     * 并把新变"已缓存"的那些字节数累进 [cachedBytes]（进度条的淡蓝与状态栏的读数靠它）。
     */
    private fun rescan(
        starts: LongArray,
        ends: LongArray,
        cached: BooleanArray,
        sizes: LongArray,
        s: Sample,
        addBytes: (Long) -> Unit,
    ) {
        val from = plan.indexAt(s.posMs).coerceAtLeast(0)
        val to = (firstCursor(s) + RESCAN_LOOKAHEAD).coerceAtMost(cached.lastIndex)
        var changed = false
        for (i in from..to) {
            if (cached[i]) continue
            val c = plan.chunkAt(i) ?: continue
            if (isChunkCached(cache, c)) {
                cached[i] = true
                if (sizes[i] == 0L) sizes[i] = chunkBytes(c)
                addBytes(sizes[i])
                changed = true
            }
        }
        if (changed) mergeAndPush(starts, ends, cached)
    }

    /** 状态推送：只有真的变了才写（免得每 0.5 秒重组一次 UI）*/
    private fun refreshState(starts: LongArray, ends: LongArray, cached: BooleanArray, bytes: Long) {
        val next = PrefetchState(mergeCachedRanges(starts, ends, cached), bytes)
        if (next != _state.value) _state.value = next
    }

    private fun mergeAndPush(starts: LongArray, ends: LongArray, cached: BooleanArray) {
        val next = _state.value.copy(rangesMs = mergeCachedRanges(starts, ends, cached))
        if (next != _state.value) _state.value = next
    }

    /**
     * 预下载该从哪一段开始：取"当前播放位置"与"播放器已缓冲到的位置"里更靠后的那个。
     *
     * 后者通常更靠前（前向缓冲 90 秒 / 192MB），用它当起点就**天然避开**播放器正在写的区间 ——
     * 两边同时写同一 key 的相邻/重叠范围会互相等锁（实测卡死，画面停住、零流量）。
     * 播放器自己会把它缓冲窗口里的部分下完，预下载负责的是窗口之外。
     */
    private fun firstCursor(s: Sample): Int =
        maxOf(plan.indexAt(s.posMs), plan.indexAt(s.bufferedEndMs) + plan.gap)

    /**
     * 这一段有多大（累计"本轮灌了多少"用）。
     * 直链的字节区间长度是我们自己给的；HLS 分片没有固定长度，写完从缓存索引的
     * content-length 读它（写完一片时 media3 会把总长记下来）。
     */
    private fun chunkBytes(chunk: PrefetchChunk): Long {
        val len = chunk.dataSpec.length
        if (len != C.LENGTH_UNSET.toLong()) return len
        val known = ContentMetadata.getContentLength(cache.getContentMetadata(chunk.key))
        return if (known != C.LENGTH_UNSET.toLong()) known else 0L
    }

    /** 一次主线程取样：位置 / 是否在缓冲 / 时长 / 播放器自己缓冲到的位置 */
    private data class Sample(
        val posMs: Long,
        val buffering: Boolean,
        val durationMs: Long,
        val bufferedEndMs: Long,
    )

    /**
     * 取样。必须回主线程读（ExoPlayer 有线程约束）。
     *
     * [Sample.bufferedEndMs] 是**播放器自己**已经下到哪儿了 —— 预下载必须从它之后开始：
     * 两边同时写同一个 key 的同一段区间会互相等锁，实测直接卡死（画面停住、网络零流量）。
     */
    private suspend fun sample(player: Player): Sample = try {
        withContext(Dispatchers.Main) {
            Sample(
                posMs = player.currentPosition,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration,
                bufferedEndMs = player.bufferedPosition,
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 取消要照常向上传，否则预下载循环会在协程已取消后多跑一轮（见 MediaScanner 的同款纪律）
    } catch (e: Exception) {
        Sample(0L, false, 0L, 0L)
    }
}
