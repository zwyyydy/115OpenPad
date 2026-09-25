package com.open115.pad.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlin.concurrent.thread

/**
 * 播放缓存：SimpleCache 必须对同一目录保持单例。
 * 上限用 LRU 淘汰：达到 maxBytes 后自动清理最旧的分片，等效于"超过限制不再增长"。
 *
 * 缓存只在**单次播放会话内**有价值：缓存 key 是完整播放地址（含签名 query，见
 * PlayerActivity 里 CacheDataSource 那段"刻意不改写 key"的注释），而每次进播放器
 * 都会向 115 重新解析地址，新地址签名不同 ⇒ 上一会话的缓存永远不会再命中，
 * 留着只是占空间。所以退出播放器时整个清掉（[clearAsync]）；会话内的回退进度、
 * 切档预载复用不受影响。
 */
object PlayerCache {

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var configuredBytes = -1L

    fun dir(context: Context): File = File(context.cacheDir, "video_cache")

    fun get(context: Context, maxBytes: Long): SimpleCache = synchronized(this) {
        val existing = instance
        if (existing != null && configuredBytes == maxBytes) return existing
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
     * 全量清空（删分片 + SimpleCache 的索引库）。用 [SimpleCache.delete] 而不是只删目录：
     * 只删目录会留下指向已删分片的索引脏数据（索引库在 databases/ 下，不随目录走）。
     */
    fun clear(context: Context) {
        synchronized(this) {
            instance?.release()
            instance = null
            configuredBytes = -1L
        }
        SimpleCache.delete(dir(context), StandaloneDatabaseProvider(context))
    }

    /**
     * 退出播放器时的清理入口：文件删除放后台线程，不拖慢返回上一页的那一瞬间。
     *
     * 删除阶段不持 PlayerCache 锁（GB 级分片要删好几秒，握着锁会把紧跟着重进播放器的
     * [get] 卡在主线程上）。代价是一个极窄的竞态：删除途中立刻重进播放器，新 SimpleCache
     * 会在半空的目录上初始化——索引与文件对不上时由读失败兜底（播放器带
     * FLAG_IGNORE_CACHE_ON_ERROR），残留的孤儿分片也会被下一次退出清理一并删掉。
     */
    fun clearAsync(context: Context) {
        val app = context.applicationContext
        thread(name = "video-cache-clear", isDaemon = true) {
            runCatching { clear(app) }
        }
    }
}
