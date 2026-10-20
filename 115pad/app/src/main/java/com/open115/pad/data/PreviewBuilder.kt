package com.open115.pad.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "PreviewBuild"

/** 分级限速三档：与分类整理/查找重复同一口径（列目录对 115 都是一把刀） */
private const val THROTTLE_FAST_MS = 300L
private const val THROTTLE_MEDIUM_MS = 1000L
private const val THROTTLE_SLOW_MS = 2000L

/**
 * 抽帧节拍（按本轮已提取的视频数阶梯放慢）：
 * 前 10 部每部间隔 2s、11~20 部 4s、21 部起 6s，各带 ±10% 抖动。
 * 抓一部的代价是多次 CDN 请求 + 解码，全局请求间隔（设置里的「请求限速」）管不到
 * CDN 分片，单靠它压不住密度。
 */
private const val PACE_FAST_MS = 2000L
private const val PACE_MEDIUM_MS = 4000L
private const val PACE_SLOW_MS = 6000L
private const val PACE_JITTER = 0.10

/** 每提取这么多部，额外歇一分钟（给 CDN/转码服务留恢复窗口） */
private const val PACE_GROUP_SIZE = 30
private const val PACE_GROUP_REST_MS = 60_000L

/** 结果页最多留这么多条目（铺缩略图够看，也不至于把几十万条留在内存里） */
private const val MAX_KEPT_VIDEOS = 400

/**
 * 本轮已提取 [done] 部后该等多久的基础档位（不含 ±10% 抖动）：
 * ≤10 部 2s、11~20 部 4s、21 部起 6s。
 */
internal fun previewPaceBaseMs(done: Int): Long = when {
    done <= 10 -> PACE_FAST_MS
    done <= 20 -> PACE_MEDIUM_MS
    else -> PACE_SLOW_MS
}

/** 基础档位 + ±10% 抖动：固定节奏容易撞上 CDN/转码服务的节拍，抖一下更稳 */
internal fun previewPaceMs(done: Int): Long {
    val base = previewPaceBaseMs(done)
    val factor = 1.0 + (kotlin.random.Random.nextDouble() * 2 - 1) * PACE_JITTER
    return (base * factor).toLong()
}

/**
 * 按目录批量生成视频预览（本地缓存）：封面一张 + 预览条若干张。
 *
 * 干的事：走一遍目录 → 每个视频按片长抽 2~10 帧（115 最小转码档的 HLS 分片，
 * 见 [VideoFrameGrabber].[grabPreviewSet]）→ 落进 [VideoPreviewStore]。
 * 已经齐了的**直接跳过**，所以重跑一轮只会补缺的那些。
 *
 * 骨架照 [DuplicateFinder]：`walkDirs` 遍历 + 目录级三档限速 + `stopRequested` 可停 +
 * [progress] 供面板订阅。区别是**只读**（不删任何云端文件），且视频越多越慢但代价可预期
 * （每张约 376KB 流量）。
 *
 * 抽帧另有**逐部阶梯节拍**：前 10 部每部 2s、11~20 部 4s、21 部起 6s（各 ±10% 抖动），
 * 每 30 部额外歇 1 分钟 —— 设置里的「请求限速」只管 open 接口，管不到 CDN 分片。
 */
class PreviewBuilder(
    private val api: OpenApi,
    private val frames: VideoFrameGrabber,
    private val store: VideoPreviewStore,
    /** 抓完把动过的目录缓存留着不动：这一步不改云端内容，列表不需要失效 */
    private val scope: CoroutineScope,
) {

    data class Progress(
        val running: Boolean = false,
        val rootCid: String = "",
        val rootPath: String = "",
        val recursive: Boolean = true,
        val scannedDirs: Int = 0,
        /** 见过的视频总数 */
        val seenVideos: Int = 0,
        /** 本轮新生成 */
        val generated: Int = 0,
        /** 本来就有、跳过的 */
        val skipped: Int = 0,
        /** 没转码档/抓帧失败 */
        val failed: Int = 0,
        /** 本轮已写入的字节（面板直接显示，不用去列缓存目录） */
        val bytesWritten: Long = 0,
        /** 本轮生成的**预览条帧数**（预览条 = 行下面那条宽幅横排图） */
        val stripFrames: Int = 0,
        /** 最近生成的那张（结果页用它即时显示，也让用户看到"在动"） */
        val lastPickCode: String? = null,
        /**
         * 见过的视频条目（结果页直接铺缩略图用）。
         * 上限 [MAX_KEPT_VIDEOS] 条：一次几十万个条目没必要全留着，铺图也就看前几屏。
         */
        val videos: List<FileItem> = emptyList(),
        val summary: String? = null,
        val stopped: Boolean = false,
        val finishedAt: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var stopRequested = false

    private var job: Job? = null

    private class StopSignal : Exception()

    /** 开始。[recursive] = false 只看这一层 */
    fun start(rootCid: String, rootPath: String, recursive: Boolean) {
        if (_progress.value.running) return
        stopRequested = false
        _progress.value = Progress(running = true, rootCid = rootCid, rootPath = rootPath, recursive = recursive)
        job = scope.launch { run(rootCid, rootPath, recursive) }
    }

    /** 请求停止：下一部开始前生效（节拍停顿中每 500ms 检查一次），已生成的照样留着 */
    fun stop() {
        stopRequested = true
    }

    /** 关面板/开新一轮时清掉上一轮结果 */
    fun clearResult() {
        if (_progress.value.running) return
        _progress.value = Progress()
    }

    private suspend fun run(rootCid: String, rootPath: String, recursive: Boolean) {
        var generated = 0
        var skipped = 0
        var failed = 0
        var stripFrames = 0
        /** 本轮真正尝试提取过的视频数（含失败；缓存跳过的不算）——阶梯节拍按它选档 */
        var processed = 0
        try {
            walkDirs(api, rootCid, rootPath, recursive, beforeDir = { throttle() }) { _, _, items ->
                checkStop()
                val videos = items.filter { !it.isDir && isVideoItem(it) && !it.pc.isNullOrBlank() }
                // 要做的条件：封面没有，或者预览条张数与"按片长该有几张"不符（含一张都没抓到的情况）
                val fresh = videos.filter { v ->
                    val pc = v.pc!!
                    !store.has(pc) || store.stripCount(pc) != stripFrameCount(v.play_long)
                }
                val already = videos.size - fresh.size
                _progress.value = _progress.value.copy(
                    scannedDirs = _progress.value.scannedDirs + 1,
                    seenVideos = _progress.value.seenVideos + videos.size,
                    skipped = skipped + already,
                    videos = (_progress.value.videos + videos).take(MAX_KEPT_VIDEOS),
                )
                skipped += already
                // 逐部提取，并按本轮已处理的视频数阶梯放慢（2s / 4s / 6s，±10%）；
                // 每 30 部额外歇 1 分钟。失败也照样计档、照样等——请求已经发出去了
                for (item in fresh) {
                    checkStop()
                    val res = runCatching { grabOne(item) }.getOrNull()
                    processed++
                    if (res != null) {
                        generated++
                        stripFrames += res.second
                        _progress.value = _progress.value.copy(
                            generated = generated,
                            stripFrames = _progress.value.stripFrames + res.second,
                            lastPickCode = item.pc,
                            bytesWritten = _progress.value.bytesWritten + res.first,
                        )
                    } else {
                        failed++
                        _progress.value = _progress.value.copy(failed = failed)
                    }
                    if (processed % PACE_GROUP_SIZE == 0) paceRest(PACE_GROUP_REST_MS)
                    else paceRest(previewPaceMs(processed))
                }
            }
            finish(
                "已生成 $generated 个视频的预览（封面 $generated 张 · 预览条 $stripFrames 帧）" +
                    (if (skipped > 0) " · 跳过已有 $skipped" else "") +
                    (if (failed > 0) " · $failed 个没抓到（多为云端未转码）" else ""),
            )
        } catch (e: StopSignal) {
            finish("已停止 · 本轮生成 $generated 个视频的预览（已生成的都留着）", stopped = true)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "批量生成预览图失败：${e.message}")
            finish("出错了（${e.message ?: e.toString()}）· 已完成 $generated 个", stopped = true)
        }
    }

    /**
     * 抓一个视频的**封面 + 预览条**并落盘。
     *
     * @return (写入字节数, 预览条张数)；封面与预览条**两个都没抓到**才算失败（返回 null）
     */
    private suspend fun grabOne(item: FileItem): Pair<Long, Int>? {
        val pc = item.pc ?: return null
        val count = stripFrameCount(item.play_long)
        val set = frames.grabPreviewSet(pc, count)
        if (set.isEmpty()) return null
        val cover = set.firstOrNull()
        val strip = set.drop(1)
        var bytes = 0L
        val coverFile = cover?.let { store.put(pc, it) }
        bytes += coverFile?.length() ?: 0L
        val written = store.putStrip(pc, strip)
        bytes += strip.sumOf { it?.size?.toLong() ?: 0L }
        // 上限的淘汰由 store 自己管（写在 put/putStrip 里），这里不再显式 prune
        if (coverFile == null && written == 0) return null
        Log.i(TAG, "生成预览 pc=$pc 封面=${coverFile?.length() ?: 0}B 预览条=$written/$count 张 共 ${bytes}B")
        return bytes to written
    }

    private fun finish(summary: String, stopped: Boolean = false) {
        _progress.value = _progress.value.copy(
            running = false,
            summary = summary,
            stopped = stopped,
            finishedAt = System.currentTimeMillis(),
        )
        Log.i(TAG, "批量生成结束：$summary")
    }

    private fun checkStop() {
        if (stopRequested) throw StopSignal()
    }

    /**
     * 阶梯节拍的停顿：切成 500ms 小段，每段前检查停止请求 ——
     * 否则用户点了停止，还得干等最长一分钟的那次打盹结束。
     */
    private suspend fun paceRest(ms: Long) {
        var left = ms
        while (left > 0) {
            checkStop()
            val step = minOf(left, 500L)
            delay(step)
            left -= step
        }
    }

    /** 分级限速：越扫越慢（前 10 个目录 300ms / 11~20 个 1000ms / 再多 2000ms） */
    private suspend fun throttle() {
        val scanned = _progress.value.scannedDirs
        delay(
            when {
                scanned >= 20 -> THROTTLE_SLOW_MS
                scanned >= 10 -> THROTTLE_MEDIUM_MS
                else -> THROTTLE_FAST_MS
            },
        )
    }
}

/**
 * 视频扩展名清单 —— **全仓唯一一份**：文件页的 `videoExts`、列表图标判定、
 * 批量预览图任务都引用它（以前散在 ui 层，加功能时容易只改一处）。
 */
val VIDEO_EXTS: Set<String> = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "webm", "m4v", "mpg", "mpeg", "rmvb", "m2ts",
)

/** 是不是视频（与文件页图标同一判据：115 的 isv 标记优先，再看扩展名） */
internal fun isVideoItem(item: FileItem): Boolean =
    !item.isDir && (item.isv == 1 || (item.ico?.lowercase() ?: "") in VIDEO_EXTS)
