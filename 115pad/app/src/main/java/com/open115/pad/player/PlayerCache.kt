package com.open115.pad.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 播放缓存：SimpleCache 必须对同一目录保持单例。
 * 上限用 LRU 淘汰：达到 maxBytes 后自动清理最旧的分片，等效于"超过限制不再增长"。
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

    fun clear(context: Context) = synchronized(this) {
        instance?.release()
        instance = null
        configuredBytes = -1L
        dir(context).deleteRecursively()
    }

    fun sizeBytes(context: Context): Long =
        dir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
