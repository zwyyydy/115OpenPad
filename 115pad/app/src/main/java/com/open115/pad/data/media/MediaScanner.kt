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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * 扫描方式。
 *
 * - [Full]：全量，不跳过任何目录（重新索引一切）
 * - [Incremental]：**原来的增量**（判据 `cloudUpt + dirFingerprint`）：每个目录都列一次，
 *   没变只跳过"重新索引"。列目录请求数 ≈ 目录数 —— 所有版本一直以来的行为
 * - [Fast]：快速，**没变的叶子目录连列都不列**（判据逐条写在 [MediaScanner.runScan] 的爬取段里）：
 *   父目录列表里这个子目录项的 `upt`/`uet` 跟上次记的一样、它上次扫时又不是"有下级"的目录
 *   → 它的内容不可能变（115 的目录 `upt` 对成员的增删移入敏感，实测）。省的是列目录请求
 *   （媒体库里绝大多数目录是叶子）。盲区只有"目录内改名"（实测：改名不顶任何时间字段），
 *   靠每轮抽查一部分 + 全量兜底。
 *
 * 新加一种方式而不是改掉增量：这条路的判据多、容易出偏差，增量与全量随时可以退回去。
 */
enum class ScanMode { Full, Incremental, Fast }

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
    /** 快速模式（见 [ScanMode.Fast]）。落盘的老快照没有这个字段 → false = 原增量，正好 */
    val fast: Boolean = false,
    val rateLimitMs: Long = 0,
    /** 阶梯限速（[MediaLibraryEntity.rateTiered]）。老快照没有这个字段 → false = 老行为 */
    val rateTiered: Boolean = false,
    /** 每 [TIER_REST_EVERY] 个目录休息几分钟（0 = 不休息）。老快照没有 → 0 = 不休息 */
    val tierRestMinutes: Int = 0,
    val minVideoSizeMb: Int = 0,
    /**
     * 单目录任务（[PadSync] 用）：[rootCids]/[rootPaths] 里是**一个个具体目录**，不往下递归。
     *
     * 服务器只改了几个影片目录（写 nfo/海报），没必要把整棵树再走一遍 ——
     * 这也是这个功能存在的意义：一轮只花几次列目录请求。
     * 落盘的老快照没有这个字段 → false = 原来的整根递归，正好兼容。
     */
    val noRecurse: Boolean = false,
    /**
     * 来自**启动时的定时扫描**（见 [MediaScanner.enqueue] 的 auto 参数）。
     *
     * 唯一的用处：流水线同步（[PadSync]）排定向任务时，把同一个库**还没开跑**的定时任务撤掉 ——
     * 定向任务拿的是服务器那一轮最新的变动，这时再整库扫一遍纯属重复（两件事同时发生就忽略定时任务）。
     * 用户手动点的扫描不带这个标记，谁也不能替他撤掉。
     * 老快照没有这个字段 → false = 当成手动任务，宁可多扫一次也不误撤。
     */
    val auto: Boolean = false,
) {
    val mode: ScanMode
        get() = when {
            !incremental -> ScanMode.Full
            fast -> ScanMode.Fast
            else -> ScanMode.Incremental
        }

    companion object {
        /** 按库当前的设置造一个扫描任务（界面「快速/增量/全量扫描」、启动自动扫描都走这里） */
        fun of(library: MediaLibraryEntity, mode: ScanMode, auto: Boolean = false) = QueuedScan(
            libraryId = library.id,
            libraryName = library.name,
            rootCids = library.rootCids,
            rootPaths = library.rootPaths,
            incremental = mode != ScanMode.Full,
            fast = mode == ScanMode.Fast,
            rateLimitMs = library.rateLimitMs,
            rateTiered = library.rateTiered,
            tierRestMinutes = library.tierRestMinutes,
            minVideoSizeMb = library.minVideoSizeMb,
            auto = auto,
        )

        /**
         * 造一条"只扫这几个目录"的任务（流水线同步用）。
         *
         * 固定 Full + 不递归：**不能被增量/快速的判据跳过**。云盘上的 nfo/海报是原地覆盖写的，
         * upt 与 pick_code 都可能没变 —— 增量那条"指纹没变就只刷新状态行"的分支正好会把
         * 我们要的重新索引跳掉（见 [PadSync] 里的说明）。
         */
        fun targeted(library: MediaLibraryEntity, dirs: List<Pair<String, String>>) = QueuedScan(
            libraryId = library.id,
            libraryName = library.name,
            rootCids = dirs.map { it.first },
            rootPaths = dirs.map { it.second },
            incremental = false,
            fast = false,
            rateLimitMs = library.rateLimitMs,
            rateTiered = library.rateTiered,
            tierRestMinutes = library.tierRestMinutes,
            minVideoSizeMb = library.minVideoSizeMb,
            noRecurse = true,
        )
    }
}

/**
 * 落盘的队列快照：正在跑的那一轮（[running]）、还没轮到的（[pending]）、
 * 以及两份**现场**（[inflight] 在途 / [proposal] 待回答）。
 *
 * 为什么要两份现场（一开始只有一份，两个真问题逼出来的）：
 *  - [inflight] 是**正在跑那一轮**的进度，任务开跑时重建、每 N 个目录刷一次。进程被杀时
 *    留在盘上，重启后据此知道"扫到哪儿了"；
 *  - [proposal] 是**还没回答的那个问题**（异常中断时从 inflight 转过来）。
 *    两者必须分开：只有一份的话，下一个排队任务开跑就会把它覆盖、正常跑完又会把它清掉 ——
 *    于是"上一个库扫到一半被频控打断"这件事在界面上和重启后都消失（真机路径，不是理论）。
 *
 * 重启后的区别对待（见 [MediaScanner.restoreQueue]）：pending 原样恢复（用户当时选的
 * 增量还是全量都认）；没跑完的那一轮**不自动接着跑**，用 [proposal]（没有就退回 [inflight]）
 * 问一句"要不要接着扫"。**`running` 非空本身不代表没跑完** —— 用户按了「全部停止」/登出时
 * 那一轮也还在 running 里（进程这时被杀就会留在盘上），所以判据只用这两份现场，不看 running。
 *
 * 老快照（本功能之前那一版）只有 running/pending → 两份现场都是 null = 没有未尽的任务、
 * 不无故弹窗（那一次升级会丢掉"半途那一轮"的续扫机会，拿不准就不问，比问错强）。
 */
@Serializable
internal data class ScanQueueSnapshot(
    val running: QueuedScan? = null,
    val pending: List<QueuedScan> = emptyList(),
    /** 正在跑那一轮的进度现场（[ScanResume]）。正常跑完 / 用户主动停止都会清掉 */
    val inflight: ScanResume? = null,
    /** 还没回答的"要不要接着扫"。用户答过、或被同一库的新扫描覆盖就清掉 */
    val proposal: ScanResume? = null,
)

/**
 * 重启后该问哪一条：优先**还没回答的那个问题**（上一次异常中断留下的，或被用户
 * 「跳过本次」跳过的那一份 —— 它写在同一个槽里，见 [proposalToPersist]），
 * 其次是**在途现场**（进程在上次扫描中途被杀掉，没来得及变成问题）。
 *
 * ★ 只看这两份现场、**不看 [ScanQueueSnapshot.running]**：running 非空只说明"进程死的时候
 *   队列里有条任务在跑"，而它也可能是用户刚点过「全部停止」的那一轮 —— 拿它去问，
 *   用户会被问"要不要接着扫"一个他明确停掉的扫描（v2 复审抓到的真问题）。
 *
 * 纯函数，单测直接打。
 */
internal fun proposalToRestore(snapshot: ScanQueueSnapshot): ScanResume? =
    (snapshot.proposal ?: snapshot.inflight)?.takeIf { it.task != null }

/**
 * 盘上那个"待回答的问题"（[ScanQueueSnapshot.proposal]）该写谁。
 *
 * 内存里活着的那个问题优先；没有就写**用户跳过的那一份**（deferred）—— 这正是「跳过本次」的语义：
 * 这次不问了，但现场得留在盘上，下次启动由 [proposalToRestore] 再问一遍。
 * 答过「继续」/「不再询问」、或全部停止/登出之后两处都空了，盘上那份自然也就没了。
 *
 * 纯函数，单测直接打。
 */
internal fun proposalToPersist(active: ScanResume?, deferred: ScanResume?): ScanResume? =
    active ?: deferred

/**
 * 这一轮任务**覆盖**了那份现场吗（同库 + 同一种任务：都是整库，或都是流水线定向）？
 *
 * 覆盖了就该把那个问题撤掉 —— 不然用户会看到"上次没扫完"，而它刚刚才被扫过。
 * 定向任务**不算**覆盖整库的问题（它只扫几个目录，整库那轮仍然没扫完）。
 * [MediaScanner.drain] 对"活着的那个问题"与"被跳过的那份"用的是同一条判据。
 *
 * 纯函数，单测直接打。
 */
internal fun taskCoversResume(task: QueuedScan, resume: ScanResume?): Boolean {
    val t = resume?.task ?: return false
    return t.libraryId == task.libraryId && t.noRecurse == task.noRecurse
}

/**
 * 一轮扫描的**现场**：任务本身 + 跑到哪儿了 + 为什么断。
 * 它有两种身份（见 [ScanQueueSnapshot]）：正在跑那一轮的**在途进度**、以及还没回答的**问题**。
 *
 * 为什么任务也存一份（[ScanQueueSnapshot.running] 里不是有了吗）：一轮扫描可能由**多个**
 * 目录/根组成（流水线同步一条任务带 N 个目录），而"没跑完"这件事是在任务级别成立的；
 * 进程在任务中途被杀时 running 确实还在，但**异常中断**（频控、网络）是在进程活着的时候
 * 结束的 —— 那一路会把 running 清掉，只剩这份现场，没有任务就无从续起。
 *
 * [doneDirs] / [totalDirs] 是**正在扫的那个根**的数字（多根库会以最后一个根为准）——
 * 只为让提示里能写"已完成 N 个目录"，续扫本身靠 scan_state 按目录跳过，不依赖这两个数。
 */
@Serializable
data class ScanResume(
    val task: QueuedScan? = null,
    /** 最后一个目录被处理的时刻（进程被杀时这就是"最后一条进度"） */
    val at: Long = 0,
    /** 中断原因（异常文案）；空 = 进程被系统/用户杀掉的，没有原因可写 */
    val reason: String = "",
    val doneDirs: Int = 0,
    val totalDirs: Int = 0,
    val moviesIndexed: Int = 0,
    /** 断在哪个目录（提示里给用户看，多根库还能看出是哪个根） */
    val currentDir: String = "",
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
    // ★ 卡位是"库 + 任务类型"而不是只有库：单目录任务（流水线同步，见 [PadSync]）与整库任务
    //   各占一格。只看库的话，一条定向任务会把用户/自动扫描排的整库任务顶掉（反过来也一样）。
    val at = queue.indexOfFirst { it.libraryId == task.libraryId && it.noRecurse == task.noRecurse }
    if (at < 0) return queue + task
    return queue.toMutableList().also { it[at] = task }
}

/**
 * 撤掉某个库里**来自定时扫描**（[QueuedScan.auto]）的排队任务，返回撤掉了几个。
 *
 * 流水线同步排下定向任务时调（见 [MediaScanner.dropAutoQueued]）：那一刻服务器刚刮完的那批
 * 变动已经被定向任务覆盖了，整库的定时扫描就是重复劳动 —— 用户的要求是"两件事同时触发时忽略定时任务"。
 * **只撤定时任务**：用户手动点的扫描、以及流水线自己排的定向任务，谁都不能替他撤掉。
 *
 * 纯函数，单测直接打。
 */
internal fun queueWithoutAuto(queue: List<QueuedScan>, libraryId: Long): List<QueuedScan> =
    queue.filterNot { it.libraryId == libraryId && it.auto }

/**
 * 快照 → 要恢复的**排队任务**（还没开跑的那些，原样照旧：用户当时选增量还是全量都认）。
 *
 * 正在跑的那条**不在**这里 —— 它要经过一次用户确认（[resumeTaskOf] + UI 弹窗），
 * 见 [MediaScanner.restoreQueue]。抽成纯函数是为了单测能钉住"排队原样、未跑完不自动续"。
 */
internal fun pendingToRestore(snapshot: ScanQueueSnapshot): List<QueuedScan> = snapshot.pending

/**
 * 未跑完那一轮的**续扫任务**（用户点了"继续扫描"才入队）。返回 null = 没有可续的任务。
 *
 * **全量降级成增量**：全量的语义是"重新索引一切"，拿它去续扫等于把已经扫完的目录
 * 从头再问一遍（白耗频控额度）；降成增量后 scan_state 会让它从断点接着跑 ——
 * 这正是"续扫"该有的样子。快速/增量原样保留（两者的跳过判据都基于 scan_state 的目录指纹，
 * 半路恢复不会出偏差），单目录任务（流水线同步）也原样 —— 它本来就固定全量、只扫几个目录。
 *
 * 纯函数，单测直接打。
 */
internal fun resumeTaskOf(resume: ScanResume): QueuedScan? = resume.task?.let { task ->
    when {
        task.noRecurse || task.incremental -> task
        else -> task.copy(incremental = true, fast = false)
    }
}

/** 扫描方式名。日志、扫描记录、队列卡片共用一份文案 */
val ScanMode.label: String
    get() = when (this) {
        ScanMode.Full -> "全量"
        ScanMode.Incremental -> "增量"
        ScanMode.Fast -> "快速"
    }

/**
 * 扫描逻辑版本 —— 快速扫描只剪"用当前这套判据扫过的目录"。
 *
 * `scan_state.logicVersion` 与它相等是剪枝条件之一：升级上来的老行是 0 → 每个目录先被列一次
 * （补上 parentUpt/parentUet/hasSubDirs，顺带把既有的老数据自愈逻辑跑完），之后才开始剪。
 * 以后加新判据就 +1，全库自动重列一轮 —— 与 [NfoParser.PARSER_VERSION] 是同一个套路。
 *
 * 版本史：
 * - 1：初版（`hasSubDirs` 把屏蔽目录也算进去）
 * - 2：`hasSubDirs` 改成只算**会递归进去的**子目录 —— 带 extrafanart/.actors 的影片目录
 *   从此算叶子（1 的时候它们全都剪不动，真机实测快速扫描与增量一样慢）。存量行是 1，
 *   所以升级后**下一轮快速扫描会再全列一遍**重算，再下一轮才真正省。
 */
internal const val SCAN_LOGIC_VERSION = 2

/** 快速扫描每轮最多抽查复核几个"剪掉的目录" */
internal const val FAST_SAMPLE_MAX = 20

/**
 * 快速扫描每轮抽查多少个被剪掉的目录**真正列一次**复核。
 *
 * 为什么至少 1 个：小库（只有几个影片目录）取 10% 会取整成 0，那就等于"永远不复核"，
 * 而"目录内改名"恰恰只靠复核才发现。为什么封顶 [FAST_SAMPLE_MAX]：大库里不能让抽查
 * 把省下来的请求又吃回去。
 *
 * 纯函数，单测直接打。
 */
internal fun fastSampleSize(candidates: Int): Int =
    if (candidates <= 0) 0 else (candidates / 10).coerceIn(1, FAST_SAMPLE_MAX)

/**
 * **阶梯限速**的五档间隔（见 [MediaLibraryEntity.rateTiered]）：越扫越慢。
 *
 * 档位按"这一轮正在扫第几个目录"（[MediaScanner.scanDirsDone] + 1）选，用户定的就是这五档：
 * 前 10 个 500ms、11~20 个 1000ms、21~50 个 1500ms、51~100 个 2000ms、100 往上 2500ms。
 *
 * 为什么不是"请求数"而是"目录数"：一个目录可能带来好几次请求（列目录 + 每个簇一次 nfo + 海报），
 * 按请求数选档会让小库很快冲到最慢档、大库反而早早就"变慢"—— 现象上很难解释。
 * 目录数是用户看得见、也说得清的那个进度（与文件整理的 `分级限速` 同一个口径）。
 *
 * 纯函数，单测直接打边界（10/11/20/21/50/51/100/101）。
 */
internal fun tierRateLimitMs(dirsDone: Int): Long = when {
    dirsDone <= 10 -> 500L
    dirsDone <= 20 -> 1_000L
    dirsDone <= 50 -> 1_500L
    dirsDone <= 100 -> 2_000L
    else -> 2_500L
}

/** 每扫这么多个目录休息一次（见 [MediaLibraryEntity.tierRestMinutes]） */
internal const val TIER_REST_EVERY = 100

/**
 * 该不该休息了：这一轮已经跨过几个 [TIER_REST_EVERY] 的整倍数（[restsDone]）比上次多的那一刻。
 *
 * 用"跨过几个整倍数"而不是 `dirsDone % 100 == 0`：后者在续扫/多根任务里会因为计数不从 0 开始
 * 而永远不等（或者连着触发）。纯函数，单测直接打。
 */
internal fun tierRestDue(dirsDone: Int, restsDone: Int): Boolean =
    dirsDone / TIER_REST_EVERY > restsDone

/**
 * 续扫询问前的**探活重试节奏**（第几次探活 → 距上一次失败等多久；[attempt] 从 0 起）。
 *
 * 第 1 次不问隔多久、立刻探（重启后要马上把这个问题处理掉，不让用户白等）；不通就逐步拉开，
 * 最后稳定在每分钟一次 —— 频控/封禁是分钟级到小时级的事，等它的时刻不可知，
 * 每分钟一次列目录的代价可以忽略，而"网络刚回来就能问"对用户很重要
 * （不重试的话，这次进程里就永远不问，那份现场只能留到下次启动）。
 *
 * 纯函数，单测直接打。
 */
internal fun resumeProbeDelayMs(attempt: Int): Long = when {
    attempt <= 0 -> 0L
    attempt == 1 -> 5_000L
    attempt == 2 -> 15_000L
    attempt == 3 -> 30_000L
    else -> 60_000L
}

/**
 * 探一下"115 资源接口现在通不通" —— **续扫询问的准入条件**（见 [MediaScanner.raiseResumeProposal]）。
 *
 * 为什么要有它：扫描多半是被频控/网络打断的（见 [ScanResume.reason]），那种时候弹"要不要接着扫"
 * 纯属白问 —— 用户一点「继续扫描」只会立刻再失败一次，还白搭一次请求。所以问之前先探，通了才问。
 *
 * 探的是**列根目录**（limit=1，最轻的一次列目录）而不是 `user/info`：它与扫描打的是同一批接口、
 * 同一个 [OpenApi] 实例（扫描走 bulkOpenApi），被频控时它最先报错。并且结果要过
 * [parseFilesResponse] —— "HTTP 200 但 state=false"（115 的报错就是这么来的，比如
 * "请求过于频繁"）也算失败，只看 HTTP 状态码会漏掉这一大类。
 *
 * 只咽 Exception、**取消原样抛出**：`runCatching` 会把 CancellationException 一起吞掉，
 * 那样协程被取消之后还会再跑一轮（靠下一轮的引用检查才退出）—— 取消得靠下一轮兜底就是不可靠。
 *
 * [listRoot] 做成参数是为了单测能塞假响应（真的 OpenApi 接口太大，不值得为它写个实现）。
 */
internal suspend fun probeApiHealthy(listRoot: suspend () -> JsonObject): Boolean = try {
    parseFilesResponse(listRoot())
    true
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

/**
 * 扫描记录里"这一条是流水线同步"的模式名。
 *
 * 定向任务（[QueuedScan.noRecurse]）与整库扫描的执行方式都是全量重新索引，光看 [ScanMode]
 * 分不出来；而用户要看的是"这条记录是服务器刮削同步过来的"还是"我自己点的一次扫描"。
 * 所以定向任务汇总出来的那条记录固定用这个模式名。
 */
internal const val SCAN_MODE_PIPELINE = "流水线同步"

/**
 * 把一条**定向任务**（流水线同步）跨多个目录的多轮 [MediaScanner.runScan] 汇总成**一条**
 * 扫描记录。
 *
 * 为什么需要：定向任务的 [QueuedScan.rootCids]/[QueuedScan.rootPaths] 是一个个具体目录，
 * [MediaScanner.runTask] 会为每个目录各跑一轮 runScan —— 每轮都写一条记录的话，服务器一次
 * 同步 5 个目录就在「扫描记录」里变成 5 条「全量 · 新增 1 部」，用户根本看不出这是同一次同步
 * （2026-10-06 用户报的）。汇总之后是一条「流水线同步 · 新增 N 部」，点开就是这一批全部的新片。
 *
 * 纯数据累积（写库交给调用方），单测可以直接打。
 */
class ScanLogAccumulator {
    private var startedAt = 0L
    private var finishedAt = 0L
    private var totalDirs = 0
    private var doneDirs = 0
    private var skippedDirs = 0
    private var indexed = 0
    private var postersFetched = 0
    private var listRequests = 0
    private var stopped = false
    private val newKeys = LinkedHashSet<String>()

    /** 收一轮 runScan 的结果（[ScanReport] 上没有新增键，得单独带上） */
    fun add(report: ScanReport, keys: List<String>) {
        if (startedAt == 0L || (report.startedAt in 1 until startedAt)) startedAt = report.startedAt
        if (finishedAt == 0L || report.finishedAt > finishedAt) finishedAt = report.finishedAt
        totalDirs += report.totalDirs
        doneDirs += report.doneDirs
        skippedDirs += report.skippedDirs
        indexed += report.indexed
        postersFetched += report.postersFetched
        listRequests += report.listRequests
        stopped = stopped || report.stopped
        newKeys += keys
    }

    /** 一轮都没跑成（被停止/列目录就抛）时返回 null：这种"没发生的事"不该记一条记录 */
    fun toEntity(libraryId: Long, libraryName: String): ScanLogEntity? {
        if (finishedAt == 0L) return null
        return ScanLogEntity(
            libraryId = libraryId,
            libraryName = libraryName,
            at = finishedAt,
            elapsedMs = (finishedAt - startedAt).coerceAtLeast(0L),
            totalDirs = totalDirs,
            doneDirs = doneDirs,
            skippedDirs = skippedDirs,
            indexed = indexed,
            postersFetched = postersFetched,
            stopped = stopped,
            mode = SCAN_MODE_PIPELINE,
            listRequests = listRequests,
            newKeys = newKeys.joinToString("\n"),
        )
    }
}

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
 *   列到的那一页直接交给处理那一头复用（**边爬边入库**，见 [runScan]），
 *   早先是同一个目录列两遍（找子目录一遍、取文件又一遍）。
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
         * 正在"防频控休息"的话，休息到什么时候（毫秒时间戳）；0 = 没在休息。
         *
         * 休息是 3~5 分钟的静默期，界面上不说明的话就是"扫描卡死"（真机上用户会以为坏了）——
         * 进度卡据此显示一行"休息中（防频控）"。
         */
        val restingUntil: Long = 0,
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

    /**
     * 「上次那一轮没跑完，要不要接着扫」。
     *
     * 进程被杀（或网络/频控把扫描打断）之后**重启**时才会有值，界面据此弹一次确认
     * （见 [acceptResume] / [dismissResume]）。答过就清掉 —— 同一个中断只问一次。
     *
     * [checking] = 还在**探活**（见 [raiseResumeProposal]）：问题已经立起来了（也落了盘），
     * 但网络/接口很可能还是不通，这时候弹窗只会让用户白点一次 —— 界面据此先不弹，
     * 探通了才把它翻成 false 放出来。
     */
    data class ResumeProposal(
        val task: QueuedScan,
        val resume: ScanResume,
        val checking: Boolean = false,
    )

    private val _resumeProposal = MutableStateFlow<ResumeProposal?>(null)
    val resumeProposal: StateFlow<ResumeProposal?> = _resumeProposal

    /**
     * 续扫询问的探活循环（[raiseResumeProposal] 起的那个）。同一时刻只允许一个 ——
     * 新问题立起时把旧的取消掉（旧的那个已经不代表任何东西了）。
     */
    private var resumeProbeJob: Job? = null

    /**
     * 用户点了「跳过本次」的那份现场：**这次不问也不扫，但留着下次启动再问**。
     *
     * 它只活在内存里 —— [persistQueue] 会把它镜像进盘上那个"待回答的问题"槽
     * （见 [proposalToPersist]），所以下次启动照问。**本次进程里不会再立起来**：
     * 用户刚说过"跳过本次"，同一个进程里再弹一次就是没听见他说话。
     * 被同库的新一轮扫描覆盖（[taskCoversResume]）、或全部停止/登出时清掉。
     */
    private var deferredResume: ScanResume? = null

    /**
     * 正在跑那一轮的**在途现场**（[ScanQueueSnapshot.inflight] 的内存一份）。
     *
     * 落盘的时机：任务开跑时建立、每 [RESUME_PROGRESS_STEP] 个目录刷一次进度、跑完清掉
     * （异常中断则转成 [resumeProposal] 那个问题，见 [drain]）。进程被杀时盘上那份就是
     * "最后一条进度"，重启后据此问用户要不要接着扫。
     * @Volatile：写它的是扫描 worker，读它的 [persistQueue] 可能从任何线程来。
     */
    @Volatile
    private var inflight: ScanResume? = null

    /** 上次把进度刷进 [inflight] 时的目录数（每 N 个写一次盘，不必逐目录都写） */
    private var resumeNotedAt = 0

    /**
     * **这一轮任务**已处理完的目录数（跨根累计，[drain] 取到任务时归零）。
     *
     * 阶梯限速（[tierRateLimitMs]）与"每 100 个休息"（[tierRestDue]）都按它走 ——
     * 必须是**任务级**而不是 runScan 级：流水线同步一条任务会带 N 个目录，每个目录各调一次 runScan，
     * 按 runScan 计数的话每调一次都从 0 开始，阶梯永远停在第一档。
     */
    private var scanDirsDone = 0

    /** 这一轮任务已经休息过几次（防频控休息，见 [tierRestDue]） */
    private var restsDone = 0

    /** 这一轮的限速设置（[drain] 取到任务时从快照写入）：`throttle` 与休息逻辑都读它 */
    @Volatile
    private var tieredRate = false

    @Volatile
    private var restMinutes = 0

    /** 这一条任务里出现过异常中断吗（[runScan] 的 catch 里写）。跑完由 [drain] 决定要不要留续扫现场 */
    private var abnormalInterrupt: String? = null

    /**
     * 本轮队列**累计**入库数 —— 界面完成提示「扫描完成，新增 N 部」用它。
     *
     * [Progress.moviesIndexed] 只有**当前这一轮 runScan** 的数字；一次排了多个目录时
     * （流水线同步是"一条任务多个 root"，每个 root 一次 runScan）拿最后一条会少报。
     * 从空闲状态入队时清零（见 [enqueue]），每条 runScan 收尾累加（见它的 finally）。
     */
    private val _batchIndexed = MutableStateFlow(0)
    val batchIndexed: StateFlow<Int> = _batchIndexed

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
                    ScanQueueSnapshot(
                        running = _current.value,
                        pending = _pending.value,
                        inflight = inflight,
                        // 未回答的问题就是界面上那个，不另存一份内存副本（两处会走岔）；
                        // 「跳过本次」跳过的那份也落在同一个槽里 —— 差别只在内存里它还立不立着
                        proposal = proposalToPersist(_resumeProposal.value?.resume, deferredResume),
                    ),
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
        synchronized(qLock) {
            // 从"空闲"起的这一批任务算一轮：累计入库数清零（完成提示读它，见 [batchIndexed]）
            if (_pending.value.isEmpty() && _current.value == null) _batchIndexed.value = 0
            _pending.value = queueWithTask(_pending.value, task)
        }
        persistQueue()
        ensureDrain()
    }

    /**
     * 按库当前的设置排队扫一个库（界面「快速/增量/全量扫描」、启动自动扫描都走这里）。
     *
     * [auto] = 这条来自**启动时的定时扫描**（见 [QueuedScan.auto]）：流水线同步一排定向任务
     * 就会把它撤掉（[dropAutoQueued]），因为那一轮已经把变动覆盖了。
     */
    fun enqueue(library: MediaLibraryEntity, mode: ScanMode, auto: Boolean = false) =
        enqueue(QueuedScan.of(library, mode, auto))

    /**
     * 撤掉这个库**排队中（还没开跑）的定时扫描任务**，返回撤掉了几个。
     *
     * 流水线同步排下定向任务时调：同一时刻两边都触发了，就忽略定时任务（用户明确要求的规则）——
     * 定向任务拿的是服务器刚刮完那批变动，这时再整库扫一遍纯属白花频控额度。
     * 正在跑的那一轮撤不掉（扫描由 mutex 串行、不打断在途请求），那种情况只记一行日志。
     */
    fun dropAutoQueued(libraryId: Long): Int {
        var dropped = 0
        synchronized(qLock) {
            val rest = queueWithoutAuto(_pending.value, libraryId)
            dropped = _pending.value.size - rest.size
            if (dropped > 0) _pending.value = rest
        }
        if (dropped > 0) {
            persistQueue()
            Log.i(TAG, "流水线同步排下定向任务，撤掉该库排队中的 $dropped 个定时扫描任务")
        }
        val runningAuto = _current.value?.let { it.libraryId == libraryId && it.auto } == true
        if (runningAuto) Log.i(TAG, "该库的定时扫描已经在跑，这一轮与定向同步并行（撤不掉在途的那一轮）")
        return dropped
    }

    /**
     * 立起"要不要接着扫"这个问题 —— 但**先探活**：接口/网络不通时问了也白问
     * （用户点「继续」只会立刻再失败一次）。问题先以 [ResumeProposal.checking] = true 挂着
     * （界面据此不弹窗，盘上照常落一份），探到接口正常才放出来。
     *
     * 探不通就按 [resumeProbeDelayMs] 的节奏重试；这一轮里探多少次都不通也无所谓 ——
     * 盘上那份 proposal 还在，下次启动 [restoreQueue] 会再问、再探。
     *
     * 让问题失效的动作（答「继续」/「忽略」、同库新一轮扫描覆盖、全部停止/登出）都是把
     * [_resumeProposal] 置空或换掉，所以循环每轮先认一次**引用相等**、对不上就自己退出 ——
     * 不需要那些地方记得来取消它，也就不会漏。
     */
    private fun raiseResumeProposal(task: QueuedScan, scene: ScanResume) {
        val proposal = ResumeProposal(task = task, resume = scene, checking = true)
        _resumeProposal.value = proposal
        resumeProbeJob?.cancel()
        resumeProbeJob = workerScope.launch {
            var attempt = 0
            while (true) {
                resumeProbeDelayMs(attempt).takeIf { it > 0 }?.let { delay(it) }
                if (_resumeProposal.value !== proposal) return@launch
                if (probeApiHealthy { openApi.files(cid = "0", limit = 1) }) {
                    // 探通的那一刻再认一次：等它的这段时间里用户可能已经答过、或被新一轮扫描覆盖
                    if (_resumeProposal.value === proposal) {
                        _resumeProposal.value = proposal.copy(checking = false)
                        Log.i(TAG, "接口正常，问用户要不要续扫「${task.libraryName}」")
                    }
                    return@launch
                }
                attempt++
                Log.i(
                    TAG,
                    "接口/网络不通，先不问续扫（第 $attempt 次探活失败），" +
                        "${resumeProbeDelayMs(attempt) / 1000} 秒后再试",
                )
            }
        }
    }

    /**
     * 用户答"继续扫描"：把上次没跑完的那一轮重新排进队列（[resumeTaskOf] 会把全量降级成增量，
     * 于是 scan_state 让它从断点接着跑）。
     */
    fun acceptResume() {
        val p = _resumeProposal.value ?: return
        _resumeProposal.value = null
        // 现场交给队列（任务本身在队列里，还没跑完的目录在 scan_state 里），盘上那份可以清了 ——
        // 但**有任务正在跑时不能动**：内存里那份是它的（两个任务各刷各的进度会互相踩）
        clearInflightIfIdle()
        val task = resumeTaskOf(p.resume) ?: return
        Log.i(TAG, "继续上次未完成的扫描：「${task.libraryName}」（${task.mode.label}）")
        enqueue(task)
    }

    /** 用户答「不再询问」：这次中断就此作罢，盘上的现场也一起清掉（同一个中断只问一次） */
    fun dismissResume() {
        if (_resumeProposal.value == null) return
        _resumeProposal.value = null
        clearInflightIfIdle()
        persistQueue()
        Log.i(TAG, "不再询问上次未完成的扫描")
    }

    /**
     * 用户答「跳过本次」：这次不扫，**现场留着** —— 下次启动会再问一遍
     * （它落在盘上那个"待回答的问题"槽里，见 [proposalToPersist]）。
     *
     * 与「不再询问」的区别就在这儿：那个是"这次中断就此作罢"，盘上一起清掉、从此不问。
     * 本次进程内不再立起来 —— 用户刚说过跳过，马上再弹一次就是没听见他说话。
     */
    fun deferResume() {
        val p = _resumeProposal.value ?: return
        _resumeProposal.value = null
        // 与另外两个答复一样：现场从"问题"退回盘上（此处不当场重建 inflight —— 没有任务在跑）
        clearInflightIfIdle()
        deferredResume = p.resume
        persistQueue()
        Log.i(TAG, "跳过本次续扫：「${p.task.libraryName}」下次启动再问")
    }

    /**
     * 清掉在途现场（[inflight]）—— **只在没有任务跑的时候**。
     *
     * 正在跑的那一轮自己会用这个字段刷进度（见 [noteResume]），把它清掉等于让它以后的进度
     * 都不落盘；而它本来就是"在途现场"的主人 —— 用户刚答过的那个问题已经是过去式了
     * （答"继续"任务进了队列、答"忽略"队列里本来就没有它），清的是它的残留。
     */
    private fun clearInflightIfIdle() {
        if (_progress.value.running) return
        inflight = null
    }

    /**
     * 排一条"只扫这几个目录"的任务（流水线同步，见 [PadSync]）。[dirs] 是 (cid, path) 对。
     *
     * 同一个库已经有排队中的定向任务时**把目录并进去**再替换（[queueWithTask] 对同类型是就地替换）：
     * 信号是一批一批来的，直接替换会把上一批还没轮到的目录丢掉。
     * 正在跑的那条不算进来 —— 它的目录已经在扫了。
     */
    fun enqueueTargeted(library: MediaLibraryEntity, dirs: List<Pair<String, String>>) {
        if (dirs.isEmpty()) return
        val queuedDirs = synchronized(qLock) {
            _pending.value.firstOrNull { it.libraryId == library.id && it.noRecurse }
        }?.let { it.rootCids.zip(it.rootPaths) }.orEmpty()
        val merged = (queuedDirs + dirs).distinctBy { it.first }
        enqueue(QueuedScan.targeted(library, merged))
    }

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
     * 两类东西区别对待：
     *  - **排队中**（还没开跑）的：原样排回去（用户当时选增量还是全量都认）——
     *    它们没开跑，谈不上"未尽"，也没什么可问的；
     *  - **没跑完的那一轮**：**不自动接着跑**，改成 [resumeProposal] 交给界面问一句
     *    （用户可能已经不想扫了；而且它也可能是在频控/网络异常里断的，见 [ScanResume]）。
     *    判据只看两份现场（[proposalToRestore]），**不看 running** —— 见那个函数上的说明。
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
        val stale = snapshot.running == null && snapshot.pending.isEmpty() &&
            snapshot.inflight == null && snapshot.proposal == null
        if (stale) return
        // ★ 先立起这一轮的问题、再恢复排队的任务：恢复入队会立刻把 drain 拉起来跑，
        //   而 drain 开跑时会撤掉"同一个库"的那个问题（新的扫描覆盖了它）
        proposalToRestore(snapshot)?.let { scene ->
            val task = scene.task ?: return@let
            // 只立"问题"，不动 inflight：启动这一刻没有任何任务在途（盘上那份由 persistQueue
            // 按 proposal 镜像回写），留着它反而会让"在途现场"这个字段说着假话
            raiseResumeProposal(task, scene)
            Log.i(
                TAG,
                "上次「${task.libraryName}」扫描未完成（扫完 ${scene.doneDirs} 个目录，" +
                    "原因：${scene.reason.ifBlank { "进程被关闭" }}），先探活，接口正常再问是否续扫",
            )
        }
        pendingToRestore(snapshot).forEach { enqueue(it) }
        // 写一次盘：盘上那份 running 从此只反映"此刻真的有一条在跑"（未跑完那一轮不再自动跑，
        // 上面的 enqueue 可能已经把恢复出来的排队任务拉起来了）。**不能**直接 `_current.value = null`
        // 去清 —— 那一刻 `_current` 可能已经是刚起跑的新任务，覆盖它等于让"正在跑的是谁"对不上
        persistQueue()
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
                // 这一条开跑了：立起**在途现场**（进程这会儿被杀掉，盘上就留着"扫到哪儿了"）。
                // 同一个库的那个"要不要接着扫"也就过期了 —— 这一轮本来就覆盖它（别的库的问题
                // 不受影响：用户还没回答之前，那个问题该一直等着）。
                // ★ 只换在途现场、不碰那个问题：两件事必须分开存 —— 否则排队里的下一个任务
                //   一开跑就把"上一个库扫到一半被打断"这件事抹掉（复审抓到的真问题）。
                abnormalInterrupt = null
                resumeNotedAt = 0
                // 阶梯限速 / 休息是**任务级**的进度（一条任务可能含多个根、多个目录）：
                // 计数在任务开跑时归零，这一轮从头按第一档走
                scanDirsDone = 0
                restsDone = 0
                tieredRate = task.rateTiered
                restMinutes = task.tierRestMinutes
                inflight = ScanResume(task = task, at = System.currentTimeMillis())
                // 同库、同一种任务（都是整库，或都是流水线定向）→ 这一轮覆盖了那个问题；
                // 定向任务**不算**覆盖整库的问题（它只扫几个目录，整库那轮仍没扫完）
                if (taskCoversResume(task, _resumeProposal.value?.resume)) _resumeProposal.value = null
                // 「跳过本次」留下的那份同理：同一个库刚被扫过，就没有"下次启动再问"的必要了
                if (taskCoversResume(task, deferredResume)) deferredResume = null
                persistQueue()
                runCatching { runTask(task) }
                    .onFailure { Log.w(TAG, "排队扫描「${task.libraryName}」中断: ${it.message}") }
                // 正常跑完 / 用户主动跳过 → 在途现场清掉；**异常中断（频控、网络）则把它变成
                // 一个"要不要接着扫"的问题** —— 当场就能问，重启后也还问得到（见 ScanQueueSnapshot）
                if (abnormalInterrupt == null) {
                    inflight = null
                } else {
                    val scene = inflight ?: ScanResume(task = task, at = System.currentTimeMillis())
                    inflight = null
                    // 异常中断的**当场**就立问题，但照例先探活：断的原因多半就是接口/网络，
                    // 那一刻直连再打一次大概率还是失败 —— 探通了才弹（见 raiseResumeProposal）
                    raiseResumeProposal(task, scene)
                    Log.i(
                        TAG,
                        "「${task.libraryName}」这一轮被中断（${abnormalInterrupt}），" +
                            "扫完 ${scene.doneDirs} 个目录，先探活，接口正常再问是否续扫",
                    )
                }
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
        // 定向任务（流水线同步）：rootCids/rootPaths 是一个个**具体目录**，逐目录跑，
        // 但**只写一条**扫描记录（见 [ScanLogAccumulator]）—— 一次同步 5 个目录就是
        // 一条「流水线同步 · 新增 N 部」，而不是 5 条「全量 · 新增 1 部」。
        if (task.noRecurse) {
            val sink = ScanLogAccumulator()
            task.rootCids.zip(task.rootPaths).forEach { (cid, path) ->
                runScan(
                    cid, path,
                    includeSubDirs = false,
                    mode = task.mode,
                    rateLimitMs = task.rateLimitMs,
                    minVideoSizeMb = task.minVideoSizeMb,
                    libraryId = task.libraryId,
                    libraryName = task.libraryName,
                    logSink = sink,
                )
            }
            runCatching { sink.toEntity(task.libraryId, task.libraryName)?.let { dao.addScanLog(it) } }
                .onFailure { Log.w(TAG, "「${task.libraryName}」写流水线同步记录失败: ${it.message}") }
            return
        }
        task.rootCids.zip(task.rootPaths).forEach { (cid, path) ->
            runScan(
                cid, path,
                includeSubDirs = true,
                mode = task.mode,
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
        // 用户要停这一轮 → 在途现场也不保留：他要停，就别（在这次进程被杀之后）再被问
        // "要不要接着扫" —— 界面那句"用增量扫描可从断点继续"已经把续扫的门留着了。
        // 顺手落盘：不写的话盘上那份还在，进程在收尾的空档里死掉下次启动又会问
        inflight = null
        persistQueue()
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
        // 「全部停止」/ 登出是**明确的用户意图**：连带把续扫询问与现场清掉 ——
        // 留着的话下次启动会问"要不要接着扫上次那轮"，而用户刚刚才说不要扫。
        // （登出尤其必须清：那条任务属于上一个账号的库。「跳过本次」留下的那份也是同一个道理，
        //   它同样是"下一次启动再问"，更同样是上一个账号的事。）
        _resumeProposal.value = null
        inflight = null
        deferredResume = null
        // 排队是空的（clearQueue 内部只在撤掉东西时才落盘）也得写一次：上面刚把现场清了，
        // 不写的话盘上那份还在，进程这时死掉下次启动又会问一遍
        persistQueue()
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
        inflight = null
        persistQueue()
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

    /**
     * 本次扫描的**列目录请求次数**（[listFiles] 里逐次累加，含翻页与侧挂素材目录）。
     *
     * 存在的意义是量化快速扫描省了多少：增量模式它 ≈ 处理过的目录数，快速模式应当明显更小。
     * 扫描由 mutex 串行化，用普通字段就够；[runScan] 开头清零。
     */
    private var listRequests = 0

    private suspend fun throttle(rateLimitMs: Long) {
        // 阶梯限速打开时**忽略**库上那个定死值（两套规则同时生效只会互相打架）：
        // 间隔按"这一轮正在扫第几个目录"选档，见 [tierRateLimitMs]
        val limit = if (tieredRate) tierRateLimitMs(scanDirsDone + 1) else rateLimitMs
        if (limit <= 0) return
        val now = System.currentTimeMillis()
        val wait = lastRequestAt + limit - now
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
     *
     * [dirPath] 只用于日志：光打 cid 的话那句"只取到 X/Y 项"没人看得懂是哪个目录
     * （排查"某个目录少扫了内容"时，路径才是能对上号的那个信息）。
     */
    private suspend fun listFiles(cid: String, dirPath: String, rateLimitMs: Long): FilesPage {
        listRequests++
        val first = parseFilesResponse(
            run { throttle(rateLimitMs); openApi.files(cid = cid, limit = PAGE_SIZE) },
        )
        if (first.count <= first.items.size) return first

        val all = first.items.toMutableList()
        while (all.size < first.count && all.size < MAX_PAGES * PAGE_SIZE) {
            listRequests++
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
            Log.w(TAG, "目录 $dirPath 只取到 ${all.size}/${first.count} 项（分页上限 $MAX_PAGES 页，cid=$cid）")
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
     * [mode] 见 [ScanMode]：全量不跳过任何目录；增量按 scan_state 的 cloudUpt + 指纹跳过**重索引**；
     * 快速在此之上连"没变的叶子目录"都不列（省列目录请求，判据见 [runScan] 的爬取段）。
     *
     * [minVideoSizeMb] 是**每库**的体积过滤（0 = 不过滤）：小于它的视频不入库。
     * 在聚类阶段就滤掉，见 [sniffDirectory]。
     *
     * 限速读的是**任务级**字段（[tieredRate] / [restMinutes] / [scanDirsDone]，由 [drain] 在
     * 任务开跑时布置）：一条任务的多根、多目录共用一个计数，阶梯才会真的往上走。
     *
     * [libraryId] 非空 = 这一轮是为某个媒体库跑的：跑完把 `libraries.lastScanAt` 写掉
     * （定时增量扫描靠它判断"距上次扫描够久了吗"），进度里也带上它（界面据此显示是哪个库）。
     * 调用方不给就什么都不记 —— 扫描器本身不认识"库"这个概念，只认识根目录。
     */
    suspend fun runScan(
        rootCid: String,
        rootPath: String,
        includeSubDirs: Boolean = true,
        mode: ScanMode = ScanMode.Incremental,
        rateLimitMs: Long = 0,
        minVideoSizeMb: Int = 0,
        libraryId: Long? = null,
        /** 库名，只用于进度显示（[Progress.libraryName]） */
        libraryName: String = "",
        /**
         * 非空 = 这一轮的结果**不单独写扫描记录**，而是累加给它，由调用方最后汇总成一条
         * （流水线同步的定向任务用，见 [ScanLogAccumulator]）。
         */
        logSink: ScanLogAccumulator? = null,
    ) {
        val incremental = mode != ScanMode.Full
        val fast = mode == ScanMode.Fast
        /**
         * 日志里标识"这一轮扫的是哪个库的哪个根"。
         *
         * 队列化之后日志里会连着出现好几个库的好几轮扫描 —— 光有"跳过 7/7 个目录"认不出是谁，
         * 只给库名又认不出多根库的哪一个根，所以两样都给（单根库看着有点冗余，但读日志的人
         * 不用再去查一遍库表）。库名为空（直接调 runScan 的场合）时只剩路径。
         */
        val roundLabel = if (libraryName.isBlank()) rootPath else "$libraryName:$rootPath"
        mutex.withLock {
            if (_progress.value.running) return
            // 队列间隙里点的停止（上一条刚跑完、这一条已经出队还没开跑）：这一条不跑了。
            // 与 running = true 在**同一个临界区**里，那个空隙才关得掉。
            // 顺带把停止位消费掉，不让它留给下一个库（放进队循环也做同样的事，见 [drain]）。
            if (stopRequested.getAndSet(false)) {
                Log.i(TAG, "「$roundLabel」在开始前被停止，这一轮跳过")
                return
            }
            postersFetched = 0
            listRequests = 0
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
        /** 其中是**快速模式剪掉的**（一次都没列）—— 日志与扫描记录里用来量化省了多少 */
        var pruned = 0
        /**
         * **列目录阶段已发现的目录数**（含被剪掉的）：进度、日志、扫描记录都用它当分母。
         *
         * 边爬边入库之后它不再等于"扫完的总数"（爬到哪儿才数到哪儿），所以它同时充当进度里的
         * `totalDirs` —— 停在中途时"已完成 M/N 个目录"里的 N 是"已发现"，不是"全库"。
         */
        var discovered = 0
        /** 快速模式**剪掉**的目录（本轮一次都没列）：爬完再按"最久没复核"抽查一部分真正列一次 */
        val prunedListings = mutableListOf<DirListing>()
        /** 方式名（"全量"/"增量"/"快速"）：日志与扫描记录共用，收尾的 finally 里还要用 */
        val modeLabel = mode.label
        /** 本次**新增**的顶层影片键（分集不单列）—— 存进扫描记录，界面拿它 join 出海报与标题 */
        val newKeys = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        resumeNotedAt = 0
        try {
            // 老数据自愈：哪些目录还是"元数据与视频分成两条"的样子（`ABC-101-4K-C` + `ABC-101-U`）。
            // 合并逻辑是后加的，而指纹一致时下面的循环会跳过这个目录 —— 不特判就永远合不起来。
            // 判据是纯本地的，一次查完全库（见 dao.dirsNeedingOrphanMerge）。
            // 提前到列目录之前：**快速模式的剪枝也要拿它当判据**（在集合里的目录不能剪）。
            val orphanMergeDirs = if (incremental) dao.dirsNeedingOrphanMerge().toHashSet() else emptySet()

            /** 进度刷新：done / 已发现 / 已入库 / 已缓存图一次写全，顺带把续扫现场按步长落盘 */
            fun noteProgress() {
                _progress.value = _progress.value.copy(
                    doneDirs = done,
                    totalDirs = discovered,
                    discoveredDirs = discovered,
                    moviesIndexed = movies,
                    postersFetched = postersFetched,
                )
                noteResume(
                    done = done,
                    total = discovered,
                    movies = movies,
                    currentDir = _progress.value.currentDir,
                )
            }

            /**
             * 到了"每 [TIER_REST_EVERY] 个目录休息一次"的点就睡几分钟（防频控）。
             * 设置没开（[restMinutes] 为 0）时什么都不做。
             *
             * 睡的是**1 秒一片**的短觉、每片查一次停止位：用户点了「停止」（以及删库前的那次等待）
             * 最多多等 1 秒 —— 一整觉睡满 3~5 分钟才醒的话，那个键就废了（删除媒体库时更是卡住）。
             *
             * 休息期间**整个队列一起等**（排在后面的库也停）：冷却的是**这个账号**对 115 的请求节奏，
             * 不是某一个库 —— 让下一个库接着猛冲等于白休。
             */
            suspend fun maybeRest() {
                if (restMinutes <= 0) return
                if (!tierRestDue(scanDirsDone, restsDone)) return
                restsDone = scanDirsDone / TIER_REST_EVERY
                val total = restMinutes * 60_000L
                Log.i(
                    TAG,
                    "「$roundLabel」已扫 $scanDirsDone 个目录，休息 $restMinutes 分钟（防频控）",
                )
                _progress.value = _progress.value.copy(
                    restingUntil = System.currentTimeMillis() + total,
                )
                var left = total
                while (left > 0 && !stopRequested.get()) {
                    val slice = minOf(left, 1000L)
                    kotlinx.coroutines.delay(slice)
                    left -= slice
                }
                _progress.value = _progress.value.copy(restingUntil = 0)
            }

            /**
             * 一个目录处理完了：计数 → 刷进度 → 该休息就休息（每 [TIER_REST_EVERY] 个目录一次）。
             *
             * 收在一处是为了**阶梯限速的计数不漏**：`done` 有四个 ++ 的出口（被屏蔽的、剪掉的、
             * 指纹没变跳过的、真索引的）。漏一个的后果不是报错，而是阶梯比设置走得慢 ——
             * 防 ban 的那道闸悄悄失效，真机上只会以"莫名其妙被限流"的形式暴露。
             */
            suspend fun finishDir() {
                done++
                scanDirsDone++
                noteProgress()
                maybeRest()
            }

            /**
             * 处理一个**已经列到**的目录：聚类 → 拉 nfo → 入库 → 写 scan_state。
             * 返回 false = 用户在这一步中途按了停止，爬取循环要立刻收尾。
             *
             * 抽成局部函数就是为了**边爬边入库**：列到一个目录就当场处理它 —— 叶子目录爬完当即入库，
             * 不再等整棵树的列目录阶段跑完（大库的列目录本身就能占掉整轮的大半时间）。
             * 处理逻辑与"先爬完再统一索引"那版一字不差，所以入库结果也没有区别。
             */
            suspend fun process(listing: DirListing): Boolean {
                if (stopRequested.get()) return false
                _progress.value = _progress.value.copy(currentDir = listing.path)
                // 被屏蔽的目录（侧挂素材 / 花絮）不该出现在这里（爬取那边已经剪掉），
                // 这道判断是兜底：万一以后有人改了剪枝、或者换了入口，混进来的后果不是报错
                // 而是静默多出垃圾条目。仍然算作"处理完一个目录"（进度里的分母是已发现的目录数）。
                val isRoot = listing.path.trimEnd('/') == rootPath.trimEnd('/')
                if (!isRoot && isIgnoredDirName(listing.path.substringAfterLast('/'))) {
                    finishDir()
                    return true
                }
                // 快速模式剪掉的目录：本轮**一次请求都不发** —— 父目录那一页告诉我们
                // "它的成员集合没变"，而它上次扫的时候又不是"有下级"的目录。
                // 海报照旧从库里已有的 pick_code 补（全都在本地时零请求），与增量模式被跳过的目录同款待遇。
                if (listing.pruned) {
                    prefetchDirPosters(listing.cid, rateLimitMs)
                    pruned++
                    skipped++
                    finishDir()
                    return true
                }
                // 爬取那边一定把列到的那一页一起传过来了；兜底再列一次，防的是"以后换了入口没带 page"
                val page = listing.page ?: listFiles(listing.cid, listing.path, rateLimitMs)
                val files = fileRefsOf(page)
                // 以前扫进去的"附加内容"（花絮/预告）要清掉：那些目录现在被屏蔽了、
                // 不会再出现在待扫目录里，靠循环末尾那套"陈旧条目清理"永远够不着它们，
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
                        // ★ 复算"剪枝要用的那几列"。**这个分支不重新索引，但状态行必须跟上**：
                        //   老行（DB v15 之前）的 logicVersion 是 0、hasSubDirs 还是"屏蔽目录也算"的
                        //   旧口径 —— 补不上的话快速扫描永远等不到"版本对得上"，会一轮轮全列下去。
                        //   真机踩到过：两轮快速扫描的列目录数跟增量一模一样，库里全是 lv=1 的行。
                        //   走到这里说明指纹与 cloudUpt 都匹配，所以只刷新这几列、不动它们。
                        dao.upsertScanState(
                            state.copy(
                                dirPath = listing.path,
                                parentUpt = listing.parentUpt,
                                parentUet = listing.parentUet,
                                hasSubDirs = hasTraversableSubDirs(page),
                                logicVersion = SCAN_LOGIC_VERSION,
                                scannedAt = System.currentTimeMillis(),
                            ),
                        )
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
                            val art = sideArtOf(page.items, listing.path, rateLimitMs)
                            dao.backfillSideArtInDir(
                                dirKey = listing.cid,
                                fanartCodes = encodePickCodes(art.fanart.map { it.pickCode }),
                                fanartDirCid = art.fanartDirCid,
                                actorsDirCid = art.actorsDirCid,
                                actorAvatars = art.actorAvatars.mapValues { it.value.pickCode },
                            )
                        }
                        skipped++
                        finishDir()
                        return true
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
                val sideArt = if (includeSubDirs) sideArtOf(page.items, listing.path, rateLimitMs) else null
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
                    return false
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
                        // 这几个是给快速模式用的：下次扫描靠它们判断"这个目录还用不用列"。
                        // 父视角的两个值直接来自**发现它时那一页**（免费），不是这次列出来的。
                        parentUpt = listing.parentUpt,
                        parentUet = listing.parentUet,
                        // 只算**会递归进去的**子目录：屏蔽目录（extrafanart / .actors / 花絮…）不算。
                        // 它们里面的图变化不顶本目录的任何时间字段，拿它们当"有下级"等于让每个
                        // 带侧挂素材的影片目录永远不能剪 —— 实测某库 248 个目录里 164 个就是它们，
                        // 那样快速扫描一点都不省（真机实测过：列目录数与增量一样）。
                        // 代价：往 extrafanart 里加图只影响详情页剧照，要等抽查/全量才补上。
                        hasSubDirs = hasTraversableSubDirs(page),
                        logicVersion = SCAN_LOGIC_VERSION,
                    ),
                )
                finishDir()
                return true
            }

            // ---------------- 爬取 + 入库（边爬边入库） ----------------
            // ★ **列到一个目录就当场处理它**（process），不再"先爬完整棵树、再挨个索引"。
            //   爬取顺序仍是 **BFS**，与老版本一字不差：所以父目录一定先于它的子目录入库 ——
            //   分集往上找系列、继承系列海报靠的就是这一点。
            //   快速模式的判据（为什么能"连列都不列"）全在下面这段里，缺一不可：
            //    1. `state.parentUpt` == 父目录那一页里这个条目的 `upt`。这是主判据：115 的目录 `upt`
            //       对**成员集合**变化敏感（文件/子目录的新增、删除、移入移出 —— 实测），所以"现在给我的
            //       upt 跟我记的一样"就等于"我这层的成员没动过"。★ 必须在**父目录那一页**里比：
            //       它自己那一页是"它下面有什么"，不是"它自己变没变"。
            //    2. `state.parentUet` == 同一页里的 `uet`。`upt` 对**改名**不敏感（实测），`uet` 有没有
            //       反应还没验过 —— 现在这条是个恒等比较（值相同就放行），等日志里看出它会动，判据自动就紧了。
            //    3. `state.dirPath` == 当前路径。**这一条专门挡"目录被改名"**：改名不改 cid、也不顶任何
            //       时间字段，只有路径对不上才看得出来；不放行的话库里那些行会一直挂着旧路径。
            //    4. `!state.hasSubDirs`：上次列它时它没有**会递归进去的**子目录。有下级的目录不能剪 ——
            //       变化不向上传播，想确认下级没变只能列它看下级的 upt。
            //    5. `state.status == 2 && state.logicVersion == SCAN_LOGIC_VERSION`：上次扫完了，而且是用
            //       **当前这套判据**扫的。升级上来的老行是 0 → 先全列一遍补判据。
            //    6. 不在老数据自愈集合里（[MediaDao.dirsNeedingOrphanMerge]，纯本地查询，零请求）。
            //   剪掉的目录**照样记进 prunedListings**：之后按"最久没复核的优先"抽 [fastSampleSize] 个
            //   真正列一次 —— 改名不顶任何字段，只有真列出来才看得见，抽查就是把"漏掉一次改名"
            //   变成"漏几次"的那道兜底。
            if (!includeSubDirs) {
                // 单目录任务（流水线同步）：就这一个目录，不递归。这里没有"爬取"阶段，
                // 进度按 0/1 → 1/1 走（与老版本对定向任务的表现一致）
                val listing = DirListing(rootCid, rootPath, listFiles(rootCid, rootPath, rateLimitMs))
                discovered = 1
                _progress.value = _progress.value.copy(phase = Phase.Indexing, totalDirs = 1)
                if (!process(listing)) stoppedEarly = true
            } else {
                val rootPage = listFiles(rootCid, rootPath, rateLimitMs)
                val queue = ArrayDeque<PendingDir>()
                discovered = 1
                noteProgress()
                if (!process(DirListing(rootCid, rootPath, rootPage))) {
                    // 停止请求在开跑前就立起来了（队列间隙里点的）：这一轮什么都不做
                    stoppedEarly = true
                } else {
                    pushChildren(rootPage, rootPath, queue)
                    // 剪枝"一个都没剪"时最容易被当成功能坏了（真机上就撞过一次：所有影片目录都带
                    // extrafanart，全被判成有下级）。所以把"没剪的原因"数出来，一个都没剪时打一行 ——
                    // 只有诊断价值，不影响扫描。
                    var prunedCount = 0
                    var whyVersion = 0
                    var whyChildren = 0
                    var whyChanged = 0
                    var whyOther = 0
                    while (queue.isNotEmpty()) {
                        if (stopRequested.get()) {
                            stoppedEarly = true
                            break
                        }
                        val next = queue.removeFirst()
                        if (fast) {
                            val st = dao.scanState(next.cid)
                            val prunable = st != null && st.status == 2 &&
                                st.logicVersion == SCAN_LOGIC_VERSION &&
                                st.dirPath == next.path &&
                                !st.hasSubDirs &&
                                st.parentUpt == next.entry.upt &&
                                st.parentUet == next.entry.uet &&
                                next.cid !in orphanMergeDirs
                            if (prunable && st != null) {
                                // 日志里留一份判据现场：`uet` 到底动没动、`upt` 对不对得上，
                                // 排查"该剪的没剪/不该剪的剪了"时只有这行能回答
                                Log.i(
                                    TAG,
                                    "快速·剪掉 ${next.path}（upt=${next.entry.upt} uet=${next.entry.uet}）",
                                )
                                prunedCount++
                                discovered++
                                prunedListings += DirListing(
                                    cid = next.cid,
                                    path = next.path,
                                    page = null,
                                    pruned = true,
                                    lastVerifiedAt = st.scannedAt,
                                    parentUpt = next.entry.upt,
                                    parentUet = next.entry.uet,
                                )
                                continue
                            }
                            when {
                                st == null || st.status != 2 || st.dirPath != next.path -> whyOther++
                                st.logicVersion != SCAN_LOGIC_VERSION -> whyVersion++
                                st.hasSubDirs -> whyChildren++
                                st.parentUpt != next.entry.upt || st.parentUet != next.entry.uet -> whyChanged++
                                else -> whyOther++
                            }
                            // 顺带的观测：这个目录**这轮要列**，把判据现场打出来。
                            // 想验"改名顶不顶 uet / upt"，就看同一个目录两轮日志里的这两个值 ——
                            // 剪掉的那条也会打（上面那行），所以"该剪 / 不该剪"都能对上号。
                            Log.i(TAG, "快速·列 ${next.path}（upt=${next.entry.upt} uet=${next.entry.uet}）")
                        }
                        val page = listFiles(next.cid, next.path, rateLimitMs)
                        discovered++
                        noteProgress()
                        // 发现它时那一页里的两个时间字段要落进 scan_state（下轮剪枝的判据），
                        // 也直接交给 process 用 —— 不再对同一个目录重复请求
                        val listing = DirListing(
                            cid = next.cid,
                            path = next.path,
                            page = page,
                            parentUpt = next.entry.upt,
                            parentUet = next.entry.uet,
                        )
                        if (!process(listing)) {
                            stoppedEarly = true
                            break
                        }
                        pushChildren(page, next.path, queue)
                    }
                    if (fast) {
                        Log.i(
                            TAG,
                            "「$roundLabel」快速扫描：${discovered - prunedCount}/${discovered} 个目录要列" +
                                "（$prunedCount 个没变的叶子目录被剪掉，其中含每轮抽查复核的）",
                        )
                        if (prunedCount == 0 && discovered > 1) {
                            Log.i(
                                TAG,
                                "快速·这轮一个都没剪（候选 ${discovered - 1} 个）：判据版本待补 $whyVersion 个、" +
                                    "有下级 $whyChildren 个、父视角时间变了 $whyChanged 个、其他 $whyOther 个",
                            )
                        }
                    }
                }
            }

            // 快速扫描的抽查复核 + 被剪目录的待遇（补海报、计入"已处理"）。
            // 放在爬取**之后**是因为抽查名单要等候选齐了才能挑；而被剪掉的目录其子树本来就不在
            // 爬取范围里（剪枝就是整棵跳过），所以在这里补处理不会打乱"父目录先于子目录入库"的顺序。
            if (prunedListings.isNotEmpty()) {
                val sample = if (stoppedEarly) 0 else fastSampleSize(prunedListings.size)
                val sampled = if (sample > 0) {
                    prunedListings.sortedBy { it.lastVerifiedAt }.take(sample).toHashSet()
                } else {
                    emptySet()
                }
                if (sample > 0) {
                    Log.i(TAG, "快速·抽查 $sample 个最久没复核的目录（候选 ${prunedListings.size} 个）")
                }
                for (listing in prunedListings) {
                    if (stopRequested.get()) {
                        stoppedEarly = true
                        break
                    }
                    if (listing !in sampled) {
                        // 没被抽到的：按老样子只补海报 + 计入"已处理"（一次请求都不发）
                        if (!process(listing)) {
                            stoppedEarly = true
                            break
                        }
                        continue
                    }
                    // 抽到的：真列一次，走同一条"列目录 → 指纹判断要不要重索引"的路。
                    // 先把它从"剪掉的"里摘出来（它这轮要列了），再交给 process 正常处理
                    val page = listFiles(listing.cid, listing.path, rateLimitMs)
                    if (!process(listing.copy(page = page, pruned = false))) {
                        stoppedEarly = true
                        break
                    }
                }
            }

            // 每轮跑完都留一行 —— 多库排队之后，这是日志里回答"这一轮是谁、扫成什么样"的地方。
            // 早先只有"增量扫描且跳过了目录"才打（全量扫描在日志里完全是静默的，几轮连跑时
            // 只剩几条 per-目录 的日志，根本拼不出哪一轮到哪了）。
            val bits = listOfNotNull(
                "目录 $done/$discovered",
                if (skipped > 0) "跳过 $skipped 个未变化" else null,
                if (pruned > 0) "其中 $pruned 个按父目录时间剪掉、一次都没列" else null,
                "列目录 $listRequests 次",
                "入库 $movies 部",
                "耗时 ${System.currentTimeMillis() - startedAt}ms",
                if (stoppedEarly) "被停止" else null,
            ).joinToString(" · ")
            Log.i(TAG, "「$roundLabel」${modeLabel}扫描完成：$bits")
        } catch (e: Exception) {
            // 异常中断（频控 / 网络 / 未知）与"进程被杀"一样会让这一轮半途而废 ——
            // 记下原因：那句"要不要接着扫"要能说清是被谁打断的（见 drain 与 ResumeProposal）。
            // 已经扫完的目录都在 scan_state 里，所以续扫不会白干。
            // 只留**第一条**原因（多根库的后续根即使又抛，报第一个才是"从哪里断的"）。
            abnormalInterrupt = abnormalInterrupt ?: (e.message ?: e::class.java.simpleName)
            // 顺手把进度与原因刷进在途现场（noteResume 平时按步长写，这里是收尾那一次）——
            // 原因取任务里**第一条**异常（多根库报"从哪里开始断的"），进度取这一刻的。
            // 进程被杀时走不到这里，盘上留的是最后一个目录的现场（reason 为空 = "被关闭"）。
            inflight?.let {
                inflight = it.copy(
                    reason = abnormalInterrupt ?: "",
                    doneDirs = done,
                    totalDirs = discovered,
                    moviesIndexed = movies,
                    at = System.currentTimeMillis(),
                )
                persistQueue()
            }
            Log.w(TAG, "「$roundLabel」扫描中断: ${e.message}")
        } finally {
            val finishedAt = System.currentTimeMillis()
            // 记"这个库刚扫完"。**一个目录都没跑完就不记**（done == 0 通常是列目录就抛了，
            // 多半是网络问题）：那种情况该在下次启动时再试，而不是白白占用一个扫描间隔。
            // 用户中途按停止 → done > 0 → 照记（他确实扫过一轮了，别每次启动都来烦他）。
            if (libraryId != null && done > 0) {
                runCatching { dao.markLibraryScanned(libraryId, finishedAt) }
                    .onFailure { Log.w(TAG, "「$roundLabel」记录扫描时间失败 libraryId=$libraryId: ${it.message}") }
            }
            // 媒体库自己的记录里留一条：**何时扫的、扫的哪个库、结果、新增了哪些片**
            // （表 scan_log → 媒体库页的「扫描记录」，那里用海报图展示新增影片）。
            // **跑没跑完都记** —— 用户就是靠它回答"上次扫到哪儿了"。翻车了也只当没记，不影响扫描本身。
            runCatching {
                val report = ScanReport(
                    libraryName = libraryLabel(libraryId, rootPath),
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    totalDirs = discovered,
                    doneDirs = done,
                    skippedDirs = skipped,
                    indexed = movies,
                    postersFetched = postersFetched,
                    newCount = newKeys.size,
                    stopped = stoppedEarly,
                    mode = modeLabel,
                    listRequests = listRequests,
                )
                // 定向任务（流水线同步）把结果交给汇总器，最后只写一条「流水线同步」记录
                // （不然一次同步几个目录就变成几条记录，见 ScanLogAccumulator）
                val sink = logSink
                if (sink != null) {
                    sink.add(report, newKeys)
                } else {
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
                            mode = report.mode,
                            listRequests = report.listRequests,
                            newKeys = newKeys.joinToString("\n"),
                        ),
                    )
                }
            }.onFailure { Log.w(TAG, "「$roundLabel」写扫描记录失败: ${it.message}") }
            // 本轮队列的累计入库数（完成提示用）：**不随 [Progress] 重置**，跨任务/跨 root 累加。
            // 在这里而不是别处：正常结束、抛异常、被停止三条路都会走到，与入库事实一致。
            _batchIndexed.value += movies
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

    /**
     * 一个目录 + **发现它时那一次列目录的结果**（扫描循环直接复用，不再重复请求）。
     *
     * [parentUpt] / [parentUet] 是**父目录列表里这个目录项自己的**两个时间字段：既要落进
     * scan_state（下轮剪枝的判据），也要在剪枝时跟当前这一页的值比。根目录没有父，是 0。
     */
    private data class DirListing(
        val cid: String,
        val path: String,
        val page: FilesPage?,
        /** 快速模式：这一条被**剪掉**了 —— 本轮不列它（判据见 [runScan] 的爬取段） */
        val pruned: Boolean = false,
        /** 上一次真正列它的时间：抽查时优先复核最久没看过的那些 */
        val lastVerifiedAt: Long = 0,
        val parentUpt: Long = 0,
        val parentUet: Long = 0,
    )

    /** 待访问的目录 + 父目录那一页里它的条目（快速模式剪枝要靠条目里的时间字段） */
    private data class PendingDir(val cid: String, val path: String, val entry: FileItem)

    /**
     * 这一页里有没有**会递归进去的**子目录 —— 屏蔽目录（extrafanart / .actors / 花絮…）不算。
     *
     * 就是快速扫描 `scan_state.hasSubDirs` 那一列的算法：有下级的目录不能剪（变化不向上传播），
     * 但屏蔽目录不算"下级"，否则带侧挂素材的影片目录永远剪不动（详见 [runScan] 爬取段里的判据 4）。
     */
    private fun hasTraversableSubDirs(page: FilesPage): Boolean =
        page.items.any { it.isDir && !isIgnoredDirName(it.fn) }

    /**
     * 把一页里的子目录排进待访问队列（爬取循环用）。
     *
     * ★ 屏蔽的目录（侧挂素材 extrafanart/.actors、附加内容 behind the scenes/extras…）
     *   **不递归**：侧挂素材的内容由 [sideArtOf] 在"这个影片目录需要重扫"时单独列；
     *   附加内容是整棵丢弃。列进这里等于每轮扫描都白问一遍（实测某库 248 个目录里
     *   164 个是它们），附加内容还会把花絮当成正片入库。
     *
     * 只把有 fid 的目录收进来：早先 fid 为空时会拿空串当 cid 塞进结果，
     * 而空 cid 在 115 那边就是根目录 —— 会把根目录的内容当成某个子目录扫一遍。
     */
    private fun pushChildren(page: FilesPage, basePath: String, queue: ArrayDeque<PendingDir>) {
        page.items.forEach { dir ->
            if (!dir.isDir || isIgnoredDirName(dir.fn)) return@forEach
            dir.fid?.let { queue.add(PendingDir(it, "$basePath/${dir.fn}", dir)) }
        }
    }

    /**
     * 把当前进度写进**续扫现场**并落盘。
     *
     * 每 [RESUME_PROGRESS_STEP] 个目录写一次、以及异常收尾时写一次：进程在扫描途中被杀掉时，
     * 盘上那份现场就是重启后那句"已完成 N 个目录"的来历。不必逐目录都写 —— 一次落盘虽然很小，
     * 但一轮扫描几百个目录就是几百次 DataStore 写。
     */
    private fun noteResume(done: Int, total: Int, movies: Int, currentDir: String) {
        val r = inflight ?: return
        if (done < resumeNotedAt + RESUME_PROGRESS_STEP) return
        resumeNotedAt = done
        inflight = r.copy(
            at = System.currentTimeMillis(),
            doneDirs = done,
            totalDirs = total,
            moviesIndexed = movies,
            currentDir = currentDir,
        )
        persistQueue()
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
     * [parentPath] 只用于日志里的目录标识（见 [listFiles]）。
     *
     * 只认直接子目录（`X/extrafanart`、`X/.actors`）：这是刮削器的固定写法。
     * 深一层（`.actors/<演员名>/folder.jpg`）是 Emby 另一套，实测库里没有，不做。
     */
    private suspend fun sideArtOf(
        parentItems: List<FileItem>,
        parentPath: String,
        rateLimitMs: Long,
    ): SideArt {
        val fanartDir = sideDirItem(parentItems, EXTRAFANART_DIR_NAMES)
        val actorsDir = sideDirItem(parentItems, ACTORS_DIR_NAMES)
        return SideArt(
            fanart = fanartDir?.let {
                extraFanartFilesOf(fileRefsOf(listFiles(it.fid!!, "$parentPath/${it.fn}", rateLimitMs)))
            } ?: emptyList(),
            fanartDirCid = fanartDir?.fid,
            actorsDirCid = actorsDir?.fid,
            actorAvatars = actorsDir?.let {
                actorAvatarFilesOf(fileRefsOf(listFiles(it.fid!!, "$parentPath/${it.fn}", rateLimitMs)))
            } ?: emptyMap(),
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
         * 续扫现场隔多少个目录落一次盘（见 [MediaScanner.noteResume]）。
         *
         * 10 是"被杀掉时最多丢 10 个目录的计数"与"一轮几百个目录别写几百次 DataStore"之间的折中：
         * 丢的那点计数不影响续扫本身（续扫靠 scan_state 按目录跳过），只让提示里的数字保守一点。
         */
        private const val RESUME_PROGRESS_STEP = 10

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
