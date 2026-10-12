package com.open115.pad.data.media.scrape

import android.graphics.BitmapFactory
import com.open115.pad.data.FileItem
import com.open115.pad.data.OpenApi
import com.open115.pad.data.TransferLog
import com.open115.pad.data.Uploader
import com.open115.pad.data.envData
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieEntity
import com.open115.pad.data.media.PER_MOVIE_FANART_SUFFIX
import com.open115.pad.data.media.fileBasePrefix
import com.open115.pad.data.media.isSideArtDirName
import com.open115.pad.data.media.isVideoFileName
import com.open115.pad.data.media.libraryForPath
import com.open115.pad.data.media.looksLikeTvDirectory
import com.open115.pad.data.media.movieGroupOf
import com.open115.pad.data.media.parseEpisodeNumber
import com.open115.pad.data.optStr
import com.open115.pad.data.parseFilesResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 媒体库详情页的准备：把一个 DB 行映射成 [ScrapePlan]（纯映射，无 IO）。
 *
 * 覆盖提示只在**这条已经有 nfo** 时出现 —— 详情页里用户显式点「刮削」才算确认覆盖
 * （批量任务走的是"只补空"，候选集本身就排除了已有 nfo 的片）。
 */
fun scrapePlanOfMovie(movie: MovieEntity): ScrapePlan = ScrapePlan(
    key = movie.mediaKey,
    title = movie.title,
    year = movie.year,
    videoName = movie.videoName.orEmpty(),
    dirCid = movie.dirCid.orEmpty(),
    dirPath = movie.dirPath.orEmpty(),
    isEpisodeLike = movie.isEpisodeLike,
    oldNfoFid = movie.nfoFid,
    overwriteNote = if (!movie.nfoPickCode.isNullOrBlank()) {
        "注意：这部片已经有 nfo（多半是服务器流水线刮的），写入会用新内容覆盖它（旧文件移入回收站，可还原）。"
    } else {
        null
    },
    inLibrary = true,
)

/**
 * 元数据刮削的**宿主侧编排**：搜索 → 候选 → 写回云盘（nfo + 海报）→ 作废扫描状态 → 定向重扫。
 *
 * 分工（这就是"插件只输出数据、宿主拥有一切 IO"的落点）：
 *  - 源（[MetadataSource]）只说"搜到什么、这条片的元数据是什么"；
 *  - 清理旧文件、上传、失效缓存、排重扫、把结果认回库里 —— 全在这里，跑在应用级 scope 上，
 *    用户切页/锁屏都不会中断（与查重、预览图那批后台任务同一套骨架，进度汇进 taskActivity）。
 *
 * 写回的两条硬规则（**只补空** 与 **人工确认**）不在这个类里强制 —— 它们是界面的默认行为：
 * 批量任务的候选集就排除了"已有 nfo"的片；覆盖只发生在详情页里用户显式点的那一条。
 */
class MetadataScraper(
    private val api: OpenApi,
    private val transferLog: TransferLog,
    private val dao: MediaDao,
    private val scanner: MediaScanner,
    /** 第三方 HTTP（下载海报/缩略图）。必须是**不带 115 鉴权**的独立 client（见 App115 的注释） */
    private val http: OkHttpClient,
    /** 当前配置出来的源；没配（没填 key）时返回 null */
    private val source: () -> MetadataSource?,
    /** 剧照张数上限（设置 → 实验室；0 = 不限制，源给多少存多少）。写回时现读 */
    private val stillsMax: suspend () -> Int = { EXTRAFANART_MAX },
    private val scope: CoroutineScope,
) {

    /** 汇进 taskActivity 的进度（红点 / 前台服务保活 / 完成提示都读它） */
    data class Progress(
        val running: Boolean = false,
        val label: String = "",
        /** 跑完时的一句话（完成提示的正文）；进行中为 null */
        val summary: String? = null,
        val finishedAt: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    /** 单条写入（详情页/文件页的「刮削」）的状态：界面照着它显示"正在做什么" */
    data class WriteState(
        /** 与 [ScrapePlan.key] 对应（界面靠它认出"这一轮是我发的"） */
        val key: String,
        val title: String,
        val running: Boolean,
        val step: String = "",
        val error: String? = null,
        /** 成功后的新 mediaKey（写完带 tmdbid 的 nfo 后键会从文件前缀变成 `tmdb-<id>`；null = 键不变） */
        val newKey: String? = null,
        /** 本次真的写了 nfo / 海报文件 */
        val wroteNfo: Boolean = false,
        val wrotePoster: Boolean = false,
        /** 背景图（`<前缀>-fanart.jpg`）写上了吗 */
        val wroteFanart: Boolean = false,
        /** 剧照写进了 `extrafanart/` 几张（0 = 没勾或没图） */
        val wroteStills: Int = 0,
        /** 分集写了几个 `episodedetails` nfo（剧集用；0 = 没勾或没匹配） */
        val wroteEpisodes: Int = 0,
        val finishedAt: Long = 0,
    )

    private val _writeState = MutableStateFlow<WriteState?>(null)
    val writeState: StateFlow<WriteState?> = _writeState

    /**
     * 单片搜索（详情页/文件页「刮削」对话框发起）的状态：跑在应用级 scope 上，
     * 慢源搜索期间关掉对话框也继续，跑完走"完成提示"（红点/弹窗/系统通知，与写回同一通道）；
     * 重新打开同一部片的对话框会接上这轮的结果（key 对得上就复用）。
     * 同一个槽只留最新一轮：换检索词/换源再搜会顶掉旧的，被顶掉的那轮搜完也不再写状态。
     */
    data class SearchState(
        /** 与 [ScrapePlan.key] 对应（对话框靠它认出"这一轮是我发的"） */
        val key: String,
        val title: String,
        /** 发起时的检索词（重开对话框回填用） */
        val query: String = "",
        val running: Boolean = false,
        val error: String? = null,
        val results: List<ScrapeCandidate> = emptyList(),
        /** 自动选中的候选下标（autoPick 的结果）；null = 没认出匹配 */
        val autoPicked: Int? = null,
        val finishedAt: Long = 0,
    )

    private val _searchState = MutableStateFlow<SearchState?>(null)
    val searchState: StateFlow<SearchState?> = _searchState

    private var searchJob: Job? = null

    /**
     * 单片刮削对话框正开着。AppRoot 的完成提示分发读它：结果就在眼前时不再另弹窗
     * （对话框关掉它自动归 false —— 挂后台的搜索/写回跑完照常报）。
     */
    @Volatile
    var singleDialogOpen = false

    /**
     * 发起单片搜索：立即置"搜索中"，结果与错误都落在 [searchState] 里。
     * 换检索词重搜时旧候选先留着（重搜期间旧列表还在屏上，搜到再换）。
     */
    fun startSearch(plan: ScrapePlan, queryText: String) {
        val q = queryText.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val carried = _searchState.value
            ?.takeIf { it.key == plan.key && !it.running }
            ?.results
            .orEmpty()
        val mine = SearchState(plan.key, plan.title, query = q, running = true, results = carried)
        _searchState.value = mine
        _progress.update { it.copy(running = true, label = "正在搜索《${plan.title}》", summary = null) }
        searchJob = scope.launch {
            val r = runCatching { search(ScrapeQuery(title = q, year = plan.year, kind = plan.kind)) }
            // 已被新一轮顶掉 / 被清掉：什么都不写（进度已经归新一轮或清空方管）
            if (_searchState.value !== mine) return@launch
            val err = r.exceptionOrNull()
            val list = r.getOrNull().orEmpty()
            _searchState.value = mine.copy(
                running = false,
                error = err?.let { friendly(it) },
                results = list,
                autoPicked = autoPick(list, plan.title, plan.year),
                finishedAt = System.currentTimeMillis(),
            )
            _progress.value = Progress(
                running = false,
                label = "刮削搜索",
                summary = if (err == null) "《${plan.title}》搜索完成，找到 ${list.size} 个候选"
                else "《${plan.title}》搜索失败：${friendly(err)}",
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 清掉单片搜索状态（对话框关闭且这轮已结束 / 换源 / AI 改词时用）；跑着的搜索一并取消 */
    fun clearSearch() {
        val wasRunning = _searchState.value?.running == true
        searchJob?.cancel()
        searchJob = null
        _searchState.value = null
        // 取消会让 startSearch 收尾那笔进度写入跑不到：不补的话红点/保活一直亮着。
        // 批量会话也在用这条进度 —— 它在跑就别去掀它。
        if (wasRunning && _session.value?.let { it.searching || it.writing } != true) {
            _progress.value = Progress(running = false, label = "刮削", summary = null, finishedAt = System.currentTimeMillis())
        }
    }

    // ---------------- 批量「补全缺失元数据」 ----------------

    data class BatchItem(
        val mediaKey: String,
        val title: String,
        val year: Int?,
        val videoName: String?,
        val dirCid: String?,
        val dirPath: String?,
        val isEpisodeLike: Boolean,
        /** 电影还是剧集（文件页多选时按每个条目各自的类型；库批量都是电影） */
        val kind: ScrapeKind = ScrapeKind.MOVIE,
        val candidates: List<ScrapeCandidate> = emptyList(),
        /** 勾选的候选下标；null = 不写这条 */
        val chosen: Int? = null,
        /** 状态行：搜索/写入的阶段文案、错误原因、"已写入" */
        val note: String? = null,
        val written: Boolean = false,
        val error: String? = null,
    )

    data class BatchSession(
        val libraryId: Long,
        val libraryName: String,
        val items: List<BatchItem>,
        val searching: Boolean = false,
        val searchDone: Int = 0,
        val writing: Boolean = false,
        val writeDone: Int = 0,
        val finished: Boolean = false,
        val summary: String? = null,
        /** 批量确认框勾了"同时刮剧照"：写回时每部多刮几张背景图进专属目录 */
        val withStills: Boolean = false,
        /** 多选刮削前的开关：写回时把视频文件改成"规范名"（= 用来搜索的那个 title） */
        val renameToClean: Boolean = false,
    )

    private val _session = MutableStateFlow<BatchSession?>(null)
    val session: StateFlow<BatchSession?> = _session

    private var batchJob: Job? = null

    /** 停止请求：搜索/写入循环在每个条目边界查一次（同一套"标志位而不是 cancel"的做法） */
    @Volatile
    private var stopRequested = false

    /** 当前源的名字（界面文案用）；没配返回 null */
    fun sourceName(): String? = source()?.displayName

    /** 这个库里有多少部"缺元数据"的片（确认框先亮数字，再决定要不要跑） */
    suspend fun countTargets(library: MediaLibraryEntity): Int =
        targetsOf(library).size

    private suspend fun targetsOf(library: MediaLibraryEntity) = library.rootPaths
        .flatMap { p ->
            runCatching { dao.scrapeTargetsInPath(p.trim().trimEnd('/')) }
                .onFailure { android.util.Log.w(TAG, "统计待刮削条目失败（根路径 $p）：界面会误报成「没有需要刮削的片」", it) }
                .getOrDefault(emptyList())
        }
        .distinctBy { it.mediaKey }

    /**
     * 开一轮批量：逐个搜索（自带节流），搜完停在"复核"态 —— **不自动写**，
     * 用户在面板里勾选、必要时改候选，再点「写入选中」。
     *
     * [withStills] = 批量确认框勾的"同时刮剧照"：每部写回时多刮几张背景图进专属目录。
     */
    fun startBatch(library: MediaLibraryEntity, withStills: Boolean = false) {
        if (_session.value != null) return
        stopRequested = false
        batchJob = scope.launch {
            val items = targetsOf(library).map {
                BatchItem(it.mediaKey, it.title, it.year, it.videoName, it.dirCid, it.dirPath, it.isEpisodeLike)
            }
            runSearchSession(library.id, library.name, items, withStills)
        }
    }

    /**
     * 从**显式条目**开一轮批量（文件页多选刮削用）：不来自"库里缺 nfo 的片"，而是用户当场选中的
     * 那些目录/视频。libraryId 传 0（这些条目可能不在任何库、或分属多个库；写回时各自判）。
     * [renameMode]/[renameTemplate]/[aiRename]：多选前先选的改名方式，写回时逐条套用。
     */
    fun startBatchFromItems(
        items: List<BatchItem>,
        libraryName: String,
        withStills: Boolean = false,
        renameToClean: Boolean = false,
    ) {
        if (_session.value != null) return
        stopRequested = false
        batchJob = scope.launch {
            runSearchSession(0L, libraryName, items, withStills, renameToClean)
        }
    }

    /** 建会话 + 起搜索循环（库批量与文件页多选共用）。跑在应用级 scope 上，可关界面后台继续 */
    private suspend fun runSearchSession(
        libraryId: Long,
        libraryName: String,
        items: List<BatchItem>,
        withStills: Boolean,
        renameToClean: Boolean = false,
    ) {
        val src = source()
        _session.value = BatchSession(
            libraryId, libraryName, items, searching = true, withStills = withStills, renameToClean = renameToClean,
        )
        if (items.isEmpty()) {
            _session.update { it?.copy(searching = false, finished = true, summary = "没有需要刮削的条目") }
            _progress.value = Progress(
                running = false, label = "刮削", finishedAt = System.currentTimeMillis(),
                summary = "「$libraryName」没有需要刮削的条目",
            )
            return
        }
        if (src == null) {
            // 没配源：把每条都标清楚，别让面板上的"没有搜到匹配"显得像"搜了但没结果"
            items.indices.forEach { i -> updateItem(i) { it.copy(note = "没配置刮削源") } }
            _session.update {
                it?.copy(searching = false, finished = true, summary = "还没配置刮削源：设置 → 实验室 → 元数据刮削（TMDB）")
            }
            _progress.value = Progress(
                running = false, label = "刮削", finishedAt = System.currentTimeMillis(),
                summary = "没有配置刮削源（TMDB 没填 API Key）",
            )
            return
        }

        _progress.value = Progress(running = true, label = "正在搜索「$libraryName」的 ${items.size} 个条目")
        var stopped = false
        items.forEachIndexed { i, item ->
            if (stopRequested) {
                stopped = true
                return@forEachIndexed
            }
            val cands = runCatching { src.search(queryOf(item.title, item.year, item.kind)) }
                .getOrElse { e ->
                    updateItem(i) { it.copy(note = friendly(e)) }
                    emptyList()
                }
            val pick = autoPick(cands, item.title, item.year)
            updateItem(i) {
                it.copy(
                    candidates = cands,
                    chosen = pick,
                    note = if (cands.isEmpty()) it.note ?: "没有搜到匹配" else null,
                )
            }
            _session.update { it?.copy(searchDone = i + 1) }
        }
        val s = _session.value ?: return
        if (stopped) {
            s.items.forEachIndexed { i, it2 ->
                if (it2.candidates.isEmpty() && it2.note == null) updateItem(i) { it.copy(note = "已停止") }
            }
        }
        _session.update { it?.copy(searching = false) }
        val cur = _session.value ?: return
        val withCands = cur.items.count { it.candidates.isNotEmpty() }
        val noMatch = cur.items.size - withCands
        val summary = buildString {
            if (stopped) append("搜索已停止：") else append("搜索完成：")
            append("${withCands} 部有候选")
            if (noMatch > 0) append("，${noMatch} 部没搜到")
            append("。勾选后点「写入选中」")
        }
        _progress.value = Progress(
            running = false, label = "刮削搜索", summary = summary, finishedAt = System.currentTimeMillis(),
        )
    }

    fun setChosen(index: Int, chosen: Int?) {
        updateItem(index) { it.copy(chosen = chosen, error = null) }
    }

    fun stopBatch() {
        stopRequested = true
        // 立刻结束"正在搜索"态：否则卡在一条挂起的网络调用里，面板一直转圈、开关/按钮都像点不动。
        // 挂起的那条调用超时返回后，循环会因 stopRequested 在下一条边界自行停下（不会误写）。
        _session.update { if (it != null && it.searching && !it.writing) it.copy(searching = false) else it }
    }

    /** 写入选中的条目：逐条 详情 → 下载海报 → 上传 nfo/海报 → 作废扫描状态 → 定向重扫 */
    fun writeSelected() {
        val s = _session.value ?: return
        if (s.searching || s.writing) return
        val picked = s.items.withIndex().filter { it.value.chosen != null && !it.value.written }
        if (picked.isEmpty()) return
        stopRequested = false
        batchJob = scope.launch {
            _session.update { it?.copy(writing = true, writeDone = 0, finished = false, summary = null) }
            _progress.value = Progress(running = true, label = "正在写入 ${picked.size} 部影片的元数据")
            var ok = 0
            var fail = 0
            picked.forEachIndexed { n, (idx, item) ->
                if (stopRequested) return@forEachIndexed
                val cand = item.candidates.getOrNull(item.chosen ?: -1)
                if (cand == null) {
                    fail++
                    updateItem(idx) { it.copy(error = "候选已失效，请重新搜索", chosen = null) }
                } else {
                    val r = runCatching {
                        writeBack(
                            plan = ScrapePlan(
                                key = item.mediaKey,
                                title = item.title,
                                year = item.year,
                                videoName = item.videoName.orEmpty(),
                                dirCid = item.dirCid.orEmpty(),
                                dirPath = item.dirPath.orEmpty(),
                                isEpisodeLike = item.isEpisodeLike,
                                kind = item.kind,
                            ),
                            candidate = cand,
                            // 批量在每条的"重扫该目录"只入队不等：整批跑完统一等一次（队列本来就是串行的）
                            awaitRescan = false,
                            withStills = s.withStills,
                            renameToClean = s.renameToClean,
                            onStep = { step -> updateItem(idx) { it.copy(note = step) } },
                        )
                    }
                    if (r.isSuccess) {
                        ok++
                        val stills = r.getOrNull()?.wroteStills ?: 0
                        updateItem(idx) {
                            it.copy(written = true, note = if (stills > 0) "已写入（剧照 $stills 张）" else "已写入", error = null)
                        }
                    } else {
                        fail++
                        updateItem(idx) { it.copy(error = friendly(r.exceptionOrNull()!!), note = null) }
                    }
                }
                _session.update { it?.copy(writeDone = n + 1) }
            }
            // 最后统一等一轮重扫落库（等不到也只是提示口径不同，不影响已写入的云端文件）。
            // 文件页多选可能整批都不在库里（libraryId=0）—— 没库可等，跳过。
            if (s.libraryId > 0L) {
                withTimeoutOrNull(AWAIT_SCAN_TIMEOUT_MS) {
                    scanner.pending.first { q -> q.none { it.libraryId == s.libraryId && it.noRecurse } }
                    scanner.progress.first { !it.running }
                }
            }
            val summary = "已写入 $ok 部" + if (fail > 0) "，失败 $fail 部" else ""
            _session.update { it?.copy(writing = false, finished = true, summary = summary) }
            _progress.value = Progress(
                running = false, label = "刮削写入", summary = summary, finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 关掉面板：正在跑就先停（停止位 + 取消循环），再清会话 */
    fun closeSession() {
        val wasRunning = _progress.value.running
        stopRequested = true
        batchJob?.cancel()
        batchJob = null
        _session.value = null
        // 取消会跳过循环收尾的那次状态写入 —— 这里补一刀：不补的话红点/保活会一直亮着
        if (wasRunning) {
            _progress.value = Progress(
                running = false, label = "刮削", summary = "已停止刮削",
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    fun stop() {
        val wasRunning = _progress.value.running
        stopRequested = true
        batchJob?.cancel()
        batchJob = null
        _session.value = null
        _writeState.value = null
        searchJob?.cancel()
        _searchState.value = null
        if (wasRunning) {
            _progress.value = Progress(
                running = false, label = "刮削", summary = "已停止刮削",
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun updateItem(index: Int, block: (BatchItem) -> BatchItem) {
        _session.update { cur ->
            if (cur == null || index !in cur.items.indices) cur
            else cur.copy(items = cur.items.mapIndexed { i, it -> if (i == index) block(it) else it })
        }
    }

    // ---------------- 搜索 / 单条写入（详情页直接用） ----------------

    /** 详情页/文件页的搜索：抛 [ScrapeException] 时把 message 给用户看（同时落一条日志，便于看真实原因） */
    suspend fun search(query: ScrapeQuery): List<ScrapeCandidate> {
        val src = source() ?: throw ScrapeException(
            ScrapeFailure.RequestError("还没配置刮削源：设置 → 实验室 → 元数据刮削（TMDB）"),
        )
        return runCatching { src.search(query) }
            .onFailure { android.util.Log.w(TAG, "搜索失败 query=$query", it) }
            .getOrThrow()
    }

    /**
     * 详情页/文件页点「写入」：跑在应用级 scope 上（用户切页也不中断），界面看 [writeState]。
     * 完成后媒体库详情页还要调 [resolveCard] 把这条片重新认回库里（键可能已经变了）。
     *
     * [withStills] = 对话框勾的"同时刮剧照"（单片入口默认开）。
     * [withEpisodes] = 剧集对话框勾的"同时写分集"（逐集写 episodedetails nfo）。
     */
    fun startWrite(plan: ScrapePlan, candidate: ScrapeCandidate, withStills: Boolean = false, withEpisodes: Boolean = false) {
        if (_writeState.value?.running == true) return
        _writeState.value = WriteState(plan.key, plan.title, running = true, step = "准备…")
        _progress.update { it.copy(running = true, label = "正在刮削《${plan.title}》", summary = null) }
        scope.launch {
            val r = runCatching {
                writeBack(plan, candidate, withStills = withStills, withEpisodes = withEpisodes) { step ->
                    _writeState.update { cur -> cur?.copy(step = step) }
                }
            }
            val err = r.exceptionOrNull()
            val res = r.getOrNull()
            _writeState.value = WriteState(
                key = plan.key,
                title = plan.title,
                running = false,
                error = err?.let { friendly(it) },
                newKey = res?.newKey,
                wroteNfo = res != null,
                wrotePoster = res?.wrotePoster == true,
                wroteFanart = res?.wroteFanart == true,
                wroteStills = res?.wroteStills ?: 0,
                wroteEpisodes = res?.wroteEpisodes ?: 0,
                finishedAt = System.currentTimeMillis(),
            )
            _progress.value = Progress(
                running = false,
                label = "刮削",
                summary = if (err == null) {
                    val wrote = buildList {
                        add("nfo")
                        if (res?.wrotePoster == true) add("海报")
                        if (res?.wroteFanart == true) add("背景图")
                        (res?.wroteStills ?: 0).takeIf { it > 0 }?.let { add("剧照 $it 张") }
                        (res?.wroteEpisodes ?: 0).takeIf { it > 0 }?.let { add("分集 $it 集") }
                    }
                    "《${plan.title}》已写入 ${wrote.joinToString("、")}"
                } else {
                    "《${plan.title}》刮削失败：${friendly(err)}"
                },
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    fun clearWrite() {
        _writeState.value = null
    }

    /**
     * **文件页**的准备：给一个云盘目录（或目录里的某个视频文件），列目录凑齐写回要用的信息
     * —— 视频文件名（nfo 名前缀）、已有的 nfo（覆盖提示 + file_id）、检索词与年份。
     *
     * 电影目录常见两层结构（`片名 (2011)/片名.2011.1080p/xxx.mkv`）：本级没有视频时**自动下探一层**，
     * 但只认"恰好一个子目录里有视频"的情况 —— 多个就说不清该刮哪个，提示用户自己进去选。
     *
     * 失败直接抛（消息给用户看）：目录里没有视频、文件名认不出前缀、列目录失败。
     * [videoNameHint] 传了就用它（用户在目录里选的是某个视频文件）；没传就挑体积最大的视频。
     */
    suspend fun planFromDirectory(
        dirCid: String,
        dirPath: String,
        dirName: String,
        videoNameHint: String? = null,
    ): ScrapePlan {
        if (dirCid.isBlank()) error("拿不到这个目录的 id")
        var cid = dirCid
        var path = dirPath
        var items = listItems(cid)
        var videos = items.filter { !it.isDir && isVideoFileName(it.fn) }

        // 剧集目录（一堆分集 / 有 Season N 季目录）→ 走剧级刮削，写 tvshow.nfo 到本目录、不归位。
        // 只在"选的是目录"时判（选了具体文件 = 用户要那一个，按电影走）。
        if (videoNameHint == null && looksLikeTvDirectory(items)) {
            val (showTitle, showYear) = ScrapeMatch.splitYearFromTitle(dirName.ifBlank { cid }.trim())
            val existingShow = items.firstOrNull {
                !it.isDir && it.fn.equals("tvshow.nfo", true)
            }
            val inLibrary = runCatching { libraryForPath(path, dao.libraries().first()) != null }.getOrDefault(false)
            return ScrapePlan(
                key = "dir:$cid",
                title = showTitle,
                year = showYear,
                videoName = "",
                dirCid = cid,
                dirPath = path,
                isEpisodeLike = false,
                oldNfoFid = existingShow?.fid?.takeIf { it.isNotBlank() },
                overwriteNote = existingShow?.let {
                    "这个目录里已经有 tvshow.nfo，写入会覆盖它（旧文件移入回收站，可还原）。"
                },
                inLibrary = inLibrary,
                kind = ScrapeKind.TV,
            )
        }

        if (videos.isEmpty() && videoNameHint == null) {
            // 只下探一层，且子目录数有上限：每个候选都要多列一次目录，太多不划算
            val subs = items.filter { it.isDir }.mapNotNull { d -> d.fid?.let { d to it } }.take(SUBDIR_PROBE_MAX)
            if (subs.size < items.count { it.isDir }) {
                error("这个目录里没有视频文件，子目录又太多 —— 请进去选中具体那一层再刮")
            }
            val hits = subs.mapNotNull { (d, fid) ->
                val inner = runCatching { listItems(fid) }.getOrDefault(emptyList())
                val vs = inner.filter { !it.isDir && isVideoFileName(it.fn) }
                if (vs.isEmpty()) null else Triple(d, fid, inner)
            }
            val only = hits.singleOrNull() ?: run {
                if (hits.isEmpty()) error("这个目录里没有视频文件")
                error("这个目录下有多个子目录含视频，请进去选中具体那一层再刮")
            }
            cid = only.second
            path = listOf(path, only.first.fn).filter { it.isNotBlank() }.joinToString("/")
            items = only.third
            videos = items.filter { !it.isDir && isVideoFileName(it.fn) }
        }

        val video = videoNameHint?.let { hint -> videos.firstOrNull { it.fn == hint } }
            ?: videos.maxByOrNull { it.fs }
            ?: error("这个目录里没有视频文件")
        val base = fileBasePrefix(video.fn)?.takeIf { it.isNotBlank() }
            ?: error("文件名认不出前缀：${video.fn}")
        val existingNfo = items.firstOrNull {
            !it.isDir && it.fn.substringAfterLast('.', "").equals("nfo", true) && fileBasePrefix(it.fn) == base
        }
        val rawTitle = (if (videoNameHint != null) base else dirName.ifBlank { base }).trim()
        val (title, year) = ScrapeMatch.splitYearFromTitle(rawTitle)
        val inLibrary = runCatching {
            libraryForPath(path, dao.libraries().first()) != null
        }.getOrDefault(false)
        return ScrapePlan(
            key = "dir:$cid",
            title = title,
            year = year,
            videoName = video.fn,
            dirCid = cid,
            dirPath = path,
            // 文件页看不出是不是番号式的剧集目录：按影片处理。这里只影响"预测新键"，
            // 而文件页不需要拿新键替换卡片（媒体库那边靠 resolveCard 走 videoPickCode）
            isEpisodeLike = false,
            oldNfoFid = existingNfo?.fid?.takeIf { it.isNotBlank() },
            overwriteNote = existingNfo?.let {
                "这个目录里已经有 nfo（${it.fn}），写入会覆盖它（旧文件移入回收站，可还原）。"
            },
            inLibrary = inLibrary,
        )
    }

    /** 列一个目录（文件页准备用；limit 1000 与本工程其它一次性列目录一致） */
    private suspend fun listItems(cid: String): List<FileItem> =
        runCatching { parseFilesResponse(api.files(cid = cid, limit = 1000)).items }
            .getOrElse { throw IllegalStateException("读取目录失败：${it.message ?: "网络错误"}") }

    /**
     * 写回并重扫之后把这条片重新认回库里。顺序：先按预测的新键（`tmdb-<id>`）查，
     * 查不到再按**视频 pick_code** 反查（键变没变都找得到），都查不到说明重扫还没把新文件认进来。
     */
    suspend fun resolveCard(videoPickCode: String?, predictedKey: String?): MovieCard? {
        repeat(3) { attempt ->
            predictedKey?.let { key ->
                val hit = runCatching { dao.movieCardsByKeys(listOf(key)).firstOrNull() }.getOrNull()
                if (hit != null) return hit
            }
            videoPickCode?.let { pc ->
                val row = runCatching { dao.movieByVideoPickCode(pc) }.getOrNull()
                if (row != null) {
                    dao.movieCardsByKeys(listOf(row.mediaKey)).firstOrNull()?.let { return it }
                }
            }
            if (attempt < 2) delay(500)
        }
        return null
    }

    /** 缩略图用的派生 client：共享连接池，只把单次调用超时压到 15s（列表里别让一行挂 40s） */
    private val thumbHttp: OkHttpClient by lazy { http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** 候选缩略图/海报字节（走独立的第三方 client，带超时保护） */
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        // 海外图床直连是"间歇性"的（dmm 实测：首连 200，紧接着突发性连拒——写回能成、
        // 搜索的并发缩略图全挂就是这么来的），所以失败再试一次；
        // JavBus 等图床有防盗链，带上同源 Referer（对 dmm 无害）；
        // 全失败落一行日志——占位图一直空着但原因被吞掉，排查会两眼一抹黑。
        val referer = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }
        var last: ByteArray? = null
        repeat(2) { attempt ->
            if (last == null) {
                if (attempt > 0) delay(600)
                last = runCatching {
                    val req = Request.Builder().url(url).get().apply {
                        if (referer != null) header("Referer", referer)
                    }.build()
                    thumbHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.bytes()
                    }
                }.getOrNull()
            }
        }
        if (last == null) Log.w(TAG, "图片下载失败（重试后仍不通）：$url")
        last
    }

    // ---------------- 写回内核 ----------------

    private data class WriteResult(
        val newKey: String?,
        val wrotePoster: Boolean,
        val wroteFanart: Boolean = false,
        val wroteStills: Int = 0,
        val wroteEpisodes: Int = 0,
    )

    /**
     * 一条片的完整写回：详情 → 图片字节（解码校验）→ 清理同名旧文件 → 上传 → 作废扫描状态 → 定向重扫。
     *
     * 上传的图片：nfo 之外固定两张（海报 `<前缀>-poster.jpg`、背景 `<前缀>-fanart.jpg`）；
     * [withStills] 时再把按赞数挑的前 N 张（张数上限读设置，0 = 不限制）传进**这部片专属**的 `<前缀>-extrafanart/`
     * 目录（当剧照 —— 不用共享的 `extrafanart/`，同目录多部片会串图）。
     * 剧照全链路**尽力而为**：取列表失败、某张下载失败、建目录失败都不拦 nfo/海报的写入，
     * 只是写进 [WriteResult.wroteStills] 的数量如实反映。
     *
     * 上传前后的两个经验：
     *  - **同名旧文件必须先回收**：115 同名上传会生成「（1）」副本（播放器"设为预览图"那边同款处理）；
     *    extrafanart 里旧剧照整批清掉再传新的 —— 留着旧的会和新的一起被当剧照轮播。
     *  - nfo 的匹配按**前缀相等**而不是文件名相等：`{tmdbid-...}` 这类装饰后缀在聚类时被剥掉，
     *    同一个片可能同时存在 `X.nfo` 与 `X {tmdbid-3}.nfo` 两种写法，只按名字删会留一份、造成二义。
     */
    private suspend fun writeBack(
        plan: ScrapePlan,
        candidate: ScrapeCandidate,
        awaitRescan: Boolean = true,
        withStills: Boolean = false,
        withEpisodes: Boolean = false,
        organize: Boolean = true,
        renameToClean: Boolean = false,
        onStep: (String) -> Unit,
    ): WriteResult {
        val src = source() ?: throw ScrapeException(
            ScrapeFailure.RequestError("还没配置刮削源：设置 → 实验室 → 元数据刮削（TMDB）"),
        )
        val dirCid0 = plan.dirCid.takeIf { it.isNotBlank() } ?: error("这条影片没有云端目录，无法写回")
        val dirPath0 = plan.dirPath.takeIf { it.isNotBlank() } ?: error("这条影片没有目录路径，无法写回")
        var dirCid = dirCid0
        var dirPath = dirPath0
        // 归位过程中被搬空的老目录：重扫时要连它一起作废/排扫，好让库里的旧行消失
        var movedFromCid: String? = null
        var base = if (plan.kind == ScrapeKind.TV) "tvshow" else fileBasePrefix(plan.videoName)?.takeIf { it.isNotBlank() }
            ?: error("拿不到视频文件名，生成不了 nfo 名")
        var videoName = plan.videoName

        onStep("正在读取元数据…")
        val meta = src.fetch(candidate)

        // 按"规范名"重命名视频（多选刮削前的开关；规范名 = 用来搜索的 plan.title）。只电影做。
        // ★ 必须在算 nfo/海报名、归位之前：这样装饰文件天生用新前缀、和改名后的视频对得上；
        //   批量刮削的目标本就"缺 nfo"，没有旧装饰文件会被留下变孤儿。
        if (renameToClean && plan.kind == ScrapeKind.MOVIE && plan.title.isNotBlank()) {
            val proposed = matchedFileName(plan.title, videoName)
            if (proposed != null && proposed != videoName) {
                val vid = runCatching { listItems(dirCid) }.getOrNull()
                    ?.firstOrNull { !it.isDir && it.fn == videoName }
                if (vid?.fid != null) {
                    runCatching { api.updateFile(vid.fid!!, proposed) }
                        .onSuccess {
                            videoName = proposed
                            base = fileBasePrefix(proposed) ?: base
                        }
                        .onFailure {
                            // 改名没成，nfo/海报仍按原名写：不喊一声的话，视频旧名 + 装饰新名会被扫成两条
                            android.util.Log.w(TAG, "按规范名改名失败（$videoName → $proposed），nfo/海报仍按原名写", it)
                        }
                }
            }
        }
        val nfoName = "$base.nfo"
        val posterName = "$base-poster.jpg"
        val fanartName = "$base-fanart.jpg"

        onStep("正在下载海报…")
        val poster = meta.posterUrl?.let { url -> fetchBytes(url)?.takeIf { isDecodableImage(it) } }
        val fanart = meta.fanartUrl?.let { url -> fetchBytes(url)?.takeIf { isDecodableImage(it) } }

        val stills: List<ByteArray> = if (withStills) {
            onStep("正在下载剧照…")
            // 张数上限读设置（0 = 不限制 → 给源传一个足够大的数，pickStills / take 全收）
            val maxStills = runCatching { stillsMax() }.getOrDefault(EXTRAFANART_MAX)
            runCatching {
                src.fetchBackdropUrls(candidate, if (maxStills <= 0) Int.MAX_VALUE else maxStills).mapNotNull { url ->
                    fetchBytes(url)?.takeIf { isDecodableImage(it) }
                }
            }.getOrElse { e ->
                android.util.Log.w(TAG, "获取剧照失败（不影响 nfo/海报写入）", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        // 一部一目录：这部片的视频若和别的片混在同一目录，先建独立文件夹把它（含各张盘 + 同名图 + 专属剧照）搬进去。
        // 剧集整目录就是一部剧，不归位（搬哪一集都不对）。
        var listing = runCatching { parseFilesResponse(api.files(cid = dirCid, limit = 1000)).items }
            .getOrDefault(emptyList())
        if (organize && plan.kind == ScrapeKind.MOVIE) {
            val moved = organizeIntoOwnFolder(dirCid, dirPath, videoName, base, listing, onStep)
            if (moved != null) {
                movedFromCid = dirCid
                dirCid = moved.first
                dirPath = moved.second
                listing = listItems(dirCid)
            }
        }

        onStep("正在清理同名旧文件…")
        val deleteFids = buildList {
            listing.filter { !it.isDir && it.fn == posterName }.mapNotNullTo(this) { it.fid }
            listing.filter { !it.isDir && fileBasePrefix(it.fn) == base && hasArtSuffix(it.fn, "-fanart", "-backdrop") }
                .mapNotNullTo(this) { it.fid }
            listing.filter { !it.isDir && it.fn.substringAfterLast('.', "").equals("nfo", true) }
                .filter { fileBasePrefix(it.fn) == base }
                .mapNotNullTo(this) { it.fid }
            plan.oldNfoFid?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()
        // 已有专属剧照目录且这次要重刮：旧图整批回收（同名上传会变「（1）」副本）
        val stillsDirName = "$base$PER_MOVIE_FANART_SUFFIX"
        var extraCid = listing.firstOrNull { it.isDir && it.fn.equals(stillsDirName, ignoreCase = true) }
            ?.fid?.takeIf { it.isNotBlank() }
        if (stills.isNotEmpty() && extraCid != null) {
            val cid = extraCid
            val oldFids = runCatching { listItems(cid) }.getOrDefault(emptyList())
                .filter { !it.isDir }.mapNotNull { it.fid }
            if (oldFids.isNotEmpty()) runCatching { api.deleteFiles(oldFids.joinToString(","), cid) }
                .onFailure { android.util.Log.w(TAG, "清理旧剧照失败（旧图会与新上传的并存成「（1）」副本）", it) }
        }
        if (deleteFids.isNotEmpty()) runCatching { api.deleteFiles(deleteFids.joinToString(","), dirCid) }
            .onFailure { android.util.Log.w(TAG, "清理同名旧 nfo/海报/背景失败（可能生成「（1）」副本）", it) }

        onStep("正在上传海报…")
        if (poster != null) {
            Uploader.uploadSmallLogged(transferLog, api, posterName, poster, dirCid, null)
        }
        if (fanart != null) {
            Uploader.uploadSmallLogged(transferLog, api, fanartName, fanart, dirCid, null)
        }

        var wroteStills = 0
        if (stills.isNotEmpty()) {
            val cid = extraCid ?: ensureExtraFanartDir(dirCid, stillsDirName)
            extraCid = cid
            if (cid != null) {
                onStep("正在上传剧照（${stills.size} 张）…")
                stills.forEachIndexed { i, bytes ->
                    runCatching {
                        Uploader.uploadSmallLogged(transferLog, api, "fanart${i + 1}.jpg", bytes, cid, null)
                    }.onSuccess { wroteStills++ }
                }
            } else {
                android.util.Log.w(TAG, "extrafanart 目录建不出来，剧照跳过")
            }
        }

        onStep("正在写入 nfo…")
        Uploader.uploadSmallLogged(
            transferLog, api, nfoName, NfoWriter.build(meta).toByteArray(Charsets.UTF_8), dirCid, null,
        )

        // 剧集分集：本目录 + 一层季子目录里的分集文件，按 SxxExx 对到 TMDB 的集，逐集写同名 .nfo
        // （每集真名/简介/首播/季集号）。尽力而为，认不上的集跳过。
        var wroteEpisodes = 0
        if (withEpisodes && plan.kind == ScrapeKind.TV) {
            wroteEpisodes = writeEpisodeNfos(src, candidate, meta, dirCid, listing, onStep)
        }

        // 上传完成到"列目录可见"之间可能有几秒延迟：轻确认一下再重扫，免得白扫一轮
        onStep("正在确认云端文件…")
        if (!waitListingHas(dirCid, nfoName)) {
            android.util.Log.w(TAG, "上传后 $LISTING_CONFIRM_MS 内没在列表里看到 $nfoName，仍照常排重扫")
        }

        onStep("正在重扫该目录…")
        dao.invalidateScanState(dirCid)
        movedFromCid?.let { dao.invalidateScanState(it) }
        val match = libraryForPath(dirPath, dao.libraries().first())
        if (match != null) {
            // 搬过家：新目录建条目、老目录重扫把搬走的旧行清掉 —— 两个都要排
            val dirs = buildList {
                add(dirCid to match.path)
                if (movedFromCid != null) {
                    val oldPath = match.path.removeSuffix("/$base").ifBlank { match.path }
                    add(movedFromCid!! to oldPath)
                }
            }
            // 传了剧照必须带 withSideArt：定向任务不递归，不带它 extrafanart 永远收不进库
            scanner.enqueueTargeted(match.library, dirs, withSideArt = wroteStills > 0)
            if (awaitRescan) {
                withTimeoutOrNull(AWAIT_SCAN_TIMEOUT_MS) {
                    scanner.pending.first { q -> q.none { it.libraryId == match.library.id && it.noRecurse } }
                    scanner.progress.first { !it.running }
                }
            }
        }

        // 写完带 tmdbid 的 nfo，扫描器会把键变成 tmdb-<id>（番号式行不吃 nfo 的 id，键不变）
        val newKey = if (plan.isEpisodeLike) null else meta.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb-$it" }
        return WriteResult(
            newKey = newKey,
            wrotePoster = poster != null,
            wroteFanart = fanart != null,
            wroteStills = wroteStills,
            wroteEpisodes = wroteEpisodes,
        )
    }

    /**
     * 给一部剧的分集逐集写 `episodedetails` nfo：扫本目录 + 一层季子目录里的视频，按文件名抽
     * (季,集) → 拉对应季的 TMDB 分集 → 命中的就在**该视频所在目录**写 `<前缀>.nfo`（先删同名旧的）。
     *
     * 返回写成的集数。季只按出现的季号各拉一次（缓存）；认不出季集号 / TMDB 没这集 → 跳过该文件。
     */
    private suspend fun writeEpisodeNfos(
        src: MetadataSource,
        candidate: ScrapeCandidate,
        meta: ScrapedMetadata,
        dirCid: String,
        topListing: List<FileItem>,
        onStep: (String) -> Unit,
    ): Int {
        // 候选目录：本目录 + 一层子目录（排除侧挂素材目录），季子目录布局也能覆盖
        val dirs = buildList {
            add(dirCid to topListing)
            topListing.filter { it.isDir && !it.fid.isNullOrEmpty() && !isSideArtDirName(it.fn) }
                .take(SEASON_SUBDIR_MAX).forEach { d ->
                    val items = runCatching { listItems(d.fid!!) }.getOrNull() ?: return@forEach
                    add(d.fid!! to items)
                }
        }
        val seasonCache = HashMap<Int, ScrapedSeason?>()
        var written = 0
        var total = 0
        for ((cid, items) in dirs) {
            val videos = items.filter { !it.isDir && isVideoFileName(it.fn) && parseEpisodeNumber(it.fn) != null }
            total += videos.size
            for (v in videos) {
                val se = parseEpisodeNumber(v.fn) ?: continue
                val season = seasonCache.getOrPut(se.first) {
                    runCatching { src.fetchSeason(candidate, se.first) }.getOrNull()
                }
                val ep = season?.episodes?.firstOrNull { it.episode == se.second } ?: continue
                val epBase = fileBasePrefix(v.fn)?.takeIf { it.isNotBlank() } ?: continue
                // 同名旧 nfo 先回收（115 同名上传会变「（1）」副本）
                val old = items.filter { !it.isDir && it.fn.substringAfterLast('.', "").equals("nfo", true) && fileBasePrefix(it.fn) == epBase }
                    .mapNotNull { it.fid }
                if (old.isNotEmpty()) runCatching { api.deleteFiles(old.joinToString(","), cid) }
                    .onFailure { android.util.Log.w(TAG, "清理旧分集 nfo 失败（$epBase）", it) }
                val xml = NfoWriter.buildEpisode(meta.title, ep)
                runCatching {
                    Uploader.uploadSmallLogged(transferLog, api, "$epBase.nfo", xml.toByteArray(Charsets.UTF_8), cid, null)
                }.onSuccess {
                    written++
                    onStep("正在写分集（$written/$total）…")
                }.onFailure {
                    android.util.Log.w(TAG, "写分集 nfo 失败（$epBase）", it)
                }
            }
        }
        return written
    }

    /**
     * 建（或找回）这部片的专属剧照目录（`<前缀>-extrafanart/`），返回它的 cid；建不出返回 null。
     *
     * 不再写共享的 `extrafanart/`：一个目录里放多部片时那份剧照会挂到**每条**行上（实测串图），
     * 专属目录按前缀归属、各挂各的（见 [com.open115.pad.data.media.perMovieFanartPrefix]）。
     *
     * 找回那一步照 BackupManager 的 ensureBackupDir 的姿势：
     * folder/add 失败多半是目录已存在（并发/上次留下的），列目录认回来即可。
     */
    private suspend fun ensureExtraFanartDir(dirCid: String, stillsDirName: String): String? =
        ensureSubfolder(dirCid, stillsDirName)

    /** 在 [dirCid] 下建名为 [name] 的子目录，返回其 cid；已存在（或建失败）就列目录认回来，都没有返回 null */
    private suspend fun ensureSubfolder(dirCid: String, name: String): String? {
        val resp = runCatching { api.addFolder(dirCid, name) }.getOrNull()
        resp?.envData()?.optStr("file_id")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { listItems(dirCid) }
            .getOrDefault(emptyList())
            .firstOrNull { it.isDir && it.fn.equals(name, ignoreCase = true) }?.fid
    }

    /**
     * 「一部一目录」：这部片的视频若和**别的片**混在同一目录，建 `<片名前缀>/` 子目录、
     * 把它整组（正片含各张盘 + 同名 nfo/海报/背景/头像 + `<前缀>-extrafanart/`）搬进去，
     * 返回新的 (cid, path)。已经独占目录 / 认不出分组 / 建夹或移动失败 → 返回 null（原地写，不搬）。
     *
     * 判"别的片"用 [movieGroupOf]（分 CD 的一部片算一组，不会被拆散）。搬完由调用方重列目录再走清理/上传。
     */
    private suspend fun organizeIntoOwnFolder(
        dirCid: String,
        dirPath: String,
        videoName: String,
        base: String,
        listing: List<FileItem>,
        onStep: (String) -> Unit,
    ): Pair<String, String>? {
        val chosen = listing.firstOrNull { !it.isDir && it.fn == videoName } ?: return null
        val group = movieGroupOf(chosen) ?: return null
        // 目录里除了这部片，还有别的片的视频吗？没有 = 已经独占，不用搬
        val hasOthers = listing.any { !it.isDir && isVideoFileName(it.fn) && movieGroupOf(it) != group }
        if (!hasOthers) return null
        onStep("正在为这部片建独立文件夹并归位…")
        val folderCid = ensureSubfolder(dirCid, base) ?: return null
        val memberIds = listing.filter { movieGroupOf(it) == group }.mapNotNull { it.fid }.distinct()
        if (memberIds.isEmpty()) return null
        val ok = runCatching { api.moveFiles(memberIds.joinToString(","), folderCid) }
            .onFailure { android.util.Log.w(TAG, "归位失败（这部片仍原地写，不搬进独立目录）", it) }
            .isSuccess
        if (!ok) return null
        return folderCid to listOf(dirPath, base).filter { it.isNotBlank() }.joinToString("/")
    }

    /** 一个目录里"一部片"的候选（多片目录让你选用）：分 CD/分盘算一部，取体积最大的那个视频当代表 */
    data class DirGroup(val videoName: String, val sizeBytes: Long)

    /**
     * 列目录、按"部"归组返回候选。多于一个 = 这个目录混了多部片，界面该让用户挑要刮哪一部
     * （而不是像以前那样默默拿最大的那个）。识别失败/没视频返回空。
     */
    suspend fun directoryGroups(dirCid: String): List<DirGroup> {
        if (dirCid.isBlank()) return emptyList()
        val items = runCatching { listItems(dirCid) }.getOrDefault(emptyList())
        // 剧集目录不当"多片"列（否则每集都是一条）—— 交给 planFromDirectory 判成剧级
        if (looksLikeTvDirectory(items)) return emptyList()
        val videos = items.filter { !it.isDir && isVideoFileName(it.fn) }
        return videos.groupBy { movieGroupOf(it) }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.fs }?.let { DirGroup(it.fn, it.fs) } }
            .sortedByDescending { it.sizeBytes }
    }

    /** 文件名（去扩展名后）以这些尾巴结尾 —— 认旧背景图用（`X-fanart.jpg` / `X-backdrop.png` 都算） */
    private fun hasArtSuffix(fn: String, vararg suffixes: String): Boolean {
        val stem = fn.substringBeforeLast('.')
        return suffixes.any { stem.endsWith(it, ignoreCase = true) }
    }

    private suspend fun waitListingHas(cid: String, name: String): Boolean {
        val deadline = System.currentTimeMillis() + LISTING_CONFIRM_MS
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                parseFilesResponse(api.files(cid = cid, limit = 1000)).items.any { it.fn == name }
            }.getOrDefault(false)
            if (ok) return true
            delay(1200)
        }
        return false
    }

    // ---------------- 检索词与匹配（纯逻辑在 ScrapeMatch，这里只做转发给界面用） ----------------

    /** 见 [ScrapeMatch.cleanQueryTitle] */
    fun cleanQueryTitle(raw: String): String = ScrapeMatch.cleanQueryTitle(raw)

    private fun queryOf(title: String, year: Int?, kind: ScrapeKind = ScrapeKind.MOVIE): ScrapeQuery =
        ScrapeQuery(title = cleanQueryTitle(title), year = year, kind = kind)

    /** 见 [ScrapeMatch.autoPick] */
    fun autoPick(cands: List<ScrapeCandidate>, localTitle: String, year: Int?): Int? =
        ScrapeMatch.autoPick(cands, localTitle, year)

    private fun friendly(e: Throwable): String = when (e) {
        is ScrapeException -> when (val f = e.failure) {
            is ScrapeFailure.NotFound -> f.message
            is ScrapeFailure.RequestError -> f.message
        }
        else -> scrapeErrorText(e)
    }

    private fun isDecodableImage(bytes: ByteArray): Boolean {
        if (bytes.size < 512) return false
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "MetadataScraper"

        /** 剧照张数：默认值（设置里的「剧照数量上限」，0 = 不限制；写回时经 stillsMax 现读） */
        const val EXTRAFANART_MAX = 8

        /** 写分集时最多下探几个季子目录（每个都要多列一次目录） */
        private const val SEASON_SUBDIR_MAX = 20

        /** 等重扫空闲的上限：排在别的库的整库扫描后面时可能要等一阵 */
        private const val AWAIT_SCAN_TIMEOUT_MS = 180_000L

        /** 上传后确认"列表里能看到"的上限（超过就照常重扫，后续扫描会兜住） */
        private const val LISTING_CONFIRM_MS = 6_000L

        /** 目录里没有视频时，最多下探几个子目录去找（每个都要多列一次目录） */
        private const val SUBDIR_PROBE_MAX = 4
    }
}
