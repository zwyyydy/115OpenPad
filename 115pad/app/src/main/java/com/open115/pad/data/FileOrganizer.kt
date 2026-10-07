package com.open115.pad.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 文件页顶栏「分类整理」：把当前目录（含所有子文件夹）里的**文件**按扩展名归类，
 * 移动到本目录下的 视频 / 音频 / 文本 / 应用程序 / 图片 / 压缩包 / 其他 七个文件夹里。
 *
 * 挂在 [com.open115.pad.App115.AppContainer] 而不是 FilesViewModel 上，理由与上传同款：
 * 任务跑在应用级 transferScope，切页/换目录/关面板都不停；progress 汇进 taskActivity，
 * 「防止被杀」的前台服务跟着亮；进度横幅在文件页顶栏下面，人走了回来还在。
 *
 * 三条不动文件的红线：
 *  - 只搬文件，子文件夹一律原地不动（文件夹本身没有"类型"）；
 *  - 根一层里**与分类同名的文件夹**就是归档目标：复用它、且不再进去扫 ——
 *    里面的文件已经各就各位，再扫一遍只会把它们又端上来重复移动一遍；
 *  - 找不到 fid 的条目（正常列目录不会出现）直接跳过，留在原地。
 *
 * 例外机制：[exclusions] 里的 fid 不搬（文件夹连带整个子树）。集合**执行中可改**，
 * 三个环节（目录出队 / 文件入桶 / 每批移动前）都现读，所以中途勾选也来得及保住没搬的文件。
 *
 * 全程跑在应用级协程里（构造传入的 [scope]，App 容器用的是 transferScope）：
 * UI 只发车和收进度，不参与执行 —— 切页、锁屏都不影响，配合前台服务（taskActivity）
 * 后台也照跑。请求一律走 [throttle] 分级限速（防 ban）。
 *
 * 同名冲突不用管：115 不做覆盖写，同名移入服务端自动改名成「xxx（1）」（实测）。
 */
class FileOrganizer(
    private val api: OpenApi,
    private val opLog: OpLog? = null,
    /** 整理完把动过的目录缓存作废（列目录结果全变了）；null = 没缓存也就不用失效 */
    private val dirCache: DirCache? = null,
    private val scope: CoroutineScope,
) {

    /** 整理进行到哪一步：横幅文案按它分叉 */
    enum class Phase { SCAN, MOVE, CLEANUP }

    /**
     * 一次运行的进度快照。
     *
     * [summary] + [finishedAt]：结束时落一次（finishedAt 是全局时间戳，UI 拿它做
     * "这条结果我报过没有"的判据 —— finishedAt 归零表示没有可报告的结果）。
     */
    data class Progress(
        val running: Boolean = false,
        val rootCid: String = "",
        val rootPath: String = "",
        val phase: Phase = Phase.SCAN,
        val scannedDirs: Int = 0,
        val foundFiles: Int = 0,
        /** 扫描结束后才有意义：待移动总数 */
        val totalToMove: Int = 0,
        val movedFiles: Int = 0,
        val createdDirs: Int = 0,
        val failed: Int = 0,
        /** 收尾清理：删掉了多少个空文件夹 */
        val removedEmptyDirs: Int = 0,
        val summary: String? = null,
        val finishedAt: Long = 0L,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 例外条目（fid 集合）：勾了的文件不搬、文件夹整个子树跳过。
     *
     * **执行中可随时改**（文件页横幅「例外」入口）：每轮目录出队、每个文件入桶、
     * 每批移动前都现读这份集合 —— 所以扫描过半再把某个文件夹加进来也来得及，
     * 还没移到它的文件照样保得住。跨运行保留（同一个目录再整理，上次的例外还认）。
     */
    private val _exclusions = MutableStateFlow<Set<String>>(emptySet())
    val exclusions: StateFlow<Set<String>> = _exclusions.asStateFlow()

    fun setExclusions(v: Set<String>) {
        _exclusions.value = v
    }

    private var job: Job? = null

    /** 用户点了「停止」：跑完当前这一批就优雅收尾（ cancel 会把收尾和记录一起杀掉） */
    @Volatile
    private var stopRequested = false

    fun start(rootCid: String, rootPath: String) {
        if (_progress.value.running) return
        // 同步置位再起协程：连点两下时第二下必须看到 running=true，不然会起两个任务
        stopRequested = false
        _progress.value = Progress(running = true, rootCid = rootCid, rootPath = rootPath)
        job = scope.launch { run(rootCid, rootPath) }
    }

    fun stop() {
        stopRequested = true
    }

    /** 收尾动作，标记 StopSignal 的 catch 也要走，所以单独提出来 */
    private fun releaseCache(touched: List<String>) {
        dirCache?.let { c -> touched.forEach(c::invalidateDir) }
    }

    /**
     * 分级限速（防 ban）：请求间隔按**已扫目录数**分档 ——
     * 前 10 个目录 300ms，11~20 个 1000ms，20 个往上 2000ms。
     * 列目录翻页、建文件夹、移文件每一类请求都走它：对 115 来说都是同一把刀。
     */
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

    private class StopSignal : Exception()

    private fun checkStop() {
        if (stopRequested) throw StopSignal()
    }

    private suspend fun run(rootCid: String, rootPath: String) {
        val touched = mutableListOf<String>()
        try {
            // ---- ① 递归扫描：BFS 整棵子树，文件按扩展名分桶 ----
            val byCategory = Array(FileClassifier.CATEGORIES.size) { mutableListOf<FileItem>() }
            /** 已存在的分类文件夹：分类下标 -> fid（名字对上就复用，不重复建） */
            val targetCids = arrayOfNulls<String>(FileClassifier.CATEGORIES.size)

            val queue = ArrayDeque<String>()
            queue.addLast(rootCid)
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                checkStop()
                val cid = queue.removeFirst()
                // 例外文件夹：整个子树不碰（不递归、里面的文件一个不搬）。
                // 执行中勾的也生效 —— 目录是出队时才查的，还没扫到的来得及躲开
                if (cid in _exclusions.value) continue
                val items = listAll(cid)
                touched.add(cid)
                val excluded = _exclusions.value
                for (item in items) {
                    if (item.isDir) {
                        // 只在根一层认"分类同名目录"：更深层的同名目录是用户自己的内容，照常递归
                        if (cid == rootCid) {
                            val idx = FileClassifier.CATEGORIES.indexOf(item.fn)
                            if (idx >= 0) {
                                item.fid?.let { targetCids[idx] = it }
                                continue
                            }
                        }
                        item.fid?.let(queue::addLast)
                    } else if (item.fid == null || item.fid !in excluded) {
                        byCategory[FileClassifier.indexOf(item.fn)].add(item)
                    }
                }
                _progress.value = _progress.value.copy(
                    scannedDirs = _progress.value.scannedDirs + 1,
                    foundFiles = _progress.value.foundFiles + items.count {
                        !it.isDir && (it.fid == null || it.fid !in excluded)
                    },
                )
                throttle()
            }

            // 搬运清单：扫描完按**当下**的例外集合再筛一遍 —— 扫描期勾的例外在这里生效，
            // 之后（移动途中）勾的在每批移动前还会再查一次
            fun isExcluded(f: FileItem, excluded: Set<String>): Boolean =
                f.fid == null || f.fid in excluded || f.pid in excluded

            val moveLists: List<List<FileItem>> = byCategory.map { list ->
                val excluded = _exclusions.value
                list.filter { !isExcluded(it, excluded) }
            }
            val toMove = moveLists.sumOf { it.size }
            if (toMove == 0) {
                // 没有要搬的文件，也可能有空文件夹等着清（上次整理/手动搬完剩下的）
                val removed = cleanupEmptyDirs(rootCid, targetCids, touched)
                releaseCache(touched)
                finish(
                    if (removed > 0) "目录已经很整洁：顺手清掉了 $removed 个空文件夹"
                    else "目录已经很整洁：没有需要归类的文件",
                )
                return
            }

            // ---- ② 建缺的分类目录（只为有文件的分类建，空类不建空文件夹）----
            for (idx in moveLists.indices) {
                coroutineContext.ensureActive()
                checkStop()
                if (moveLists[idx].isEmpty() || targetCids[idx] != null) continue
                val resp = api.addFolder(rootCid, FileClassifier.CATEGORIES[idx])
                val cid = if (resp.envOk()) resp.envData()?.optStr("file_id") else null
                if (cid != null) {
                    targetCids[idx] = cid
                    touched.add(cid)
                    _progress.value = _progress.value.copy(createdDirs = _progress.value.createdDirs + 1)
                } else {
                    // 建不出来就把这一类整个留在原地：硬塞进别的类才是事故
                    _progress.value = _progress.value.copy(failed = _progress.value.failed + moveLists[idx].size)
                }
                throttle()
            }

            // ---- ③ 按类分批移动：一批一次请求，批间限速；移动途中勾例外也来得及 ----
            _progress.value = _progress.value.copy(phase = Phase.MOVE, totalToMove = toMove)
            for (idx in moveLists.indices) {
                val to = targetCids[idx] ?: continue
                for (batch in moveLists[idx].chunked(MOVE_BATCH)) {
                    coroutineContext.ensureActive()
                    checkStop()
                    // 每批现读例外集合：文件夹级例外连带里面的文件（pid 判定），
                    // 扫描早就过了、但还没轮到搬的文件照样保得住
                    val excluded = _exclusions.value
                    val ids = batch.mapNotNull { f -> f.fid?.takeIf { !isExcluded(f, excluded) } }
                    if (ids.isEmpty()) continue
                    val ok = runCatching { api.moveFiles(ids.joinToString(","), to) }
                        .getOrNull()?.envOk() == true
                    _progress.value = _progress.value.copy(
                        movedFiles = _progress.value.movedFiles + if (ok) ids.size else 0,
                        failed = _progress.value.failed + if (ok) 0 else ids.size,
                    )
                    throttle()
                }
            }

            // ---- ④ 清理空文件夹：文件搬走后腾空的子目录就地删除 ----
            _progress.value = _progress.value.copy(phase = Phase.CLEANUP)
            val removedEmpty = cleanupEmptyDirs(rootCid, targetCids, touched)

            releaseCache(touched)
            val p = _progress.value
            val parts = breakdown(moveLists)
            finish(
                buildString {
                    append("分类整理完成：移动 ${p.movedFiles} 项")
                    if (p.createdDirs > 0) append("，新建 ${p.createdDirs} 个文件夹")
                    if (removedEmpty > 0) append("，清理空文件夹 $removedEmpty 个")
                    if (p.failed > 0) append("，失败 ${p.failed} 项")
                    if (parts.isNotEmpty()) append("（").append(parts).append("）")
                },
            )
            opLog?.log(
                OpType.MOVE,
                "分类整理 ${p.movedFiles} 项",
                "「$rootPath」 $parts",
                rootCid,
                rootPath,
            )
        } catch (e: StopSignal) {
            releaseCache(touched)
            val p = _progress.value
            finish("分类整理已停止：已移动 ${p.movedFiles} 项" + if (p.failed > 0) "，失败 ${p.failed} 项" else "")
            opLog?.log(OpType.MOVE, "分类整理 ${p.movedFiles} 项（手动停止）", "「$rootPath」", rootCid, rootPath)
        } catch (e: CancellationException) {
            releaseCache(touched)
            val p = _progress.value
            _progress.value = p.copy(
                running = false,
                summary = "分类整理中断：已移动 ${p.movedFiles} 项",
                finishedAt = System.currentTimeMillis(),
            )
            throw e
        } catch (e: Exception) {
            releaseCache(touched)
            _progress.value = _progress.value.copy(
                running = false,
                summary = "分类整理失败：${e.message ?: e.toString()}",
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * ④ 清理空文件夹：整理把文件都搬走了，原本装文件的子文件夹常常就空了。
     * [dirs] 是 BFS 序（父先于子），**倒过来逐个重列一遍**：子孙先删掉，父层此刻再列
     * 才是准的；内容真空了才删（115 的删除异步生效，父层偶尔因此漏删一两个，下次整理
     * 会再收掉 —— 宁可漏删，不能误删没空的）。
     *
     * 三类不动：根目录（[rootCid]）；七个分类文件夹（[skipCids]，它们是归档目标，
     * 空着也是结构的一部分）；列不出来/删除失败的一律跳过。[stopRequested] 同样生效。
     */
    private suspend fun cleanupEmptyDirs(rootCid: String, skipCids: Array<String?>, dirs: List<String>): Int {
        val skip = skipCids.mapNotNull { it }.toHashSet() + rootCid
        var removed = 0
        for (cid in dirs.asReversed().distinct()) {
            coroutineContext.ensureActive()
            checkStop()
            if (cid in skip) continue
            // 列出来确认真空了才删；列失败（被删了/网络）当"没空"处理 —— 宁可漏删，不能误删。
            // 节拍每个目录一次：列目录与删除对 115 是同一把刀，连续空目录也不能放行
            val empty = runCatching { listAll(cid) }.map { it.isEmpty() }.getOrDefault(false)
            if (empty) {
                val ok = runCatching { api.deleteFiles(cid) }.getOrNull()?.envOk() == true
                if (ok) removed++
            }
            throttle()
        }
        if (removed > 0) {
            _progress.value = _progress.value.copy(removedEmptyDirs = removed)
        }
        return removed
    }

    /** 按分类下标拼"视频 12 · 图片 3"这样的汇总（空的类不出场）；opLog 与结束文案共用 */
    private fun breakdown(moveLists: List<List<FileItem>>): String =
        moveLists.withIndex()
            .filter { (_, list) -> list.isNotEmpty() }
            .joinToString(" · ") { (idx, list) -> "${FileClassifier.CATEGORIES[idx]} ${list.size}" }

    private fun finish(summary: String) {
        _progress.value = _progress.value.copy(
            running = false,
            summary = summary,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** 列全一个目录（按 count 翻页取全，与媒体库扫描同款），并打限速 */
    private suspend fun listAll(cid: String): List<FileItem> {
        val out = mutableListOf<FileItem>()
        var offset = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = parseFilesResponse(
                api.files(cid = cid, offset = offset, limit = PAGE_SIZE, order = "file_name", asc = 1),
            )
            out += page.items
            if (page.items.isEmpty() || out.size >= page.count) break
            offset += page.items.size
            // 防御：单目录异常大（比如误在根目录上整理）时不再无限翻页
            if (offset >= MAX_ITEMS_PER_DIR) break
            throttle()
        }
        return out
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val MAX_ITEMS_PER_DIR = 20_000
        private const val MOVE_BATCH = 50

        /** 分级限速（防 ban）：前 10 个目录 300ms，11~20 个 1000ms，20+ 2000ms */
        private const val THROTTLE_FAST_MS = 300L
        private const val THROTTLE_MEDIUM_MS = 1000L
        private const val THROTTLE_SLOW_MS = 2000L
    }
}

/**
 * 扩展名 → 分类 的纯逻辑，单测直接打这张表（无 Android 依赖）。
 *
 * 用户定的六类 + 兜底：视频 / 音频 / 文本 / 应用程序 / 图片 / 压缩包 / 其他。
 * [CATEGORIES] 的顺序即建目录顺序，文件夹名就是分类名；「其他」永远最后。
 */
object FileClassifier {
    val CATEGORIES = listOf("视频", "音频", "文本", "应用程序", "图片", "压缩包", "其他")

    private const val VIDEO = 0
    private const val AUDIO = 1
    private const val TEXT = 2
    private const val APP = 3
    private const val IMAGE = 4
    private const val ARCHIVE = 5
    private const val OTHER = 6

    private val table: Map<String, Int> = buildMap {
        // 视频
        setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "webm",
            "rmvb", "rm", "mpg", "mpeg", "m4v", "3gp", "vob", "f4v", "ogv", "wtv",
        ).forEach { put(it, VIDEO) }
        // 音频
        setOf(
            "mp3", "flac", "wav", "ape", "ogg", "m4a", "aac", "wma", "opus",
            "dsf", "dff", "tak", "wv", "ac3", "dts", "mid", "mka",
        ).forEach { put(it, AUDIO) }
        // 文本（含文档、字幕、nfo）
        setOf(
            "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "epub",
            "mobi", "azw3", "csv", "log", "json", "xml", "html", "htm", "nfo",
            "srt", "ass", "ssa", "sub", "sup", "lrc",
        ).forEach { put(it, TEXT) }
        // 应用程序
        setOf(
            "exe", "msi", "apk", "dmg", "ipa", "deb", "rpm", "appimage", "pkg",
            "jar", "bat", "cmd", "app", "msix",
        ).forEach { put(it, APP) }
        // 图片
        setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff",
            "svg", "ico", "raw", "cr2", "cr3", "nef", "arw", "dng", "psd", "tga", "jxl",
        ).forEach { put(it, IMAGE) }
        // 压缩包（iso 是光盘镜像不算压缩包，留在其他）
        setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "xz", "txz",
            "zst", "lz4", "br", "cab", "lzh", "arj", "cpio",
        ).forEach { put(it, ARCHIVE) }
    }

    /** 文件名 → 分类下标；无扩展名 / 认不出的扩展名进「其他」 */
    fun indexOf(fileName: String): Int {
        val dot = fileName.lastIndexOf('.')
        // 无点、以点开头（".gitignore" 这类隐藏文件按约定当整名无扩展名看）、以点结尾 → 其他
        if (dot <= 0 || dot == fileName.lastIndex) return OTHER
        val ext = fileName.substring(dot + 1).lowercase()
        if (ext.length > 8) return OTHER
        return table[ext] ?: OTHER
    }
}
