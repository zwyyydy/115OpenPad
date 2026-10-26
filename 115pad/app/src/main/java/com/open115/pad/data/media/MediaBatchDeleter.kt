package com.open115.pad.data.media

import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.OpLog
import com.open115.pad.data.OpType
import com.open115.pad.data.VideoPreviewStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 批量删除影片（海报墙上多选之后）。
 *
 * 与影片迁移是**同一类东西**（逐条云端写操作的长任务），所以做法一样：跑在应用级 [scope] 上、
 * 切页不断、结果汇总报一次、有进度卡能停。区别只有两点：
 *
 * ① **不落盘**。删除是"删一条少一条"，中途被杀最坏就是没删完 —— 剩余的那些还好好在库里，
 *    用户再选一次就行；而迁移中途被杀会留下"云端搬了、索引没跟"的中间态，所以才要持久队列。
 * ② 逐条调用 [deleteMovie]，所以单部那条路上的每一条保护（先云端后本地、云端失败本地不动、
 *    旧数据没存 file_id 时不硬删）原样都在。
 *
 * 云写走**限速的那份接口**（`openApi`，与单部删除同一条链路）+ 每条之间再留一点间隔：
 * 删除是不可逆的，宁可慢一点也不要撞上 115 的频控。
 */
class MediaBatchDeleter(
    private val api: OpenApi,
    private val dao: MediaDao,
    private val mediaCache: MediaCache?,
    private val imageUrlResolver: ImageUrlResolver,
    private val cacheDir: File,
    private val videoPreviews: VideoPreviewStore?,
    private val opLog: OpLog? = null,
    private val scope: CoroutineScope,
) {

    data class Progress(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val deleted: Int = 0,
        val failed: Int = 0,
        val alsoCloud: Boolean = false,
        /** 连回收站里对应的项一起清（彻底删除）。onlyCloud 的"删除"不清回收站 */
        val alsoPurgeRecycle: Boolean = false,
        val currentTitle: String = "",
        /** 逐条失败/跳过的原因（最多 [MAX_NOTES] 条，别把卡片撑爆） */
        val notes: List<String> = emptyList(),
        val summary: String? = null,
        val finishedAt: Long = 0L,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var stopRequested = false
    private var job: Job? = null

    /**
     * 起一批删除。[alsoCloud] = true 连云盘文件一起删；[alsoPurgeRecycle] 再把回收站里
     * 对应的项清掉（「彻底删除」，不可逆）—— 调用方必须已经在对话框里二次确认过。
     * 三种模式与文件页同一套语义：仅移除 / 删除（进回收站）/ 彻底删除（清回收站）。
     */
    fun start(mediaKeys: List<String>, alsoCloud: Boolean, alsoPurgeRecycle: Boolean = false) {
        val keys = mediaKeys.distinct()
        if (keys.isEmpty()) return
        if (_progress.value.running) return
        stopRequested = false
        _progress.value = Progress(
            running = true, total = keys.size,
            alsoCloud = alsoCloud, alsoPurgeRecycle = alsoCloud && alsoPurgeRecycle,
        )
        job = scope.launch { run(keys, alsoCloud, alsoPurgeRecycle) }
    }

    /** 跑完当前这一部就收尾（cancel 会把本地索引清理做到一半） */
    fun stop() {
        stopRequested = true
    }

    /** 一次性消费"刚结束那一批"的汇总（与迁移同一套写法，免得换个页面又弹一遍） */
    fun takeSummary(): String? {
        val s = _progress.value.summary ?: return null
        _progress.value = _progress.value.copy(summary = null)
        return s
    }

    private suspend fun run(keys: List<String>, alsoCloud: Boolean, alsoPurgeRecycle: Boolean) {
        var deleted = 0
        var failed = 0
        val notes = mutableListOf<String>()
        var stopped = false
        for (key in keys) {
            if (stopRequested) {
                stopped = true
                break
            }
            val title = dao.movie(key)?.title.orEmpty()
            _progress.value = _progress.value.copy(currentTitle = title)
            val r = try {
                deleteMovie(
                    api = api,
                    dao = dao,
                    mediaCache = mediaCache,
                    imageUrlResolver = imageUrlResolver,
                    cacheDir = cacheDir,
                    mediaKey = key,
                    alsoCloud = alsoCloud,
                    alsoPurgeRecycle = alsoPurgeRecycle,
                    videoPreviews = videoPreviews,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MovieDeleteResult.CloudFailed(e.message ?: "未知错误")
            }
            when (r) {
                MovieDeleteResult.Ok -> deleted++
                is MovieDeleteResult.CloudFailed -> {
                    failed++
                    if (notes.size < MAX_NOTES) notes += "「$title」失败：${r.message}"
                }
                MovieDeleteResult.NoCloudId -> {
                    failed++
                    if (notes.size < MAX_NOTES) {
                        notes += "「$title」是升级前的旧数据、没存云盘文件 id，没删"
                    }
                }
            }
            _progress.value = _progress.value.copy(
                done = deleted + failed,
                deleted = deleted,
                failed = failed,
                currentTitle = "",
                notes = notes.toList(),
            )
            // 删除不可逆：同一条链路上慢一点，别把 115 的频控撞出来
            delay(ITEM_GAP_MS)
        }
        // 拼接里带条件的段一律走 listOfNotNull：字符串里的 if 会把后面几段一起吞掉（踩过的坑）
        val verb = when {
            alsoPurgeRecycle -> "已彻底删除"
            alsoCloud -> "已删除"
            else -> "已从媒体库移除"
        }
        val summary = listOfNotNull(
            "$verb $deleted 部",
            if (failed > 0) "$failed 部失败" else null,
            if (stopped) "已停止，剩 ${keys.size - deleted - failed} 部没处理" else null,
        ).joinToString(" · ")
        _progress.value = _progress.value.copy(
            running = false,
            currentTitle = "",
            notes = notes.toList(),
            summary = summary,
            finishedAt = System.currentTimeMillis(),
        )
        runCatching {
            opLog?.log(
                OpType.DELETE,
                "$verb $deleted 部影片",
                listOfNotNull(
                    "ok=$deleted",
                    if (failed > 0) "fail=$failed" else null,
                    if (stopped) "stopped" else null,
                ).joinToString(" "),
            )
        }
    }
}

/** 每条之间的间隔：删除不可逆，宁慢勿撞频控 */
private const val ITEM_GAP_MS = 400L

/** 进度卡上最多列几条失败原因 */
private const val MAX_NOTES = 4
