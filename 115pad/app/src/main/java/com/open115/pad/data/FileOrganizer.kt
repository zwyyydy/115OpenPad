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
    enum class Phase { SCAN, MOVE }

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
        val summary: String? = null,
        val finishedAt: Long = 0L,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

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
                val items = listAll(cid)
                touched.add(cid)
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
                    } else {
                        byCategory[FileClassifier.indexOf(item.fn)].add(item)
                    }
                }
                _progress.value = _progress.value.copy(
                    scannedDirs = _progress.value.scannedDirs + 1,
                    foundFiles = _progress.value.foundFiles + items.count { !it.isDir },
                )
                delay(LIST_INTERVAL_MS)
            }

            val toMove = byCategory.sumOf { it.size }
            if (toMove == 0) {
                releaseCache(touched)
                finish("目录已经很整洁：没有需要归类的文件")
                return
            }

            // ---- ② 建缺的分类目录（只为有文件的分类建，空类不建空文件夹）----
            for (idx in byCategory.indices) {
                coroutineContext.ensureActive()
                checkStop()
                if (byCategory[idx].isEmpty() || targetCids[idx] != null) continue
                val resp = api.addFolder(rootCid, FileClassifier.CATEGORIES[idx])
                val cid = if (resp.envOk()) resp.envData()?.optStr("file_id") else null
                if (cid != null) {
                    targetCids[idx] = cid
                    touched.add(cid)
                    _progress.value = _progress.value.copy(createdDirs = _progress.value.createdDirs + 1)
                } else {
                    // 建不出来就把这一类整个留在原地：硬塞进别的类才是事故
                    _progress.value = _progress.value.copy(failed = _progress.value.failed + byCategory[idx].size)
                    byCategory[idx].clear()
                }
                delay(WRITE_INTERVAL_MS)
            }

            // ---- ③ 按类分批移动：一批一次请求，批间限速 ----
            _progress.value = _progress.value.copy(phase = Phase.MOVE, totalToMove = toMove)
            for (idx in byCategory.indices) {
                val to = targetCids[idx] ?: continue
                for (batch in byCategory[idx].chunked(MOVE_BATCH)) {
                    coroutineContext.ensureActive()
                    checkStop()
                    val ids = batch.mapNotNull { it.fid }
                    if (ids.isEmpty()) continue
                    val ok = runCatching { api.moveFiles(ids.joinToString(","), to) }
                        .getOrNull()?.envOk() == true
                    _progress.value = _progress.value.copy(
                        movedFiles = _progress.value.movedFiles + if (ok) ids.size else 0,
                        failed = _progress.value.failed + if (ok) 0 else ids.size,
                    )
                    delay(WRITE_INTERVAL_MS)
                }
            }

            releaseCache(touched)
            val p = _progress.value
            val parts = breakdown(byCategory)
            finish(
                buildString {
                    append("分类整理完成：移动 ${p.movedFiles} 项")
                    if (p.createdDirs > 0) append("，新建 ${p.createdDirs} 个文件夹")
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

    /** 按分类下标拼"视频 12 · 图片 3"这样的汇总（空的类不出场）；opLog 与结束文案共用 */
    private fun breakdown(byCategory: Array<MutableList<FileItem>>): String =
        byCategory.withIndex()
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
            delay(LIST_INTERVAL_MS)
        }
        return out
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val MAX_ITEMS_PER_DIR = 20_000
        private const val LIST_INTERVAL_MS = 120L
        private const val WRITE_INTERVAL_MS = 300L
        private const val MOVE_BATCH = 50
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
