package com.open115.pad.cast

import android.content.Context
import android.content.Intent
import com.open115.pad.util.AppLog as Log
import com.open115.pad.MainActivity
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.player.PlayerActivity
import com.open115.pad.util.APP_USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

private const val TAG = "CastSession"

/** PadTv：连续这么多次 /status 失败才提示"失联"（2 秒一次轮询 → 约 12 秒） */
private const val TV_STATUS_FAIL_NOTICE = 6

/** 会话自己的轮询间隔（进度、播放态、以及"电视端退出了播放"都靠它发现） */
private const val POLL_INTERVAL_MS = 2_000L

/** 连播队列最多带多少集（含当前集）：直链是投屏时现解析的，带太多后半段就该过期了 */
private const val QUEUE_MAX_ITEMS = 30

/** 每解析好这么多集补推一次：让电视尽快拿到"下一集"，又不至于几十个请求连发 */
private const val QUEUE_PUSH_BATCH = 3

/**
 * 当前投屏会话的**唯一状态源**。
 *
 * 为什么是进程级单例而不是塞进 ViewModel：投屏要跨过播放页的销毁继续跑（前台服务兜着进程），
 * 通知栏的「停止投屏」也得能独立清理会话——这两条都发生在 Compose 作用域之外。
 *
 * 状态分成两份：
 * - [state]：Idle / Connecting / Casting(device, playing)。变化不频繁，UI 顶层收集；
 * - [progress]：电视侧进度，2 秒一次。单独放在这里，播放页只让投屏控制条收集它，
 *   避免每 2 秒把整个巨大的 PlayerScreen 重组一遍。
 */
internal object CastSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * 会话自己的轮询协程（2 秒一次）。
     *
     * 为什么必须由会话自己驱动、而不是交给播放页的 effect：用户随时会离开播放页停在文件页/媒体库，
     * 那时屏幕上只剩 App 内浮标和通知栏通知——"电视端退出了播放"这件事仍然得有人去发现，
     * 而播放页的 effect 早就不在了（实测就是这样：浮标与通知场景下不响应）。
     */
    private var pollJob: Job? = null

    private val _state = MutableStateFlow<CastUiState>(CastUiState.Idle)
    val state: StateFlow<CastUiState> = _state

    private val _progress = MutableStateFlow<CastProgress?>(null)
    val progress: StateFlow<CastProgress?> = _progress

    /**
     * 一次性提示（如"电视端已退出投屏"）：界面收到后 toast 一句。
     * 用 SharedFlow 而不是 StateFlow：提示是事件，重复值（两条一样的提示）不该被去重。
     */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    /** PadTv：电视端是否**真的开始播过**（用来区分"投屏刚发出去还没起来"与"电视退了播放"） */
    private var tvHasPlayed = false

    /** PadTv：连续多少次 /status 失败（电视端失联），成功即清零 */
    private var tvStatusFails = 0

    /** 连播队列的解析/补推协程：跑在进程级 scope 里，用户退出播放页也照常送完 */
    private var queueJob: Job? = null

    /**
     * 电视最近一次上报的"当前集" pick_code。
     * 不随 stop 清空：停止投屏时要用它判断电视是不是已经自动连播到别的集了。
     */
    var lastRemotePickCode: String = ""
        private set

    var device: CastDevice? = null
        private set

    /** 这一次投的是哪部片（界面据此决定停止后要不要回到本地播放） */
    var sourcePickCode: String = ""
        private set

    /** 投屏片的标题：通知与遥控界面都显示它（通知点击回遥控器时要带上） */
    var sourceTitle: String = ""
        private set

    var lastPositionMs: Long = 0L
        private set

    private var relay: CastRelayServer? = null
    private var client: DlnaClient? = null

    /** PadTv 通道的遥控句柄；与 [client] 互斥——同一时刻只有一条通道在投 */
    private var padTv: PadTvClient? = null
    private var appContext: Context? = null

    fun isActive(): Boolean = _state.value is CastUiState.Casting || _state.value is CastUiState.Connecting

    /**
     * 开始投屏。成功返回 null，失败返回给用户看的原因。
     *
     * 顺序很重要：先把中转起起来（电视要能拉到），再推地址、起播、补进度。
     * 电视收到 SetAVTransportURI 后不是立刻能播，400ms 的缓冲是各家电视实践里
     * 最不容易踩到 "Transition not available" 的间隔。
     */
    suspend fun start(
        context: Context,
        http: OkHttpClient,
        device: CastDevice,
        sourceUrl: String,
        fileName: String,
        title: String,
        pickCode: String,
        startMs: Long,
        /** 115 access token（PadTv 通道）：电视用它自取清晰度/直链；DLNA 忽略 */
        token: String? = null,
        /** 当前集之后的播放队列：投屏成功后异步解析直链、分批补推给电视 */
        upcoming: List<PlaylistEntry> = emptyList(),
        /** 取某一集原盘直链（由播放器注入，避免 cast 层直接依赖 115 接口） */
        resolveUrl: (suspend (String) -> String?)? = null,
        /** 当前集的外挂字幕直链（PadTv 通道挂给电视当文本轨；DLNA 忽略） */
        subtitles: List<CastSubtitle> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopInternalLocked(sendStop = true)
            appContext = context.applicationContext
            lastRemotePickCode = ""
            _state.value = CastUiState.Connecting(device)

            val mime = castMimeOf(fileName)
            val padTvSubs = subtitles.map { PadTvSubtitle(url = it.url, mime = it.mime, name = it.name) }

            // 两条通道：电视端接收器（免中转直连）/ DLNA 渲染器（通用兼容，需本机中转）
            val error: Throwable? = if (device.target == CastTarget.PadTv) {
                // ★ 直链与 UA 一起交给电视、由它自己拉流；平板发完这条指令就自由了
                val remote = PadTvClient(http, device.avTransportUrl)
                val e = runCatching {
                    remote.play(sourceUrl, title, mime, startMs, APP_USER_AGENT, pickCode, token, padTvSubs)
                }.exceptionOrNull()
                if (e == null) {
                    padTv = remote
                    lastRemotePickCode = pickCode
                    // 后续集在后台解析：不拖慢起播，也不受用户离开播放页影响（scope 是进程级的）
                    if (upcoming.isNotEmpty() && resolveUrl != null) {
                        queueJob?.cancel()
                        queueJob = scope.launch {
                            publishQueue(
                                remote = remote,
                                current = PadTvQueueItem(
                                    url = sourceUrl,
                                    title = title,
                                    mime = mime,
                                    pickCode = pickCode,
                                    subs = padTvSubs,
                                ),
                                upcoming = upcoming,
                                resolve = resolveUrl,
                            )
                        }
                    }
                    // 通知：PadTv 不需要保活（流是电视自己拉的），但**离开播放页之后要有一条
                    // 回到遥控器的路**——通知就是那个入口（点击落到播放页，也就是遥控界面）
                    runCatching { CastService.start(context, device.name, keepAlive = false) }
                }
                e
            } else {
                val server = CastRelayServer(http)
                val localUrl = try {
                    server.start(sourceUrl, fileName, mime)
                } catch (e: Exception) {
                    _state.value = CastUiState.Idle
                    return@withLock e.message ?: "本机中转启动失败"
                }
                relay = server
                // 保活服务起不来只影响"锁屏后还稳不稳"，不该阻断这次投屏
                runCatching { CastService.start(context, device.name, keepAlive = true) }

                val dlna = DlnaClient(http, device)
                client = dlna
                runCatching {
                    dlna.setAvTransportUri(localUrl, title, mime)
                    delay(400)
                    dlna.play()
                    // 从本地播放位置接着投；电视不支持 seek 就算了，从头播也算合理降级
                    if (startMs > 5000) runCatching { dlna.seekTo(startMs) }
                }.exceptionOrNull()
            }

            if (error != null) {
                Log.w(TAG, "投屏失败: ${error.message}")
                stopInternalLocked(sendStop = true)
                return@withLock if (device.target == CastTarget.PadTv) {
                    padTvErrorText(error)
                } else {
                    castErrorText(error)
                }
            }

            this@CastSession.device = device
            sourcePickCode = pickCode
            sourceTitle = title
            lastPositionMs = startMs.coerceAtLeast(0)
            _progress.value = CastProgress(startMs.coerceAtLeast(0), 0)
            _state.value = CastUiState.Casting(device, true)
            Log.i(
                TAG,
                if (device.target == CastTarget.PadTv) {
                    "已投屏到 ${device.name}（电视端直拉，免中转）"
                } else {
                    "已投屏到 ${device.name}"
                },
            )
            startPolling()
            null
        }
    }

    /** 播放页点「停止投屏」：尽量先取一次电视当前进度，停下来后好让本地从那儿接着播 */
    suspend fun stopAndPosition(context: Context): Long? = mutex.withLock {
        val pos = if (padTv != null) {
            runCatching { padTv?.status()?.positionMs }.getOrNull()
        } else {
            runCatching { client?.positionMs() }.getOrNull()
        }
        // 界面只从 lastPositionMs 接进度（投屏状态 Stop 之后 progress 已被清空），
        // 所以这一次现取的、比 2 秒轮询更新的位置必须落进去
        if (pos != null) lastPositionMs = pos
        stopInternalLocked(sendStop = true, context = context)
        pos
    }

    /** 通知栏 / 服务侧停止：没有界面可回，只做清理 */
    fun stopFromService(context: Context) {
        scope.launch {
            mutex.withLock { stopInternalLocked(sendStop = true, context = context) }
        }
    }

    /** 会话开始后拉起轮询；已经活着就什么都不做 */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (this@CastSession.isActive()) {
                runCatching { refresh() }
                delay(POLL_INTERVAL_MS)
            }
            pollJob = null
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 轮询电视状态（播放/暂停 + 进度 + 倍速音量）。两条通道取数方式不同，喂给状态机的是同一份数据 */
    suspend fun refresh() {        val p = padTv
        val c = client
        if (p == null && c == null) return
        val st: String?
        val pos: Long?
        val dur: Long?
        var speed = 1f
        var vol = -1
        var volMax = -1
        var decode = PadTv.DECODE_HW
        var playerMode = PadTv.PLAYER_BUILTIN
        var external = false
        var quality = 0
        var qualityOptions: List<CastQualityOption> = emptyList()
        var subtitleOn = true
        if (p != null) {
            val s = p.status()
            if (s == null) {
                // 电视端失联（关机/被杀/网络断）：只提示一句，**不结束会话**——
                // Wi-Fi 抖一下就判"投屏结束"比多提示一句糟糕得多
                tvStatusFails += 1
                if (tvStatusFails == TV_STATUS_FAIL_NOTICE) {
                    _notices.tryEmit("与电视端失联，检查电视上的「115 openpad电视端」是否还在运行")
                }
                return
            }
            tvStatusFails = 0
            // 电视自己连播到下一集后 /status 里的 title/pickCode 会变：把"当前集"同步过来。
            // 通知点回播放页、以及停止投屏后的本地续播都读 sourcePickCode，同步晚了就会停在旧集上
            // （续播那一步还会再用 lastRemotePickCode 校验，见 PlayerActivity 的 Idle 分支）。
            val remotePick = s.pickCode
            if (!remotePick.isNullOrBlank()) {
                lastRemotePickCode = remotePick
                if (remotePick != sourcePickCode) {
                    sourcePickCode = remotePick
                    if (s.title.isNotBlank()) sourceTitle = s.title
                }
            }
            // 电视端退出了播放页（按了返回，或从待机页消失）：结束投屏会话——
            // 遥控界面随之收起、本地从电视最后的位置接着播。
            // ★ 只有"真的在电视上播过"才算数：投屏指令刚发出去那一两秒 /status 也是 idle，
            //   没这个门槛会把每次投屏都瞬间判死。
            if (s.state == PadTv.STATE_IDLE) {
                if (tvHasPlayed) {
                    mutex.withLock {
                        _notices.tryEmit("电视端已退出投屏")
                        stopInternalLocked(sendStop = false, context = appContext)
                    }
                }
                return
            }
            tvHasPlayed = true
            st = s.state
            pos = s.positionMs
            dur = s.durationMs
            speed = s.speed
            vol = s.volume
            volMax = s.volumeMax
            decode = s.decode
            playerMode = s.player
            external = s.state == PadTv.STATE_EXTERNAL
            quality = s.quality
            qualityOptions = s.qualityOptions.map { CastQualityOption(it.def, it.label) }
            subtitleOn = s.subtitle != PadTv.SUBTITLE_OFF
        } else {
            val dlna = c ?: return
            st = runCatching { dlna.transportState() }.getOrNull()
            val pd = dlna.positionAndDuration()
            pos = pd.first
            dur = pd.second
        }
        if (pos != null || dur != null) {
            val prev = _progress.value
            _progress.value = CastProgress(
                positionMs = pos ?: prev?.positionMs ?: 0L,
                durationMs = dur ?: prev?.durationMs ?: 0L,
                speed = speed,
                volume = vol,
                volumeMax = volMax,
                decode = decode,
                player = playerMode,
                quality = quality,
                qualityOptions = qualityOptions,
                subtitleOn = subtitleOn,
                external = external,
            )
        }
        if (pos != null) lastPositionMs = pos
        val cur = _state.value
        if (st != null && cur is CastUiState.Casting) {
            val playing = if (p != null) {
                st.equals(PadTv.STATE_PLAYING, ignoreCase = true) ||
                    st.equals(PadTv.STATE_BUFFERING, ignoreCase = true)
            } else {
                st.equals("PLAYING", ignoreCase = true) ||
                    st.equals("TRANSITIONING", ignoreCase = true)
            }
            if (cur.playing != playing) _state.value = cur.copy(playing = playing)
        }
    }

    suspend fun setPlaying(playing: Boolean) {
        val p = padTv
        val c = client
        if (p != null) {
            p.control(if (playing) PadTv.ACTION_PLAY else PadTv.ACTION_PAUSE)
        } else if (c != null) {
            runCatching { if (playing) c.play() else c.pause() }
                .onFailure { Log.w(TAG, "切换播放状态失败: ${it.message}") }
        } else {
            return
        }
        val cur = _state.value
        if (cur is CastUiState.Casting) _state.value = cur.copy(playing = playing)
    }

    /**
     * 平板面板拖进度条松手后定位。
     *
     * 立刻把本地进度预置到目标值：否则下一次 2 秒轮询会先把进度条拽回旧位置，
     * 观感是"拖完又跳回去"，等轮询追上来才对。
     */
    suspend fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val p = padTv
        val c = client
        if (p != null) {
            p.control(PadTv.ACTION_SEEK, positionMs = target)
        } else if (c != null) {
            runCatching { c.seekTo(target) }.onFailure { Log.w(TAG, "远端 seek 失败: ${it.message}") }
        } else {
            return
        }
        lastPositionMs = target
        _progress.value = _progress.value?.copy(positionMs = target)
    }

    /** 远端倍速（只有 PadTv 通道有；DLNA 没有远端倍速能力） */
    suspend fun setSpeed(speed: Float) {
        val p = padTv ?: return
        val s = speed.coerceIn(0.25f, 4f)
        p.control(PadTv.ACTION_SPEED, speed = s)
        _progress.value = _progress.value?.copy(speed = s)
    }

    suspend fun volumeUp() {
        padTv?.control(PadTv.ACTION_VOLUME_UP)
    }

    suspend fun volumeDown() {
        padTv?.control(PadTv.ACTION_VOLUME_DOWN)
    }

    /** 直接设音量（预留：音量拖到某个值时用） */
    suspend fun setVolume(value: Int) {
        padTv?.control(PadTv.ACTION_VOLUME_SET, volume = value)
    }

    /** 远端解码方式（hw/sw）：只有 PadTv 通道有 */
    suspend fun setDecode(mode: String) {
        val p = padTv ?: return
        p.control(PadTv.ACTION_DECODE, mode = mode)
        _progress.value = _progress.value?.copy(decode = mode)
    }

    /** 远端播放器（builtin/system）：切到 system 后电视端会把流交给它自带的播放器 */
    suspend fun setPlayer(mode: String) {
        val p = padTv ?: return
        p.control(PadTv.ACTION_PLAYER, mode = mode)
        _progress.value = _progress.value?.copy(player = mode)
    }

    /** 远端画质档（def 编号）：与电视端画质面板同一条切换路径，从当前进度续播 */
    suspend fun setQuality(def: Int) {
        val p = padTv ?: return
        p.control(PadTv.ACTION_QUALITY, quality = def)
        _progress.value = _progress.value?.copy(quality = def)
    }

    /** 远端字幕开关：off = 关文本轨；on = 电视端锁回选中轨（新推的字幕会自动选中） */
    suspend fun setSubtitle(on: Boolean) {
        val p = padTv ?: return
        p.control(PadTv.ACTION_SUBTITLE, mode = if (on) PadTv.SUBTITLE_ON else PadTv.SUBTITLE_OFF)
        _progress.value = _progress.value?.copy(subtitleOn = on)
    }

    /**
     * 推一路在线字幕给电视（内容 base64 直发，电视端落盘挂轨并自动选中）。
     * @return 是否送达（电视端失联/DLNA 通道都返回 false）
     */
    suspend fun pushSubtitle(name: String?, mime: String?, bytes: ByteArray): Boolean {
        val p = padTv ?: return false
        return p.pushSubs(name, mime, bytes)
    }

    /**
     * 回到「遥控器」的 Intent：能拿到 pick_code 就落到播放页（投屏中它就是遥控界面），
     * 拿不到（会话已清理）退回主页。
     *
     * `CLEAR_TOP + SINGLE_TOP`：播放页还在任务栈里就复用它，不重建播放器——重进只为看遥控界面，
     * 不该再拉一遍流。投屏通知与 App 内「投屏中」浮标两处入口共用这一份。
     */
    fun remoteIntent(context: Context): Intent {
        val base = if (sourcePickCode.isNotBlank()) {
            PlayerActivity.intent(
                context = context,
                pickCode = sourcePickCode,
                name = sourceTitle,
                startMs = lastPositionMs,
            )
        } else {
            Intent(context, MainActivity::class.java)
        }
        return base.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    /** 停止并清理（调用方已持有 mutex） */
    private suspend fun stopInternalLocked(sendStop: Boolean, context: Context? = null) {
        stopPolling()
        // 队列补推跟着会话走：换了设备/停止投屏就不该再往旧电视发
        queueJob?.cancel()
        queueJob = null
        val p = padTv
        val c = client
        val r = relay
        val ctx = context?.applicationContext ?: appContext
        padTv = null
        client = null
        relay = null
        device = null
        sourcePickCode = ""
        sourceTitle = ""
        tvHasPlayed = false
        tvStatusFails = 0
        _state.value = CastUiState.Idle
        _progress.value = null
        if (sendStop) {
            // 电视掉线时 Stop 会卡住：最多等 1.5 秒，清理不能跟着它耗
            if (p != null) {
                withTimeoutOrNull(1500) { p.control(PadTv.ACTION_STOP) }
            } else if (c != null) {
                withTimeoutOrNull(1500) { runCatching { c.stop() } }
            }
        }
        r?.stop()
        // PadTv 通道没起过保活服务，这里的 stop 是幂等 no-op
        if (ctx != null) CastService.stop(ctx)
    }

    /**
     * 解析后续集的直链，分批补推给电视（PadTv 通道专有）。
     *
     * 为什么跑在进程级 [scope] 而不是播放页里：用户投完屏随时会退出播放页，这正是
     * "平板退出、电视也能连播"的前提——队列必须在收屏之前送完。前台通知服务兜着进程，
     * 几百毫秒一集的解析节奏足够在用户离开前把最近几集送到。
     *
     * 取不到直链的条目直接跳过：115 偶发失败不该让整条队列作废（少一集总比全没有强）。
     */
    private suspend fun publishQueue(
        remote: PadTvClient,
        current: PadTvQueueItem,
        upcoming: List<PlaylistEntry>,
        resolve: suspend (String) -> String?,
    ) {
        val items = mutableListOf(current)
        var pushed = 0
        for (entry in upcoming.take(QUEUE_MAX_ITEMS - 1)) {
            if (padTv !== remote) return // 会话已经换了/停了：别再往后发
            val url = runCatching { resolve(entry.pc) }.getOrNull()
            if (url.isNullOrBlank()) {
                Log.w(TAG, "连播队列：跳过取不到直链的 ${entry.fn}")
                continue
            }
            items += PadTvQueueItem(
                url = url,
                title = entry.fn,
                mime = castMimeOf(entry.fn),
                pickCode = entry.pc,
            )
            // 攒几条推一次：电视早一点拿到"下一集"，同时别把补推拆成几十个请求
            if (items.size - pushed >= QUEUE_PUSH_BATCH) {
                if (!pushQueue(remote, current.pickCode, items)) return
                pushed = items.size
            }
        }
        if (items.size > pushed) pushQueue(remote, current.pickCode, items)
    }

    /** 补推一次（整段下发，电视按 pick_code 去重）；会话已换过就放弃 */
    private suspend fun pushQueue(
        remote: PadTvClient,
        anchor: String?,
        items: List<PadTvQueueItem>,
    ): Boolean {
        if (padTv !== remote) return false
        return runCatching { remote.queue(items.toList(), anchor) }
            .onFailure { Log.w(TAG, "补推连播队列失败: ${it.message}") }
            .isSuccess
    }

    private fun castErrorText(e: Throwable): String = when (e) {
        is DlnaException -> "电视没有接受投屏：${e.message}"
        is java.net.SocketTimeoutException -> "电视无响应（检查是否在同一 Wi-Fi）"
        is java.net.UnknownHostException -> "本机网络异常"
        is java.io.IOException -> "网络异常：${e.message}"
        else -> e.message ?: "投屏失败"
    }

    /** PadTv 通道的失败翻译：最常见就是电视上没开接收端（连接直接被拒） */
    private fun padTvErrorText(e: Throwable): String = when (e) {
        is PadTvException -> "电视端拒绝了投屏：${e.message}"
        is java.net.SocketTimeoutException ->
            "电视端没有响应（确认电视上的「115 openpad电视端」已打开）"

        is java.net.ConnectException ->
            "连不上电视端（确认电视上的「115 openpad电视端」已打开、且与本机同一 Wi-Fi）"

        is java.net.UnknownHostException -> "本机网络异常"
        is java.io.IOException -> "网络异常：${e.message}"
        else -> e.message ?: "投屏失败"
    }
}
