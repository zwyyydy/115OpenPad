package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.FileItem
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.parseFilesResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 服务器写的那份信号（与流水线端的契约，见那边的 `PAD_SIGNAL.md`）。
 *
 * 只用到 [dirs]/[gone] 两个字段：目录是**115 绝对路径**，App 侧拿它去反查 cid。
 * [codes]/[actors] 是给人看的（日志/排查），另外也是为了服务端解析不出目录时有兜底线索 ——
 * 这一版 App 直接用 dirs，解析不出目录的信号就只记日志。
 */
@Serializable
internal data class PadSignal(
    val v: Int = 1,
    val ts: String = "",
    val source: String = "",
    val run: String = "",
    val codes: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val dirs: List<String> = emptyList(),
    val gone: List<String> = emptyList(),
)

/** 一次同步的结果（设置页状态行 + 日志） */
data class PadSyncReport(
    val signals: Int = 0,
    val dirs: Int = 0,
    val gone: Int = 0,
    val note: String,
)

internal val padSignalJson = Json { ignoreUnknownKeys = true }

/** 读一份信号。格式不对返回 null（调用方会退化成整库快速扫描，见 [PadSync.pollInner]） */
internal fun parseSignal(text: String): PadSignal? =
    runCatching { padSignalJson.decodeFromString<PadSignal>(text) }.getOrNull()

/**
 * [names] 里还没处理过的信号文件名（旧→新）。
 *
 * 名字是服务器给的时间戳（`20260928-103138.json`），所以**字符串比大小即时间序** ——
 * 不需要解析时间、也不受设备时钟影响。[cursor] 为空 = 头一次，全部算新。
 */
internal fun newSignalNames(cursor: String, names: List<String>): List<String> =
    names.filter { it.endsWith(".json") && it > cursor }.sorted()

/**
 * 一条 115 路径落在哪个库里，以及**换算成那个库的路径写法**。
 *
 * ★ 为什么要换算：库里存的路径写法不统一 —— 实测同一个账号下有 `媒体库/子目录/...`（不带前导斜杠）
 *   也有 `/另一个库`（带）。而服务器写的信号永远是 `/媒体库根/...` 这种带前导斜杠的绝对路径。
 *   只比字符串的话，不带斜杠的库会整库匹配不上；即使匹配上，拿服务器那套写法去扫，
 *   写进 `dirPath` 的形式也会跟历史行不一致（同一目录出现两份索引）。
 */
internal data class PathMatch(val library: MediaLibraryEntity, val path: String)

/**
 * 按"去掉前导斜杠再比"的规则找**最长**匹配的库根（库根可能嵌套，
 * 比如 `/媒体库根` 与 `/媒体库根/JAV_output` 同时存在时，后者才是对的），
 * 并把路径改写成该库根自己的写法。返回 null = 这条路径不在任何库根下（扫不进任何库，丢掉）。
 */
internal fun libraryForPath(path: String, libraries: List<MediaLibraryEntity>): PathMatch? {
    val bare = path.trim().trimEnd('/').trimStart('/')
    if (bare.isEmpty()) return null
    return libraries
        .flatMap { lib -> lib.rootPaths.map { it to lib } }
        .filter { (root, _) ->
            val rb = root.trim().trimEnd('/').trimStart('/')
            rb.isNotEmpty() && (bare == rb || bare.startsWith("$rb/"))
        }
        .maxByOrNull { (root, _) -> root.trim().trimEnd('/').length }
        ?.let { (root, lib) ->
            val r = root.trim().trimEnd('/')
            PathMatch(lib, r + bare.removePrefix(r.trimStart('/')))
        }
}

/**
 * 逐级上溯的候选父路径（`/a/b/c` → `/a/b`、`/a`），[floor] 是下界（库根，含）。
 *
 * 反查 cid 用：目标目录可能还没扫过（新片），但它的上级一定扫过 ——
 * 拿上级的 cid 列一层就能换到目标的 cid。
 */
internal fun ancestorPaths(path: String, floor: String): List<String> {
    val out = mutableListOf<String>()
    val stop = floor.trimEnd('/')
    var cur = path.trimEnd('/').substringBeforeLast('/', "")
    while (cur.isNotEmpty() && cur.length >= stop.length && out.size < 8) {
        out += cur
        if (cur == stop) break
        cur = cur.substringBeforeLast('/', "")
    }
    return out
}

/**
 * 流水线同步：把服务器刮削的结果搬进媒体库。
 *
 * **为什么需要它**：媒体库是 App 自己爬 115 目录攒出来的本地索引，触发时机只有
 * 「手动点按」和「启动时的自动扫描」—— 服务器那边（刮削流水线）改完了，App 并不知道。
 * 所以服务器每轮刮削跑完会往 115 里写 `<信号目录>/<时间戳>.json`（默认 `/媒体库根/.pad_signal/`），
 * 这里负责把它读出来、变成"只扫这几个目录"的任务。
 *
 * 一轮做的事（每个环节都写在对应函数上）：
 *
 *  1. [SIGNAL_DIR_NAME] 目录列一次（**1 个请求**，没新文件就到此为止）；
 *  2. 读出新信号的 `dirs`/`gone`，按库根分组（不在任何库里的路径丢掉）；
 *  3. `dirs`：**先清这个目录的 nfo/海报缓存**，再排一条"只扫这些目录"的队列任务
 *     （[MediaScanner.enqueueTargeted]，Full + 不递归）；
 *  4. `gone`：按路径前缀把索引连扫描状态一起清掉（影片被移走/源目录被删，留着就是幽灵卡片）；
 *  5. 游标（处理到哪个文件）落盘，下次接着走 —— 离线期间攒下的信号不会丢。
 *
 * **清缓存这步是必须的**：115 上的 nfo/海报是**原地覆盖**写的（cd2 不留 `（1）` 副本），
 * pick_code 很可能没变，而 nfo 缓存的 key 是 `nfo|版本|pickCode|upt`、海报缓存按
 * `img_<pickCode>` 命名 —— 不清就永远命中旧内容，重扫了也看不见新简介/新海报。
 *
 * ★ 请求量：一轮正常同步就是"列信号目录 1 次 + 每个变动目录几次"，很小；**只有**积压太多时
 *   会退化成快速扫描，那一轮的量由库自己的限速（`MediaLibraryEntity.rateLimitMs`）决定 ——
 *   库没设限速时建议补一个（500~1000ms），否则大库的一轮快速扫描会把 115 的访问上限打满。
 */
class PadSync(
    private val api: OpenApi,
    private val okHttpClient: OkHttpClient,
    private val dao: MediaDao,
    private val scanner: MediaScanner,
    private val prefs: MediaPrefs,
    private val mediaCache: MediaCache?,
    private val imageUrlResolver: ImageUrlResolver,
    private val imageCacheDir: File,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "PadSync"

        /** 服务器默认写的信号目录名（在媒体库根下面） */
        internal const val SIGNAL_DIR_NAME = ".pad_signal"

        /** 一次列信号目录取多少条（服务器只留最近 50 个） */
        private const val SIGNAL_LIST_LIMIT = 100

        /** 一轮最多按信号定向扫几个目录；超了改成整库快速扫描（省请求也更稳） */
        private const val MAX_TARGETED_DIRS = 80

        /** 一轮最多吃几个积压信号；超了同上（离线好几天之后的补课） */
        private const val MAX_TARGETED_SIGNALS = 12

        /**
         * 同一个信号连续读不出来多少轮之后**跳过它**。
         *
         * 读失败基本都是瞬时的（115 频控、网络抖动），所以前几轮不推进游标、原地下次再试；
         * 但万一是文件真坏了，不能让后面所有信号永远堵着 —— 到次数就跳过并提示。
         */
        private const val MAX_READ_RETRIES = 5
    }

    private val mutex = Mutex()
    private var loop: Job? = null

    /** 这个进程里找过信号目录了吗 —— 找不到时不至于每轮都去列库根（手动同步会重置它） */
    private var discoverTried = false

    /** 连续"信号读不出来"的轮数（见 [MAX_READ_RETRIES]） */
    private var readFailures = 0

    /** 最近一次列目录失败的原因（限流/网络）—— 用来把状态行写实话，别把"列不动"说成"没新信号" */
    private var lastListError: String? = null

    /** 起常驻轮询（登录后调）。重复调只起一个；间隔每轮重读，改设置下一轮就生效 */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                runCatching { poll(manual = false) }
                val minutes = runCatching { prefs.padPollMinutes.first() }.getOrDefault(10)
                delay(minutes.coerceIn(1, 24 * 60) * 60_000L)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** 设置页「立即同步」：忽略"找过没找到"的记忆，重找一次信号目录 */
    suspend fun syncNow(): PadSyncReport = poll(manual = true)

    private suspend fun poll(manual: Boolean): PadSyncReport {
        val report = try {
            mutex.withLock { pollInner(manual) }
        } catch (e: Exception) {
            Log.w(TAG, "同步失败: ${e.message}")
            PadSyncReport(note = "同步失败：${e.message ?: "未知错误"}")
        }
        runCatching { prefs.setPadLast(System.currentTimeMillis(), report.note) }
        if (report.signals > 0 || report.dirs > 0 || report.gone > 0 || manual) {
            Log.i(TAG, "同步：${report.note}")
        }
        return report
    }

    private suspend fun pollInner(manual: Boolean): PadSyncReport {
        if (!prefs.padSyncEnabled.first()) return PadSyncReport(note = "已关闭")
        if (manual) discoverTried = false

        val dir = signalDir() ?: return PadSyncReport(
            note = lastListError?.let { "列目录失败：$it" } ?: "没找到信号目录 $SIGNAL_DIR_NAME",
        )
        val page = listFiles(dir.cid) ?: return PadSyncReport(note = "列信号目录失败：${lastListError ?: "网络异常"}")
        val files = page.items.filter { !it.isDir }
        val cursor = prefs.padCursor.first()
        val fresh = newSignalNames(cursor, files.map { it.fn })
        if (fresh.isEmpty()) return PadSyncReport(note = "无新信号")

        val byName = files.associateBy { it.fn }
        val signals = mutableListOf<PadSignal>()
        var broken = 0
        fresh.forEach { name ->
            val text = byName[name]?.let { readText(it.pc) }
            val parsed = text?.let { parseSignal(it) }
            if (parsed == null) broken++ else signals += parsed
        }

        val libraries = dao.libraries().first()
        val newest = fresh.last()
        val dirs = signals.flatMap { it.dirs }.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        val gone = signals.flatMap { it.gone }.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        // 读不出来（多半是 115 频控/网络抖动）：**不推进游标**，下一轮原样重试 ——
        // 把游标推过去就等于这批变动永远丢了（信号文件还在，重试是免费的）。
        // 只有连续多次都读不出来（文件真坏了）才跳过，免得后面的信号被永远堵着。
        if (broken > 0) {
            readFailures++
            if (readFailures < MAX_READ_RETRIES) {
                Log.w(TAG, "有 $broken 个信号读不出来（第 $readFailures 次），本轮不推进游标，下次重试")
                return PadSyncReport(signals = fresh.size, note = "有 $broken 个信号读不出来，稍后重试")
            }
            Log.w(TAG, "有 $broken 个信号连续 $readFailures 次读不出来，跳过它们")
            prefs.setPadCursor(newest)
            readFailures = 0
            return PadSyncReport(signals = fresh.size, note = "有 $broken 个信号读不出来，已跳过")
        }
        readFailures = 0

        // 信号本身没问题、只是积压太多（离线好几天）：别逐个目录追了，整库快速扫一遍更稳
        // （快速扫描省请求，且"没变的叶子目录"本来就会被剪掉）。
        // ★ 只扫**信号里那些目录所属的库**，不是所有库 —— 积压时按整账号的库全扫一遍，
        //   碰上大库（几百个演员目录）会瞬间打满 115 的访问上限（实测踩过，见 PAD_SIGNAL.md）。
        if (signals.size > MAX_TARGETED_SIGNALS || dirs.size > MAX_TARGETED_DIRS) {
            val owners = dirs.mapNotNull { libraryForPath(it, libraries)?.library?.id }.distinct()
            owners.mapNotNull { id -> libraries.firstOrNull { it.id == id } }
                .forEach { scanner.enqueue(it, ScanMode.Fast) }
            prefs.setPadCursor(newest)
            return PadSyncReport(
                signals = signals.size,
                note = "积压 ${signals.size} 个信号/${dirs.size} 个目录，改为这 ${owners.size} 个库快速扫描",
            )
        }

        // dirs 落到库：不在任何库根下的路径本来也不会入库，丢掉（日志里记个数）。
        // 落库时**同时把路径改写成该库的写法**（见 libraryForPath），后面查 cid、写 dirPath 都用它。
        val matches = dirs.mapNotNull { p -> libraryForPath(p, libraries)?.let { it.library.id to it } }
        val grouped = matches.groupBy({ it.first }, { it.second.path })
        val outside = dirs.size - matches.size
        var queued = 0
        var resolvedCount = 0
        grouped.forEach { (libId, paths) ->
            val library = libraries.firstOrNull { it.id == libId } ?: return@forEach
            val resolved = paths.mapNotNull { p -> resolveDirCid(library, p)?.let { it to p } }
            resolvedCount += resolved.size
            if (resolved.isNotEmpty()) {
                // 先失效缓存再入队：扫描是队列串行执行的，等它开跑时缓存已经被清掉了
                resolved.forEach { (_, p) -> dropCaches(p) }
                scanner.enqueueTargeted(library, resolved)
                queued++
            } else {
                Log.i(TAG, "「${library.name}」这几个目录没解析出 cid（跳过）：$paths")
            }
        }

        // gone：同样按库的写法换算，才能删到历史行（换算不出来就按原样删）
        gone.forEach { raw -> deleteGone(libraryForPath(raw, libraries)?.path ?: raw) }
        prefs.setPadCursor(newest)

        val note = buildString {
            append("同步 ${signals.size} 个信号")
            if (resolvedCount > 0) append("，排了 $resolvedCount 个目录（$queued 个库）")
            if (gone.isNotEmpty()) append("，清掉 ${gone.size} 个已移走目录")
            if (outside > 0) append("，${outside} 个目录不在任何库内")
        }
        return PadSyncReport(signals = signals.size, dirs = resolvedCount, gone = gone.size, note = note)
    }

    // ---- 信号目录：先用手里的 cid，没有就自己找（媒体库根下面的 .pad_signal）----

    private data class DirRef(val cid: String, val name: String)

    private suspend fun signalDir(): DirRef? {
        val saved = prefs.padSignalCid.first()
        if (saved.isNotEmpty()) return DirRef(saved, prefs.padSignalName.first())
        if (discoverTried) return null
        discoverTried = true

        // 自己找：库根都是已知 cid，列一层看有没有 .pad_signal（一次请求）
        dao.libraries().first().forEach { lib ->
            lib.rootCids.zip(lib.rootPaths).forEach { (cid, _) ->
                val hit = listFiles(cid)?.items?.firstOrNull { it.isDir && it.fn == SIGNAL_DIR_NAME }
                if (hit != null) {
                    val found = DirRef(hit.fid ?: return@forEach, hit.fn)
                    Log.i(TAG, "找到信号目录「${found.name}」cid=${found.cid}")
                    prefs.setPadSignalDir(found.cid, found.name)
                    return found
                }
            }
        }
        Log.i(TAG, "没找到信号目录 $SIGNAL_DIR_NAME（库根下没有）")
        return null
    }

    private suspend fun listFiles(cid: String) =
        runCatching { parseFilesResponse(api.files(cid = cid, limit = SIGNAL_LIST_LIMIT)) }
            .onSuccess { lastListError = null }
            .onFailure {
                lastListError = it.message ?: it::class.java.simpleName
                Log.w(TAG, "列目录失败 cid=$cid: ${it.message}")
            }
            .getOrNull()

    /**
     * 读信号文件正文：115 要先用 pick_code 换直链再 GET。
     *
     * 失败的常见成因是 115 的频控 —— 返回 null 让上层走"整库快速扫描"兜底，不在这里重试。
     */
    private suspend fun readText(pickCode: String?): String? {
        if (pickCode.isNullOrEmpty()) {
            Log.w(TAG, "信号文件没有 pick_code，读不了")
            return null
        }
        return runCatching {
            val env = api.downUrl(pickCode)
            val data = env.envData()
            if (data == null) {
                // 频控/失效在这一步就出结果：115 回的是 state=false + error 文案
                Log.w(TAG, "信号 downurl 没给 data：${env.envMsg() ?: env.toString().take(120)}")
                return null
            }
            // data 的 key 不一定是 pickCode（可能换成 fid），条目里自带 pick_code；url 有时是对象、有时是字符串
            val entry = data.values.firstOrNull() as? JsonObject
            val url = when (val el = entry?.get("url")) {
                is JsonObject -> (el.values.firstOrNull() as? JsonPrimitive)?.content
                is JsonPrimitive -> el.content
                else -> null
            }
            if (url.isNullOrEmpty()) {
                Log.w(TAG, "信号 downurl 里没有 url：${entry?.toString()?.take(120)}")
                return null
            }
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "信号下载 HTTP ${resp.code}")
                        return@withContext null
                    }
                    resp.body?.string()
                }
            }
        }.onFailure {
            // 带上异常类型：115 这边的异常常带 null message，光打 message 查不出是谁
            Log.w(TAG, "读信号文件异常 ${it::class.java.simpleName}: ${it.message}")
        }.getOrNull()
    }

    // ---- 路径 → cid → 扫描任务 ----

    /**
     * 115 路径换 cid：
     *  ① 扫过的目录：`scan_state` 里按路径直接反查（零请求）；
     *  ② 其它目录（新片、新演员目录，甚至**整个库还没扫过**）：选一个"手里已经有 cid"的起点，
     *     再从那儿一层层列下去 —— 起点优先取路径下最深的、`scan_state` 里有的祖先；
     *     一个都没有就退回**库根本身**（它的 cid 是库设置里现成的，不需要任何请求换来）。
     */
    private suspend fun resolveDirCid(library: MediaLibraryEntity, path: String): String? {
        dao.scanStateKeyByPath(path)?.let { return it }
        val rootPair = library.rootCids.zip(library.rootPaths)
            .filter { (_, rp) -> path == rp.trimEnd('/') || path.startsWith(rp.trimEnd('/') + "/") }
            .maxByOrNull { (_, rp) -> rp.length }
            ?: return null
        val rootPath = rootPair.second.trimEnd('/')
        val hit = ancestorPaths(path, rootPath).firstNotNullOfOrNull { anc ->
            dao.scanStateKeyByPath(anc)?.let { anc to it }
        }
        val basePath = hit?.first ?: rootPath
        var cid = hit?.second ?: rootPair.first
        val rest = path.removePrefix(basePath).trimStart('/').split('/').filter { it.isNotEmpty() }
        for (seg in rest) {
            cid = listFiles(cid)?.items?.firstOrNull { it.isDir && it.fn == seg }?.fid ?: return null
        }
        return cid
    }

    /**
     * 清这个目录下面条目的 nfo/海报缓存（**按 pick_code 精确清**，不能用粗前缀，
     * 那会把别的库的缓存一起清掉）。理由见 [PadSync] 的类注释。
     */
    private suspend fun dropCaches(path: String) {
        val codes = runCatching { dao.pickCodesInPath(path) }.getOrDefault(emptyList())
        if (codes.isEmpty()) return
        mediaCache?.invalidateAll(
            codes.mapNotNull { it.nfoPickCode }.distinct().map { MediaScanner.nfoCachePrefix(it) },
        )
        imageUrlResolver.evictPosterCache(
            codes.flatMap { listOfNotNull(it.posterPickCode, it.fanartPickCode, it.thumbPickCode) }.distinct(),
            imageCacheDir,
        )
    }

    /** 云端已经没有的目录：缓存清掉 + 索引连扫描状态一起删（[MediaDao.deleteLibraryContent] 就是按路径前缀做的） */
    private suspend fun deleteGone(path: String) {
        dropCaches(path)
        dao.deleteLibraryContent(path)
        Log.i(TAG, "清理已移走的目录：$path")
    }
}
