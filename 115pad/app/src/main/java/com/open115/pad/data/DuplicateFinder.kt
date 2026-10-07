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

private const val TAG = "Dedupe"

/** 分级限速的三档（与分类整理同一口径：对 115 来说列目录都是一把刀） */
private const val THROTTLE_FAST_MS = 300L
private const val THROTTLE_MEDIUM_MS = 1000L
private const val THROTTLE_SLOW_MS = 2000L

/**
 * 一个"重复副本"：某个 sha1 下的一个文件 + 它在云端的位置。
 *
 * 整个 [FileItem] 都留着 —— 界面要拿它显示缩略图（`Thumb`）与大小/时间，而这些都是列表响应
 * 本来就带着的，多存一份引用不额外花请求。
 */
data class DupFile(
    val item: FileItem,
    val dirCid: String,
    /** 副本所在目录的可读路径（从根一路拼下来，帮用户分辨"删哪一个"） */
    val dirPath: String,
) {
    val fid: String get() = item.fid.orEmpty()
    val name: String get() = item.fn
    val size: Long get() = item.fs
    /** 云端时间（秒）；0 = 未知 */
    val upt: Long get() = item.upt
}

/** 一组内容完全相同（sha1 相同）的文件，≥2 个。[files] 已按"该保留的排在最前"排好 */
data class DupGroup(
    val sha1: String,
    val size: Long,
    val files: List<DupFile>,
) {
    /** 组里除保留项外都可清理 */
    val removable: List<DupFile> get() = files.drop(1)

    /** 清掉这一组的多余副本能省下的字节（按各文件自身大小算） */
    val wasteBytes: Long get() = removable.sumOf { it.size }
}

/**
 * 一组里哪个该留下：**云端时间最早的**（通常就是"原件"，其余是后来拷/传的副本）；
 * 时间缺失或相同时路径短、名字短的优先（"更靠根、更干净的那份"）；最后用 fid 兜底，
 * 保证顺序稳定 —— 不然同一份数据两次扫描可能给出不同的保留项，用户会觉得"怎么每次说的不一样"。
 */
internal fun keeperFirst(files: List<DupFile>): List<DupFile> = files.sortedWith(
    compareBy(
        { if (it.upt > 0) it.upt else Long.MAX_VALUE },
        { it.dirPath.length },
        { it.dirPath },
        { it.fid },
    ),
)

/**
 * 把扫到的文件按 sha1 分组，只留 ≥2 个的组（那就是"重复"）。
 *
 * 两类必须排除：
 *  - **没有 sha1 的**：判不了内容是否相同（宁可漏报，不能误报）；
 *  - **0 字节的**：空文件的 sha1 全一样，留着会把整盘的空文件串成一组，看着吓人还容易误删。
 *
 * 组按"能省下的空间"从大到小排 —— 用户最关心的就是先把大的清了。
 */
internal fun buildDupGroups(files: List<DupFile>): List<DupGroup> = files
    .filter { !it.item.sha1.isNullOrBlank() && it.size > 0 }
    .groupBy { it.item.sha1!!.lowercase() }
    .mapNotNull { (sha1, list) ->
        if (list.size < 2) null else DupGroup(sha1 = sha1, size = list.first().size, files = keeperFirst(list))
    }
    .sortedWith(
        compareByDescending<DupGroup> { it.wasteBytes }
            .thenByDescending { it.files.size }
            .thenBy { it.files.first().name },
    )

/**
 * 删完之后的结果：还留着哪些组 + 起始页上该说的那句话。
 *
 * 抽成纯函数是因为"删光了就别再说发现 N 组"这条最容易写错 —— 实测第一版就是把**扫描时的旧结论**
 * 留在界面上：那 25 个刚删掉，"可清理 25 个"还挂着，读起来完全对不上。
 */
internal fun afterDelete(groups: List<DupGroup>, gone: Set<String>): Pair<List<DupGroup>, String> {
    val removed = groups.flatMap { it.files }.filter { it.fid in gone }
    val kept = groups
        .map { g -> g.copy(files = g.files.filterNot { it.fid in gone }) }
        .filter { it.files.size >= 2 }
    val summary = if (kept.isEmpty()) {
        "已清理 ${removed.size} 个重复副本（约 ${com.open115.pad.util.Format.size(removed.sumOf { it.size })}，" +
            "都在 115 回收站，可还原）"
    } else {
        "还剩 ${kept.size} 组重复待处理"
    }
    return kept to summary
}

/**
 * 重复文件查找（去重一期的引擎）。
 *
 * 判重依据是 115 列目录响应里自带的 **sha1**（[FileItem.sha1]，同一个内容的文件 sha1 必定相同），
 * 所以**零额外请求、也不用下载算哈希**；代价是要把目录列一遍 —— 因此：
 *  - 走调用方注入的那份接口 + **自己的分级限速**（同分类整理，别和全局限速叠）；
 *  - 可停（[stop]）、可只看当前一层（[start] 的 recursive=false）。
 *
 * 生命周期：跑在容器传入的 [scope]（transferScope）上 —— 切页不断，进程被杀就断（重跑一遍即可，
 * 反正只读不写）。
 */
class DuplicateFinder(
    private val api: OpenApi,
    /** 删完把动过的目录缓存作废（列表内容变了）；null = 没缓存也就不用失效 */
    private val dirCache: DirCache? = null,
    private val scope: CoroutineScope,
) {

    data class Progress(
        val running: Boolean = false,
        val rootCid: String = "",
        /** 起始目录的可读名字（界面显示"正在扫描 …"） */
        val rootPath: String = "",
        val recursive: Boolean = false,
        val scannedDirs: Int = 0,
        val seenFiles: Int = 0,
        /** 已确认重复的组数（扫描中就实时更新，O(1)） */
        val groupCount: Int = 0,
        /** 完整结果（扫完/停下之后才有） */
        val groups: List<DupGroup> = emptyList(),
        /** 结束语（"发现 3 组重复" / "已停止 · …" / 出错信息），null = 还没结果 */
        val summary: String? = null,
        val stopped: Boolean = false,
        /** 结束时刻：界面靠它判断"这是刚跑完的结果"（0 = 没结果可报） */
        val finishedAt: Long = 0,
        /**
         * 最近一次删除动过的目录 + 时刻。文件页据此决定要不要刷新列表
         * （删的是别的目录就不用白刷一遍）—— 与"重命名队列 finishedAt"同一套跨页通知思路。
         */
        val lastDeletedCids: List<String> = emptyList(),
        val lastDeletedAt: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var stopRequested = false

    private var job: Job? = null

    private class StopSignal : Exception()

    /** 开始查找。[recursive] = false 只看这一层 */
    fun start(rootCid: String, rootPath: String, recursive: Boolean) {
        if (_progress.value.running) return
        stopRequested = false
        _progress.value = Progress(
            running = true,
            rootCid = rootCid,
            rootPath = rootPath,
            recursive = recursive,
        )
        job = scope.launch { run(rootCid, rootPath, recursive) }
    }

    /** 请求停止：在目录边界生效（不打断正在进行的那个请求），已扫到的结果照样给出来 */
    fun stop() {
        stopRequested = true
    }

    /** 关面板/重开新一轮时清掉上一轮结果 */
    fun clearResult() {
        if (_progress.value.running) return
        _progress.value = Progress()
    }

    /**
     * 删完之后调一下：把删掉的文件从结果里摘掉、作废相关目录缓存，并记下"动过哪些目录"。
     * 结果页因此不用重扫就能保持一致（用户能接着处理下一组）。
     */
    fun markDeleted(fids: Collection<String>, cids: Collection<String>) {
        if (fids.isEmpty()) return
        val (kept, summary) = afterDelete(_progress.value.groups, fids.toSet())
        cids.forEach { c -> dirCache?.invalidateDir(c) }
        _progress.value = _progress.value.copy(
            groups = kept,
            groupCount = kept.size,
            summary = summary,
            lastDeletedCids = cids.toList(),
            lastDeletedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun run(rootCid: String, rootPath: String, recursive: Boolean) {
        // sha1 → 该内容的所有副本
        val buckets = HashMap<String, MutableList<DupFile>>()
        var dupGroups = 0
        try {
            walkDirs(api, rootCid, rootPath, recursive, beforeDir = { throttle() }) { cid, path, items ->
                checkStop()
                var files = 0
                for (item in items) {
                    if (item.isDir) continue
                    val sha = item.sha1?.takeIf { it.isNotBlank() } ?: continue
                    if (item.fs <= 0) continue
                    files++
                    val bucket = buckets.getOrPut(sha.lowercase()) { mutableListOf() }
                    bucket += DupFile(item, cid, path)
                    if (bucket.size == 2) dupGroups++ // 恰好凑成一对那一刻记一次，省得每次重算
                }
                _progress.value = _progress.value.copy(
                    scannedDirs = _progress.value.scannedDirs + 1,
                    seenFiles = _progress.value.seenFiles + files,
                    groupCount = dupGroups,
                )
            }
            finish(buildDupGroups(buckets.values.flatten()).let { g -> summaryOf(g) to g })
        } catch (e: StopSignal) {
            // 停下也把已经扫到的结果给出来：用户可能就是看了前几组就够用了
            val groups = buildDupGroups(buckets.values.flatten())
            finish("已停止 · 已扫 ${_progress.value.scannedDirs} 个目录，发现 ${groups.size} 组重复" to groups, stopped = true)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "查找重复失败：${e.message}")
            val groups = buildDupGroups(buckets.values.flatten())
            finish("出错了（${e.message ?: e.toString()}）· 已扫到的 ${groups.size} 组仍在下面" to groups, stopped = true)
        }
    }

    private fun summaryOf(groups: List<DupGroup>): String = when {
        groups.isEmpty() -> "没有发现重复文件"
        else -> {
            val extra = groups.sumOf { it.removable.size }
            val bytes = groups.sumOf { it.wasteBytes }
            "发现 ${groups.size} 组重复 · 可清理 $extra 个 · 约 ${com.open115.pad.util.Format.size(bytes)}"
        }
    }

    private fun finish(result: Pair<String, List<DupGroup>>, stopped: Boolean = false) {
        _progress.value = _progress.value.copy(
            running = false,
            groups = result.second,
            groupCount = result.second.size,
            summary = result.first,
            stopped = stopped,
            finishedAt = System.currentTimeMillis(),
        )
    }

    private fun checkStop() {
        if (stopRequested) throw StopSignal()
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
