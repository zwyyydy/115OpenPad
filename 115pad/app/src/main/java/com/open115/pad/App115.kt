package com.open115.pad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.open115.pad.data.AuthApi
import com.open115.pad.data.AuthInterceptor
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.KeepAliveService
import com.open115.pad.data.OpenApi
import com.open115.pad.data.QrApi
import com.open115.pad.data.Session
import com.open115.pad.data.TokenAuthenticator
import com.open115.pad.data.media.autoScanDue
import com.open115.pad.data.media.autoScanModeOrDefault
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class App115 : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * 主界面（MainActivity）在前台吗 —— 任务完成的通知只在它**不可见**时发（见 ui/AppRoot 的 finishNotice）。
     *
     * 用 started 计数而不是"Application 在前台"：播放器是**另一个 Activity**，用户在播放器里时
     * 主界面已经 stopped —— 那种时候也该发通知（Snackbar 挂在主界面上，他看不到）。
     */
    private var mainUiStarted = 0
    val isMainUiVisible: Boolean get() = mainUiStarted > 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) mainUiStarted++
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) mainUiStarted--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

class AppContainer(context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    /** 应用私有缓存根目录：图片落盘（huge_img / media_img）都挂在这里 */
    val cacheDir: File = File(context.cacheDir, "images")

    val session = Session(context)
    val playerPrefs = com.open115.pad.data.PlayerPrefs(context)
    val filesPrefs = com.open115.pad.data.FilesPrefs(context)
    val downloadPrefs = com.open115.pad.data.DownloadPrefs(context)

    /** 应用级偏好（目前只有「启动首页」，设置 → 界面显示） */
    val appPrefs = com.open115.pad.data.AppPrefs(context)

    /** 文件夹置顶（本机生效，115 开放平台无对应接口） */
    val pinnedPrefs = com.open115.pad.data.PinnedPrefs(context)

    /**
     * 目录列表缓存（进程级、纯内存、LRU）。
     * 放在 AppContainer 而不是 ViewModel 里：ViewModel 会随导航条目被清掉，
     * 放里面收益只剩"目录间切换"，太薄。
     */
    val dirCache = com.open115.pad.data.DirCache()

    /**
     * 目录内容被移除了某些条目的广播：cid → 被移除条目名集合（同目录内名字唯一，按名认不会错）。
     * 发送方：回收站（彻底删除/清空）、云下载（删任务且勾了删源文件）。
     * 接收方：文件页 —— 作废这些目录的 [dirCache] 条目（持久，下次进入必然真实请求）；
     * 正停在受影响目录时把这些条目**就地**从列表里拿掉，而不是整页重拉 ——
     * 115 的删除是异步生效的，删完立即重拉很可能把"还没删完"的旧状态写回缓存，
     * 界面上反而一直是"删了还在"。值允许空集合 = 该目录内容整体变了，只作废缓存。
     */
    val dirRemovals = kotlinx.coroutines.flow.MutableSharedFlow<Map<String, Set<String>>>(
        extraBufferCapacity = 16,
    )

    /** 只用于极少数与 UI 无关的长期观察（目前只有"登出后清缓存"） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 上传/恢复协程的作用域：不随页面销毁（切页/换目录不会中断上传），
     * SupervisorJob 防止单个上传失败连坐。暂停/取消由传输中心显式发起。
     */
    val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 传输中心的历史记录（本机下载 + 上传） */
    val transferLog = com.open115.pad.data.TransferLog(context)

    /** 用户操作记录（复制/移动/删除/上传/下载/云离线/预览/播放），侧栏展示 */
    val opLog = com.open115.pad.data.OpLog(context)

    /** 快捷目录收藏（文件页侧栏，点击直达对应目录） */
    val quickDirs = com.open115.pad.data.QuickDirs(context)

    /** 剪贴板 / 外部唤起的下载链接汇聚点，由 MainActivity 投递、AppRoot 消费 */
    val downloadLinks = com.open115.pad.data.DownloadLinkBus(context, downloadPrefs)

    /**
     * 「请求限速」的内存镜像（毫秒，0 = 关闭）。
     *
     * 和媒体库缓存上限那两份镜像同一个套路：DataStore → 内存，设置里改完立刻生效、不用重启。
     * 只有 OkHttp 的工作线程读它，用 @Volatile 保证可见性。
     */
    @Volatile
    var apiRateIntervalMs: Long = com.open115.pad.data.AppPrefs.DEFAULT_API_RATE_MS
        private set

    /** 全局限速的节拍器：只挂在 [apiOkHttpClient] 上（理由见 [com.open115.pad.data.RateLimitInterceptor]） */
    private val apiPacer = com.open115.pad.data.ApiPacer(intervalMs = { apiRateIntervalMs })

    /**
     * 通用 client：**不限速**。
     *
     * 图片字节（Coil 用的就是它）、第三方弹幕源、登录/二维码接口、备份包的传输都走它 ——
     * 这些要么不是 115 的 open 接口，要么是 CDN/OSS 字节流，跟"接口被频控"不是一回事。
     */
    val okHttpClient: OkHttpClient = newHttpClient(rateLimited = false)

    /**
     * 打 115 资源接口（proapi）的 client：比上面多一层全局限速。
     *
     * 为什么不让两者共用一个 client：那会把图片加载与 OSS 上传一起限速 ——
     * 海报墙会明显变慢、上传会掉速，而它们本来就不占接口额度。
     */
    private val apiOkHttpClient: OkHttpClient = newHttpClient(rateLimited = true)

    private fun newHttpClient(rateLimited: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (rateLimited) builder.addInterceptor(com.open115.pad.data.RateLimitInterceptor(apiPacer))
        return builder
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", com.open115.pad.util.APP_USER_AGENT)
                        .build()
                )
            }
            .addInterceptor(com.open115.pad.data.Logical401Interceptor(session))
            .addInterceptor(AuthInterceptor(session))
            .authenticator(TokenAuthenticator(session))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // 二维码状态接口是长轮询
            .build()
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient = okHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi = retrofit("https://passportapi.115.com/").create(AuthApi::class.java)
    val qrApi: QrApi = retrofit("https://qrcodeapi.115.com/").create(QrApi::class.java)

    /** 115 资源接口：**受限速保护**（文件浏览/搜索、直链解析、各类写操作都走它） */
    val openApi: OpenApi =
        retrofit("https://proapi.115.com/", apiOkHttpClient).create(OpenApi::class.java)

    /**
     * 同一套 115 资源接口的**不限速**版本，只给"自己已经管着节流"的后台任务用
     * （媒体库扫描、分类整理）：它们那套"按目录数选档"的间隔是专门调过的，
     * 再叠一层全局间隔就是两套时钟互相顶 —— 见 [com.open115.pad.data.RateLimitInterceptor]。
     */
    private val bulkOpenApi: OpenApi =
        retrofit("https://proapi.115.com/").create(OpenApi::class.java)

    /**
     * 图片直链三级解析链（uo → downurl → thumb）+ downurl 防频控缓存。
     *
     * 后两个参数是**媒体库海报缓存**的上限与开关：传 lambda 而不是当场取值，
     * 所以这里虽然声明在 mediaCacheMaxBytes 之前也安全（调用发生在使用期，不是构造期）。
     */
    val imageUrlResolver = com.open115.pad.data.ImageUrlResolver(
        openApi,
        okHttpClient,
        mediaCacheMaxBytes = { mediaCacheMaxBytes },
        mediaCacheEnabled = { mediaCacheEnabled },
    )

    /**
     * 视频预览图缓存的设置（上限，MB）。
     * 上限存的是"偏好值"，运行时生效的那份是下面的 [previewCacheMaxBytes] 镜像。
     */
    val previewPrefs = com.open115.pad.data.PreviewPrefs(context)

    /**
     * 预览图缓存上限的内存镜像（字节）。由下面的 collect 从 DataStore 灌进来，
     * 写入路径（播放器/批量/截图）是普通函数、不是协程，只能读这个镜像。
     */
    @Volatile
    var previewCacheMaxBytes: Long = com.open115.pad.data.VideoPreviewStore.DEFAULT_MAX_BYTES
        private set

    /**
     * 视频预览图：本地缓存（按 pick_code 落盘）+ 抽帧器（115 最小转码档的 HLS 分片）。
     * 文件页列表读缓存显示（零请求），播放时与批量任务负责生成，见 VideoFrameGrab/VideoPreviewStore。
     * 上限到顶由 store 自己在写入后淘汰最久没看的那批（见 VideoPreviewStore.prune）。
     */
    val videoPreviews = com.open115.pad.data.VideoPreviewStore(
        root = cacheDir,
        maxBytes = { previewCacheMaxBytes },
    )
    val videoFrames = com.open115.pad.data.VideoFrameGrabber(openApi, okHttpClient, cacheDir)


    /** 批量重命名的持久化任务队列：任务逐条落库，进程被杀后重启能精确续跑 */
    val renameQueue = com.open115.pad.data.RenameQueue(context)
    /**
     * 队列执行器：一条常驻协程把队列里的任务**顺序**跑完。挂在容器上（而不是面板或
     * ViewModel 里）—— 关面板、切页、切任务页都不该中断它，跟上传一样由 [transferScope] 承载。
     */
    val renameWorker = com.open115.pad.data.RenameQueueWorker(
        openApi, renameQueue, opLog,
        // 改过名的目录要作废媒体库的扫描状态：115 的目录 upt 不随改名变，
        // 而快速扫描正是靠"父目录看到的 upt 没变"跳过目录的（理由见 worker 的构造参数注释）
        invalidateDir = { cid -> mediaDatabase.mediaDao().invalidateScanState(cid) },
    )

    /** 高级文件过滤：方案存储 + 文件页右上角总开关 */
    val filterPrefs = com.open115.pad.data.FilterPrefs(context)

    /** 媒体库（类 Yamby）：Room 索引 + 手动扫描引擎。扫描由调用方在 transferScope 里 launch。 */
    val mediaDatabase = com.open115.pad.data.media.MediaDatabase.build(context)
    val mediaPrefs = com.open115.pad.data.media.MediaPrefs(context)

    /** 弹幕客户端（实验室：弹幕）：对兼容弹弹play 协议的源做匹配与拉取，带 Room 缓存 */
    internal val danmuClient by lazy { com.open115.pad.player.DanmuClient(okHttpClient) }

    /**
     * 媒体库缓存设置的两份镜像（设置里改完要立刻生效，不能等重启）。
     *
     * 由下面的 collect 从 DataStore 灌进来，缓存层与海报加载器读的是这两个值 ——
     * 它们只被主线程之外的协程读，用 @Volatile 保证可见性。
     */
    @Volatile
    var mediaCacheEnabled: Boolean = true
        private set

    @Volatile
    var mediaCacheMaxBytes: Long = com.open115.pad.data.media.MediaPrefs.DEFAULT_MAX_MB * 1024 * 1024
        private set  // 0 = 不限制（不能存 Long.MAX_VALUE：textPoolBytes 里再乘会溢出）

    /**
     * 媒体库落盘缓存：nfo 的解析结果（文本）。
     * 海报字节不在这里 —— 它必须是裸图片文件，走 ImageUrlResolver 的 media_img。
     */
    val mediaCache = com.open115.pad.data.media.MediaCache(
        root = File(context.cacheDir, "media_cache"),
        maxBytes = { com.open115.pad.data.media.MediaPrefs.textPoolBytes(mediaCacheMaxBytes / (1024 * 1024)) },
        enabled = { mediaCacheEnabled },
    )

    val mediaScanner = com.open115.pad.data.media.MediaScanner(
        // ★ 用**不限速**的那一份接口：扫描器自己有按目录数选档的阶梯限速，
        //   再叠一层全局间隔就是两套时钟互相顶（见 RateLimitInterceptor）
        bulkOpenApi, okHttpClient, mediaDatabase.mediaDao(), mediaCache,
        // 扫描期顺手把海报字节取到本地：扫完进海报墙/详情页不必再等图
        // （缓存命中时是纯文件判断，不占限速等待；缓存关掉时什么都不做）
        imageUrlResolver = imageUrlResolver,
        imageCacheDir = cacheDir,
        // 队列落盘：进程被杀之后重启，没轮到 / 没跑完的扫描接着跑（见 restoreQueue）
        prefs = mediaPrefs,
    )

    /**
     * 流水线同步：服务器（刮削流水线）每轮刮完会往 115 里写一份「本次变了哪些目录」
     * 的信号文件，这里轮询它，把变动的目录排成"只扫这几个目录"的扫描任务（见 PadSync）。
     *
     * 只在登录后跑，启停在下面的 loggedInFlow 里（和改名 worker 同一处）。
     */
    val padSync = com.open115.pad.data.media.PadSync(
        openApi, okHttpClient, mediaDatabase.mediaDao(), mediaScanner, mediaPrefs,
        mediaCache, imageUrlResolver, cacheDir, scope,
    )

    /** 云下载提交（含持久化的保存位置），手动添加/剪贴板/外部唤起共用 */
    val downloadSubmitter = com.open115.pad.data.DownloadSubmitter(downloadPrefs)

    /**
     * 文件页「分类整理」：递归扫描当前目录，把文件按类型移进本目录的 视频/音频/文本/
     * 应用程序/图片/压缩包/其他 七个文件夹。挂应用级（transferScope 跑任务、进度进下面的
     * taskActivity 让前台服务跟着亮），切页不停、进程死了就断（断点无从谈起，纯云端移动）。
     */
    val fileOrganizer = com.open115.pad.data.FileOrganizer(
        // 同理：整理自己有 300/1000/2000ms 的分级限速，走不限速的那一份接口
        bulkOpenApi, opLog, dirCache, transferScope,
    )

    /**
     * 重复文件查找（文件页「查找重复」）：按列表响应自带的 sha1 分组，只读不写。
     *
     * 同样走 `bulkOpenApi` + 自己的分级限速（它要把目录一层层列一遍，属于后台批量）；
     * 跑在 transferScope 上，切页不断 —— 下面把它接进 taskActivity，任务期间前台服务会保命。
     */
    val duplicateFinder = com.open115.pad.data.DuplicateFinder(
        bulkOpenApi, dirCache, transferScope,
    )

    /**
     * "同片多版本"预筛（A 段）：只按时长（±3 秒）找候选，零额外请求（时长/体积都是列表自带的）。
     * 与 [duplicateFinder] 同一套骨架（bulkOpenApi + 自己的分级限速 + 可停），只是分组判据不同。
     */
    val versionFinder = com.open115.pad.data.VersionFinder(bulkOpenApi, transferScope)

    /**
     * 视频预览图批量生成（只读：抽帧落本机缓存，不动云端）。
     * 走 bulkOpenApi + 自己的分级限速，与查重/多版本同一套骨架。
     */
    val previewBuilder = com.open115.pad.data.PreviewBuilder(
        bulkOpenApi, videoFrames, videoPreviews, transferScope,
    )

    /** 备份：配置 / 媒体库（数据+记录+图片缓存）/ 操作记录 → 云端（实现见 data/BackupManager.kt） */
    val backupPrefs = com.open115.pad.data.BackupPrefs(context)

    val backupManager = com.open115.pad.data.BackupManager(
        context, openApi, backupPrefs, imageUrlResolver,
        mediaDatabase, okHttpClient, transferLog,
        cacheDir,
    )

    /**
     * "现在有什么任务在跑" —— 一句话描述，空闲为 null。
     *
     * 前台服务（防止被杀，见 [KeepAliveService]）靠它决定要不要常驻、通知上写什么；
     * 也是全局唯一把三类任务汇总起来的地方。**只算"进程被杀就断"的任务**：
     *  - 媒体库扫描：进程内协程（靠 scan_state + 落盘队列能续，但断在半路就白等一轮）
     *  - 上传：进程内协程（有断点续传）
     *  - 重命名队列：进程内 worker（任务逐条落库）
     *  - 分类整理：进程内协程（纯云端移动，进程死了剩下的文件留在原地，重跑一遍即可）
     * 不包括云下载（在 115 服务端跑）和本机下载（系统 DownloadManager 扛着，App 死了也照下）。
     */
    /**
     * "除了查重之外的任务在跑什么"：原来的五路合并原样留着（那段逻辑调过很多次，不动它）。
     * `combine` 的类型化重载只到 5 路，第 6 路（查重）在下面套一层 —— 比改成 vararg
     * 数组形式（要统一成 `Flow<Any?>`，可读性反而差）清楚。
     */
    private val baseTaskActivity: StateFlow<String?> = combine(
        mediaScanner.progress,
        mediaScanner.pending,
        transferLog.uploads,
        renameWorker.activeTaskId,
        fileOrganizer.progress,
    ) { scanning, pending, uploads, renaming, organizing ->
        val bits = buildList {
            if (scanning.running) {
                add(
                    if (scanning.libraryName.isNotBlank()) "正在扫描「${scanning.libraryName}」"
                    else "正在扫描媒体库",
                )
                if (scanning.totalDirs > 0) add("${scanning.doneDirs}/${scanning.totalDirs} 个目录")
                // 防频控休息时进度一动不动，通知/前台服务上要说一句，不然看着像卡死
                if (scanning.restingUntil > System.currentTimeMillis()) add("休息中（防频控）")
            }
            if (pending.isNotEmpty()) add("${pending.size} 个扫描排队")
            val uploading = uploads.count { it.finishedAt == null && !it.paused }
            if (uploading > 0) add("正在上传 $uploading 个文件")
            if (renaming != null) add("正在重命名文件")
            if (organizing.running) add("正在分类整理文件")
        }
        bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val taskActivity: StateFlow<String?> = combine(
        baseTaskActivity,
        duplicateFinder.progress,
        versionFinder.progress,
        previewBuilder.progress,
    ) { base, deduping, versioning, previewing ->
        val extra = when {
            deduping.running -> "正在查找重复文件"
            versioning.running -> "正在预筛同片多版本"
            previewing.running -> "正在生成视频预览图"
            else -> null
        }
        if (extra == null) base else listOfNotNull(base, extra).joinToString(" · ")
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 盯着"有没有任务在跑"，决定前台服务的起停（设置 → 后台任务）。
     *
     * ★ **必须防抖**。任务状态是毫秒级抖动的：队列里上一条刚出队、下一条的 Progress 还没置上
     *   （`running` 还是 false、`pending` 已经空了）—— 那个空档会被当成"没任务了"。实测不防抖时
     *   want 在 50 毫秒内翻了 4 次，前台服务刚 `startForeground` 就被系统按
     *   "有 start 在等 startForeground" 判超时，**直接把进程杀掉**
     *   （ForegroundServiceDidNotStartInTimeException）。
     *   防抖 1.2 秒之后：短任务（<1.2s）根本不起服务，任务之间的空档也掀不起浪。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun watchTasksForKeepAlive(appContext: Context) {
        combine(appPrefs.keepAlive, taskActivity) { on, act -> on to act }
            .debounce(KEEP_ALIVE_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { (on, act) ->
                if (on && act != null) {
                    Log.i("App115", "防止被杀：有任务在跑（$act），起前台服务")
                    KeepAliveService.start(appContext)
                } else if (KeepAliveService.isRunning) {
                    // 两种成因分开写：排查"服务怎么没了"时，是任务跑完还是开关被关掉差别很大。
                    // 只在"服务确实在跑"时才打这一行/才去停它 —— 冷启动本来就没服务，白打一行只添噪音
                    Log.i(
                        "App115",
                        if (!on) "防止被杀：开关关掉了，停前台服务" else "防止被杀：任务跑完了，停前台服务",
                    )
                    KeepAliveService.stop(appContext)
                }
            }
    }

    init {
        session.refresher = { refreshToken ->
            try {
                authApi.refreshToken(refreshToken)
            } catch (e: Exception) {
                null
            }
        }
        // 图片加载走同一套 OkHttpClient：User-Agent / 鉴权 / 401 刷新重试全链一致
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(context)
                .okHttpClient(okHttpClient)
                .crossfade(true)
                .build()
        )
        // 防杀（设置 → 后台任务 → 「任务进行时防止被杀」）：开关开着 + 有任务 → 前台服务常驻，
        // 空闲或关掉 → 停掉服务（服务自己也会在空闲时退场，这里管的是"开关被关掉"那一半）。
        scope.launch { watchTasksForKeepAlive(context) }
        // 登出（含因终态授权码被强制登出）后，本机缓存必须作废：
        // 不清的话，换个账号登录会直接看到上一个账号的目录内容 / 海报 / 简介
        //
        // 图片那几层也要清：画廊的原图/缩略图磁盘缓存、超大图的 huge_img 现在都按
        // **pick_code** 命名（见 ImageGalleryDialog.diskKeyOf），不再随直链签名变化自动失效，
        // 留着就可能被下一个账号的同名文件命中。
        scope.launch {
            session.loggedInFlow.collect {
                if (!it) {
                    dirCache.clear()
                    mediaCache.clear()
                    withContext(Dispatchers.IO) {
                        coil.Coil.imageLoader(context).diskCache?.clear()
                        ImageUrlResolver.clearHugeCache(cacheDir)
                    }
                    // 已解码的位图按 Coil 的约定在主线程清（内存缓存只在进程内有效，
                    // 但换账号后不该让上一账号的图继续从内存里命中）
                    withContext(Dispatchers.Main) {
                        coil.Coil.imageLoader(context).memoryCache?.clear()
                    }
                }
            }
        }

        // 媒体库缓存设置 → 内存镜像。上限调小要**立刻**淘汰，不能等下次写入才生效
        // （海报的 pruneCache 平时只在下载后跑，这里得显式补一次）。
        scope.launch {
            mediaPrefs.cacheEnabled.collect { mediaCacheEnabled = it }
        }

        // 预览图缓存上限 → 内存镜像。阈值调小同样**立刻**淘汰一次：用户把它从 2GB 拉到 256MB
        // 就是想马上腾出空间，等"下次抓帧才生效"等于没生效。
        scope.launch {
            previewPrefs.maxMb.collect { mb ->
                val oldBytes = previewCacheMaxBytes
                previewCacheMaxBytes = mb.coerceAtLeast(0) * 1024L * 1024
                android.util.Log.i(
                    "VPreview",
                    "预览缓存上限 → ${previewCacheMaxBytes / 1024 / 1024} MB" +
                        if (previewCacheMaxBytes < oldBytes) "（调小，立刻淘汰一次）" else "",
                )
                if (previewCacheMaxBytes < oldBytes) {
                    withContext(Dispatchers.IO) { videoPreviews.prune() }
                }
            }
        }

        // 「请求限速」设置 → 内存镜像：改完立刻生效（OkHttp 工作线程读的就是它）
        scope.launch {
            appPrefs.apiRateIntervalMs.collect { apiRateIntervalMs = it }
        }

        scope.launch {
            mediaPrefs.cacheMaxMb.collect { mb ->
                val oldBytes = mediaCacheMaxBytes
                // 0 = 不限制，镜像里保持 0（MediaCache.sweep 与海报 pruneCache 都把 ≤0 视为不限）
                val newBytes = if (mb == com.open115.pad.data.media.MediaPrefs.UNLIMITED_MB) 0L
                    else mb * 1024 * 1024
                mediaCacheMaxBytes = newBytes
                // 上限调小要**立刻**淘汰，不能等下次写入才生效（海报的 pruneCache
                // 平时只在下载后跑，这里得显式补一次）；从"不限制"切回具体值同理。
                val shrunk = newBytes != 0L && (oldBytes == 0L || newBytes < oldBytes)
                if (shrunk) {
                    mediaCache.sweep()
                    imageUrlResolver.sweepPosterCache(cacheDir)
                }
            }
        }

        // 改名队列按登录态启停。**启动即恢复**：worker 一跑起来就会捡起队列里所有
        // 未完成的任务，不需要额外的扫描步骤（也就不依赖用户先打开哪个页面）。
        // 登出必须停 —— 没有 token 还继续跑，只会把剩下的文件全跑成失败。
        scope.launch {
            session.loggedInFlow.collect { loggedIn ->
                if (loggedIn) {
                    renameWorker.start(transferScope)
                    // 流水线同步：轮询服务器写在 115 里的信号文件（幂等，重复发 true 不会起第二个循环）
                    padSync.start()
                    // 云端备份的定时检查：先避让 30 秒（不与扫描/登录抢带宽），之后每小时复查一次。
                    // 每类只查自己的开关与间隔，到点才真的打包上传（见 BackupManager.autoBackupIfDue）。
                    scope.launch {
                        delay(30_000L)
                        runCatching { backupManager.autoBackupIfDue() }
                        while (true) {
                            delay(3_600_000L)
                            runCatching { backupManager.autoBackupIfDue() }
                        }
                    }
                } else {
                    renameWorker.stop()
                    padSync.stop()
                    // 查重也在列目录：登出后 token 没了，继续跑只会一路失败、白耗频控额度
                    duplicateFinder.stop()
                    versionFinder.stop()
                    previewBuilder.stop()
                    // 媒体库扫描同理，而且队列化之后更明显：登出时排着 5 个库，
                    // 不撤的话它们会一个一个跑下去，每个目录都请求失败 —— 白占频控额度。
                    // stopAll 的语义正是要的：停当前那轮 + 清空队列（队列也一并落盘清掉，
                    // 不然重启后还会把上一个账号的扫描捡回来）。
                    // 启动时未登录也会走这里（流立刻发一个 false），此时队列是空的、没在跑，
                    // 只是把停止位立起来 —— 下一次扫描开始时（队列出队处）就会被清掉，不影响。
                    mediaScanner.stopAll()
                }
            }
        }

        // 开关打开的库：登录后自动跑一轮扫描（**方式由库自己选**：快速/增量，见
        // MediaLibraryEntity.autoScanMode；未登录不跑）。开关是**每库各自**的设置。
        //
        // 几个库一起到点时不在这里串着跑，而是**排进 mediaScanner 的队列顺序执行**：
        // 这样界面上能看见"还有谁在等"，用户在启动扫描期间手动点的扫描也不会像早先那样
        // 被静默丢弃（那时是先来的那轮占着，后来的直接 return）。
        //
        // **先把上次留下的队列捡回来**（进程被杀 / 被系统清掉时剩下的任务）：顺序上它排在
        // 自动扫描前面 —— 那是用户更早的意图。restoreQueue 自带一次性开关，这条流重复发 true
        // 也不会重复捡（token 刷新也会让 DataStore 重新发一次）；它也会把"上次没扫完"的那一轮
        // 交给界面问一句（见 MediaScanner.resumeProposal）—— **且问之前先探活**：
        // 接口/网络还不通就先不问（问了也白扫），通了才弹。
        //
        // 「间隔多少小时」也在这里判：**距上次扫描不足就把这个库跳过**（纯本地比较，零请求）。
        // 间隔 0 = 不限 → 每次启动都扫，与这个开关原来的行为一致（老库升级上来不会突变）。
        scope.launch {
            session.loggedInFlow.collect { loggedIn ->
                if (!loggedIn) return@collect
                mediaScanner.restoreQueue()
                val dao = mediaDatabase.mediaDao()
                val now = System.currentTimeMillis()
                dao.libraries().first()
                    .filter { it.autoScanOnStart }
                    .forEach { lib ->
                        // 距上次扫描不足设定间隔就跳过这个库（判据纯本地，零请求）。
                        // 间隔 0 = 不限 → 每次启动都扫，与这个开关原来的行为一致。
                        if (!lib.autoScanDue(now)) {
                            Log.i(
                                "App115",
                                "跳过自动扫描「${lib.name}」：距上次扫描不足 ${lib.autoScanIntervalHours} 小时",
                            )
                            return@forEach
                        }
                        // 已经在扫 / 已经排着队（比如刚从落盘里捡回来的那条）就不排第二次：
                        // enqueue 对同库是**就地替换**，拿自动扫描的任务盖掉用户要的，
                        // 会让他"重启前特意排的那一轮"变了样
                        if (mediaScanner.isBusy(lib.id)) return@forEach
                        // ★ 流水线同步刚刚关照过这个库（排了定向任务，或正在跑）→ **忽略这次定时任务**：
                        // 定向任务拿的是服务器刚刮完的那批变动，这时再整库扫一遍纯属重复，
                        // 而 115 的频控额度是稀缺的。反向的那一半在 PadSync 里（它会撤掉已排队的定时任务）
                        if (padSync.hasRecentWork(lib.id, now)) {
                            Log.i(
                                "App115",
                                "忽略定时扫描「${lib.name}」：流水线同步刚同步过这个库",
                            )
                            return@forEach
                        }
                        // 方式取库自己的设置（老库没有这一列 → 增量，与升级前一致）
                        mediaScanner.enqueue(lib, lib.autoScanModeOrDefault, auto = true)
                    }
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as App115).container

/**
 * 主界面（MainActivity）现在可见吗 —— 任务完成通知只在它**不可见**时发（见 ui/AppRoot 的 finishNotice）。
 *
 * 取不到 App 实例时按"可见"处理：宁可少发一条通知，也不在用户正看着屏幕时多响一声。
 */
val Context.mainUiVisible: Boolean
    get() = (applicationContext as? App115)?.isMainUiVisible ?: true

/** 防杀服务的防抖时长（见 [AppContainer.watchTasksForKeepAlive]）*/
private const val KEEP_ALIVE_DEBOUNCE_MS = 1200L
