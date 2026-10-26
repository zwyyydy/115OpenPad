package com.open115.pad.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 一条兜底海报裁剪记录：[rev] 是生成版本（用来给落盘文件/Coil 换新），[rect] 为 null = 默认右半张 */
data class PosterCropEntry(val rev: Int, val rect: PosterCropRect?)

/**
 * 兜底海报**手动裁剪**的持久化（跨重启、跨"清空媒体缓存"保留）。
 *
 * 为什么放 `filesDir` 而不是缓存目录：缓存目录会被 LRU 淘汰/设置里清空，而裁剪规格是用户
 * 手动调过的，丢了就得重调。规格只有四个浮点，几百上千条也占不了几 KB；上限 [MAX_ENTRIES]
 * 只是防止极长使用周期里无限增长（先进先出淘汰）。
 *
 * 键是**背景图的 pickCode**（与兜底海报落盘文件同源）：背景图换了（重刮/换剧照）就是另一条，
 * 旧条目自然失效，不会拿旧构图去套新图。
 */
class PosterCropStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }
    private val mutex = Mutex()
    private var loaded = false
    private val entries = LinkedHashMap<String, StoredCrop>()

    suspend fun get(backgroundPickCode: String): PosterCropEntry? = mutex.withLock {
        ensureLoaded()
        entries[backgroundPickCode]?.toEntry()
    }

    /**
     * 写入一条裁剪（[rect] = null 表示恢复默认），并**版本号 +1**。
     *
     * 版本号必须变：落盘文件名/Coil 的内存键都靠它区分新旧图。只覆盖同一个文件的话，
     * Coil 按路径缓存会继续显示旧构图（壁纸换图踩过同一个坑，见 wallpaperStamp）。
     *
     * @return 新的版本号
     */
    suspend fun put(backgroundPickCode: String, rect: PosterCropRect?): Int = mutex.withLock {
        ensureLoaded()
        val rev = (entries[backgroundPickCode]?.rev ?: 0) + 1
        // 重新插入到队尾：淘汰时先扔最久没动过的
        entries.remove(backgroundPickCode)
        entries[backgroundPickCode] = StoredCrop(
            rev = rev,
            rect = rect?.clamped()?.let { listOf(it.left, it.top, it.right, it.bottom) }.orEmpty(),
        )
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
        save()
        rev
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() } ?: return
        val parsed = runCatching { json.decodeFromString<CropFile>(text) }.getOrNull() ?: return
        entries.putAll(parsed.entries)
    }

    private suspend fun save() {
        val text = json.encodeToString(CropFile(entries.toMap()))
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    @Serializable
    private data class StoredCrop(
        val rev: Int = 0,
        val rect: List<Float> = emptyList(),
    ) {
        fun toEntry(): PosterCropEntry = PosterCropEntry(
            rev = rev,
            rect = rect.takeIf { it.size == 4 }
                ?.let { PosterCropRect(it[0], it[1], it[2], it[3]).clamped() },
        )
    }

    @Serializable
    private data class CropFile(val entries: Map<String, StoredCrop> = emptyMap())

    private companion object {
        const val FILE_NAME = "poster_crops.json"
        const val MAX_ENTRIES = 400
    }
}
