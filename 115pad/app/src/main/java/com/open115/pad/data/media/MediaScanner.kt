package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.parseFilesResponse
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
 */
class MediaScanner(
    private val openApi: OpenApi,
    private val okHttpClient: OkHttpClient,
    private val dao: MediaDao,
) {
    data class Progress(
        val running: Boolean = false,
        val doneDirs: Int = 0,
        val totalDirs: Int = 0,
        val currentDir: String = "",
        val moviesIndexed: Int = 0,
        val finishedAt: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val mutex = Mutex()

    /** 实际执行（调用方在自己的 scope 里 launch）：手动触发、可续跑、带进度 */
    suspend fun runScan(rootCid: String, rootPath: String, includeSubDirs: Boolean = true) {
        mutex.withLock {
            if (_progress.value.running) return
            _progress.value = _progress.value.copy(running = true, doneDirs = 0, totalDirs = 0, moviesIndexed = 0)
        }
        try {
            var movies = 0
            var done = 0
            val queue = ArrayDeque<Pair<String, String>>()
            queue.add(rootCid to rootPath)
            // 先统计总数（递归列目录），供进度条用
            val allDirs = collectDirs(rootCid, rootPath, includeSubDirs)
            _progress.value = _progress.value.copy(totalDirs = allDirs.size)
            for ((cid, path) in allDirs) {
                _progress.value = _progress.value.copy(currentDir = path)
                val page = parseFilesResponse(openApi.files(cid = cid, limit = 200))
                val dirUpt = page.items.maxOfOrNull { it.upt } ?: 0L
                // 手动扫描不增量跳过：nfo 链路修过一轮，旧扫描（解析坏掉的时期）留下的
                // 空元数据要靠重扫补齐；nfo 都是小文件，重扫的代价只有每目录一次 files 列表
                // + 每影片一次 downurl（签名期内 LRU 命中不重复）。
                // state 保留在库里，将来数据量大再恢复增量。
                val files = page.items.filter { !it.isDir }.map {
                    FileRef(name = it.fn, pickCode = it.pc ?: "", sizeBytes = it.fs, upt = it.upt)
                }
                val scan = sniffDirectory(files)
                for (cluster in scan.clusters) {
                    try {
                        indexCluster(cid, path, cluster, isEpisodeLike = scan.isMultiVideo)
                        movies++
                    } catch (e: Exception) {
                        Log.w("MediaScanner", "索引失败 ${cluster.prefix}: ${e.message}")
                    }
                }
                dao.upsertScanState(
                    ScanStateEntity(
                        dirKey = cid,
                        dirPath = path,
                        status = 2,
                        cloudUpt = dirUpt,
                        scannedAt = System.currentTimeMillis(),
                    ),
                )
                done++
                _progress.value = _progress.value.copy(doneDirs = done, moviesIndexed = movies)
            }
            _progress.value = _progress.value.copy(running = false, finishedAt = System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("MediaScanner", "扫描中断: ${e.message}")
            _progress.value = _progress.value.copy(running = false, finishedAt = System.currentTimeMillis())
        }
    }

    /** 递归收集子目录（手动扫描是一次性任务，不并发，控制频控代价） */
    private suspend fun collectDirs(rootCid: String, rootPath: String, includeSubDirs: Boolean): List<Pair<String, String>> {
        if (!includeSubDirs) return listOf(rootCid to rootPath)
        val result = mutableListOf(rootCid to rootPath)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootCid to rootPath)
        while (queue.isNotEmpty()) {
            val (cid, path) = queue.removeFirst()
            // 只列子目录：一页 200 足够大多数目录；目录数超出的极端情况后续增量补
            val page = parseFilesResponse(openApi.files(cid = cid, limit = 200))
            page.items.filter { it.isDir }.forEach { dir ->
                result.add((dir.fid ?: "") to "$path/${dir.fn}")
                if (dir.fid != null) queue.add(dir.fid to "$path/${dir.fn}")
            }
        }
        return result
    }

    /** 单条入库：聚类 → 下载 nfo 解析 → 一个事务 */
    private suspend fun indexCluster(dirCid: String, dirPath: String, cluster: Cluster, isEpisodeLike: Boolean) {
        var meta = NfoMeta()
        cluster.nfo?.let { nfo ->
            if (nfo.pickCode.isNotEmpty()) {
                try {
                    meta = fetchNfoMeta(nfo.pickCode)
                    if (meta.plot == null && meta.rating == null && meta.title == null) {
                        Log.w(TAG, "nfo 解析为空 ${cluster.prefix}（直链/内容异常），详情页会按需重拉")
                    }
                } catch (e: Exception) {
                    // 扫描期拉失败不能永久丢失元数据：详情页打开时按需重拉（refetchNfo）
                    Log.w(TAG, "nfo 拉取失败 ${cluster.prefix}: ${e.message}")
                }
            }
        }
        val key = meta.uniqueTmdbid?.let { "tmdb-$it" } ?: cluster.mediaKey()
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
            posterPickCode = cluster.poster?.pickCode,
            fanartPickCode = cluster.fanart?.pickCode,
            nfoPickCode = cluster.nfo?.pickCode,
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
    }

    /** 下载 .nfo 并解析（直链由调用方解析后传入更优；这里先用 pickCode 走 downurl） */
    private suspend fun fetchNfoMeta(pickCode: String): NfoMeta {
        val url = resolvePickCodeUrl(pickCode)
        if (url == null) {
            Log.w(TAG, "nfo 直链解析失败 pickCode=$pickCode（downurl 无数据/无 url 字段）")
            return NfoMeta()
        }
        val req = Request.Builder().url(url).build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "nfo 下载失败 HTTP ${resp.code} pickCode=$pickCode")
                return NfoMeta()
            }
            val body = resp.body ?: return NfoMeta().also { Log.w(TAG, "nfo 下载空响应 pickCode=$pickCode") }
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

        /**
         * 详情页按需自愈：扫描期 nfo 拉取失败（网络/频控）的影片没有简介/评分，
         * 这里重试一次并回写。幂等：nfo 本身还是那个 pick_code，拉不到就静默返回。
         */
        suspend fun refetchNfo(openApi: OpenApi, okHttpClient: OkHttpClient, dao: MediaDao, mediaKey: String) {
            val movie = dao.movie(mediaKey) ?: return
            val nfoPc = movie.nfoPickCode?.takeIf { it.isNotEmpty() } ?: return
            // 已有简介/评分就不浪费一次 downurl（它最容易触发频控）
            if (!movie.plot.isNullOrBlank() || movie.rating != null) return
            val scanner = MediaScanner(openApi, okHttpClient, dao)
            val meta = try {
                // nfo 下载是同步网络请求：调用方（详情页 produceState）在主线程，
                // 直接执行会抛 NetworkOnMainThreadException——必须切到 IO
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.fetchNfoMeta(nfoPc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "nfo 按需重拉失败 $mediaKey: ${e.message}")
                return
            }
            if (meta.plot == null && meta.rating == null && meta.title == null && meta.genres.isEmpty()) return
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
