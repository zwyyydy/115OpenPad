package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.FilesPage
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.parseFilesResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 媒体库扫描引擎：手动触发、可续跑、带进度。
 * 扫描一个目录 = 列目录（响应里自带全部文件名，聚类零额外请求）→ 聚类 →
 * 逐条下载 .nfo（小文件）解析 → 入库（每条一个事务）→ 更新 scan_state。
 * 中断后重启能续跑：已完成的目录 cloudUpt 未变就跳过。
 *
 * 接口调用量（115 有频控，这里是媒体库最大的一处）：
 * - 列目录：**每个目录每次扫描 1 次**。collectDirs 那次列目录的结果会传给扫描循环复用，
 *   早先是列两遍（collectDirs 找子目录一遍、循环取文件又一遍）。
 *   降不到 0 —— 判断"目录变没变"本身就得问服务器，scan_state.cloudUpt 只是记住上次的值。
 * - .nfo：内容没变（pickCode + upt 相同）**零请求**，见 fetchNfoMeta。
 */
class MediaScanner(
    private val openApi: OpenApi,
    private val okHttpClient: OkHttpClient,
    private val dao: MediaDao,
    /** 媒体库落盘缓存。为 null = 不缓存（测试 / 没接容器时） */
    private val cache: MediaCache? = null,
) {
    data class Progress(
        val running: Boolean = false,
        /** 已请求停止、正在当前目录收尾（UI 据此把按钮置灰显示"停止中…"） */
        val stopping: Boolean = false,
        /** 本次是被停止的（而非正常跑完）—— 供 UI 提示"可以继续" */
        val stopped: Boolean = false,
        /**
         * 本次扫描的库根 cid。
         * 删媒体库时靠它判断"正在扫的是不是这个库" —— 是的话必须先停，
         * 否则扫描会在删除之后把条目又写回来（见 MediaLibraryScreen 的删除确认）。
         */
        val rootCid: String = "",
        val doneDirs: Int = 0,
        val totalDirs: Int = 0,
        val currentDir: String = "",
        val moviesIndexed: Int = 0,
        val finishedAt: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val mutex = Mutex()

    /**
     * 停止请求。runScan 在**列目录 / 每个目录 / 每个簇**的边界各查一次，
     * 置位后收尾退出 —— 不会打断正在进行的单个网络请求，所以延迟上界约等于一次请求
     * （限速开着的话再加一个 rateLimitMs），这也是删库时敢"等它停"的依据。
     *
     * 停止**不丢进度**：已经扫完的目录早已写进 scan_state，续扫（增量扫描）会跳过它们；
     * 没扫完的那个目录**不写 scan_state**，所以续扫会把它重扫一遍。
     */
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 请求停止当前扫描（UI 的「停止」按钮、删库前的收尾）。没在跑就什么都不做 */
    fun requestStop() {
        if (!_progress.value.running) return
        stopRequested.set(true)
        _progress.value = _progress.value.copy(stopping = true)
    }

    /**
     * 清掉"已停止"提示。
     *
     * 删库后要调：那条提示里写着"已完成 N/M 个目录"，而它指向的库已经没了，
     * 留在界面上只会让人以为还有个没跑完的扫描（提示本来只在下一次扫描开始时才被清）。
     */
    fun clearStoppedNotice() {
        if (_progress.value.running || !_progress.value.stopped) return
        _progress.value = _progress.value.copy(stopped = false)
    }

    /** 限速：相邻两次 115 API 请求的最小间隔（毫秒），0 = 不限 */
    private var lastRequestAt = 0L

    private suspend fun throttle(rateLimitMs: Long) {
        if (rateLimitMs <= 0) return
        val now = System.currentTimeMillis()
        val wait = lastRequestAt + rateLimitMs - now
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    /** 列目录（带限速）：响应里自带文件名，聚类零额外请求 */
    private suspend fun listFiles(cid: String, rateLimitMs: Long) =
        parseFilesResponse(run { throttle(rateLimitMs); openApi.files(cid = cid, limit = 200) })

    /**
     * 实际执行（调用方在自己的 scope 里 launch）：手动触发、可续跑、带进度。
     * incremental=true 时按 scan_state 的 cloudUpt 增量跳过（目录列表 upt 未变 = 无新增资源）；
     * false 为全量扫描（不跳过任何目录）。
     *
     * [minVideoSizeMb] 是**每库**的体积过滤（0 = 不过滤）：小于它的视频不入库。
     * 在聚类阶段就滤掉，见 [sniffDirectory]。
     */
    suspend fun runScan(
        rootCid: String,
        rootPath: String,
        includeSubDirs: Boolean = true,
        incremental: Boolean = false,
        rateLimitMs: Long = 0,
        minVideoSizeMb: Int = 0,
    ) {
        mutex.withLock {
            if (_progress.value.running) return
            // 清掉上一轮遗留的停止请求。**必须在 running 判断之后**——
            // 放前面的话，一次被拒绝的并发调用会把正在跑的那轮扫描的停止标志擦掉。
            stopRequested.set(false)
            _progress.value = Progress(running = true, rootCid = rootCid, currentDir = rootPath)
        }
        var stoppedEarly = false
        try {
            var movies = 0
            var skipped = 0
            var done = 0
            // 递归列目录。**这一步列到的结果直接传给下面复用**，不再对同一个目录重复请求
            // （早先是 collectDirs 列一遍找子目录、下面的循环再列一遍取文件，等于每目录 2 次）
            val allDirs = collectDirs(rootCid, rootPath, includeSubDirs, rateLimitMs)
            if (stopRequested.get()) {
                // 列目录阶段没有写入，停在这里没有任何损失（续扫会重新列）
                stoppedEarly = true
                Log.i(TAG, "扫描在列目录阶段被停止")
            } else {
                _progress.value = _progress.value.copy(totalDirs = allDirs.size)
                for (listing in allDirs) {
                    if (stopRequested.get()) {
                        stoppedEarly = true
                        break
                    }
                    _progress.value = _progress.value.copy(currentDir = listing.path)
                    // includeSubDirs=false 时 collectDirs 没列过这个目录，这里补一次
                    val page = listing.page ?: listFiles(listing.cid, rateLimitMs)
                    val dirUpt = page.items.maxOfOrNull { it.upt } ?: 0L
                    if (incremental) {
                        // 增量：目录上次扫完且列表 upt 未变 → 无新增/修改，跳过
                        val state = dao.scanState(listing.cid)
                        if (state != null && state.status == 2 && state.cloudUpt == dirUpt && dirUpt > 0) {
                            skipped++
                            done++
                            _progress.value = _progress.value.copy(doneDirs = done, moviesIndexed = movies)
                            continue
                        }
                    }
                    val files = page.items.filter { !it.isDir }.map {
                        FileRef(name = it.fn, pickCode = it.pc ?: "", sizeBytes = it.fs, upt = it.upt, fid = it.fid ?: "")
                    }
                    val scan = sniffDirectory(files, minVideoSizeMb.toLong() * 1024 * 1024)
                    // 分集目录才需要往上找归属与继承的图；影片目录不找
                    // （一部电影不是某一集，给它套系列海报、认系列当爹都是错的）
                    val ancestor = if (scan.isMultiVideo) ancestorInfoOf(listing.path, files, rootPath) else null
                    val produced = mutableSetOf<String>()
                    var failed = false
                    var aborted = false
                    for (cluster in scan.clusters) {
                        // 簇边界也查一次：一个大目录可能有很多簇，每个簇都要一次 downurl + 一次 nfo 下载
                        if (stopRequested.get()) {
                            aborted = true
                            break
                        }
                        try {
                            produced += indexCluster(
                                listing.cid, listing.path, cluster,
                                isEpisodeLike = scan.isMultiVideo,
                                rateLimitMs = rateLimitMs,
                                ancestor = ancestor,
                            )
                            movies++
                        } catch (e: Exception) {
                            failed = true
                            Log.w("MediaScanner", "索引失败 ${cluster.prefix}: ${e.message}")
                        }
                    }
                    if (aborted) {
                        // 这个目录**没扫完**：不写 scan_state（写了续扫就会当它已完成而跳过，
                        // 剩下的簇永远补不上）、也不做陈旧清理（会把还没产出的那些行删掉）。
                        // 已入库的簇是 upsert，续扫重来一遍无害。
                        stoppedEarly = true
                        break
                    }
                    // 清理这个目录里**本次没产出**的旧条目：云盘上已删的片、以及 mediaKey 变了的片
                    // （nfo 解析成功后 key 会从"目录前缀"变成 "tmdb-348"，旧键的行不清就是重复条目）。
                    //
                    // 有簇索引失败时**一条都不清** —— 那可能只是这次网络不好，删掉就把已索引的
                    // 元数据弄丢了，而重扫本来就是为了补数据。
                    if (!failed) {
                        val stale = dao.mediaKeysInDir(listing.cid).filterNot { it in produced }
                        if (stale.isNotEmpty()) {
                            dao.deleteMovies(stale)
                            Log.i(TAG, "清理 ${listing.path} 下 ${stale.size} 条陈旧条目")
                        }
                    }
                    dao.upsertScanState(
                        ScanStateEntity(
                            dirKey = listing.cid,
                            dirPath = listing.path,
                            status = 2,
                            cloudUpt = dirUpt,
                            scannedAt = System.currentTimeMillis(),
                        ),
                    )
                    done++
                    _progress.value = _progress.value.copy(doneDirs = done, moviesIndexed = movies)
                }
                if (skipped > 0) Log.i(TAG, "增量扫描完成：跳过 $skipped/${allDirs.size} 个未变化目录")
            }
        } catch (e: Exception) {
            Log.w("MediaScanner", "扫描中断: ${e.message}")
        } finally {
            // 收尾统一放 finally：正常结束、抛异常、被停止三条路都要把 running 落回去，
            // 否则 UI 会永远停在"扫描中"、且 requestStop 之后的等待永远等不到
            _progress.value = _progress.value.copy(
                running = false,
                stopping = false,
                stopped = stoppedEarly,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 一个目录 + **发现它时那一次列目录的结果**（扫描循环直接复用，不再重复请求） */
    private data class DirListing(val cid: String, val path: String, val page: FilesPage?)

    /**
     * 递归收集子目录（手动扫描是一次性任务，不并发，控制频控代价）。
     *
     * 顺带把每个目录**已经列到的**那一页带回去：调用方拿它当文件来源，
     * 于是"列目录"从每目录 2 次降到 1 次。列表值完全一样（同一次扫描内的快照），
     * 增量跳过要的 dirUpt 也取自它。
     *
     * 只把有 fid 的目录收进来：早先 fid 为空时会拿空串当 cid 塞进结果，
     * 而空 cid 在 115 那边就是根目录 —— 会把根目录的内容当成某个子目录扫一遍。
     *
     * 停止请求在这里也查：大库的列目录阶段本身就是几百次请求、可能占掉整轮扫描的大半时间，
     * 不在这里响应的话用户点了「停止」要等很久才有反应。中途退出返回的是**不完整**的列表，
     * 所以调用方看到停止标志后必须整个放弃（那里也确实这么做了）。
     */
    private suspend fun collectDirs(
        rootCid: String,
        rootPath: String,
        includeSubDirs: Boolean,
        rateLimitMs: Long,
    ): List<DirListing> {
        // 不递归时这里不列目录，交给调用方（它本来就要列一次）
        if (!includeSubDirs) return listOf(DirListing(rootCid, rootPath, null))

        val rootPage = listFiles(rootCid, rateLimitMs)
        val result = mutableListOf(DirListing(rootCid, rootPath, rootPage))
        val queue = ArrayDeque<Pair<String, String>>()
        rootPage.items.filter { it.isDir }.forEach { dir ->
            dir.fid?.let { queue.add(it to "$rootPath/${dir.fn}") }
        }
        while (queue.isNotEmpty()) {
            if (stopRequested.get()) break
            val (cid, path) = queue.removeFirst()
            val page = listFiles(cid, rateLimitMs)
            result.add(DirListing(cid, path, page))
            page.items.filter { it.isDir }.forEach { dir ->
                dir.fid?.let { queue.add(it to "$path/${dir.fn}") }
            }
        }
        return result
    }

    /** 分集目录要用的上层信息：归属哪个系列、海报背景从哪继承 */
    private data class AncestorInfo(
        val seriesKey: String?,
        val posterPickCode: String?,
        val fanartPickCode: String?,
    )

    /**
     * 分集目录往上找：**归属的系列**（往上第一个"系列/影片"行）与**要继承的图**。
     *
     * 图的顺序：
     *  ① **本目录自己的季级图**（`season01-poster.jpg`）—— 它比系列海报更贴这一季
     *  ② 往上第一个"系列/影片"行带的图 —— 标准剧集包只在系列根放 `poster.jpg`/`fanart.jpg`，
     *     `Season 1/` 里什么都没有，整季卡片就会全空白
     *
     * 找不到祖先行时 `seriesKey` 为 null —— 这个目录的分集**不归到任何系列**，仍旧各占一张卡。
     * 实测 `test/多视频目录`（一堆集但没刮系列元数据）就是这种：造一条虚拟系列行也能收拢，
     * 但那会凭空多出一张没元数据没海报的卡，比现在更差。
     */
    private suspend fun ancestorInfoOf(dirPath: String, dirFiles: List<FileRef>, rootPath: String): AncestorInfo {
        val sp = dirFiles.firstOrNull { SEASON_ART_POSTER.matches(it.name) }
        val sf = dirFiles.firstOrNull { SEASON_ART_FANART.matches(it.name) }

        var seriesKey: String? = null
        var poster: String? = sp?.pickCode
        var fanart: String? = sf?.pickCode
        if (poster != null && fanart != null) return AncestorInfo(null, poster, fanart)

        for (ancestor in ancestorPaths(dirPath, rootPath)) {
            val row = dao.ancestorRowOf(ancestor) ?: continue
            if (seriesKey == null) seriesKey = row.mediaKey
            if (poster == null) poster = row.posterPickCode
            if (fanart == null) fanart = row.fanartPickCode
            if (seriesKey != null && poster != null && fanart != null) break
        }
        return AncestorInfo(seriesKey, poster, fanart)
    }

    /** 单条入库：聚类 → 下载 nfo 解析 → 一个事务。**返回入库用的 mediaKey**（调用方拿它清陈旧条目） */
    private suspend fun indexCluster(
        dirCid: String,
        dirPath: String,
        cluster: Cluster,
        isEpisodeLike: Boolean,
        rateLimitMs: Long = 0,
        /** 分集目录的上层信息：归哪个系列 + 继承的图；影片目录传 null */
        ancestor: AncestorInfo? = null,
    ): String {
        var meta = NfoMeta()
        cluster.nfo?.let { nfo ->
            if (nfo.pickCode.isNotEmpty()) {
                try {
                    meta = fetchNfoMeta(nfo.pickCode, nfo.upt, rateLimitMs)
                    if (!meta.hasContent) {
                        Log.w(TAG, "nfo 解析为空 ${cluster.prefix}（直链/内容异常），详情页会按需重拉")
                    }
                } catch (e: Exception) {
                    // 扫描期拉失败不能永久丢失元数据：详情页打开时按需重拉（refetchNfo）
                    Log.w(TAG, "nfo 拉取失败 ${cluster.prefix}: ${e.message}")
                }
            }
        }
        // 主键：影片用 nfo 的 tmdb id、分集一律用文件前缀（分集 nfo 的 id 常是整部剧的，
        // 拿它当主键会把整季覆盖成一行 —— 见 mediaKeyOf 的注释）
        val key = mediaKeyOf(cluster.prefix, meta.uniqueTmdbid, isEpisodeLike)
        val title = meta.title ?: cluster.prefix.substringBeforeLast('(').trim().ifEmpty { cluster.prefix }
        val actors = meta.actors.ifEmpty {
            // 演员式资源：nfo 没带演员时从目录名提取（`ABC-301 示例演员二,示例演员三`）
            actorsFromDirName(dirPath)
        }
        val movie = MovieEntity(
            mediaKey = key,
            title = title,
            year = meta.year ?: cluster.prefix.substringAfterLast('(').takeWhile { it.isDigit() }.toIntOrNull()
                ?: TMDB_ID.find(cluster.prefix)?.groupValues?.get(1)?.toIntOrNull(),
            rating = meta.rating,
            plot = meta.plot,
            genre = meta.genres.joinToString(" / ").ifEmpty { null },
            isEpisodeLike = isEpisodeLike,
            dirCid = dirCid,
            dirPath = dirPath,
            videoPickCode = cluster.video?.pickCode,
            videoName = cluster.video?.name,
            // 自己的图优先；没有才用继承来的（分集继承系列的海报/背景）
            posterPickCode = cluster.poster?.pickCode ?: ancestor?.posterPickCode,
            fanartPickCode = cluster.fanart?.pickCode ?: ancestor?.fanartPickCode,
            thumbPickCode = cluster.thumb?.pickCode,
            nfoPickCode = cluster.nfo?.pickCode,
            // file_id：只在「彻底删除」时用，空串折成 null（115 的空 id 传上去是参数非法）。
            // 图片的 fid 只存**自己目录里**的 —— 分集继承来的海报属于系列，删单集不该连带删掉系列封面
            videoFid = cluster.video?.fid?.takeIf { it.isNotEmpty() },
            nfoFid = cluster.nfo?.fid?.takeIf { it.isNotEmpty() },
            posterFid = cluster.poster?.fid?.takeIf { it.isNotEmpty() },
            fanartFid = cluster.fanart?.fid?.takeIf { it.isNotEmpty() },
            thumbFid = cluster.thumb?.fid?.takeIf { it.isNotEmpty() },
            // 分集归属的系列：海报墙只显示 seriesKey IS NULL 的顶层条目，点进系列再列分集
            seriesKey = ancestor?.seriesKey,
            nfoUpt = cluster.nfo?.upt ?: 0,
            sourceDirKey = dirCid,
            scannedAt = System.currentTimeMillis(),
        )
        dao.upsertMovieWithPeople(
            movie = movie,
            actors = actors,
            tags = meta.genres,
            episodes = if (!isEpisodeLike) emptyList() else listOf(
                EpisodeEntity(
                    mediaKey = key,
                    episodeKey = episodeKeyOf(cluster.prefix),
                    season = meta.season,
                    episode = meta.episode,
                    videoPickCode = cluster.video?.pickCode,
                    videoName = cluster.video?.name,
                    thumbPickCode = cluster.thumb?.pickCode,
                    nfoPickCode = cluster.nfo?.pickCode,
                ),
            ),
        )
        return key
    }

    /**
     * .nfo 内容（**缓存的是解析结果，不是原始 XML**）。
     *
     * 为什么缓存解析结果：原始字节现在走 `charStream()`，编码取决于响应头；存下来再读
     * 等于多一层编码风险，而且命中时还得重新解析一遍 XML。
     *
     * key 带 upt：文件没变（pickCode 与 upt 都没变）就永久命中 —— **重扫一个库的 nfo
     * 请求直接归零**，详情页的按需自愈也不会反复打 downurl。
     *
     * 空结果也缓存，但 TTL 短得多（见 [NFO_EMPTY_TTL_MS]）：nfo 本来就没写简介的片，
     * 详情页自愈的判据是"plot/rating 为空"，不缓存它就会每次进详情页重打一次；
     * 而"真失败"也长得一样，所以给个短 TTL，过一会儿还会重试。
     */
    private suspend fun fetchNfoMeta(pickCode: String, upt: Long, rateLimitMs: Long = 0): NfoMeta {
        val cacheKey = "nfo|$pickCode|$upt"
        val store = cache
        if (store != null) {
            store.getText(cacheKey)?.let { entry ->
                val cached = runCatching { nfoJson.decodeFromString<NfoMeta>(entry.body) }.getOrNull()
                val ttl = if (cached?.hasContent == true) NFO_TTL_MS else NFO_EMPTY_TTL_MS
                if (cached != null && store.isFresh(entry, ttl)) return cached
            }
        }
        val meta = downloadNfoMeta(pickCode, rateLimitMs) ?: return NfoMeta()
        // 拉到了就存（内容为空也存，靠上面的短 TTL 兜住重试）
        store?.putText(cacheKey, nfoJson.encodeToString(meta))
        return meta
    }

    /**
     * 下载并解析 .nfo。
     *
     * **返回 null = 没拉到**（直链失败 / HTTP 非 2xx / 空响应），
     * 返回 `NfoMeta()` = 拉到了但里面没内容。缓存必须区分这两者：
     * 前者不能缓存，否则一次网络抖动会被永久记成"这部片没有元数据"。
     */
    private suspend fun downloadNfoMeta(pickCode: String, rateLimitMs: Long): NfoMeta? {
        throttle(rateLimitMs)
        val url = resolvePickCodeUrl(pickCode)
        if (url == null) {
            Log.w(TAG, "nfo 直链解析失败 pickCode=$pickCode（downurl 无数据/无 url 字段）")
            return null
        }
        val req = Request.Builder().url(url).build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "nfo 下载失败 HTTP ${resp.code} pickCode=$pickCode")
                return null
            }
            val body = resp.body ?: run {
                Log.w(TAG, "nfo 下载空响应 pickCode=$pickCode")
                return null
            }
            // 必须用 charStream()：它返回 OkHttp 的 BomAwareReader，会**剥掉 UTF-8 BOM**。
            // 换成 bytes().inputStream().reader() 会让 BOM 留在 <?xml 前面 —— 那是非法 XML，
            // 解析器第一步就抛，被 NfoParser 的容错接住之后返回一个全 null 的 NfoMeta
            // （2026-09-22 实测：8 部片全部缓存成了空结果，界面上没有简介也没有评分）。
            val meta = body.charStream().use { NfoParser.parse(it) }
            Log.i(TAG, "nfo 解析完成 pickCode=$pickCode title=${meta.title} rating=${meta.rating} plot=${meta.plot?.take(30)}")
            return meta
        }
    }

    /** pickCode → 直链：复用 115 的 downUrl 端点（解析方式与 ImageUrlResolver 对齐） */
    private suspend fun resolvePickCodeUrl(pickCode: String): String? = try {
        val data = openApi.downUrl(pickCode).envData() ?: return null
        // 实测：data 的 key 不一定是 pickCode 本身（可能是 fid），条目里自带 pick_code 字段；
        // url 有时是对象 {url: …}、有时直接就是字符串，两种都要认
        val entry = data.values.firstOrNull() as? JsonObject ?: return null
        when (val el = entry["url"]) {
            is JsonObject -> (el.values.firstOrNull() as? JsonPrimitive)?.content
            is JsonPrimitive -> el.content
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "nfo downurl 请求异常 pickCode=$pickCode: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "MediaScanner"

        /** nfo 解析结果的序列化器（缓存里存的是 NfoMeta 的 JSON） */
        private val nfoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 有内容的 nfo：7 天。key 里带 upt，内容变了 key 就变，所以 TTL 只是兜底 */
        private const val NFO_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * 空 nfo：1 小时。
         *
         * 空结果分两种成因，长得一样但要求相反：nfo 本来就没写简介（重试一万次也还是空），
         * 和这次网络/频控没拉到（过一会儿就该重试）。取短 TTL 是折中 —— 前者一小时最多打一次，
         * 后者一小时内也会自己恢复。
         */
        private const val NFO_EMPTY_TTL_MS = 60L * 60 * 1000

        /**
         * 详情页按需自愈：扫描期 nfo 拉取失败（网络/频控）的影片没有简介/评分，
         * 这里重试一次并回写。幂等：nfo 本身还是那个 pick_code，拉不到就静默返回。
         *
         * 有缓存时这里通常是**零请求**：nfo 本来就没内容的片会命中那条空结果缓存，
         * 不再每次进详情页都打一次 downurl（downurl 最容易触发频控）。
         */
        suspend fun refetchNfo(
            openApi: OpenApi,
            okHttpClient: OkHttpClient,
            dao: MediaDao,
            cache: MediaCache?,
            mediaKey: String,
        ) {
            val movie = dao.movie(mediaKey) ?: return
            val nfoPc = movie.nfoPickCode?.takeIf { it.isNotEmpty() } ?: return
            // 已有简介/评分就不浪费一次 downurl（它最容易触发频控）
            if (!movie.plot.isNullOrBlank() || movie.rating != null) return
            val scanner = MediaScanner(openApi, okHttpClient, dao, cache)
            val meta = try {
                // nfo 下载是同步网络请求：调用方（详情页 produceState）在主线程，
                // 直接执行会抛 NetworkOnMainThreadException——必须切到 IO
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.fetchNfoMeta(nfoPc, movie.nfoUpt)
                }
            } catch (e: Exception) {
                Log.w(TAG, "nfo 按需重拉失败 $mediaKey: ${e.message}")
                return
            }
            if (!meta.hasContent) return
            dao.upsertMovie(
                movie.copy(
                    title = meta.title ?: movie.title,
                    year = movie.year ?: meta.year,
                    rating = movie.rating ?: meta.rating,
                    plot = movie.plot ?: meta.plot,
                    genre = movie.genre ?: meta.genres.joinToString(" / ").ifEmpty { null },
                ),
            )
        }
    }
}
