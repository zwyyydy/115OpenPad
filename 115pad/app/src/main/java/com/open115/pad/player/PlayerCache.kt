package com.open115.pad.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlin.concurrent.thread

/**
 * 播放缓存：SimpleCache 必须对同一目录保持单例。
 * 上限用 LRU 淘汰：达到 maxBytes 后自动清理最旧的分片，等效于"超过限制不再增长"。
 *
 * 缓存 key 走 [contentKey]：115 的 HLS 分片地址里只有结尾的
 * `<文件sha1>_<档位>-<分片号>.ts` 是内容标识 —— 签名、会话号、query 每次请求都会换。
 * 早先按整条 URL 当 key，于是重新进片（甚至同一次播放里换一份地址）整段缓存就作废，
 * 播放缓存只剩"单次播放内回退"这点价值，上限设多大都没意义。
 * 实测：同一个文件、同一档、同一分片号，跨会话两次请求**字节完全一致**，而 URL 不同 ——
 * 所以按内容标识存是安全的，缓存也能跨会话复用。
 *
 * 因此退出播放器**不再清空**：留下来给"重进同一部片、往回拖进度"用，占用由上限兜底。
 * [clear] 留给登出与设置页的「清空播放缓存」。
 */
object PlayerCache {

    /**
     * key 前缀带版本号：万一 115 换了转码规则（同一分片号的内容语义变了），
     * 版本号 +1 就能让旧缓存整体失效，不用逐条判断，也没有迁移成本。
     */
    private const val KEY_PREFIX = "115hls-v1:"

    /** 分片文件名 = `<40位sha1>_<档位>-<分片号>.ts`（115 转码分片实测就是这个形状） */
    private val SEGMENT_NAME = Regex("^[0-9A-Fa-f]{40}_\\d+-\\d+\\.ts$")

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var configuredBytes = -1L

    /**
     * 缓存目录挂在应用数据目录（files）下，而不是缓存目录（cache）下。
     *
     * 缓存目录是"随时可以被系统或清理工具删掉"的目录：用户那边就是因为它被清掉，
     * 才出现"退出去再进来还是从云端下载、缓存好像没用"（MIUI 一类系统会定期清应用缓存）。
     * 放在 files/ 里只有"清除应用数据"或 App 自己清才会没，代价是它计入应用数据占用 ——
     * 大小由上限兜着，设置页也能手动清。
     */
    fun dir(context: Context): File = File(context.filesDir, "video_cache")

    /**
     * 旧位置（cache/video_cache）里还留着上一版存的分片，第一次取缓存时顺手清掉，别白占空间。
     * 放后台线程：可能是 GB 级目录，主线程删会卡（而且这里就在主线程上调用的）。
     */
    private fun dropLegacyCache(context: Context) {
        val legacy = File(context.cacheDir, "video_cache")
        if (!legacy.exists()) return
        val app = context.applicationContext
        thread(name = "video-cache-legacy-clean", isDaemon = true) {
            runCatching { SimpleCache.delete(legacy, StandaloneDatabaseProvider(app)) }
        }
    }

    fun get(context: Context, maxBytes: Long): SimpleCache = synchronized(this) {
        val existing = instance
        if (existing != null && configuredBytes == maxBytes) return existing
        dropLegacyCache(context)
        existing?.release()
        instance = SimpleCache(
            dir(context),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            StandaloneDatabaseProvider(context),
        )
        configuredBytes = maxBytes
        instance!!
    }

    /**
     * 内容标识（跨会话稳定）。认不出来返回 null —— 调用方退回整条 URL。
     *
     * 只认 115 域名下、形如 `<sha1>/<sha1>_<档位>-<分片号>.ts` 的路径，且**目录名必须
     * 就是文件名里那个 sha1**：对不上就不认，免得误伤别的服务上恰好同形的资源。
     * 播放列表、字幕、原盘直链这些地址本来就没有内容寻址的部分，一律不猜、不改写
     * （改写播放列表的 key 有过教训：旧列表被命中 → 吐出的分片地址签名过期 → 403）。
     */
    internal fun contentKey(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val rest = url.substring(schemeEnd + 3)
        val hostEnd = rest.indexOf('/')
        if (hostEnd <= 0) return null
        val host = rest.substring(0, hostEnd).substringBefore(':').lowercase()
        if (host != "115.com" && !host.endsWith(".115.com")) return null
        val path = rest.substring(hostEnd).substringBefore('?').substringBefore('#')
        val parts = path.trim('/').split('/')
        if (parts.size < 2) return null
        val name = parts[parts.size - 1]
        if (!SEGMENT_NAME.matches(name)) return null
        if (!parts[parts.size - 2].equals(name.substring(0, 40), ignoreCase = true)) return null
        // sha1 大小写在两处可能不一致：统一小写，免得同一分片算出两个 key
        return KEY_PREFIX + name.lowercase()
    }

    /**
     * 两个播放器（主播放器与切档预载器）共用的缓存 key 工厂。
     *
     * 顺带打一行 debug 日志（tag=PlayerCacheIO）："seek / 重进片之后到底命中没命中"就看它 ——
     * 命中时 CacheDataSource 会再补一行「命中缓存 N B」，请求行里带的仍是**签名 URL**，
     * 所以"同一分片两次请求 URL 不同"这种坑也能一眼看出来。
     */
    fun cacheKeyFactory(): CacheKeyFactory = CacheKeyFactory { dataSpec: DataSpec ->
        val url = dataSpec.uri.toString()
        val key = contentKey(url) ?: dataSpec.key ?: url
        // 诊断：把**实际用的 key** 也打出来（"缓存为什么不命中"这类问题全靠它一眼看出）
        android.util.Log.d("PlayerCacheIO", "请求 $url （key=$key）")
        key
    }

    /**
     * 直链文件（原盘 / 原画的原始文件）的内容标识。
     *
     * 直链本身每次进片都换签名（`t=`、`k=` 全变），按 URL 存下来的分片谁都再也认不出来；
     * pick_code 在 115 里唯一标识一份文件，拿它当 key 才谈得上跨会话复用。
     * 播放器侧靠 `MediaItem.setCustomCacheKey` 把同一个 key 挂到请求上（见 buildMediaItem）。
     */
    internal fun fileKey(pickCode: String): String = "115file:$pickCode"

    /**
     * 滑块挡位哨兵：「动态」= 上限按当前这部片自己的比例算（见 [dynamicLimitBytes]）。
     *
     * 取值就是滑块**最右那一格**（4096 + 一格 128）：既不在 128..4096 的正常取值里，
     * 又是个正数、存进 DataStore 一定没问题（试过 0 —— DataStore 的零值编码会让 preferences 解析失败 ✗）。
     */
    const val CACHE_DYNAMIC = 4224

    /** 动态挡位的下限/上限（MB）与"短片"阈值：≤5 分钟不套比例、按整片缓存处理 */
    const val DYNAMIC_MIN_MB = 128L
    const val DYNAMIC_MAX_MB = 4096L
    const val DYNAMIC_MIN_DURATION_MS = 5 * 60_000L

    /** 动态挡位的目标比例：片长的 5% */
    private const val DYNAMIC_RATIO = 0.05

    /**
     * 「动态」挡位下这部片该占多少缓存（字节）：**约片长的 5%**。
     *
     * 恒定码率时"片长的 5%"就等于"文件大小的 5%"，所以直接按字节算（省一次换算）。
     * 短片（≤5 分钟）不套这个比例 —— 5% 只有十几秒、没意义，按"尽量整片"给到上限；
     * 时长未知时退化成按大小算，大小也未知就给下限。
     */
    fun dynamicLimitBytes(durationMs: Long, fileSize: Long): Long {
        val min = DYNAMIC_MIN_MB * 1024 * 1024
        val max = DYNAMIC_MAX_MB * 1024 * 1024
        if (durationMs > 0L && durationMs <= DYNAMIC_MIN_DURATION_MS) return max
        if (fileSize <= 0L) return min
        return (fileSize * DYNAMIC_RATIO).toLong().coerceIn(min, max)
    }

    /** 当前占用的字节数（缓存自己维护的计数，比扫目录快） */
    internal fun spaceBytes(): Long = instance?.cacheSpace ?: 0L

    /**
     * 缓存里"最近被用过"的时刻（[prefix] 非空时只看这些 key，即某一部片）。
     * 用来判断"现在这部片是不是就是缓存里最近在看的那部"——是就别动它。
     */
    internal fun newestTouchOf(prefix: String?): Long {
        val c = instance ?: return 0L
        var newest = 0L
        for (key in c.keys) {
            if (prefix != null && !key.startsWith(prefix)) continue
            val spans = c.getCachedSpans(key)
            if (spans.isEmpty()) continue
            val t = spans.maxOf { it.lastTouchTimestamp }
            if (t > newest) newest = t
        }
        return newest
    }

    /**
     * 成批清掉**最久没用到的旧片**，给当前这部腾地方。
     *
     * 为什么要成批清：缓存满了之后 media3 的 LRU 是"**每写一块先淘汰一块**"（边写边删），
     * 实测把吞吐从 25MB/s 拖到 ~1MB/s —— 这才是"缓存满了、再开新片就慢"的真因
     * （用户实测：清空缓存后同一部片立刻回到满速）。所以在预下载开跑前一次性把地方腾出来，
     * 之后一路顺写，绝不进入边写边删的状态。
     *
     * @param keepKeyPrefix 当前这部片的 key 前缀（`115file:<pickcode>` / `115hls-v1:<sha1>_<档位>`）：
     *   绝不动它 —— 播放器正在读的就是这些。
     * @param targetBytes 清到剩这么多字节为止（一般给上限的一半）
     * @return 删掉的（片数, 字节数）
     */
    internal fun evictOldestExcept(keepKeyPrefix: String?, targetBytes: Long): Pair<Int, Long> {
        val c = instance ?: return 0 to 0L
        val victims = ArrayList<Triple<String, Long, Long>>()   // key, 字节, 最近访问时间
        for (key in c.keys) {
            if (keepKeyPrefix != null && key.startsWith(keepKeyPrefix)) continue
            val spans = c.getCachedSpans(key)
            if (spans.isEmpty()) continue
            victims += Triple(key, spans.sumOf { it.length }, spans.maxOf { it.lastTouchTimestamp })
        }
        victims.sortBy { it.third }        // 最久没用到的先删
        var freed = 0L
        var count = 0
        for ((key, bytes, _) in victims) {
            if (c.cacheSpace <= targetBytes) break
            c.removeResource(key)
            freed += bytes
            count++
        }
        return count to freed
    }

    /**
     * 全量清空（删分片 + SimpleCache 的索引库）。用 [SimpleCache.delete] 而不是只删目录：
     * 只删目录会留下指向已删分片的索引脏数据（索引库在 databases/ 下，不随目录走）。
     *
     * ⚠️ 调用方自己放后台线程：GB 级分片删起来要几秒（登出、设置页清空都在 IO 上跑）。
     */
    fun clear(context: Context) {
        synchronized(this) {
            instance?.release()
            instance = null
            configuredBytes = -1L
        }
        SimpleCache.delete(dir(context), StandaloneDatabaseProvider(context))
    }

    /** 落盘占用（递归算分片目录），设置页显示用。调用方自己放后台线程。 */
    fun sizeBytes(context: Context): Long {
        var total = 0L
        dir(context).walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
