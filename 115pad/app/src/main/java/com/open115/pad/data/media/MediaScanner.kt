package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.FileItem
import com.open115.pad.data.FilesPage
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.parseFilesResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 排队等待扫描的一个任务（一个媒体库一条；多根库的各根由 worker 依次跑）。
 *
 * 库的设置（限速 / 体积过滤 / 根路径）在**入队时快照**：任务代表"按下按钮那一刻的样子"，
 * 之后在队列里等半天、用户改了库的设置，这一轮也不该跟着变（下一轮扫描用新设置）。
 *
 * 这个类要**落盘**（见 MediaScanner 的 ScanQueueSnapshot）：所以字段都是可序列化的基本类型，
 * 库名也是冗余存下来的（重启后不用再查库就能显示"排队中：某某库"）。
 */
@Serializable
data class QueuedScan(
    val libraryId: Long,
    val libraryName: String,
    val rootCids: List<String>,
    val rootPaths: List<String>,
    val incremental: Boolean,
    val rateLimitMs: Long = 0,
    val minVideoSizeMb: Int = 0,
) {
    companion object {
        /** 按库当前的设置造一个扫描任务 */
        fun of(library: MediaLibraryEntity, incremental: Boolean) = QueuedScan(
            libraryId = library.id,
            libraryName = library.name,
            rootCids = library.rootCids,
            rootPaths = library.rootPaths,
            incremental = incremental,
            rateLimitMs = library.rateLimitMs,
            minVideoSizeMb = library.minVideoSizeMb,
        )
    }
}

/**
 * 落盘的队列快照：[running] 是上次正在跑的那一轮（进程被杀时它只跑了一半），
 * [pending] 是还没轮到的。
 *
 * **分两段存**是为了重启后区别对待（见 [MediaScanner.restoreQueue]）：
 * running 降级成增量接着跑，pending 原样恢复（用户当时选的是增量还是全量，照旧）。
 */
@Serializable
internal data class ScanQueueSnapshot(
    val running: QueuedScan? = null,
    val pending: List<QueuedScan> = emptyList(),
)

/**
 * 入队：**同一个库已经在队列里就就地替换**（不换位置），否则排到队尾。
 *
 * 替换而不是再排一条：用户连点两次扫描是"改主意"（比如把增量改成全量），不是想扫两遍；
 * 重复入队的任务也不会把队列顶长。就地不换位置，则是别让重复点击把这一条一直挤到别人后面。
 *
 * 纯函数 —— 队列的顺序与去重语义单测直接打这个。
 */
internal fun queueWithTask(queue: List<QueuedScan>, task: QueuedScan): List<QueuedScan> {
    val at = queue.indexOfFirst { it.libraryId == task.libraryId }
    if (at < 0) return queue + task
    return queue.toMutableList().also { it[at] = task }
}

/**
 * 快照 → 要重新排的任务：上次没跑完的那条**降级成增量**（理由见 [MediaScanner.restoreQueue]），
 * 排在最前面；当时排着的原样跟在后面（用户选的是增量还是全量，照旧）。
 *
 * 纯函数，单测直接打 —— 这条降级规则漏了、或者写成"原样恢复"，
 * 重启后就会把一个扫了一半的库从头再问一遍。
 */
internal fun tasksToRestore(snapshot: ScanQueueSnapshot): List<QueuedScan> =
    listOfNotNull(snapshot.running?.copy(incremental = true)) + snapshot.pending

/**
 * 媒体库扫描引擎：手动触发、可续跑、带进度。
 * 扫描一个目录 = 列目录（响应里自带全部文件名，聚类零额外请求）→ 聚类 →
 * 逐条下载 .nfo（小文件）解析 → 入库（每条一个事务）→ 预取海报 → 更新 scan_state。
 *
 * 多个库可以**同时排进队列**（正在扫的时候再点扫描不再被拒绝），由 worker **顺序执行** ——
 * 115 有频控，并发扫多个库只会互相拖慢、还更容易撞上限。
 * 排队 / 取消 / 清空见 [enqueue] / [cancelQueued] / [clearQueue] / [skipCurrent] / [stopAll]。
 * 队列会落盘（见 [restoreQueue]）：进程被杀之后重启，剩下的任务接着跑。
 *
 * 中断后重启能续跑：已完成的目录**指纹一致**就跳过（见 [dirFingerprintOf]）。
 * 早先只看"目录内 upt 的最大值"，那东西发现不了删除/改名/移入 —— 115 里目录项的 upt
 * 就是创建时间，内容变化不顶它（实测「示例影片（系列）」从建库起就没动过）。
 *
 * 接口调用量（115 有频控，这里是媒体库最大的一处）：
 * - 列目录：**每个目录每次扫描 1 次**（目录超过一页时按 count 翻页，见 [listFiles]）。
 *   collectDirs 那次列目录的结果会传给扫描循环复用，
 *   早先是列两遍（collectDirs 找子目录一遍、循环取文件又一遍）。
 *   降不到 0 —— 判断"目录变没变"本身就得问服务器，scan_state 只是记住上次看到的样子。
 * - .nfo：内容没变（pickCode + upt 相同）**零请求**，见 fetchNfoMeta。
 * - 海报：**首次扫描每条 1 次 downurl + 1 次下载**（[prefetchPoster]），
 *   之后命中落盘字节就零请求 —— 扫完进海报墙/详情页不必再等图。
 * - 剧照（`extrafanart/`）与演员头像（`.actors/`）：**只在"这个目录需要重扫"时各列一次**
 *   （见 [sideArtOf]），增量扫描跳过的目录**一次都不列**。字节一张都不下，留给详情页按需缓存。
 *   这两个目录是刮削一次就不动的素材，不值得每轮扫描都问一遍 —— 实测某库 248 个目录里
 *   164 个（66%）是它们，每轮都列等于把列目录阶段拖成三倍。
 */
class MediaScanner(
    private val openApi: OpenApi,
    private val okHttpClient: OkHttpClient,
    private val dao: MediaDao,
    /** 媒体库落盘缓存。为 null = 不缓存（测试 / 没接容器时） */
    private val cache: MediaCache? = null,
    /** 海报字节的落盘与直链解析（扫描期预取用）。为 null = 不预取海报 */
    private val imageUrlResolver: ImageUrlResolver? = null,
    private val imageCacheDir: File? = null,
    /** 队列落盘（重启接着扫）。为 null = 不落盘（一次性实例 / 测试） */
    private val prefs: MediaPrefs? = null,
) {
    /** 扫描阶段：进度条据此决定是"不确定"还是按目录数走 */
    enum class Phase { Idle, Listing, Indexing }

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
        /** 正在扫的是哪个库；null = 不是为某个库跑的（直接调 [runScan] 的场合） */
        val libraryId: Long? = null,
        /** 库名，进度卡片上显示"正在扫描「XX」"；空 = 只显示阶段与目录 */
        val libraryName: String = "",
        val phase: Phase = Phase.Idle,
        val doneDirs: Int = 0,
        val totalDirs: Int = 0,
        /**
         * 列目录阶段**已发现**的目录数。
         *
         * 这个阶段没有分母（目录数正是它要算的东西），大库又可能占掉整轮的大半时间 ——
         * 早先这里只能显示 0/0，看着像卡死。有了它至少能看出在往前走。
         */
        val discoveredDirs: Int = 0,
        val currentDir: String = "",
        val moviesIndexed: Int = 0,
        /** 本次扫描**新下载**的海报张数（命中落盘的不算） */
        val postersFetched: Int = 0,
        val finishedAt: Long = 0,
        /**
         * 这次「停止」连带撤掉的排队任务数。
         *
         * 「全部停止」停的是一批（当前 + 队列，见 [stopAll]），提示里要说清撤了几个 ——
         * 不然用户排了 5 个库、只看到"扫描已停止"，会以为剩下的还在等着跑。
         * 「跳过当前」不清队列，所以不置这个数（后面还会有下一个库接着跑）。
         */
        val cancelledQueued: Int = 0,
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

    // ---------------- 扫描队列（可排队、顺序执行） ----------------

    private val _pending = MutableStateFlow<List<QueuedScan>>(emptyList())

    /**
     * 排队等待执行的扫描任务（界面拿它显示「排队中」）。**队首就是下一个要跑的**；
     * 正在跑的那一条已经出队了，看不到它 —— 它体现在 [progress] 里。
     */
    val pending: StateFlow<List<QueuedScan>> = _pending

    /** 队列锁：所有队列改动都在这把锁里做（纯内存操作，synchronized 够用，不必 Mutex） */
    private val qLock = Any()

    /** 有人在跑 [drain] 循环吗 —— 免得排一次队就起一个循环（多个循环会抢任务、就并发了） */
    private var draining = false

    /**
     * 队列 worker 自己的 scope。
     *
     * **不能借用调用方的 scope**：界面上的调用点是 rememberCoroutineScope（离开媒体库页就没了，
     * 扫描会被半路取消）。这个 scope 与进程同寿 —— 排好队之后切页、锁屏都照跑。
     */
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 正在跑的那一条（已经出队了，所以不在 [pending] 里）。
     *
     * 界面不直接用它（进度卡上已经有库名了），它在这里是为了**落盘**：
     * 进程被杀时这一条只跑了一半，重启后要靠它接着跑（见 [restoreQueue]）。
     */
    private val _current = MutableStateFlow<QueuedScan?>(null)

    /**
     * 队列落盘通道。**CONFLATED + 一个写协程**，不直接 launch：
     * 每条任务起止都写一次，直接并发 launch 可能乱序落盘 —— 旧快照后到，
     * 重启后就会捡回一条早跑完（或早被撤掉）的任务。通道只留最新一份，写协程按顺序写。
     */
    private val persistChannel = Channel<String>(Channel.CONFLATED)

    /** 这一进程里捡过队列了吗（只捡一次，理由见 [restoreQueue]） */
    private var queueRestored = false

    init {
        val p = prefs
        if (p != null) {
            workerScope.launch {
                for (json in persistChannel) {
                    runCatching { p.setScanQueue(json) }
                        .onFailure { Log.w(TAG, "扫描队列落盘失败: ${it.message}") }
                }
            }
        }
    }

    /**
     * 把"当前 + 排队"整份写进偏好。**每次队列变动都调**（入队 / 撤单 / 清空 / 起跑 / 跑完）——
     * 少调一处的后果是重启后状态对不上（比如把已撤掉的任务捡回来）。
     *
     * 落盘是尽力而为：写不进去只记日志（队列是"顺手的事"，不该因为它把扫描本身搞挂）。
     */
    private fun persistQueue() {
        if (prefs == null) return
        val json = runCatching {
            // 快照在锁里取：当前 + 排队要**一起**读，不然可能拼出一个"任何时刻都不存在"的组合
            // （比如刚被撤掉的那条又出现在排队里、而它已经开跑了）。synchronized 可重入，
            // 所以从别的临界区里调进来也不会自己锁死自己。
            synchronized(qLock) {
                queueJson.encodeToString(
                    ScanQueueSnapshot(running = _current.value, pending = _pending.value),
                )
            }
        }.onFailure { Log.w(TAG, "扫描队列序列化失败: ${it.message}") }.getOrNull() ?: return
        persistChannel.trySend(json)
    }

    /**
     * 排一个扫描任务。**正在扫描时也能排** —— 排到队尾，当前这轮跑完自动开始下一个。
     * 同一个库已经在队列里（还没轮到）时是**替换**，见 [queueWithTask]。
     */
    fun enqueue(task: QueuedScan) {
        synchronized(qLock) { _pending.value = queueWithTask(_pending.value, task) }
        persistQueue()
        ensureDrain()
    }

    /** 按库当前的设置排队扫一个库（界面「增量/全量扫描」、启动自动扫描都走这里） */
    fun enqueue(library: MediaLibraryEntity, incremental: Boolean) =
        enqueue(QueuedScan.of(library, incremental))

    /** 这个库已经有扫描在跑、或者排在队列里吗（启动自动扫描据此不重复排，见 App115） */
    fun isBusy(libraryId: Long): Boolean =
        _current.value?.libraryId == libraryId || _pending.value.any { it.libraryId == libraryId }

    /** 撤掉某个库排队中的任务（排队卡片的 ×、删库前）。返回是否真的撤掉了 */
    fun cancelQueued(libraryId: Long): Boolean {
        var dropped = false
        synchronized(qLock) {
            val rest = _pending.value.filterNot { it.libraryId == libraryId }
            dropped = rest.size != _pending.value.size
            if (dropped) _pending.value = rest
        }
        if (dropped) persistQueue()
        return dropped
    }

    /** 清空队列，返回撤掉了几个任务（「全部停止」连队列一起清时用，见 [stopAll]） */
    fun clearQueue(): Int {
        val n = synchronized(qLock) { _pending.value.size.also { _pending.value = emptyList() } }
        if (n > 0) persistQueue()
        return n
    }

    /**
     * 把上次进程留下的队列捡回来（登录后调；**只捡一次**）。
     *
     * 自带一次性开关，不指望调用方记住：登录流会重复发 true（token 刷新也写 DataStore，
     * 而 loggedInFlow 是 map 出来的），捡两次的后果很实在 —— 一条已经跑完的任务会被重新排回来。
     *
     * **正在跑的那一条降级成增量**：它跑完的目录早已写进 scan_state，增量扫描正好从断点接着跑；
     * 原样按全量恢复的话，会把已经扫完的几百个目录从头再问一遍（白耗频控额度）。
     *
     * 顺序：未跑完的那条在前（它是更早的意图），然后是当时排着的。
     */
    suspend fun restoreQueue() {
        val p = prefs ?: return
        synchronized(qLock) {
            if (queueRestored) return
            queueRestored = true
        }
        val raw = runCatching { p.scanQueueJson() }.getOrNull()
        val snapshot = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { queueJson.decodeFromString<ScanQueueSnapshot>(it) }
                .onFailure { e -> Log.w(TAG, "扫描队列读不回来（忽略）: ${e.message}") }
                .getOrNull()
        } ?: return
        if (snapshot.running == null && snapshot.pending.isEmpty()) return
        val tasks = tasksToRestore(snapshot)
        tasks.forEach { enqueue(it) }
        Log.i(
            TAG,
            "恢复上次的扫描队列：排队 ${snapshot.pending.size} 个" +
                (snapshot.running?.let { "，未跑完「${it.libraryName}」（按增量接着扫）" } ?: ""),
        )
    }

    /** 队列非空、又没人在跑时启动 drain。入队、以及 drain 自己收尾时都叫它 */
    private fun ensureDrain() {
        val start = synchronized(qLock) {
            if (draining || _pending.value.isEmpty()) {
                false
            } else {
                draining = true
                true
            }
        }
        if (start) workerScope.launch { drain() }
    }

    /**
     * 队列循环：一条接一条顺序跑（115 有频控，并发扫多个库只会互相拖慢、更容易撞上限）。
     *
     * 取出任务时顺手**消费掉停止位**：那是上一条任务的停止意图（见 [skipCurrent] / [stopAll]），
     * 不该让下一个库替它挨这一下 —— 「跳过当前，继续下一个」正是靠这里成立的。
     */
    private suspend fun drain() {
        try {
            while (true) {
                val task = synchronized(qLock) {
                    val head = _pending.value.firstOrNull()
                    if (head != null) {
                        _pending.value = _pending.value.drop(1)
                        _current.value = head
                        stopRequested.set(false)
                    }
                    head
                } ?: return
                persistQueue()
                runCatching { runTask(task) }
                    .onFailure { Log.w(TAG, "排队扫描「${task.libraryName}」中断: ${it.message}") }
                // 这一条结束了（跑完 / 库已删跳过 / 抛了都一样）：清掉"正在跑"，落盘
                synchronized(qLock) { _current.value = null }
                persistQueue()
            }
        } finally {
            // 释放 + 兜底重启：循环退出与"刚有人入队"撞在一起时（那一方看到 draining=true 就没起新循环），
            // 这里再看一眼队列 —— 不然那条任务会永远躺在队列里没人跑。
            synchronized(qLock) { draining = false }
            ensureDrain()
        }
    }

    /** 跑一条排队任务：先确认库还在，再把各根目录依次扫掉 */
    private suspend fun runTask(task: QueuedScan) {
        // 库可能在排队期间被删了：**不能扫** —— 扫了就是把条目写进一个不存在的库，
        // 那些行既进不了海报墙、也不会再被任何一轮扫描清理（删库那条路径已经撤了队列里的它，
        // 这里是兜底）。只认库不认根：库还在、根路径被改过照扫（那是用户自己的编辑）。
        if (dao.library(task.libraryId) == null) {
            Log.i(TAG, "跳过排队扫描「${task.libraryName}」：库已不存在")
            return
        }
        task.rootCids.zip(task.rootPaths).forEach { (cid, path) ->
            runScan(
                cid, path,
                incremental = task.incremental,
                rateLimitMs = task.rateLimitMs,
                minVideoSizeMb = task.minVideoSizeMb,
                libraryId = task.libraryId,
                libraryName = task.libraryName,
            )
        }
    }

    /**
     * 跳过当前这一轮，**队列继续**（接着跑下一个）。UI 进度卡上的「跳过当前」。
     *
     * 没在跑也照样置停止位：队列里刚被取出、还没开始跑的那一条会在 [runScan] 开头看到它并放弃 ——
     * 那正是"跳过"该有的样子（那个空隙只有几毫秒，但不置位的话用户在空隙里点跳过会跳不掉）。
     *
     * 被跳过的这一轮**不丢进度**：扫完的目录都在 scan_state 里，以后用增量扫描能接着跑。
     */
    fun skipCurrent() {
        stopRequested.set(true)
        if (_progress.value.running) {
            _progress.value = _progress.value.copy(stopping = true)
        }
    }

    /**
     * 停当前这一轮 + **清空队列**（UI 排队卡上的「全部停止」、登出）。返回撤掉了几个排队任务。
     *
     * 与 [skipCurrent] 的分工：这个是"整批不干了"，那个是"这一个不干了、下一个接着上"。
     * 一停一批的语义用在这两个地方都成立 —— 用户点「全部停止」时不会还想让后面的跑下去；
     * 登出更是必须停（没 token 跑下去只会每个目录都失败、白占频控额度）。
     */
    fun stopAll(): Int {
        stopRequested.set(true)
        val dropped = clearQueue()
        if (_progress.value.running) {
            _progress.value = _progress.value.copy(stopping = true, cancelledQueued = dropped)
        }
        return dropped
    }

    /**
     * 停掉**某一个库**：撤掉它排队中的任务；正在跑的就是它的话，再停这一轮。
     * 返回 true = 真的停了正在跑的那一轮，调用方要等它收尾（见 [awaitStopped]）。
     *
     * 与 [skipCurrent] / [stopAll] 的区别是**不动别的库** —— 删库只该影响被删的那个库，
     * 拿全局停止来做会把用户排着的其他库一起撤掉。
     */
    fun stopLibrary(libraryId: Long): Boolean {
        cancelQueued(libraryId)
        if (!_progress.value.running || _progress.value.libraryId != libraryId) return false
        stopRequested.set(true)
        _progress.value = _progress.value.copy(stopping = true)
        return true
    }

    /**
     * 等当前这一轮扫描收尾（[skipCurrent] / [stopAll] / [stopLibrary] 之后用；没在跑就直接返回）。
     *
     * 判据是 [Progress.finishedAt] 变没变，**不能等 `running == false`**：队列里还有下一条时，
     * running 会在"这一条收尾、下一条起跑"之间闪一下 false，而 StateFlow 还会把中间态合并掉 ——
     * 等它等于"下一个库也扫完了"。
     *
     * 超时兜底：万一收尾卡住（比如某个请求挂死），不让调用方永远等下去 ——
     * 删库那边超时后照常删，最坏留几条孤儿行，比"删不掉"好。
     */
    suspend fun awaitStopped(timeoutMs: Long = 15_000) {
        if (!_progress.value.running) return
        val before = _progress.value.finishedAt
        withTimeoutOrNull(timeoutMs) { progress.first { it.finishedAt != before } }
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

    /**
     * 本次扫描新下载的海报张数。扫描由 mutex 串行化，用普通字段就够。
     * 进度每次 copy 都把它带上（它是**计数器**不是快照，漏带就会回退成 0）。
     */
    private var postersFetched = 0

    private suspend fun throttle(rateLimitMs: Long) {
        if (rateLimitMs <= 0) return
        val now = System.currentTimeMillis()
        val wait = lastRequestAt + rateLimitMs - now
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * 列目录（带限速）：响应里自带文件名，聚类零额外请求。
     *
     * ★ **按 count 翻页取全**。115 一页最多给 [PAGE_SIZE] 项，早先只取第一页 ——
     * 目录里超过一页的文件会被**静默丢掉**：一季带多语言字幕、或者一个目录几百个文件的库，
     * 只有前 200 个能入库，界面上看不出任何异常。现在按响应里的 count 一页页取到齐。
     *
     * 兜底两道：`page.items` 为空就停（服务端 count 不准时不至于死循环）、
     * 总页数封顶 [MAX_PAGES]（真遇到病态目录宁可少扫也不能把频控额度耗光）。
     */
    private suspend fun listFiles(cid: String, rateLimitMs: Long): FilesPage {
        val first = parseFilesResponse(
            run { throttle(rateLimitMs); openApi.files(cid = cid, limit = PAGE_SIZE) },
        )
        if (first.count <= first.items.size) return first

        val all = first.items.toMutableList()
        while (all.size < first.count && all.size < MAX_PAGES * PAGE_SIZE) {
            val page = parseFilesResponse(
                run {
                    throttle(rateLimitMs)
                    openApi.files(cid = cid, limit = PAGE_SIZE, offset = all.size)
                },
            )
            if (page.items.isEmpty()) break
            all += page.items
        }
        if (all.size < first.count) {
            Log.w(TAG, "目录 $cid 只取到 ${all.size}/${first.count} 项（分页上限 $MAX_PAGES 页）")
        }
        return first.copy(items = all)
    }

    /**
     * 把一个目录里已入库条目的海报都补到本地（增量扫描跳过该目录时用）。
     *
     * 只查库里已有的 pick_code，不重新列目录 —— 跳过目录本来就是"不打扰云端"，
     * 这里多花的请求只跟**缺多少张图**成正比：全都在本地时是 0 次。
     */
    private suspend fun prefetchDirPosters(dirKey: String, rateLimitMs: Long) {
        if (imageUrlResolver == null || imageCacheDir == null) return
        for (pc in dao.posterPickCodesInDir(dirKey)) {
            // 停止检查：这个循环是"跳过目录"里的，不受簇边界那次检查保护 ——
            // 一个上千条的目录会按限速一张张下完（几十分钟），把「停止」的响应承诺废掉
            if (stopRequested.get()) return
            if (prefetchPoster(pc, rateLimitMs)) postersFetched++
        }
    }

    /**
     * 预取一条海报的字节（扫描期顺手做掉，扫完进海报墙就不必再等图）。
     *
     * - 已在本地：纯文件判断，**不占限速等待**（分集继承系列海报时，一季几十集查的是同一张图）
     * - 没在本地：1 次 downurl + 1 次下载，走同一条限速
     * - 缓存关掉 / 没有图 / 解析失败：什么都不做（海报是锦上添花，不该让一条索引失败）
     *
     * 返回 true = **这次真的把它落盘了**（进度里"已缓存海报"只数这个）。
     * 判定方式是落盘后再查一次本地文件，而不是看 [ImageUrlResolver.posterFor] 的返回值 ——
     * 缓存关掉时它返回的是直链、并没落盘，按返回值算就会虚报。
     */
    private suspend fun prefetchPoster(pickCode: String?, rateLimitMs: Long): Boolean {
        val resolver = imageUrlResolver ?: return false
        val dir = imageCacheDir ?: return false
        if (pickCode.isNullOrBlank()) return false
        if (resolver.hasCachedPoster(pickCode, dir)) return false
        throttle(rateLimitMs)
        runCatching { resolver.posterFor(pickCode, dir) }
            .onFailure { Log.w(TAG, "海报预取异常 pickCode=$pickCode: ${it.message}") }
        return resolver.hasCachedPoster(pickCode, dir)
    }

    /**
     * 实际执行（**多由队列 worker 调**，见 [enqueue]；直接调 = 自己负责排队）：可续跑、带进度。
     * incremental=true 时按 scan_state 的 cloudUpt 增量跳过（目录列表 upt 未变 = 无新增资源）；
     * false 为全量扫描（不跳过任何目录）。
     *
     * [minVideoSizeMb] 是**每库**的体积过滤（0 = 不过滤）：小于它的视频不入库。
     * 在聚类阶段就滤掉，见 [sniffDirectory]。
     *
     * [libraryId] 非空 = 这一轮是为某个媒体库跑的：跑完把 `libraries.lastScanAt` 写掉
     * （定时增量扫描靠它判断"距上次扫描够久了吗"），进度里也带上它（界面据此显示是哪个库）。
     * 调用方不给就什么都不记 —— 扫描器本身不认识"库"这个概念，只认识根目录。
     */
    suspend fun runScan(
        rootCid: String,
        rootPath: String,
        includeSubDirs: Boolean = true,
        incremental: Boolean = false,
        rateLimitMs: Long = 0,
        minVideoSizeMb: Int = 0,
        libraryId: Long? = null,
        /** 库名，只用于进度显示（[Progress.libraryName]） */
        libraryName: String = "",
    ) {
        mutex.withLock {
            if (_progress.value.running) return
            // 队列间隙里点的停止（上一条刚跑完、这一条已经出队还没开跑）：这一条不跑了。
            // 与 running = true 在**同一个临界区**里，那个空隙才关得掉。
            // 顺带把停止位消费掉，不让它留给下一个库（放进队循环也做同样的事，见 [drain]）。
            if (stopRequested.getAndSet(false)) {
                Log.i(TAG, "扫描开始前已被停止，跳过 $rootPath")
                return
            }
            postersFetched = 0
            _progress.value = Progress(
                running = true,
                rootCid = rootCid,
                libraryId = libraryId,
                libraryName = libraryName,
                currentDir = rootPath,
                phase = Phase.Listing,
            )
        }
        var stoppedEarly = false
        // 这几个计数器声明在 try 之外，是为了收尾时能拼出"这次扫描干了什么"（扫描记录 + lastScanAt）。
        // 进度条用的 done 还要判断"这轮到底跑了吗"。
        var done = 0
        var movies = 0
        var skipped = 0
        var totalDirsSeen = 0
        /** 本次**新增**的顶层影片键（分集不单列）—— 存进扫描记录，界面拿它 join 出海报与标题 */
        val newKeys = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        try {
            // 递归列目录。**这一步列到的结果直接传给下面复用**，不再对同一个目录重复请求
            // （早先是 collectDirs 列一遍找子目录、下面的循环再列一遍取文件，等于每目录 2 次）
            val allDirs = collectDirs(rootCid, rootPath, includeSubDirs, rateLimitMs)
            totalDirsSeen = allDirs.size
            if (stopRequested.get()) {
                // 列目录阶段没有写入，停在这里没有任何损失（续扫会重新列）
                stoppedEarly = true
                Log.i(TAG, "扫描在列目录阶段被停止")
            } else {
                // 老数据自愈：哪些目录还是"元数据与视频分成两条"的样子（`ABC-101-4K-C` + `ABC-101-U`）。
                // 合并逻辑是后加的，而指纹一致时下面的循环会跳过这个目录 —— 不特判就永远合不起来。
                // 判据是纯本地的，一次查完全库（见 dao.dirsNeedingOrphanMerge），之后每目录只查一次集合。
                val orphanMergeDirs = if (incremental) dao.dirsNeedingOrphanMerge().toHashSet() else emptySet()
                _progress.value = _progress.value.copy(
                    totalDirs = allDirs.size,
                    phase = Phase.Indexing,
                )
                for (listing in allDirs) {
                    if (stopRequested.get()) {
                        stoppedEarly = true
                        break
                    }
                    _progress.value = _progress.value.copy(currentDir = listing.path)
                    // 被屏蔽的目录（侧挂素材 / 花絮）不该出现在 allDirs 里（collectDirs 已经剪掉），
                    // 这道判断是兜底：万一以后有人改了剪枝、或者换了入口，混进来的后果不是报错
                    // 而是静默多出垃圾条目。仍然算作"处理完一个目录"（进度条的分母是 allDirs.size）。
                    val isRoot = listing.path.trimEnd('/') == rootPath.trimEnd('/')
                    if (!isRoot && isIgnoredDirName(listing.path.substringAfterLast('/'))) {
                        done++
                        _progress.value = _progress.value.copy(doneDirs = done, moviesIndexed = movies)
                        continue
                    }
                    // includeSubDirs=false 时 collectDirs 没列过这个目录，这里补一次
                    val page = listing.page ?: listFiles(listing.cid, rateLimitMs)
                    val files = fileRefsOf(page)
                    // 以前扫进去的"附加内容"（花絮/预告）要清掉：那些目录现在被屏蔽了、
                    // 不会再出现在 allDirs 里，靠循环末尾那套"陈旧条目清理"永远够不着它们，
                    // 留在库里就是海报墙上一堆再也删不掉的垃圾卡。放在跳过判断**之前**跑，
                    // 增量扫描跳过这个目录时也要清（纯本地查询，清完就是空集，不花请求）。
                    purgeIgnoredSubDirs(listing, page)
                    val dirUpt = page.items.maxOfOrNull { it.upt } ?: 0L
                    // 指纹覆盖**所有条目**（含子目录项）：子目录被改名/挪走同样算这个目录变了。
                    // 条目本来就在手上，算它是纯本地开销，不多花一次请求。
                    val dirFingerprint = dirFingerprintOf(fileRefsOf(page, includeDirs = true))
                    if (incremental) {
                        // 增量：目录上次扫完、且内容没变（指纹一致）→ 跳过。
                        // 两个判据都留着：upt 是老的、只挡得住"新增"，指纹才挡得住删除/改名/移入；
                        // 老数据（v9 之前）指纹是空串，这里必然不等 → 每个目录重扫一轮补上。
                        val state = dao.scanState(listing.cid)
                        // 例外：分 CD 的老数据还没合并（合并逻辑是后加的）→ 不能跳过，
                        // 得重扫一遍把它们并成一条 + 分集。判据只看文件名，零请求。
                        val cdPending = cdMergePending(listing.cid, files)
                        // 另一类老数据：元数据与视频分成两条（见 clusterFiles 的合并判据）——
                        // 判据在上面一次性算好，这里只查集合。
                        val orphanPending = listing.cid in orphanMergeDirs
                        if (!cdPending && !orphanPending && state != null && state.status == 2 && state.cloudUpt == dirUpt &&
                            dirUpt > 0 && state.dirFingerprint == dirFingerprint
                        ) {
                            // 跳过不等于不管：把库里已有条目的海报补到本地。
                            // 缓存被清过 / 新装机的机器上，增量扫描是用户最常用的那条路，
                            // 不补的话海报墙仍要一张张现下。已命中的不产生任何请求。
                            prefetchDirPosters(listing.cid, rateLimitMs)
                            // 老库升级（v9 及更早）补剧照/头像：那些行这两列是 NULL，
                            // 而"没补过"和"这个目录根本没有侧挂素材目录"在库里长得一样 ——
                            // 用父目录列表里的目录项（免费）区分，确实有、且还有行没补上才列一次。
                            // 补完这些行都带上了目录 cid，这个判断之后永远为假，不会重复列。
                            val wantFanart = sideDirItem(page.items, EXTRAFANART_DIR_NAMES) != null
                            val wantActors = sideDirItem(page.items, ACTORS_DIR_NAMES) != null
                            if (includeSubDirs && (wantFanart || wantActors) &&
                                dao.rowsMissingSideArt(listing.cid, wantFanart, wantActors) > 0
                            ) {
                                val art = sideArtOf(page.items, rateLimitMs)
                                dao.backfillSideArtInDir(
                                    dirKey = listing.cid,
                                    fanartCodes = encodePickCodes(art.fanart.map { it.pickCode }),
                                    fanartDirCid = art.fanartDirCid,
                                    actorsDirCid = art.actorsDirCid,
                                    actorAvatars = art.actorAvatars.mapValues { it.value.pickCode },
                                )
                            }
                            skipped++
                            done++
                            _progress.value = _progress.value.copy(
                                doneDirs = done,
                                moviesIndexed = movies,
                                postersFetched = postersFetched,
                            )
                            continue
                        }
                        // ☠ 以后要是加"删除检测"（拿本轮见到的集合去差掉索引里的孤儿），
                        //    **跳过分支里的目录必须先算作"见过"** —— 它们的条目没进本轮的产出集合，
                        //   直接做差集会把没访问过的目录整个误删。115-strm-web 用 scan_token 标记本轮结果，
                        //   并且专门有个 mark_cached_dir_as_seen 把被跳过目录的已知行搬进本轮，
                        //   就是为这个；它还要求"本轮没有失败目录"才允许清理。
                    }
                    // ★ 侧挂素材目录（extrafanart / .actors）**只在这里列**，而且只在这个目录
                    //   真的要重扫时才列 —— 增量跳过的目录一次请求都不花。它们是"刮削一次就不动"
                    //   的素材，实测某库 248 个目录里 164 个是它们，每轮都列等于把列目录阶段拖成三倍。
                    //   代价：父目录指纹不含它们的内容，"只往 extrafanart 加图、nfo 没动"
                    //   要等全量扫描才更新（重刮通常连 nfo 一起改，所以少见）。
                    //   includeSubDirs=false 时传 null = "这次看不到" → 入库时保留原值。
                    val sideArt = if (includeSubDirs) sideArtOf(page.items, rateLimitMs) else null
                    val scan = sniffDirectory(files, minVideoSizeMb.toLong() * 1024 * 1024)
                    // 分 CD / 分片的资源（`ABC-201-cd1` + `-cd2`）**合成一条**：造一个"系列"行，
                    // 各 CD 挂到它名下当分集 —— 海报墙出一张卡，点进去列 CD1/CD2。
                    // 不合并的话每张盘都是一套完整的视频+nfo+海报，墙上就是 N 张几乎一样的卡。
                    val cdBase = if (scan.isMultiVideo) cdGroupBaseOf(scan.clusters) else null
                    // 元数据与图取"第一张盘"（列表顺序不稳定，不能直接用 first）
                    val cdLead = if (cdBase != null) cdGroupLead(scan.clusters) else null
                    // 合成卡的图：第一张盘优先，它没有就**借任意一张盘的** —— 实测 `ABC-204 示例演员/`
                    // 第一张盘只有视频 + nfo，海报挂在 cd2 上，不借的话合并卡是空白的
                    val cdArt = if (cdBase != null) {
                        CdArt(
                            poster = (cdLead?.poster ?: scan.clusters.firstNotNullOfOrNull { it.poster })?.pickCode,
                            fanart = (cdLead?.fanart ?: scan.clusters.firstNotNullOfOrNull { it.fanart })?.pickCode,
                        )
                    } else {
                        null
                    }
                    // 分集目录才需要往上找归属与继承的图；影片目录不找
                    // （一部电影不是某一集，给它套系列海报、认系列当爹都是错的）。
                    // CD 组例外：它自己就是系列根，不往上继承。
                    val ancestor = when {
                        cdBase != null -> null
                        scan.isMultiVideo -> ancestorInfoOf(listing.path, files, rootPath)
                        else -> null
                    }
                    // 索引前先拿这个目录**已有的键**：跑完之后既用它清陈旧条目，
                    // 也用它判"哪些是本次新增的片"（原本只在清陈旧时查，提前到这里复用，不多花查询）
                    val keysBefore = dao.mediaKeysInDir(listing.cid).toSet()
                    val produced = mutableSetOf<String>()
                    var failed = false
                    var aborted = false
                    if (cdBase != null && cdLead != null && cdArt != null) {
                        try {
                            produced += indexCdGroup(listing, cdBase, cdLead, cdArt, rateLimitMs, sideArt)
                            movies++
                        } catch (e: Exception) {
                            failed = true
                            Log.w(TAG, "CD 组合并入库失败 $cdBase: ${e.message}")
                        }
                    }
                    // 各 CD 挂到合成行名下，并继承它的海报/背景兜底（自己带了图就用自己的）
                    val cdAncestor = cdBase?.let { base ->
                        AncestorInfo(base, cdArt?.poster, cdArt?.fanart)
                    }
                    // 各 CD 的键：第一张盘要改名（见 cdChildKeys 的注释，直接叫基名会和合成行撞）
                    val cdKeys = if (cdBase != null) {
                        scan.clusters.map { it.prefix }.let { ps -> ps.zip(cdChildKeys(cdBase, ps)).toMap() }
                    } else {
                        emptyMap()
                    }
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
                                ancestor = cdAncestor ?: ancestor,
                                sideArt = sideArt,
                                keyOverride = cdKeys[cluster.prefix],
                            )
                            movies++
                            // 大目录（一季几十集）里让"已入库/已缓存图"跟着动，不然进度条整段不动
                            _progress.value = _progress.value.copy(
                                moviesIndexed = movies,
                                postersFetched = postersFetched,
                            )
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
                        val stale = keysBefore.filterNot { it in produced }
                        if (stale.isNotEmpty()) {
                            dao.deleteMovies(stale)
                            Log.i(TAG, "清理 ${listing.path} 下 ${stale.size} 条陈旧条目")
                        }
                        // 新增影片 = 本次产出的键里、索引前不存在的那些。
                        // **只收顶层条目**（seriesKey == null）—— 那正是海报墙上会多出来的卡：
                        // 一季几十集的分集不单列（系列卡已经代表它了），分 CD 的合成行是顶层 ✓、
                        // 各张盘带 seriesKey ✗。存的是**键**：界面 join movies 现取标题与海报。
                        for (key in produced - keysBefore) {
                            val row = dao.movie(key) ?: continue
                            if (row.seriesKey == null) newKeys += key
                        }
                    }
                    dao.upsertScanState(
                        ScanStateEntity(
                            dirKey = listing.cid,
                            dirPath = listing.path,
                            status = 2,
                            cloudUpt = dirUpt,
                            dirFingerprint = dirFingerprint,
                            scannedAt = System.currentTimeMillis(),
                        ),
                    )
                    done++
                    _progress.value = _progress.value.copy(
                                doneDirs = done,
                                moviesIndexed = movies,
                                postersFetched = postersFetched,
                            )
                }
                if (skipped > 0) Log.i(TAG, "增量扫描完成：跳过 $skipped/${allDirs.size} 个未变化目录")
            }
        } catch (e: Exception) {
            Log.w("MediaScanner", "扫描中断: ${e.message}")
        } finally {
            val finishedAt = System.currentTimeMillis()
            // 记"这个库刚扫完"。**一个目录都没跑完就不记**（done == 0 通常是列目录阶段就抛了，
            // 多半是网络问题）：那种情况该在下次启动时再试，而不是白白占用一个扫描间隔。
            // 用户中途按停止 → done > 0 → 照记（他确实扫过一轮了，别每次启动都来烦他）。
            if (libraryId != null && done > 0) {
                runCatching { dao.markLibraryScanned(libraryId, finishedAt) }
                    .onFailure { Log.w(TAG, "记录扫描时间失败 libraryId=$libraryId: ${it.message}") }
            }
            // 媒体库自己的记录里留一条：**何时扫的、扫的哪个库、结果、新增了哪些片**
            // （表 scan_log → 媒体库页的「扫描记录」，那里用海报图展示新增影片）。
            // **跑没跑完都记** —— 用户就是靠它回答"上次扫到哪儿了"。翻车了也只当没记，不影响扫描本身。
            runCatching {
                val report = ScanReport(
                    libraryName = libraryLabel(libraryId, rootPath),
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    totalDirs = totalDirsSeen,
                    doneDirs = done,
                    skippedDirs = skipped,
                    indexed = movies,
                    postersFetched = postersFetched,
                    newCount = newKeys.size,
                    stopped = stoppedEarly,
                )
                dao.addScanLog(
                    ScanLogEntity(
                        libraryId = libraryId ?: 0L,
                        libraryName = report.libraryName,
                        at = finishedAt,
                        elapsedMs = report.elapsedMs,
                        totalDirs = report.totalDirs,
                        doneDirs = report.doneDirs,
                        skippedDirs = report.skippedDirs,
                        indexed = report.indexed,
                        postersFetched = report.postersFetched,
                        stopped = report.stopped,
                        newKeys = newKeys.joinToString("\n"),
                    ),
                )
            }.onFailure { Log.w(TAG, "写扫描记录失败: ${it.message}") }
            // 收尾统一放 finally：正常结束、抛异常、被停止三条路都要把 running 落回去，
            // 否则 UI 会永远停在"扫描中"、且 awaitStopped 的等待永远等不到
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
            // 屏蔽的目录不递归（理由见下面循环里那段）
            if (isIgnoredDirName(dir.fn)) return@forEach
            dir.fid?.let { queue.add(it to "$rootPath/${dir.fn}") }
        }
        while (queue.isNotEmpty()) {
            if (stopRequested.get()) break
            val (cid, path) = queue.removeFirst()
            val page = listFiles(cid, rateLimitMs)
            result.add(DirListing(cid, path, page))
            // 这一阶段没有分母（目录数正是它要算的东西），至少让"已发现 N 个目录"在动
            _progress.value = _progress.value.copy(
                phase = Phase.Listing,
                discoveredDirs = result.size,
                currentDir = path,
            )
            page.items.filter { it.isDir }.forEach { dir ->
                // ★ 屏蔽的目录（侧挂素材 extrafanart/.actors、附加内容 behind the scenes/extras…）
                //   **不递归**：侧挂素材的内容由 [sideArtOf] 在"这个影片目录需要重扫"时单独列；
                //   附加内容是整棵丢弃。列进这里等于每轮扫描都白问一遍（实测某库 248 个目录里
                //   164 个是它们），附加内容还会把花絮当成正片入库。
                if (isIgnoredDirName(dir.fn)) return@forEach
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
     * 挂在影片目录上的**侧挂素材**：`extrafanart/` 剧照 + `.actors/` 演员头像。
     */
    private data class SideArt(
        val fanart: List<FileRef>,
        val fanartDirCid: String?,
        val actorsDirCid: String?,
        val actorAvatars: Map<String, FileRef>,
    )

    /** 分 CD 合并后那条**合成卡**用的图（第一张盘优先，它没有就借任意一张盘的） */
    private data class CdArt(val poster: String?, val fanart: String?)

    /**
     * 列这个目录下的侧挂素材目录（`extrafanart/`、`.actors/`）。
     *
     * ★ **调用点必须在"增量跳过"判断之后**：跳过 = 不打扰云端，这两个目录也就一次都不列。
     *   它们的内容是刮削一次就不动的素材（剧照十几张、头像几张），每轮扫描都重新列一遍
     *   纯属浪费频控额度 —— 而"目录的 cid"是从**父目录的列表项**里免费拿到的，不需要额外请求。
     *
     * 没有这两个目录就返回空 SideArt（调用方据此清掉旧值）—— 与"这次拿不到清单"
     * （includeSubDirs=false，调用方直接传 null）必须分开。
     *
     * 只认直接子目录（`X/extrafanart`、`X/.actors`）：这是刮削器的固定写法。
     * 深一层（`.actors/<演员名>/folder.jpg`）是 Emby 另一套，实测库里没有，不做。
     */
    private suspend fun sideArtOf(parentItems: List<FileItem>, rateLimitMs: Long): SideArt {
        val fanartDir = sideDirItem(parentItems, EXTRAFANART_DIR_NAMES)
        val actorsDir = sideDirItem(parentItems, ACTORS_DIR_NAMES)
        return SideArt(
            fanart = fanartDir?.let { extraFanartFilesOf(fileRefsOf(listFiles(it.fid!!, rateLimitMs))) }
                ?: emptyList(),
            fanartDirCid = fanartDir?.fid,
            actorsDirCid = actorsDir?.fid,
            actorAvatars = actorsDir?.let { actorAvatarFilesOf(fileRefsOf(listFiles(it.fid!!, rateLimitMs))) }
                ?: emptyMap(),
        )
    }

    /** 父目录列表里那个侧挂素材目录项（名字不区分大小写；没有 fid 的用不了，跳过） */
    private fun sideDirItem(items: List<FileItem>, names: List<String>): FileItem? =
        items.firstOrNull { it.isDir && !it.fid.isNullOrEmpty() && it.fn.lowercase() in names }

    /**
     * 这个目录是不是"分 CD 的合并结果还没到位、得重扫一遍"：文件名看着是一组 CD
     * （`X-cd1`+`X-cd2`），而库里的行不符合合并后的样子（见 dao.cdGroupNeedsRescan）。
     *
     * 只看文件名 + 一次本地查询，**零请求**。合并到位之后这个判断永远为假，不会再逼着重扫。
     */
    private suspend fun cdMergePending(dirCid: String, files: List<FileRef>): Boolean {
        val base = cdGroupBaseOf(clusterFiles(files)) ?: return false
        return dao.cdGroupNeedsRescan(dirCid, base) > 0
    }

    /**
     * 清掉**被屏蔽目录**里以前扫进去的条目（花絮 / 预告 / 访谈）。
     *
     * 这些目录现在整棵不扫，也就不再出现在 allDirs 里 —— 循环末尾那套"陈旧条目清理"
     * 是按目录走的，永远够不着它们。不清的话，用户升级前扫进去的花絮条目会永久留在
     * 海报墙上（既不会更新也不会被清），正是这次要修的现象。
     *
     * 只删**索引**，云端文件一个不动（和扫描的其它行为一致）。
     * 每次扫描对这个目录跑一次本地查询，第一次清完就是空集，之后零成本。
     */
    private suspend fun purgeIgnoredSubDirs(listing: DirListing, page: FilesPage) {
        for (dir in page.items) {
            if (!dir.isDir || !isIgnoredDirName(dir.fn)) continue
            val cid = dir.fid?.takeIf { it.isNotEmpty() } ?: continue
            val keys = dao.mediaKeysInDir(cid)
            if (keys.isEmpty()) continue
            dao.deleteMovies(keys)
            // 扫描状态也清掉：留着会让"已扫过 N 个目录"的统计把它算进去
            dao.deleteScanStateInPath("${listing.path.trimEnd('/')}/${dir.fn}")
            Log.i(TAG, "清理被屏蔽目录 ${dir.fn} 下的 ${keys.size} 条旧条目")
        }
    }

    /**
     * 扫描记录里这条扫描属于谁：库里那条的名字优先（用户在列表里认得的是它），
     * 取不到（没 libraryId / 库被删了）就退回根目录最后一段。
     */
    private suspend fun libraryLabel(libraryId: Long?, rootPath: String): String {
        val name = libraryId?.let { runCatching { dao.library(it) }.getOrNull()?.name }
        return name?.takeIf { it.isNotBlank() }
            ?: rootPath.trimEnd('/').substringAfterLast('/').ifBlank { rootPath }
    }

    /** 列表项 → FileRef（扫描期反复用，抽出来免得三处各写一遍字段映射） */    private fun fileRefsOf(page: FilesPage?, includeDirs: Boolean = false): List<FileRef> =
        page?.items.orEmpty().filter { includeDirs || !it.isDir }.map {
            FileRef(
                name = it.fn, pickCode = it.pc ?: "", sizeBytes = it.fs,
                upt = it.upt, fid = it.fid ?: "",
            )
        }

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
        /**
         * 这个簇要挂的侧挂素材（剧照/头像）。**同一个目录里的每个簇都挂同一份**。
         *
         * 不搞"只挂给锚点"那套：剧照/头像是**整个目录**的素材，而一个目录里可能有多个簇
         * （分 CD 的各张盘、一季的各集都各有一条行）—— 只挂锚点的话，别的行点进详情页
         * 一张剧照都没有。
         * （`ABC-101-4K-C 示例演员/` 那种"nfo 与视频前缀对不上、分成两条"的目录早先也靠它
         *  兜住元数据那条的剧照；那对现在由 clusterFiles 直接合成一条了。）
         * 代价只是十几条 pick_code 在同一个目录的几条行里各存一遍（每条约几百字节）。
         *
         * **null = 这次看不到**（includeSubDirs=false）→ 保留库里的原值，
         * 不能当成"没有剧照"把老数据抹掉。
         */
        sideArt: SideArt? = null,
        /** 分 CD 时由调用方指定的键（第一张盘要改名，见 [cdChildKeys]）；null = 按前缀算 */
        keyOverride: String? = null,
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
        // 拿它当主键会把整季覆盖成一行 —— 见 mediaKeyOf 的注释）。
        // 分 CD 时由调用方给定（第一张盘要避开合成行的键）。
        val key = keyOverride ?: mediaKeyOf(cluster.prefix, meta.uniqueTmdbid, isEpisodeLike)
        val title = meta.title ?: cluster.prefix.substringBeforeLast('(').trim().ifEmpty { cluster.prefix }
        // 演员名以 **nfo 为准**；nfo 没带才退到 `.actors` 里的文件名，再没有才从目录名猜
        // （见 actorsOf 的注释：`.actors` 的文件名正是头像的键，从这里取名字头像一定配得上）
        val actors = actorsOf(
            nfoActors = meta.actors,
            actorAvatarNames = sideArt?.actorAvatars?.values
                ?.map { it.name.substringBeforeLast('.') }
                ?.sortedWith { a, b -> compareNatural(a, b) }
                .orEmpty(),
            dirPath = dirPath,
        )
        // sideArt == null 表示"这次看不到子目录"→ 保留旧值；非 null 时以它为准（空列表就是没有）
        val prev = if (sideArt == null) dao.movie(key) else null
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
            // 剧照（extrafanart/）：**只存 pick_code，不下载字节** —— 十几张图在扫描期全下会把
            // 扫描拖长好几倍，详情页打开时按需缓存更划算（用户要求的就是这个时机）。
            extraFanartPickCodes = if (sideArt != null) {
                encodePickCodes(sideArt.fanart.map { it.pickCode })
            } else {
                prev?.extraFanartPickCodes
            },
            extraFanartDirCid = if (sideArt != null) sideArt.fanartDirCid else prev?.extraFanartDirCid,
            actorsDirCid = if (sideArt != null) sideArt.actorsDirCid else prev?.actorsDirCid,
            nfoUpt = cluster.nfo?.upt ?: 0,
            // nfo 的**完整解析结果**整份存 JSON（长尾字段不进表，见 MovieEntity.nfoJson）
            nfoJson = meta.takeIf { it.hasContent }?.let { nfoJson.encodeToString(it) },
            // 排序用的两个键提成独立列（首映日期 / 入库时间）
            premiered = meta.premiered?.takeIf { it.isNotBlank() },
            dateAdded = parseNfoDateMillis(meta.dateAdded),
            sourceDirKey = dirCid,
            scannedAt = System.currentTimeMillis(),
        )
        dao.upsertMovieWithPeople(
            movie = movie,
            actors = actors,
            tags = meta.genres + meta.tags,
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
            // 头像按**归一化演员名**配（nfo 里的名字与 .actors 文件名大小写/空格未必一致）
            actorAvatars = sideArt?.actorAvatars?.mapValues { it.value.pickCode }.orEmpty(),
            actorsDirCid = sideArt?.actorsDirCid,
        )
        // 顺手把海报字节取到本地：扫完进海报墙/详情页就不必再等图。
        // 放在入库**之后** —— 预取失败或被打断都不影响这条已经进库。
        if (prefetchPoster(movie.posterPickCode, rateLimitMs)) postersFetched++
        return key
    }

    /**
     * 分 CD 资源的**合成行**：`ABC-201-cd1` + `ABC-201-cd2` → 一条 `ABC-201`。
     *
     * 元数据全取**第一个 CD**（同一部片每张盘的 nfo 基本一样，标题把尾巴上的 CD 标记去掉：
     * `… 8 小时 BEST CD1` → `… 8 小时 BEST`），海报/背景同理。
     *
     * 它自己**没有视频**：详情页的播放按钮因此落到"播放第 1 集"（与剧集系列卡一致），
     * 分集列表由各 CD 行（seriesKey 指向这条）撑起来。
     *
     * nfo 走同一个 [fetchNfoMeta] 缓存，紧接着扫第一个 CD 时会命中，**不多花请求**。
     */
    private suspend fun indexCdGroup(
        listing: DirListing,
        baseKey: String,
        lead: Cluster,
        art: CdArt,
        rateLimitMs: Long,
        sideArt: SideArt?,
    ): String {
        val first = lead
        var meta = NfoMeta()
        first.nfo?.takeIf { it.pickCode.isNotEmpty() }?.let { nfo ->
            meta = runCatching { fetchNfoMeta(nfo.pickCode, nfo.upt, rateLimitMs) }.getOrDefault(NfoMeta())
        }
        val actors = actorsOf(
            nfoActors = meta.actors,
            actorAvatarNames = sideArt?.actorAvatars?.values
                ?.map { it.name.substringBeforeLast('.') }
                ?.sortedWith { a, b -> compareNatural(a, b) }
                .orEmpty(),
            dirPath = listing.path,
        )
        val prev = if (sideArt == null) dao.movie(baseKey) else null
        val movie = MovieEntity(
            mediaKey = baseKey,
            title = stripCdMarker(meta.title ?: first.prefix).ifEmpty { baseKey },
            year = meta.year,
            rating = meta.rating,
            plot = meta.plot,
            genre = meta.genres.joinToString(" / ").ifEmpty { null },
            // 不是"番号式剧"，就是一部片（只是分了几张盘存）
            isEpisodeLike = false,
            seriesKey = null,
            dirCid = listing.cid,
            dirPath = listing.path,
            // 自己没有视频：播放按钮落到"播放第 1 集"
            videoPickCode = null,
            // 文件名留着：海报墙的画质角标（4K/1080P）从它推
            videoName = first.video?.name,
            posterPickCode = art.poster,
            fanartPickCode = art.fanart,
            thumbPickCode = first.thumb?.pickCode,
            nfoPickCode = first.nfo?.pickCode,
            nfoUpt = first.nfo?.upt ?: 0,
            // 合成行也存整份解析结果（长尾字段详情页要用，见 MovieEntity.nfoJson）
            nfoJson = meta.takeIf { it.hasContent }?.let { nfoJson.encodeToString(it) },
            premiered = meta.premiered?.takeIf { it.isNotBlank() },
            dateAdded = parseNfoDateMillis(meta.dateAdded),
            extraFanartPickCodes = if (sideArt != null) {
                encodePickCodes(sideArt.fanart.map { it.pickCode })
            } else {
                prev?.extraFanartPickCodes
            },
            extraFanartDirCid = if (sideArt != null) sideArt.fanartDirCid else prev?.extraFanartDirCid,
            actorsDirCid = if (sideArt != null) sideArt.actorsDirCid else prev?.actorsDirCid,
            sourceDirKey = listing.cid,
            scannedAt = System.currentTimeMillis(),
        )
        dao.upsertMovieWithPeople(
            movie = movie,
            actors = actors,
            tags = meta.genres + meta.tags,
            episodes = emptyList(),
            actorAvatars = sideArt?.actorAvatars?.mapValues { it.value.pickCode }.orEmpty(),
            actorsDirCid = sideArt?.actorsDirCid,
        )
        // 海报字节顺手取到本地（第一个 CD 稍后也要这张，命中就不会重复下）
        if (prefetchPoster(movie.posterPickCode, rateLimitMs)) postersFetched++
        return baseKey
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
        val cacheKey = nfoCacheKey(pickCode, upt)
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

        /** 列目录每页取多少项（115 的上限就是这个量级） */
        private const val PAGE_SIZE = 200

        /** 一个目录最多翻几页：病态目录（count 虚高 / 服务端不认 offset）不至于把频控额度耗光 */
        private const val MAX_PAGES = 25

        /** nfo 解析结果的序列化器（缓存里存的是 NfoMeta 的 JSON） */
        private val nfoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * 扫描队列落盘用的 Json。
         *
         * `encodeDefaults = true`：快照里的默认值（限速 0 / 过滤 0）要写出来 ——
         * 省掉它们的话，读回来靠默认值兜底虽然也对，但快照就不是"原样一份"了，
         * 以后加字段容易读出错觉（哪些是存过的、哪些是补的默认值看不出来）。
         */
        internal val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** nfo 缓存的 key：`nfo|v<解析器版本>|<pickCode>|<upt>`（key 带 upt，文件没变就永久命中） */
        fun nfoCacheKey(pickCode: String, upt: Long): String =
            "nfo|v${NfoParser.PARSER_VERSION}|$pickCode|$upt"

        /** nfo 缓存的**失效前缀**（删影片/删库时按它清） */
        fun nfoCachePrefix(pickCode: String): String =
            "nfo|v${NfoParser.PARSER_VERSION}|$pickCode|"

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
        /**
         * 详情页要不要为这条**重拉一次 nfo**：
         *  - 扫描期没拉到元数据（简介与评分都空）
         *  - **或者还没存过完整解析结果**（`nfoJson` 为空）—— 解析器补全字段后老数据靠这条补上，
         *    不必逼用户跑全量扫描。拉到就写回，写回后这个判断为假，不会再重复拉。
         *
         * 调用点（详情页）与 [refetchNfo] 共用同一个判据：各写一份迟早会走岔。
         */
        fun needsNfoRefetch(movie: MovieEntity): Boolean {
            if (movie.nfoPickCode?.isNotEmpty() != true) return false
            if (movie.plot.isNullOrBlank() && movie.rating == null) return true
            val raw = movie.nfoJson ?: return true
            // 存量数据的版本比当前解析器旧 → 也要重拉（不然补的新字段永远是空的）
            val version = runCatching { nfoJson.decodeFromString<NfoMeta>(raw).parserVersion }
                .getOrDefault(0)
            return version < NfoParser.PARSER_VERSION
        }

        suspend fun refetchNfo(
            openApi: OpenApi,
            okHttpClient: OkHttpClient,
            dao: MediaDao,
            cache: MediaCache?,
            mediaKey: String,
        ) {
            val movie = dao.movie(mediaKey) ?: return
            if (!needsNfoRefetch(movie)) return
            val nfoPc = movie.nfoPickCode?.takeIf { it.isNotEmpty() } ?: return
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
                    // 完整解析结果（详情页的长尾字段）：这次拉到就一起补上
                    nfoJson = nfoJson.encodeToString(meta),
                    premiered = meta.premiered?.takeIf { it.isNotBlank() } ?: movie.premiered,
                    dateAdded = parseNfoDateMillis(meta.dateAdded) ?: movie.dateAdded,
                ),
            )
        }
    }
}
