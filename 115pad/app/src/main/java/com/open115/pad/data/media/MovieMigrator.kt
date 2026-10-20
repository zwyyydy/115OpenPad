package com.open115.pad.data.media

import com.open115.pad.data.DirCache
import com.open115.pad.data.FileItem
import com.open115.pad.data.FilesPage
import com.open115.pad.data.OpLog
import com.open115.pad.data.OpType
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.listDirPaged
import com.open115.pad.data.optStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 影片迁移：把作品从当前媒体库**搬到另一个媒体库**，云端文件真的跟着搬。单部与批量共用这一份实现。
 *
 * 整件事的关键在于"搬完之后本地几乎不用改"：
 *
 * ① `ufile/move` 只认 file_id，而 **file_id 与 pick_code 移动后都不变**（见 `PinnedPrefs` 的注释：
 *    文件夹改名或被移动后 fid 不变；只有重传/替换才换 pick_code）。所以海报、背景、剧照、
 *    演员头像、视频预览条、弹幕、观影历史、云端续播进度**全都按 pick_code 挂着，一条都不用动**。
 * ② `movies` 表**没有 libraryId 列**，库归属是拿 `dirPath` 前缀推出来的（见 `byLibraryPath`）。
 *    于是只要让这一行的 `dirPath` 变成目标库下的路径，它就"自己"换库了。
 * ③ 目标是 `mediaKey` 主键的 `@Upsert`，搬完让**扫描器在新位置重扫一次**就能把这一行原地改写
 *    （dirPath / dirCid / sourceDirKey 一起更新）—— 不用手工改行、不用先删旧行，也就不会丢
 *    观影历史、不会出现两行。
 *
 * 顺序永远是：**校验 → 云端搬 → 认回新位置 → 作废旧状态 → 排一次定向重扫 → 等那一行被认回来**。
 * 云端那一步失败就到此为止，本地一个字都不动（与"彻底删除"同一条原则）。
 *
 * 两种搬法（判据见 [planMigration]）：
 * - **[MigrationPlan.MoveDir]** 作品独占一个目录：把**整个文件夹**搬走，一个请求，
 *   视频/nfo/海报/剧照/`<前缀>-extrafanart/` 全跟着走。
 * - **[MigrationPlan.BuildDir]** 它和别的片子挤在同一个目录（或者干脆摆在库根上）：
 *   在目标位置建一个属于它的文件夹，再按 [movieGroupOf] 把它那一组文件（正片含各张盘 + 同名
 *   nfo/海报/背景 + 专属剧照目录）搬进去。共享的 `poster.jpg`/`.actors/` 前缀对不上，
 *   天然不会被带走 —— 这是"多片目录"下唯一安全的搬法。
 */
class MediaMigrator(
    private val api: OpenApi,
    private val dao: MediaDao,
    private val scanner: MediaScanner,
    private val queue: MigrationQueue,
    private val opLog: OpLog? = null,
    private val dirCache: DirCache? = null,
    private val scope: CoroutineScope,
) {

    /**
     * 批量迁移的进度。
     *
     * [moved] 把 `Pending` 也算进去：那种情况云端确实搬好了（只是索引还在排队），
     * 报成"搬成功"才是事实；索引稍后更新这件事由 [notes]/[summary] 说明。
     */
    data class Progress(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val moved: Int = 0,
        /** 前置校验拒绝的（云端没动），与 [failed] 分开报：一个是"不能搬"，一个是"没搬成" */
        val skipped: Int = 0,
        val failed: Int = 0,
        val currentTitle: String = "",
        val targetName: String = "",
        /** 逐条失败/跳过的原因，界面上列出来（最多 [MAX_NOTES] 条，别把卡片撑爆） */
        val notes: List<String> = emptyList(),
        val summary: String? = null,
        /** 结束时刻（UI 拿它做"这条结果我报过没有"的判据；0 = 还没结果） */
        val finishedAt: Long = 0L,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var stopRequested = false
    private var worker: Job? = null

    /** 分级限速的请求计数（一批任务共用） */
    private var apiCalls = 0

    private val _activeTaskId = MutableStateFlow<Long?>(null)

    /** 正在跑的是哪一批（界面用它区分"排队中"和"迁移中"） */
    val activeTaskId: StateFlow<Long?> = _activeTaskId.asStateFlow()

    /**
     * 入队一批迁移。**立刻返回** —— 真正干活的是 worker（跑在应用级 [scope] 上，切页不断、重启续跑）。
     *
     * 为什么要落盘再跑：中途被杀（或用户强杀）之后，没跑完的那些还得能接着跑。
     * 每一部落盘一次状态，[MigrationItem.fromDirPath] 就是断点判据。
     */
    suspend fun enqueue(
        mediaKeys: List<String>,
        target: MediaLibraryEntity,
        rootIndex: Int,
        subDirCid: String? = null,
        subPath: String = "",
    ): Long? {
        val keys = mediaKeys.distinct()
        if (keys.isEmpty()) return null
        val items = keys.map { key ->
            val row = dao.movie(key)
            MigrationItem(
                mediaKey = key,
                title = row?.title.orEmpty().ifBlank { key },
                fromDirPath = row?.dirPath.orEmpty(),
            )
        }
        val id = queue.enqueue(
            items = items,
            targetLibraryId = target.id,
            targetName = target.name,
            rootIndex = rootIndex,
            subDirCid = subDirCid,
            subPath = subPath,
        )
        ensureWorkerStarted()
        return id
    }

    /**
     * 单部迁移：就地跑、就地报结果（详情页与"海报墙上只勾了一部"都走这里）。
     *
     * 与批量共用 [migrateOne]，区别只在于**不经过 worker**（用户就在等这一部，不该排到别人后面），
     * 于是它也不会自己产生迁移记录 —— 所以跑完要在这里补一条（[MigrationQueue.recordFinished]）。
     * 不补的话用户单迁一部，去「迁移记录」里一个字都查不到（用户报的）。
     *
     * 成败都记：没搬成的那些（拒绝/失败）同样是他要知道的结果。
     */
    suspend fun migrateSingle(
        mediaKey: String,
        target: MediaLibraryEntity,
        rootIndex: Int,
        subDirCid: String? = null,
        subPath: String = "",
        title: String = "",
    ): MigrationResult {
        val row = dao.movie(mediaKey)
        val name = title.ifBlank { row?.title.orEmpty().ifBlank { mediaKey } }
        val result = migrateOne(mediaKey, target, rootIndex, subDirCid, subPath)
        val status = when (result) {
            is MigrationResult.Ok, is MigrationResult.Pending -> MigrationItemStatus.DONE
            is MigrationResult.Refused -> MigrationItemStatus.SKIPPED
            is MigrationResult.CloudFailed -> MigrationItemStatus.FAILED
        }
        val message = when (result) {
            is MigrationResult.Ok -> result.renamedTo?.let { "目标里已有同名目录，115 改名为「$it」" }
            is MigrationResult.Pending -> "云端已搬好，索引稍后更新"
            is MigrationResult.Refused -> result.reason
            is MigrationResult.CloudFailed -> result.message
        }
        // 记录里那句话与批量收尾的汇总同一套措辞（同一件事在界面上就该说成一样）
        val note = when (result) {
            is MigrationResult.Ok -> "已迁移 1 部到「${target.name}」"
            is MigrationResult.Pending -> "已迁移 1 部到「${target.name}」（索引稍后更新）"
            is MigrationResult.Refused -> "没搬：${result.reason}"
            is MigrationResult.CloudFailed -> "失败：${result.message}"
        }
        queue.recordFinished(
            items = listOf(
                MigrationItem(
                    mediaKey = mediaKey,
                    title = name,
                    fromDirPath = row?.dirPath.orEmpty(),
                    status = status.name,
                    message = message,
                ),
            ),
            targetLibraryId = target.id,
            targetName = target.name,
            rootIndex = rootIndex,
            subDirCid = subDirCid,
            subPath = subPath,
            note = note,
        )
        runCatching {
            val counters = when (status) {
                MigrationItemStatus.DONE -> "ok=1"
                MigrationItemStatus.SKIPPED -> "skip=1"
                else -> "fail=1"
            }
            opLog?.log(
                OpType.MOVE,
                "迁移 1 部到「${target.name}」",
                counters + (message?.let { " $it" } ?: ""),
                target.rootCids.firstOrNull(),
                target.rootPaths.firstOrNull(),
            )
        }
        return result
    }

    /** 用户点了「停止」：跑完当前这一部就收尾（cancel 会把收尾与记录一起杀掉） */
    fun stop() {
        stopRequested = true
    }

    /** 记录里点「继续」：把暂停的那一批放回队列 */
    suspend fun resume(taskId: Long) {
        queue.setPaused(taskId, paused = false)
        ensureWorkerStarted()
    }

    /** 记录里点「重试失败」：失败项退回待办，重新跑（成功/跳过的不动） */
    suspend fun retryFailed(taskId: Long) {
        queue.retryFailed(taskId)
        ensureWorkerStarted()
    }

    /**
     * 把 worker 拉起来（幂等）。App 启动时也调它一次 —— 这就是"重启后自动续跑":
     * 队列从盘上读回来，未结束的那一批会被接着跑完。
     */
    fun ensureWorkerStarted() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            queue.ensureLoaded()
            while (true) {
                // 等到"有活可干"：有可跑的，或者有跑完了没标结束的（上次在最后一条被杀）
                val tasks = queue.tasks.first { list ->
                    nextRunnableMigration(list) != null || list.any { migrationTaskNeedsFinish(it) }
                }
                tasks.firstOrNull { migrationTaskNeedsFinish(it) }?.let {
                    queue.finish(it.id, note = "上次中断在最后一条，已收尾")
                    return@let
                }
                val task = nextRunnableMigration(tasks) ?: continue
                runTask(task)
            }
        }
    }

    /**
     * 取一次"刚结束那一轮"的汇总，取完就清 —— 界面拿它做"这条结果我报过没有"的一次性消费。
     *
     * 为什么要消费掉：海报墙每次进出都是一个新组合，`LaunchedEffect(finishedAt)` 会重跑；
     * 不消费的话换个库再进墙会把同一句话再弹一遍。
     */
    fun takeSummary(): String? {
        val s = _progress.value.summary ?: return null
        _progress.value = _progress.value.copy(summary = null)
        return s
    }

    /**
     * 搬一部。单部迁移直接 await 这个（界面自己报结果），批量在 [run] 里逐条调它。
     *
     * [subDirCid] / [subPath] 是"落到目标库的哪一级子目录"：不传就落在库根；传了就以那个子目录
     * 的 cid 为目标、[subPath] 是它**相对库根**的路径（拼 dirPath 用，见 [joinPath]）。
     */
    suspend fun migrateOne(
        mediaKey: String,
        target: MediaLibraryEntity,
        rootIndex: Int,
        subDirCid: String? = null,
        subPath: String = "",
    ): MigrationResult {
        // ---- 前置校验：能在动云端之前拒绝的，全放在最前面 ----
        val rows = dao.migrationRowsOf(mediaKey)
        val self = rows.firstOrNull { it.mediaKey == mediaKey }
            ?: return MigrationResult.Refused("索引里已经找不到这部片了，刷新一下再看")
        val srcPath = self.dirPath.orEmpty()
        val srcCid = self.dirCid.orEmpty()
        val isLibraryRoot = srcCid.isNotBlank() &&
            srcCid in dao.libraries().first().flatMap { it.rootCids }
        val plan = planMigration(
            mediaKey = mediaKey,
            self = self,
            rows = rows,
            underRows = dao.migrationRowsUnderPath(srcPath),
            isLibraryRoot = isLibraryRoot,
        )
        if (plan is MigrationPlan.Refuse) return MigrationResult.Refused(plan.reason)

        val dstRootCid = target.rootCids.getOrNull(rootIndex).orEmpty()
        val dstRootPath = target.rootPaths.getOrNull(rootIndex).orEmpty()
        if (dstRootCid.isBlank()) return MigrationResult.Refused("目标库没有可用的根目录")
        val dstCid = subDirCid ?: dstRootCid
        val dstPath = joinPath(dstRootPath, subPath)
        if (dstCid == srcCid) return MigrationResult.Refused("目标就是这部片现在待的目录，没地方可搬")

        // 列一次目标目录：建夹时要避开重名，也顺便把"列不出来"尽早报掉
        val before = listing(dstCid)
            ?: return MigrationResult.CloudFailed("列不出目标目录（网络或权限），云端未移动")

        var landedDirCid: String
        var landedName: String
        /** 期望的落地名字：与它不一致 = 115 因撞名给我们改了名（要跟用户说一声） */
        var expectedName: String
        /** 源目录 cid（MoveDir 时它是被搬走的那个，BuildDir 时是还留在原地的那个） */
        val srcDirCid: String

        when (plan) {
            is MigrationPlan.MoveDir -> {
                srcDirCid = plan.dirCid
                expectedName = plan.dirName
                val moved = runCatching { api.moveFiles(plan.dirCid, dstCid) }.getOrNull()
                    ?: return MigrationResult.CloudFailed("网络异常，云端未移动")
                if (!moved.envOk()) return MigrationResult.CloudFailed(moved.envMsg() ?: "云端移动失败")
                // 认回新位置：115 是异步的，看不到就等一会儿再看。
                // 按 **file_id** 认而不是按名字：撞名时 115 会给它改名，名字已经不是原来的了，id 才是它自己
                var item: FileItem? = null
                for (wait in LANDING_WAITS_MS) {
                    delay(wait)
                    item = listing(dstCid)?.items?.firstOrNull { it.fid == plan.dirCid }
                    if (item != null) break
                }
                val name = item?.fn
                if (name.isNullOrBlank()) {
                    // 看不见也不硬等：排一次目标库的正常扫描，它按父目录的指纹/时间自己会发现新目录
                    dao.deleteScanStateInPath(srcPath)
                    dao.invalidateScanState(dstCid)
                    return MigrationResult.Pending(target.name, dstPath)
                }
                landedDirCid = plan.dirCid
                landedName = name
            }

            is MigrationPlan.BuildDir -> {
                srcDirCid = plan.srcDirCid
                val srcItems = listing(plan.srcDirCid)?.items
                    ?: return MigrationResult.CloudFailed("列不出源目录（网络或权限），云端未移动")
                val videoName = dao.movie(mediaKey)?.videoName
                val anchor = srcItems.firstOrNull { !it.isDir && videoName != null && it.fn == videoName }
                    ?: return MigrationResult.Refused("在源目录里找不到这部片的视频文件（索引可能过期了），先重扫一次这个库再迁")
                val group = movieGroupOf(anchor)
                    ?: return MigrationResult.Refused("认不出这部片的文件分组（文件名太特殊），先给它单独建个文件夹再迁")
                val members = srcItems.filter { movieGroupOf(it) == group }.mapNotNull { it.fid }.distinct()
                if (members.isEmpty()) return MigrationResult.Refused("这部片的文件在目录里一个都没认出来，先重扫一次这个库再迁")

                val folderName = uniqueFolderName(group, before.items.filter { it.isDir }.map { it.fn })
                val created = runCatching { api.addFolder(dstCid, folderName) }.getOrNull()
                val newCid = created?.envData()?.optStr("file_id")?.takeIf { it.isNotBlank() }
                    // 建夹失败多半是刚才那一列之后有人抢先建了同名目录：再列一次认回来
                    ?: listing(dstCid)?.items?.firstOrNull { it.isDir && it.fn == folderName }?.fid
                    ?: return MigrationResult.CloudFailed(created?.envMsg() ?: "在目标位置建文件夹失败")
                val moved = runCatching { api.moveFiles(members.joinToString(","), newCid) }.getOrNull()
                    ?: return MigrationResult.CloudFailed("网络异常，云端未移动")
                if (!moved.envOk()) return MigrationResult.CloudFailed(moved.envMsg() ?: "云端移动失败")
                // 等内容可见：新夹里能看到这部片的视频（按 fid）再让扫描器去扫 —— 免得它扫到一个空目录
                val vidFid = anchor.fid
                var visible = false
                for (wait in LANDING_WAITS_MS) {
                    delay(wait)
                    if (listing(newCid)?.items?.any { it.fid != null && it.fid == vidFid } == true) {
                        visible = true
                        break
                    }
                }
                if (!visible) {
                    dao.invalidateScanState(plan.srcDirCid)
                    dao.invalidateScanState(dstCid)
                    return MigrationResult.Pending(target.name, dstPath)
                }
                landedDirCid = newCid
                landedName = folderName
                expectedName = folderName
            }

            is MigrationPlan.Refuse -> return MigrationResult.Refused(plan.reason)
        }

        val landedPath = joinPath(dstPath, landedName)

        // ---- 本地：作废旧状态 + 排一次定向重扫 ----
        // MoveDir 的旧目录已经整个没了（连它子目录的状态行都成了垃圾，一次清掉）；
        // BuildDir 的源目录还在、而且还有别的片，只能抹它自己那一条
        if (plan is MigrationPlan.MoveDir) dao.deleteScanStateInPath(srcPath) else dao.invalidateScanState(srcDirCid)
        dao.invalidateScanState(dstCid)
        dirCache?.invalidateDir(dstCid)
        dirCache?.invalidateDir(srcDirCid)
        // ★ `deep = true` 是必须的，别改成"把子目录一个个排进定向任务"：
        //   剧集的分集行在 `Season 1/` 这种子目录里，定向任务默认只扫给它的那一个目录 →
        //   分集行的 dirPath 会留在旧位置（卡片看着搬过来了，点进分集却指着已经不存在的路径，
        //   下一次迁移还会因为"行不在自己的目录下"被直接拒掉）。
        //   而把子目录当独立任务排进去同样不行：seriesKey 靠 ancestorPaths 往上找祖先行解析，
        //   它拿这一趟的 rootPath 当下界，给子目录传它自己的 path 就往上爬不动，分集会全部散成独立卡片。
        //   唯一正确的形状是"从作品顶层目录往下递归"—— 两种错法都是真机实测踩出来的。
        scanner.enqueueTargeted(
            target, listOf(landedDirCid to landedPath),
            withSideArt = true, deep = true,
            // 一部一个定向重扫，用户正对着迁移进度卡看 —— 跑完别再弹"「目标库」本次新增 1 部"，
            // 迁十部就是十次打断（见 QueuedScan.silent）
            silent = true,
        )

        // 等那一次重扫真的把这一行认回来（判据就是行的 dirPath 变成了新位置）。
        // 等到它，"迁移完成"才名副其实 —— 界面刷新一下，旧库里就看不见这张卡了。
        val deadline = System.currentTimeMillis() + RESCAN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (dao.movie(mediaKey)?.dirPath == landedPath) {
                return MigrationResult.Ok(
                    libraryName = target.name,
                    landedPath = landedPath,
                    renamedTo = landedName.takeIf { !it.equals(expectedName, ignoreCase = true) },
                )
            }
            delay(300)
        }
        return MigrationResult.Pending(target.name, landedPath)
    }

    // ---------------- worker：把一批跑完（可中断、可续跑） ----------------

    private suspend fun runTask(task: MigrationTask) {
        val target = dao.library(task.targetLibraryId)
        if (target == null) {
            queue.finish(task.id, note = "目标库已经删掉了，这批没跑")
            return
        }
        stopRequested = false
        apiCalls = 0
        _activeTaskId.value = task.id
        val notes = task.items.filter { it.st != MigrationItemStatus.PENDING }
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() }?.let { m -> "「${it.title}」$m" } }
            .takeLast(MAX_NOTES)
            .toMutableList()
        _progress.value = Progress(
            running = true,
            total = task.total,
            done = task.doneCount,
            moved = task.succeeded,
            skipped = task.skipped,
            failed = task.failed,
            targetName = task.targetName,
            notes = notes.toList(),
        )
        var interrupted = false
        for (item in task.items) {
            // 这一部已经处理过了（上批剩下的、或续跑时已完成）：跳过，不重复落状态
            if (item.st != MigrationItemStatus.PENDING) continue
            if (stopRequested) {
                interrupted = true
                break
            }
            _progress.value = _progress.value.copy(currentTitle = item.title)
            // ★ 断点续跑：入队时记的目录和现在的对不上 = 上次确实搬过（云端搬完、扫描器也把行认回来了）。
            //   直接标成功 —— 不然会对一个已经不存在的源目录再发一次移动，报一堆假的"失败"。
            val nowPath = dao.movie(item.mediaKey)?.dirPath.orEmpty()
            if (item.fromDirPath.isNotBlank() && nowPath.isNotBlank() && nowPath != item.fromDirPath) {
                queue.setItemStatus(task.id, item.mediaKey, MigrationItemStatus.DONE, "续跑：上次已经搬好")
                bumpProgress()
                continue
            }
            val r = try {
                migrateOne(item.mediaKey, target, task.rootIndex, task.subDirCid, task.subPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MigrationResult.CloudFailed(e.message ?: "未知错误")
            }
            when (r) {
                is MigrationResult.Ok ->
                    queue.setItemStatus(task.id, item.mediaKey, MigrationItemStatus.DONE)

                is MigrationResult.Pending ->
                    queue.setItemStatus(
                        task.id, item.mediaKey, MigrationItemStatus.DONE, "云端已搬好，索引稍后更新",
                    )

                is MigrationResult.Refused -> {
                    queue.setItemStatus(task.id, item.mediaKey, MigrationItemStatus.SKIPPED, r.reason)
                    if (notes.size < MAX_NOTES) notes += "「${item.title}」没搬：${r.reason}"
                }

                is MigrationResult.CloudFailed -> {
                    queue.setItemStatus(task.id, item.mediaKey, MigrationItemStatus.FAILED, r.message)
                    if (notes.size < MAX_NOTES) notes += "「${item.title}」失败：${r.message}"
                }
            }
            bumpProgress()
        }
        val live = queue.snapshot().firstOrNull { it.id == task.id }
        val remaining = live?.items?.count { it.st == MigrationItemStatus.PENDING } ?: 0
        // 拼接里带条件的段一律走 listOfNotNull：字符串里的 if 会把后面几段一起吞掉（踩过的坑）
        val summary = listOfNotNull(
            "已迁移 ${live?.succeeded ?: 0} 部到「${task.targetName}」",
            if ((live?.skipped ?: 0) > 0) "${live?.skipped} 部没搬（前置校验没过）" else null,
            if ((live?.failed ?: 0) > 0) "${live?.failed} 部失败" else null,
            if (remaining > 0) "还剩 $remaining 部（迁移记录里可以继续）" else null,
        ).joinToString(" · ")
        if (remaining > 0) {
            // 用户停的：保留剩余项，等他到迁移记录里点「继续」（重启也不会自己跑起来）
            queue.setPaused(task.id, paused = true, note = summary)
        } else {
            queue.finish(task.id, note = summary)
        }
        _progress.value = _progress.value.copy(
            running = false,
            currentTitle = "",
            notes = notes.toList(),
            summary = summary,
            finishedAt = System.currentTimeMillis(),
        )
        _activeTaskId.value = null
        runCatching {
            opLog?.log(
                OpType.MOVE,
                "迁移 ${live?.succeeded ?: 0} 部到「${task.targetName}」",
                listOfNotNull(
                    "ok=${live?.succeeded ?: 0}",
                    if ((live?.skipped ?: 0) > 0) "skip=${live?.skipped}" else null,
                    if ((live?.failed ?: 0) > 0) "fail=${live?.failed}" else null,
                    if (interrupted) "stopped" else null,
                ).joinToString(" "),
                target.rootCids.firstOrNull(),
                target.rootPaths.firstOrNull(),
            )
        }
    }

    /** 逐条落库之后刷新一次进度（数字全从队列里现取，免得内存里另记一份对不上） */
    private fun bumpProgress() {
        val task = queue.snapshot().firstOrNull { it.id == _activeTaskId.value } ?: return
        _progress.value = _progress.value.copy(
            running = true,
            total = task.total,
            done = task.doneCount,
            moved = task.succeeded,
            skipped = task.skipped,
            failed = task.failed,
        )
    }

    // ---------------- 底层小件 ----------------

    /** 列一个目录的全部条目；失败返回 null（调用方按"没列出来"处理，别把异常吞成空表） */
    private suspend fun listing(cid: String): FilesPage? {
        throttle()
        return runCatching { listDirPaged(api, cid) }.getOrNull()
    }

    /**
     * 分级限速：前 10 次请求 300ms、11~20 次 1000ms、之后 2000ms（与「分类整理」同一套档位）。
     *
     * 批量迁移是没人看着的长任务，所以走不挂全局限速的那份接口（`bulkOpenApi`）+ 自带节拍，
     * 免得把前台的文件页/详情页也一起拖慢 —— 两套时钟的取舍写在 `ApiRateLimiter` 的类注释里。
     */
    private suspend fun throttle() {
        val wait = when {
            apiCalls < 10 -> 300L
            apiCalls < 20 -> 1000L
            else -> 2000L
        }
        apiCalls++
        if (wait > 0) delay(wait)
    }
}

/** 搬完等目标目录出现（/ 内容可见）的节奏：300ms 起，最多再等 3 秒 */
private val LANDING_WAITS_MS = longArrayOf(300, 700, 1500, 3000)

/** 搬完等目标库那条重扫把这一行认回来，最多等这么久（超时就如实说"索引稍后更新"） */
private const val RESCAN_WAIT_MS = 20_000L

/** 进度卡上最多列几条失败/跳过的原因（再多就把卡片撑爆了，剩下的看操作记录） */
private const val MAX_NOTES = 4

/**
 * 迁移的结果。前两种是"云端动了"，后两种是"没动"—— 界面报账必须分得清。
 */
sealed interface MigrationResult {
    /**
     * 搬完且索引已经在目标库里认回来了（这一行现在指向新位置）。
     * [renamedTo] 非空 = 目标里本来就有同名目录，115 自动改了名（不是错，但要说一声）。
     */
    data class Ok(val libraryName: String, val landedPath: String, val renamedTo: String?) : MigrationResult

    /** 云端搬好了，但目标库的重扫还排在队里 —— 扫完它自己就换库，这里如实说明 */
    data class Pending(val libraryName: String, val landedPath: String) : MigrationResult

    /** 前置校验没过：云端一个字节都没动，用户换一下再来就行 */
    data class Refused(val reason: String) : MigrationResult

    /** 云端那一步失败（网络/接口拒绝）：本地没动，可以原样重试 */
    data class CloudFailed(val message: String) : MigrationResult
}

/**
 * 这次迁移打算怎么搬 —— 纯判据，单独提出来是为了能直接跑单测（真跑一遍要动云盘）。
 */
internal sealed interface MigrationPlan {
    /** 作品独占这个目录：整个文件夹搬走（最省事，视频/nfo/图/子目录全跟着） */
    data class MoveDir(val dirCid: String, val dirName: String) : MigrationPlan

    /** 目录里还有别的作品（或它就摆在库根上）：在目标建个属于它的文件夹，只搬它那一组文件 */
    data class BuildDir(val srcDirCid: String) : MigrationPlan

    data class Refuse(val reason: String) : MigrationPlan
}

/**
 * 决定"怎么搬"。判据顺序是有讲究的：
 *
 * ① 索引里没记目录 → 拒（升级前的老数据）；
 * ② 作品的行必须**全在**它自己的目录里 —— 散在多处的（元数据与视频分家那种）搬一半会留半套；
 * ③ 目录里没有别的作品、又不是库根 → [MigrationPlan.MoveDir] 整搬；
 * ④ 否则走 [MigrationPlan.BuildDir]；但**有行落在子目录里的不行**（剧集那种）—— 共享目录下
 *    只挑得出"当前这一层里属于它的文件"，子目录里的分集挑不到，得先整理成独立文件夹。
 *
 * ★ 库根那道判据不能省：库根上落的片，[underRows] 里全是同库的邻居，看着像"共享目录"，
 *   但它和普通共享目录不一样 —— 整搬会把整个库搬走。这里靠 [isLibraryRoot] 把它挡在 MoveDir 之外。
 */
internal fun planMigration(
    mediaKey: String,
    self: MigrationRow,
    rows: List<MigrationRow>,
    underRows: List<MigrationRow>,
    isLibraryRoot: Boolean,
): MigrationPlan {
    val srcCid = self.dirCid.orEmpty()
    val srcPath = self.dirPath.orEmpty()
    if (srcCid.isBlank() || srcPath.isBlank()) {
        return MigrationPlan.Refuse("这条索引是升级前的旧数据、没记云盘目录，先重扫一次这个库再迁")
    }
    val outside = rows.count { r ->
        val p = r.dirPath.orEmpty()
        p.isBlank() || !(p == srcPath || p.startsWith("$srcPath/"))
    }
    if (outside > 0) {
        return MigrationPlan.Refuse("这部片有 $outside 行挂在别的目录上（元数据/视频分家那种老数据），先重扫整理再迁")
    }
    val others = underRows.count { it.mediaKey != mediaKey && it.seriesKey != mediaKey }
    if (others == 0 && !isLibraryRoot) {
        val name = srcPath.trimEnd('/').substringAfterLast('/')
        if (name.isBlank()) return MigrationPlan.Refuse("认不出这部片所在文件夹的名字，没法确认搬什么")
        return MigrationPlan.MoveDir(srcCid, name)
    }
    val inSubdirs = rows.count { r ->
        val p = r.dirPath.orEmpty()
        p.isNotBlank() && p != srcPath && p.startsWith("$srcPath/")
    }
    if (inSubdirs > 0) {
        return MigrationPlan.Refuse(
            "这部作品有 $inSubdirs 行在子目录里（剧集那种），共享目录下搬不齐；先把它整理成独立文件夹再迁",
        )
    }
    return MigrationPlan.BuildDir(srcCid)
}

/** 拼云盘路径：库根那一段**原样保留**（它的写法就是库的写法，可能带前导斜杠），后面逐段接上 */
internal fun joinPath(root: String, vararg rest: String): String {
    val head = root.trimEnd('/')
    val tail = rest.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    return (listOf(head) + tail).filter { it.isNotEmpty() }.joinToString("/")
}

/**
 * 在目标位置挑一个没被占用的文件夹名：先试原名，撞了就 `原名（1）`、`（2）`……
 * （与 115 自己"同名移入自动加序号"的写法一致，用户不会看到两套命名风格）
 */
internal fun uniqueFolderName(base: String, taken: Collection<String>): String {
    val clean = base.trim().ifEmpty { "迁移影片" }
    val used = taken.map { it.lowercase() }.toHashSet()
    if (clean.lowercase() !in used) return clean
    for (n in 1..99) {
        val cand = "$clean（$n）"
        if (cand.lowercase() !in used) return cand
    }
    return "$clean（${System.currentTimeMillis() % 10000}）"
}
