package com.open115.pad

import android.app.Application
import android.content.Context
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
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
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

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi = retrofit("https://passportapi.115.com/").create(AuthApi::class.java)
    val qrApi: QrApi = retrofit("https://qrcodeapi.115.com/").create(QrApi::class.java)
    val openApi: OpenApi = retrofit("https://proapi.115.com/").create(OpenApi::class.java)

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

    /** 批量重命名的持久化任务队列：任务逐条落库，进程被杀后重启能精确续跑 */
    val renameQueue = com.open115.pad.data.RenameQueue(context)

    /**
     * 队列执行器：一条常驻协程把队列里的任务**顺序**跑完。挂在容器上（而不是面板或
     * ViewModel 里）—— 关面板、切页、切任务页都不该中断它，跟上传一样由 [transferScope] 承载。
     */
    val renameWorker = com.open115.pad.data.RenameQueueWorker(openApi, renameQueue, opLog)

    /** 高级文件过滤：方案存储 + 文件页右上角总开关 */
    val filterPrefs = com.open115.pad.data.FilterPrefs(context)

    /** 媒体库（类 Yamby）：Room 索引 + 手动扫描引擎。扫描由调用方在 transferScope 里 launch。 */
    val mediaDatabase = com.open115.pad.data.media.MediaDatabase.build(context)
    val mediaPrefs = com.open115.pad.data.media.MediaPrefs(context)

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
        openApi, okHttpClient, mediaDatabase.mediaDao(), mediaCache,
        // 扫描期顺手把海报字节取到本地：扫完进海报墙/详情页不必再等图
        // （缓存命中时是纯文件判断，不占限速等待；缓存关掉时什么都不做）
        imageUrlResolver = imageUrlResolver,
        imageCacheDir = cacheDir,
        // 队列落盘：进程被杀之后重启，没轮到 / 没跑完的扫描接着跑（见 restoreQueue）
        prefs = mediaPrefs,
    )

    /** 云下载提交（含持久化的保存位置），手动添加/剪贴板/外部唤起共用 */
    val downloadSubmitter = com.open115.pad.data.DownloadSubmitter(downloadPrefs)

    /**
     * "现在有什么任务在跑" —— 一句话描述，空闲为 null。
     *
     * 前台服务（防止被杀，见 [KeepAliveService]）靠它决定要不要常驻、通知上写什么；
     * 也是全局唯一把三类任务汇总起来的地方。**只算"进程被杀就断"的任务**：
     *  - 媒体库扫描：进程内协程（靠 scan_state + 落盘队列能续，但断在半路就白等一轮）
     *  - 上传：进程内协程（有断点续传）
     *  - 重命名队列：进程内 worker（任务逐条落库）
     * 不包括云下载（在 115 服务端跑）和本机下载（系统 DownloadManager 扛着，App 死了也照下）。
     */
    val taskActivity: StateFlow<String?> = combine(
        mediaScanner.progress,
        mediaScanner.pending,
        transferLog.uploads,
        renameWorker.activeTaskId,
    ) { scanning, pending, uploads, renaming ->
        val bits = buildList {
            if (scanning.running) {
                add(
                    if (scanning.libraryName.isNotBlank()) "正在扫描「${scanning.libraryName}」"
                    else "正在扫描媒体库",
                )
                if (scanning.totalDirs > 0) add("${scanning.doneDirs}/${scanning.totalDirs} 个目录")
            }
            if (pending.isNotEmpty()) add("${pending.size} 个扫描排队")
            val uploading = uploads.count { it.finishedAt == null && !it.paused }
            if (uploading > 0) add("正在上传 $uploading 个文件")
            if (renaming != null) add("正在重命名文件")
        }
        bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
                } else {
                    renameWorker.stop()
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

        // 开关打开的库：登录后自动跑一轮**增量**扫描（upt 未变的目录全部跳过，
        // 代价只有每目录一次列表请求；未登录不跑）。开关是**每库各自**的设置。
        //
        // 几个库一起到点时不在这里串着跑，而是**排进 mediaScanner 的队列顺序执行**：
        // 这样界面上能看见"还有谁在等"，用户在启动扫描期间手动点的扫描也不会像早先那样
        // 被静默丢弃（那时是先来的那轮占着，后来的直接 return）。
        //
        // **先把上次留下的队列捡回来**（进程被杀 / 被系统清掉时剩下的任务）：顺序上它排在
        // 自动扫描前面 —— 那是用户更早的意图。restoreQueue 自带一次性开关，这条流重复发 true
        // 也不会重复捡（token 刷新也会让 DataStore 重新发一次）。
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
                        // enqueue 对同库是**就地替换**，拿自动扫描的增量版盖掉用户要的全量版，
                        // 会让他"重启前特意排的全量"变成增量
                        if (mediaScanner.isBusy(lib.id)) return@forEach
                        mediaScanner.enqueue(lib, incremental = true)
                    }
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as App115).container

/** 防杀服务的防抖时长（见 [AppContainer.watchTasksForKeepAlive]）*/
private const val KEEP_ALIVE_DEBOUNCE_MS = 1200L
