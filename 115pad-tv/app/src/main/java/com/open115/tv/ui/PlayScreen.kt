package com.open115.tv.ui

import android.media.AudioManager
import android.util.Base64
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.open115.tv.data.DEF_ORIGINAL_FILE
import com.open115.tv.data.Tv115Api
import com.open115.tv.data.TvTier
import com.open115.tv.data.apiBaseFor
import com.open115.tv.data.pickTier
import com.open115.tv.player.buildMediaItem
import com.open115.tv.player.buildTvPlayer
import com.open115.tv.player.mimeOfSubtitleUrl
import com.open115.tv.receiver.PlayBus
import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.QualityOption
import com.open115.tv.receiver.QueueItem
import com.open115.tv.receiver.StatusResponse
import com.open115.tv.receiver.SubsPushRequest
import com.open115.tv.receiver.SubtitleTrack
import com.open115.tv.receiver.TvProtocol
import com.open115.tv.util.TvPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 控制条隐藏时，左右键直接快退/快进的步长（与 115pad 播放页手势同口径：左退 10s、右进 30s） */
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L

/**
 * 长按左右键的连跳参数：按住 [HOLD_START_MS] 后开始每 [HOLD_TICK_MS] 跳一次，
 * 步长随时间翻倍（30s → 60s → 120s）。
 *
 * 为什么自己计时而不是靠系统重复键：不少遥控器/盒子对方向键**只发一次 KeyDown**，
 * 收不到 repeat 事件——那时靠系统重复就完全没反应。自己数"按下没松开的时长"最稳。
 */
private const val HOLD_START_MS = 400L
private const val HOLD_TICK_MS = 180L
private const val HOLD_STEP_MS = 30_000L

/** 位置与状态向 PlayBus 的刷新间隔（平板端 /status 轮询 2 秒一次，这里 500ms 足够细） */
private const val STATUS_TICK_MS = 500L

/** 控制条无操作后自动收起的时间 */
private const val CONTROLS_AUTO_HIDE_MS = 6_000L

/** 右上角时钟在控制条收起时的不透明度（控制在屏上不能抢画面） */
private const val CLOCK_IDLE_ALPHA = 0.7f

/** 片尾自动连播的倒计时（秒）：留几秒给用户看清下一集、想取消还来得及 */
private const val AUTO_NEXT_SECONDS = 5

/** 直链失效（401/403）后自动重取的最多次数（按当前集计） */
private const val LINK_REFRESH_LIMIT = 2

/** 选集面板每行高度：打开时按它把当前集滚到靠上的位置 */
private val QUEUE_ROW_HEIGHT = 54.dp

/**
 * 播放页：全屏 SurfaceView + 自绘控制条。
 *
 * 渲染面用 SurfaceView 而不是 115pad 播放器的 TextureView：那边要 TextureView 是因为手势缩放/
 * 旋转要走 graphicsLayer 变换，电视端没有这些需求，SurfaceView 直通还能让电视正确处理 HDR。
 *
 * D-pad 的按键分工（从零写，是本工程最大的新增面）：
 * - 控制条**隐藏**时：左/右 = 快退快进（最常用，零进入成本）；OK = 呼出控制条；
 *   上/下 = 音量；返回 = 退出播放回待机
 * - 控制条**显示**时：左/右/上/下 = 在按钮间移动焦点；OK = 按下焦点按钮；返回 = 先收起控制条
 * - 控制条里的「倍速」键展开档位面板：左右在档位间移动、OK 选中并收起、返回只关面板
 */
@Composable
internal fun PlayScreen(
    request: PlayRequest,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val decodeMode by TvPrefs.decode.collectAsState()
    val playerMode by TvPrefs.player.collectAsState()
    val miniProgressOn by TvPrefs.miniProgress.collectAsState()
    val autoNextOn by TvPrefs.autoNext.collectAsState()

    /**
     * 换解码方式要重建播放器（解码器是在构建时定的），重建后从这儿接着放。
     * 初始等于平板指定的起播位置；切软/硬解时由控制指令更新成"当前播到哪儿"。
     */
    var pendingSeekMs by remember(request) { mutableLongStateOf(request.positionMs) }

    val player = remember(request, decodeMode) {
        buildTvPlayer(context, request, softwareDecode = decodeMode == TvProtocol.DECODE_SW)
    }

    var controlsVisible by remember { mutableStateOf(false) }

    /**
     * 刚用 OK 键呼出控制条：这一次的"抬起"要吞掉。
     *
     * 呼出的一瞬间焦点会被移到"播放/暂停"按钮上，紧接着到达的 KeyUp 会被那个按钮当成
     * 一次点击——不吞就是"按 OK 打开控制条，片自己暂停了"（模拟器上必现）。
     */
    var swallowCenterUp by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var playerState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 字幕/音轨：-1 = 关（字幕）/ 默认（音轨）；数字 = currentTracks 里第几条
    var subtitleIndex by remember { mutableStateOf(-2) } // -2 = 未初始化（跟随默认），-1 = 关
    var textTrackCount by remember { mutableStateOf(0) }
    var audioTrackCount by remember { mutableStateOf(0) }
    /** 与 textTrackCount 同序的各文本轨展示名（外挂字幕是平板给的 name；内封轨常为 null） */
    var textTrackNames by remember { mutableStateOf<List<String?>>(emptyList()) }
    /**
     * 待自动选中的字幕轨名：平板推来在线字幕后按名字锁定轨道。
     * onTracksChanged 在 prepare 之后异步到达，这里先记名字、轨表里出现同名即选。
     */
    var pendingSubSelectName by remember { mutableStateOf<String?>(null) }
    var audioIndex by remember { mutableStateOf(-1) }

    var wasPlayingBeforeStop by remember { mutableStateOf(false) }

    /** 当前倍速：电视遥控与平板指令都会改它；状态心跳每轮从播放器读回真实值 */
    var speed by remember { mutableFloatStateOf(1f) }

    /**
     * 重建播放器（换解码方式）后要接着用的倍速。换片则回到 1.0——
     * 键带上 request，新投一部片就是新会话（与平板播放器同口径）。
     */
    var pendingSpeed by remember(request) { mutableFloatStateOf(1f) }

    /** 倍速面板（控制条上方那排档位胶囊）是否展开 */
    var speedPickerOpen by remember { mutableStateOf(false) }

    /**
     * 连播队列：第一条就是这次投屏的当前集，后面几集由平板异步补推（见 PlayBus.queue）。
     * 队列只增不减——已经播过的留着，选集面板里才好往回点。
     */
    var queue by remember(request) { mutableStateOf(seedQueue(request)) }
    var queueIndex by remember(request) { mutableIntStateOf(0) }

    /** 选集面板（右侧抽屉）是否展开 */
    var queuePanelOpen by remember { mutableStateOf(false) }

    /** 片尾自动连播倒计时剩余秒数（null = 没在倒计时） */
    var autoNextLeft by remember { mutableStateOf<Int?>(null) }

    /**
     * 115 接口客户端：只有平板随投屏带了 token + UA 才存在。
     * 没有它就只能播原盘、直链过期也刷不回来（老版本平板投过来的表现）。
     */
    val api = remember(request.token, request.ua) {
        val token = request.token?.takeIf { it.isNotBlank() }
        val ua = request.ua?.takeIf { it.isNotBlank() }
        if (token != null && ua != null) Tv115Api(token, ua, apiBaseFor(context)) else null
    }

    /** 当前集可选的清晰度：第一条恒为原盘（地址直接用投屏带来的直链，零等待起播） */
    var tiers by remember(request) {
        mutableStateOf(listOf(TvTier(DEF_ORIGINAL_FILE, "原盘", request.url, hls = false)))
    }

    /** 正在播的档位（可能不等于首选：这一集没有首选档时会退回原盘） */
    var playingDef by remember(request) { mutableIntStateOf(DEF_ORIGINAL_FILE) }

    /** 首选档位：画质菜单里选的，落盘（TvPrefs），换集/换投屏都沿用 */
    var qualityDef by remember(request) { mutableIntStateOf(TvPrefs.qualityDef.value) }

    var qualityPanelOpen by remember { mutableStateOf(false) }

    /** 清晰度列表正在解析（画质面板上给个明确的"还在取"） */
    var tiersLoading by remember { mutableStateOf(false) }

    /** 直链失效自愈：本集已重取次数 + 屏上提示 */
    var linkRetry by remember(request) { mutableIntStateOf(0) }
    var linkHint by remember { mutableStateOf<String?>(null) }

    /** 装载代次：切集/切清晰度/重取直链都 ++，异步解析回来据此丢弃过期结果 */
    var loadGen by remember { mutableIntStateOf(0) }

    /**
     * 音量反馈：(音量, 上限, 序号)——调完显示 2 秒。很多电视盒子播片时不显示系统音量条，自己画一个。
     *
     * ★带序号是必需的：状态用**相等性**判断要不要重组，而"连续两次 seek 到同一个位置""音量已经到顶
     * 还继续按 +"这些情况下值完全相同 —— 不给一个每次都变的东西，提示就只在第一次出现、之后不再刷新
     * （也不会重置消失计时）。
     */
    var volumeHint by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    /** seek 反馈：(方向 1/-1, 目标位置, 序号)。短按也有——不然画面跳了却不知道为什么 */
    var seekHint by remember { mutableStateOf<Triple<Int, Long, Int>?>(null) }

    /** 倍速反馈：(倍速, 序号)。平板遥控改的速度也闪一下，看电视的人才知道声音为什么变了 */
    var speedHint by remember { mutableStateOf<Pair<Float, Int>?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }

    /** 当前按住的左右键（0 = 没按）：既用于长按连跳，也用来忽略系统重复发的 KeyDown */
    var holdingKey by remember { mutableStateOf(0) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // 焦点：控制条隐藏时在根上；显示时先落"播放/暂停"；面板收起后回各自的按键
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val speedButtonFocus = remember { FocusRequester() }
    val queueButtonFocus = remember { FocusRequester() }
    val qualityButtonFocus = remember { FocusRequester() }

    val exitState = rememberUpdatedState(onExit)

    // ---- 本地 helper：必须定义在**所有 effect 之前**（Kotlin 局部函数只能先声明后使用）----

    fun markInteraction() {
        lastInteraction = System.currentTimeMillis()
    }

    /** 电视的媒体音量与上限（读不到给 -1，/status 与音量条都用它） */
    fun readVolume(): Pair<Int, Int> {
        val am = context.getSystemService(AudioManager::class.java) ?: return -1 to -1
        return runCatching {
            am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(-1 to -1)
    }

    /** 记一次音量反馈（序号见 volumeHint 注释） */
    fun showVolumeHint() {
        val (vol, max) = readVolume()
        hintSeq += 1
        volumeHint = Triple(vol, max, hintSeq)
    }

    fun seekBy(deltaMs: Long) {
        val max = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, max)
        player.seekTo(target)
        hintSeq += 1
        seekHint = Triple(if (deltaMs >= 0) 1 else -1, target, hintSeq)
        markInteraction()
    }

    /** 电视遥控与平板指令共用：改倍速、记住以便重建播放器后沿用，并闪一下反馈 */
    fun applySpeed(target: Float) {
        val v = target.coerceIn(0.25f, 4f)
        pendingSpeed = v
        speed = v
        player.setPlaybackSpeed(v)
        hintSeq += 1
        speedHint = v to hintSeq
        markInteraction()
    }

    /**
     * 收起倍速面板并把焦点还给「倍速」键。
     * 不还回去，之后的左右键就没人接：焦点元素随面板一起离开组合，系统不会自己把焦点移回控制条。
     */
    fun closeSpeedPicker() {
        speedPickerOpen = false
        markInteraction()
        runCatching { speedButtonFocus.requestFocus() }
    }

    /** 当前队列条目（状态读：自动连播/补推队列之后立刻就是新的那一集） */
    fun currentQueueItem(): QueueItem? = queue.getOrNull(queueIndex) ?: queue.firstOrNull()

    /** 档位显示名：优先用这一集接口给的标签，没有就按编号兜底 */
    fun tierLabel(def: Int): String = tiers.firstOrNull { it.def == def }?.label
        ?: if (def == DEF_ORIGINAL_FILE) "原盘" else "清晰度 $def"

    /**
     * 换媒体项（起播 / 换集 / 换清晰度 / 直链刷新共用）。
     *
     * 不重建播放器：解码器、倍速、音量都沿用，切换只等新片缓冲。
     * 字幕/音轨索引要复位——那是按"当前片的轨道表"数的，跨片后序号含义完全不同。
     */
    fun loadMedia(url: String, mime: String?, startMs: Long = 0L, def: Int = DEF_ORIGINAL_FILE) {
        playingDef = def
        errorText = null
        subtitleIndex = -2
        audioIndex = -1
        seekHint = null
        speedHint = null
        // 外挂字幕挂在队列条目上：换档位/刷新直链仍是同一集，字幕跟着走；换集后自然换成新一集的
        player.setMediaItem(buildMediaItem(url, mime, currentQueueItem()?.subs.orEmpty()))
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
        player.setPlaybackSpeed(pendingSpeed)
        speed = pendingSpeed
        player.playWhenReady = true
    }

    /** 按清晰度档位起播：115 的转码地址没有 .m3u8 后缀，必须显式声明 MIME */
    fun loadTier(tier: TvTier, startMs: Long = 0L) {
        loadMedia(
            url = tier.url,
            mime = if (tier.hls) MimeTypes.APPLICATION_M3U8 else null,
            startMs = startMs,
            def = tier.def,
        )
    }

    /**
     * 装载"当前这一集"：首选原盘时直接用队列里的直链（零等待），清晰度列表在后台取；
     * 首选转码档时先解析这一集的地址再起播——电视解不了原盘的场景不能先放原盘。
     */
    suspend fun loadForPlayback(startMs: Long, gen: Int) {
        val item = queue.getOrNull(queueIndex) ?: queue.firstOrNull() ?: return
        val api = api
        val pickCode = item.pickCode
        if (api == null || pickCode.isNullOrBlank()) {
            loadMedia(item.url, item.mime, startMs, DEF_ORIGINAL_FILE)
            return
        }
        val preferred = qualityDef
        if (preferred != DEF_ORIGINAL_FILE) {
            tiersLoading = true
            val resolved = runCatching { api.tiers(pickCode) }.getOrNull()
            tiersLoading = false
            if (gen != loadGen) return
            if (!resolved.isNullOrEmpty()) {
                tiers = resolved
                val tier = pickTier(resolved, preferred)
                if (tier != null) {
                    loadTier(tier, startMs)
                    if (tier.def != preferred) {
                        linkHint = "这一集没有${tierLabel(preferred)}，已用${tier.label}"
                    }
                    return
                }
            }
            // 一项都没取到：退回投屏带来的原盘直链
            loadMedia(item.url, item.mime, startMs, DEF_ORIGINAL_FILE)
            return
        }
        loadMedia(item.url, item.mime, startMs, DEF_ORIGINAL_FILE)
        // 原盘先播起来，清晰度列表在后台取（画质菜单要用）
        scope.launch {
            tiersLoading = true
            val resolved = runCatching { api.tiers(pickCode) }.getOrNull()
            tiersLoading = false
            if (gen != loadGen) return@launch
            if (currentQueueItem()?.pickCode == pickCode && !resolved.isNullOrEmpty()) tiers = resolved
        }
    }

    /**
     * 切到队列第 index 条（自动连播与选集共用）。
     * 按首选档位决定"立刻播原盘"还是"先取转码地址"，见 [loadForPlayback]。
     */
    fun startEpisode(index: Int) {
        val item = queue.getOrNull(index) ?: return
        queueIndex = index
        autoNextLeft = null
        linkRetry = 0
        tiers = listOf(TvTier(DEF_ORIGINAL_FILE, "原盘", item.url, hls = false))
        playingDef = DEF_ORIGINAL_FILE
        val gen = ++loadGen
        scope.launch { loadForPlayback(0L, gen) }
        markInteraction()
    }

    /** 收起画质面板并把焦点还给「画质」键 */
    fun closeQualityPanel() {
        qualityPanelOpen = false
        markInteraction()
        runCatching { qualityButtonFocus.requestFocus() }
    }

    /**
     * 换清晰度：记住首选，从当前进度接着播。
     * 列表里已有这一档就用现成地址；没有（或缺凭据）才现取一次。
     */
    fun selectQuality(def: Int) {
        qualityDef = def
        TvPrefs.setQualityDef(context, def)
        linkRetry = 0
        closeQualityPanel()
        val item = currentQueueItem() ?: return
        val resume = if (playerState == Player.STATE_ENDED) 0L else positionMs.coerceAtLeast(0L)
        tiers.firstOrNull { it.def == def }?.takeIf { it.url.isNotBlank() }?.let {
            loadTier(it, resume)
            return
        }
        val api = api
        val pickCode = item.pickCode
        if (api == null || pickCode.isNullOrBlank()) {
            loadMedia(item.url, item.mime, resume, DEF_ORIGINAL_FILE)
            linkHint = "没有 115 凭据，只能播放原盘"
            return
        }
        linkHint = "正在获取${tierLabel(def)}地址…"
        val gen = ++loadGen
        scope.launch {
            tiersLoading = true
            val resolved = runCatching { api.tiers(pickCode) }.getOrNull()
            tiersLoading = false
            if (gen != loadGen) return@launch
            if (!resolved.isNullOrEmpty()) tiers = resolved
            val tier = pickTier(resolved.orEmpty(), def)
            if (tier != null) loadTier(tier, resume) else linkHint = "没能取到这一集的播放地址"
        }
    }

    /**
     * 直链过期（播放器报 401/403）：用平板带来的凭据现取一份新地址，从刚才的位置续播。
     * 每集最多重取 [LINK_REFRESH_LIMIT] 次——无限重试只会在频控里打转。
     */
    fun refreshExpiredLink() {
        val item = currentQueueItem() ?: return
        val api = api
        val pickCode = item.pickCode
        if (api == null || pickCode.isNullOrBlank()) {
            errorText = "直链已过期，请在平板上重新投屏"
            return
        }
        if (linkRetry >= LINK_REFRESH_LIMIT) {
            errorText = "直链已过期，多次重新获取都失败"
            return
        }
        linkRetry += 1
        errorText = null
        linkHint = "直链已过期，正在重新获取…"
        val def = playingDef
        val resume = positionMs.coerceAtLeast(0L)
        scope.launch {
            val fresh = runCatching { api.tier(pickCode, def) }.getOrNull()
                ?: runCatching { pickTier(api.tiers(pickCode), def) }.getOrNull()
            if (fresh != null) {
                loadTier(fresh, resume)
                linkHint = "已重新获取到地址，继续播放"
            } else {
                errorText = "直链已过期，重新获取失败（检查网络或在平板上重投）"
            }
        }
    }

    /** 收起选集抽屉并把焦点还回「选集」键 */
    fun closeQueuePanel() {
        queuePanelOpen = false
        markInteraction()
        runCatching { queueButtonFocus.requestFocus() }
    }

    /** 短按 = 小步；按住不放进入连跳（[startHoldSeek]），松手停在当前位置 */
    fun startSeekKey(direction: Int) {
        if (holdingKey != 0) return // 系统重复发的 KeyDown（遥控器会 repeat 时）：忽略，交给自己的计时
        holdingKey = direction
        seekBy(if (direction > 0) SEEK_FORWARD_MS else -SEEK_BACK_MS)
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(HOLD_START_MS)
            var ticks = 0
            while (isActive) {
                val step = when {
                    ticks < 3 -> HOLD_STEP_MS
                    ticks < 6 -> HOLD_STEP_MS * 2
                    else -> HOLD_STEP_MS * 4
                }
                seekBy(step * direction)
                ticks++
                delay(HOLD_TICK_MS)
            }
        }
    }

    fun stopHoldSeek() {
        holdJob?.cancel()
        holdJob = null
    }

    fun adjustVolume(up: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        showVolumeHint()
        markInteraction()
    }

    /** 直接设音量（平板面板拖到某个值） */
    fun setVolume(value: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val (_, max) = readVolume()
        if (max <= 0) return
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        }
        showVolumeHint()
        markInteraction()
    }

    // 字幕：关 → 第 1 条 → … → 第 n 条 → 关（索引语义与 115pad 播放器的"多轨循环"一致）
    fun cycleSubtitle() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (groups.isEmpty()) return
        val next = when {
            subtitleIndex >= groups.size - 1 -> -1
            else -> subtitleIndex + 1
        }
        subtitleIndex = next
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, next < 0)
        if (next >= 0) {
            params.setOverrideForType(
                TrackSelectionOverride(groups[next].mediaTrackGroup, 0),
            )
        } else {
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        }
        player.trackSelectionParameters = params.build()
        markInteraction()
    }

    /**
     * 字幕总开关（平板遥控面板用）：关 = 关掉文本轨；开 = 锁回上次选中的轨（没选过就第一轨）。
     * 与 [cycleSubtitle] 操作同一套 trackSelectionParameters，两边怎么切都不打架。
     */
    fun setSubtitleEnabled(on: Boolean) {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (!on) {
            subtitleIndex = -1
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        } else {
            if (groups.isEmpty()) return
            val idx = if (subtitleIndex in 0 until groups.size) subtitleIndex else 0
            subtitleIndex = idx
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(groups[idx].mediaTrackGroup, 0))
                .build()
        }
        markInteraction()
    }

    /**
     * 平板推来的在线字幕：内容落盘 → 追加为当前集的字幕 → 重建媒体回到原进度 → 按名字自动选中。
     *
     * media3 的 MediaItem 不可变，挂新字幕只能重建媒体（与平板本地播放器同款代价：闪一下重新缓冲）。
     * 重建用的地址按当前档位取——正在放转码档时不能退回原盘。
     */
    fun applyPushedSubtitle(push: SubsPushRequest) {
        val item = currentQueueItem() ?: return
        scope.launch {
            val bytes = runCatching { Base64.decode(push.data, Base64.DEFAULT) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                linkHint = "字幕内容无效"
                return@launch
            }
            val name = push.name?.takeIf(String::isNotBlank) ?: "在线字幕"
            // 扩展名从 MIME 反推（落盘扩展名同时是 MIME 兜底的判据，见 mimeOfSubtitleUrl）
            val ext = when (push.mime?.lowercase()) {
                "text/vtt" -> "vtt"
                "text/x-ssa" -> "ass"
                "application/x-subrip" -> "srt"
                else -> name.substringAfterLast('.', "srt").lowercase()
                    .takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
            }
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "cast_subs").apply { mkdirs() }
                // 只留最近 10 份：在线字幕随来随挂，旧文件不再有用
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
                val f = File(dir, "cast_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                f
            }
            // 同名替换、不同名追加：电视本机字幕键里的"第 n 条"顺序保持稳定
            val newSubs = item.subs.filterNot { it.name == name } +
                SubtitleTrack(url = android.net.Uri.fromFile(file).toString(), mime = push.mime, name = name)
            queue = queue.map { if (queueItemKey(it) == queueItemKey(item)) it.copy(subs = newSubs) else it }
            pendingSubSelectName = name
            val resume = player.currentPosition.coerceAtLeast(0L)
            val tier = tiers.firstOrNull { it.def == playingDef && it.url.isNotBlank() }
            loadMedia(
                url = tier?.url ?: item.url,
                mime = tier?.let { if (it.hls) MimeTypes.APPLICATION_M3U8 else null } ?: item.mime,
                startMs = resume,
                def = playingDef,
            )
            linkHint = "已加载字幕：${name.take(20)}"
            markInteraction()
        }
    }

    fun cycleAudio() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (groups.isEmpty()) return
        audioIndex = (audioIndex + 1) % groups.size
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(groups[audioIndex].mediaTrackGroup, 0))
        player.trackSelectionParameters = params.build()
        markInteraction()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            // 停在片尾时按播放 = 从头重播（ExoPlayer 播完后 play() 不会自己回开头）
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        markInteraction()
    }

    val subtitleView = remember {
        SubtitleView(context).apply {
            // 与 115pad 同口径：统一白字描边，不跟随片源自带样式（ASS 自带样式在电视上常偏小、偏花）
            setApplyEmbeddedStyles(false)
            setStyle(
                CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null,
                ),
            )
            setBottomPaddingFraction(0.06f)
        }
    }

    // 播放器生命周期：release 留在 DisposableEffect；装载放到 effect 协程里——
    // 选了转码档时得先解析地址，那一步是挂起的（不能在 DisposableEffect 里等）
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        val gen = ++loadGen
        loadForPlayback(pendingSeekMs, gen)
    }

    DisposableEffect(player, subtitleView) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playerState = playbackState
                isBuffering = playbackState == Player.STATE_BUFFERING
                // 播完**不退出**播放页：停在片尾画面，平板那边的控制面板还能用
                //（拖回去重看 / 按播放重播）。会话真正结束只有两条路：电视上按返回，或平板点「停止投屏」。
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                textTrackCount = textGroups.size
                textTrackNames = textGroups.map { g -> g.getTrackFormat(0).label?.takeIf(String::isNotBlank) }
                audioTrackCount = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                // 平板推来的在线字幕：轨表里出现同名就立刻锁上它（推完自动显示，不用再按字幕键）
                val want = pendingSubSelectName
                if (want != null) {
                    val idx = textGroups.indexOfFirst { g -> g.getTrackFormat(0).label == want }
                    if (idx >= 0) {
                        pendingSubSelectName = null
                        subtitleIndex = idx
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    TrackSelectionOverride(textGroups[idx].mediaTrackGroup, 0),
                                )
                                .build()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                // 直链过期（115 的签名地址有时效）：有凭据就现取一份新的，从刚才的位置续播
                if ((httpCode == 401 || httpCode == 403) && api != null) {
                    refreshExpiredLink()
                } else {
                    errorText = describePlaybackError(error)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 位置轮询：同时是向 PlayBus 上报状态的心跳（平板 /status 与常驻通知都读它）
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            // 平板可能遥控改了倍速：跟着播放器的真实值走（与本地按键互不打架）
            speed = player.playbackParameters.speed
            val dur = player.duration
            durationMs = if (dur == C.TIME_UNSET || dur < 0) 0L else dur
            val (vol, volMax) = readVolume()
            // 队列/下标是状态读：自动连播换集或平板补推队列后，/status 里立刻是新的那一集
            val item = queue.getOrNull(queueIndex) ?: queue.first()
            PlayBus.updateStatus(
                StatusResponse(
                    state = when {
                        errorText != null -> TvProtocol.STATE_ERROR
                        player.playbackState == Player.STATE_BUFFERING -> TvProtocol.STATE_BUFFERING
                        player.isPlaying -> TvProtocol.STATE_PLAYING
                        else -> TvProtocol.STATE_PAUSED
                    },
                    positionMs = positionMs,
                    durationMs = durationMs,
                    title = item.title.ifBlank { request.title },
                    error = errorText,
                    speed = player.playbackParameters.speed,
                    volume = vol,
                    volumeMax = volMax,
                    decode = decodeMode,
                    player = playerMode,
                    // 画质档与字幕开关：平板遥控面板的 chips 高亮全靠它们
                    quality = playingDef,
                    qualityOptions = tiers.map { QualityOption(def = it.def, label = it.label) },
                    // media3 1.4.1 没有 isTrackTypeDisabled()，直接查公开的 disabledTrackTypes
                    subtitle = if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) {
                        TvProtocol.SUBTITLE_OFF
                    } else {
                        TvProtocol.SUBTITLE_ON
                    },
                    index = queueIndex,
                    queueSize = queue.size,
                    pickCode = item.pickCode,
                ),
            )
            delay(STATUS_TICK_MS)
        }
    }

    // 平板发来的遥控指令
    LaunchedEffect(player) {
        PlayBus.control.collect { ctl ->
            when (ctl.action) {
                TvProtocol.ACTION_PLAY -> {
                    // 与遥控器 OK 键同一条规则：停在片尾时"播放"= 从头重播
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                TvProtocol.ACTION_PAUSE -> player.pause()
                TvProtocol.ACTION_SEEK -> player.seekTo(ctl.positionMs.coerceAtLeast(0L))
                // 平板控制面板：倍速改播放器参数（不动系统音量），音量走 STREAM_MUSIC
                TvProtocol.ACTION_SPEED -> applySpeed(ctl.speed)
                TvProtocol.ACTION_VOLUME_UP -> adjustVolume(up = true)
                TvProtocol.ACTION_VOLUME_DOWN -> adjustVolume(up = false)
                TvProtocol.ACTION_VOLUME_SET -> setVolume(ctl.volume)
                TvProtocol.ACTION_DECODE -> {
                    // 换个解码器要重建播放器：先记住当前播到哪儿，重建后从那儿接着放。
                    // （真正的落盘由 AppRoot 的全局处理负责——那里在待机/系统播放器模式下也生效）
                    pendingSeekMs = player.currentPosition.coerceAtLeast(0L)
                    TvPrefs.setDecode(context, ctl.mode ?: TvProtocol.DECODE_HW)
                }
                // 平板遥控面板的画质 chips：与电视本机画质面板同一条 selectQuality 路径
                // （首选档落盘、切换从当前进度续播）
                TvProtocol.ACTION_QUALITY -> selectQuality(ctl.quality)
                // 平板遥控的字幕开关：off=字幕 关；on=恢复第一轨（或上次选中的那轨）
                TvProtocol.ACTION_SUBTITLE -> setSubtitleEnabled(ctl.mode != TvProtocol.SUBTITLE_OFF)
                // ACTION_STOP / ACTION_PLAYER 由 AppRoot 全局处理（见其注释）
                else -> Unit
            }
        }
    }

    // 平板推来的外挂字幕（在线字幕库内容）：落盘挂轨、按名字自动选中
    LaunchedEffect(player) {
        PlayBus.subsPush.collect { push -> applyPushedSubtitle(push) }
    }

    // 平板异步补推的连播队列：只追加后续集，不碰当前这一条
    LaunchedEffect(request) {
        // 换了一场投屏：上一场残留的倒计时不能延续到新队列上
        autoNextLeft = null
        PlayBus.queue.collect { update ->
            if (update == null) return@collect
            queue = mergeQueue(queue, update)
        }
    }

    // 片尾：还有下一集且开了自动连播 → 起倒计时；用户在倒计时里按任意键就取消（见按键处理）
    LaunchedEffect(playerState, queueIndex, autoNextOn, queue.size) {
        autoNextLeft = if (
            playerState == Player.STATE_ENDED && autoNextOn && queueIndex < queue.lastIndex
        ) {
            AUTO_NEXT_SECONDS
        } else {
            null
        }
    }

    LaunchedEffect(autoNextLeft) {
        val left = autoNextLeft ?: return@LaunchedEffect
        if (left <= 0) {
            startEpisode(queueIndex + 1)
            return@LaunchedEffect
        }
        delay(1_000)
        autoNextLeft = left - 1
    }

    // 离开前台就暂停（电视上按 Home 去干别的，不该继续响），回来再续上
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingBeforeStop = player.isPlaying
                    player.pause()
                }

                Lifecycle.Event.ON_START -> if (wasPlayingBeforeStop) {
                    wasPlayingBeforeStop = false
                    player.play()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            // 等控制条完成一次布局再要焦点：requestFocus 在元素尚未布局时会被静默吞掉，
            // 结果就是"默认焦点莫名其妙落在第一颗按钮上"（实测）
            withFrameNanos { }
            runCatching { playPauseFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // 控制条自动收起：每次按键都会顶掉计时（lastInteraction 变 → effect 重启）
    LaunchedEffect(controlsVisible, lastInteraction, speedPickerOpen, queuePanelOpen, qualityPanelOpen) {
        if (!controlsVisible) return@LaunchedEffect
        // 面板/抽屉展开时不计时：正用遥控器挑东西，画面不该在挑到一半时整条收掉
        if (speedPickerOpen || queuePanelOpen || qualityPanelOpen) return@LaunchedEffect
        val remain = CONTROLS_AUTO_HIDE_MS - (System.currentTimeMillis() - lastInteraction)
        if (remain <= 0) {
            controlsVisible = false
            speedPickerOpen = false
            queuePanelOpen = false
            qualityPanelOpen = false
            return@LaunchedEffect
        }
        delay(remain)
        if (System.currentTimeMillis() - lastInteraction >= CONTROLS_AUTO_HIDE_MS) {
            controlsVisible = false
            speedPickerOpen = false
            queuePanelOpen = false
            qualityPanelOpen = false
        }
    }

    // 音量提示 2 秒后消失（每次调音量都会重置计时）
    LaunchedEffect(volumeHint) {
        if (volumeHint == null) return@LaunchedEffect
        delay(2_000)
        volumeHint = null
    }

    // seek 提示 1.5 秒后消失；长按连跳期间持续刷新，松手才开始计时
    LaunchedEffect(seekHint) {
        if (seekHint == null) return@LaunchedEffect
        delay(1_500)
        seekHint = null
    }

    // 倍速提示 2 秒后消失（连续挑档位每次都会重置计时）
    LaunchedEffect(speedHint) {
        if (speedHint == null) return@LaunchedEffect
        delay(2_000)
        speedHint = null
    }

    // 清晰度/直链提示 2.5 秒后消失
    LaunchedEffect(linkHint) {
        if (linkHint == null) return@LaunchedEffect
        delay(2_500)
        linkHint = null
    }

    // 右上角时钟：只在分钟变化时更新（写入相同文本不会触发重组），对齐到下一分钟不用秒级轮询
    val clockFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var clockText by remember { mutableStateOf(LocalTime.now().format(clockFormat)) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = LocalTime.now().format(clockFormat)
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    // 迷你进度条：位置心跳 500ms 才来一次，用线性补间把跳变摊平（暂停/拖动时最多补 500ms）
    val miniFraction by animateFloatAsState(
        targetValue = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "miniProgress",
    )

    // 控制条一唤出，右上角时钟就从 70% 变成不透明
    val clockAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else CLOCK_IDLE_ALPHA,
        animationSpec = tween(durationMillis = 220),
        label = "clockAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // 左右键的"抬起"：结束长按连跳。必须在下面"只处理 KeyDown"之前拦掉
                if (event.type == KeyEventType.KeyUp && holdingKey != 0 &&
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                ) {
                    holdingKey = 0
                    stopHoldSeek()
                    return@onPreviewKeyEvent true
                }
                val isCenterKey = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                    event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (isCenterKey && event.type == KeyEventType.KeyUp) {
                    return@onPreviewKeyEvent if (swallowCenterUp) {
                        swallowCenterUp = false
                        true
                    } else {
                        false
                    }
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 片尾自动连播倒计时里按了任何键：用户接手了，取消自动跳（这个键本身照常处理）
                if (autoNextLeft != null) autoNextLeft = null
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            swallowCenterUp = true
                            markInteraction()
                            true
                        } else {
                            false // 交给聚焦中的按钮
                        }
                    }

                    Key.DirectionLeft -> {
                        if (queuePanelOpen) {
                            // 选集抽屉里只上下走：左右不把焦点跳到背后被压暗的控制条
                            true
                        } else if (controlsVisible) {
                            false
                        } else {
                            // 短按退 10 秒，按住不放连续快退（见 startSeekKey）
                            startSeekKey(-1)
                            true
                        }
                    }

                    Key.DirectionRight -> {
                        if (queuePanelOpen) {
                            true
                        } else if (controlsVisible) {
                            false
                        } else {
                            // 短按进 30 秒，按住不放连续快进
                            startSeekKey(+1)
                            true
                        }
                    }

                    Key.DirectionUp, Key.DirectionDown -> {
                        // 隐藏态：上下调音量；显示态：交给焦点系统在按钮间移动
                        if (controlsVisible) false else { adjustVolume(event.key == Key.DirectionUp); true }
                    }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlayPause()
                        true
                    }

                    Key.MediaFastForward -> {
                        seekBy(SEEK_FORWARD_MS)
                        true
                    }

                    Key.MediaRewind -> {
                        seekBy(-SEEK_BACK_MS)
                        true
                    }

                    Key.Back, Key.Escape -> {
                        if (queuePanelOpen) {
                            // 选集抽屉在最上层：先收它，再按一次才是收控制条
                            closeQueuePanel()
                            true
                        } else if (qualityPanelOpen) {
                            closeQualityPanel()
                            true
                        } else if (speedPickerOpen) {
                            // 先收倍速面板（此时焦点在档位胶囊上），再按一次才是收控制条
                            closeSpeedPicker()
                            true
                        } else if (controlsVisible) {
                            controlsVisible = false
                            true
                        } else {
                            exitState.value()
                            true
                        }
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv -> player.setVideoSurfaceView(sv) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AndroidView(
            factory = { subtitleView },
            modifier = Modifier.fillMaxSize(),
            update = { it.setCues(player.currentCues.cues) },
        )

        if (isBuffering && errorText == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(52.dp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }

        errorText?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC1A1B1F))
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("播放失败", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(msg, fontSize = 17.sp, color = Color(0xFFD6D8DE))
                    Spacer(Modifier.height(16.dp))
                    Text("按返回键退出", fontSize = 15.sp, color = Color(0xFF8E929C))
                }
            }
        }

        // 自动连播倒计时期间不显示"已播完"，两张卡叠在一起读起来自相矛盾
        if (playerState == Player.STATE_ENDED && autoNextLeft == null) {
            Text(
                "已播完 · 按 OK 重播",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }

        // seek / 倍速提示都贴底居中；同时出现（长按快进时又改了倍速）就上下排开，不互相盖住
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 132.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            seekHint?.let { (dir, target, _) ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (dir > 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                        contentDescription = if (dir > 0) "快进" else "快退",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatTime(target),
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                    if (durationMs > 0) {
                        Text(
                            " / ${formatTime(durationMs)}",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 20.sp,
                        )
                    }
                }
            }

            speedHint?.let { (hintSpeed, _) ->
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = "倍速",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        speedButtonLabel(hintSpeed),
                        color = Color.White,
                        fontSize = 19.sp,
                    )
                }
            }

            // 清晰度切换 / 直链自愈的提示（"正在重新获取…"这类）
            linkHint?.let { hint ->
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.HighQuality,
                        contentDescription = "画质",
                        tint = Color(0xFF9FC3FF),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(hint, color = Color.White, fontSize = 18.sp)
                }
            }
            // 片尾自动连播：倒计时卡片（按任意键取消，控制条仍可正常用）
            autoNextLeft?.let { left ->
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC101114))
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (left > 0) "$left 秒后播放下一集" else "即将播放下一集",
                        fontSize = 15.sp,
                        color = Color(0xFF9FC3FF),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        queue.getOrNull(queueIndex + 1)?.title.orEmpty().ifBlank { "下一集" },
                        fontSize = 21.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按任意键取消自动连播",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }

        volumeHint?.let { (vol, volMax, _) ->
            val frac = if (volMax > 0) (vol.toFloat() / volMax).coerceIn(0f, 1f) else 0f
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = "音量",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(140.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x4DFFFFFF)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(frac)
                            .background(Color(0xFF9FC3FF)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (volMax > 0) "$vol/$volMax" else "—",
                    fontSize = 13.sp,
                    color = Color.White,
                )
            }
        }

        // 右上角时钟：平时 70% 透明，控制条唤起时不透明（clockAlpha 负责过渡）
        Text(
            clockText,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 40.dp),
            color = Color.White.copy(alpha = clockAlpha),
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(
                // 亮画面上白字会糊掉：压一层淡阴影（不是描边，静止时看不出层次）
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 2f),
                    blurRadius = 8f,
                ),
            ),
        )

        // 迷你进度条：控制条收起时贴在最下沿（设置里可关）；播完/出错时不显示，免得像"还在播"
        if (miniProgressOn && !controlsVisible && errorText == null &&
            playerState != Player.STATE_ENDED && durationMs > 0
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(miniFraction)
                        .height(3.dp)
                        .background(Color(0xFF9FC3FF).copy(alpha = 0.92f)),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // 画质/倍速面板都叠在控制条正上方（同一竖排），收起控制条时跟着一起走
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(visible = qualityPanelOpen, enter = fadeIn(), exit = fadeOut()) {
                    QualityPicker(
                        tiers = tiers,
                        currentDef = playingDef,
                        loading = tiersLoading,
                        onSelect = { selectQuality(it) },
                    )
                }
                AnimatedVisibility(visible = speedPickerOpen, enter = fadeIn(), exit = fadeOut()) {
                    SpeedPicker(
                        currentSpeed = speed,
                        onSelect = { target ->
                            applySpeed(target)
                            closeSpeedPicker()
                        },
                    )
                }
                ControlBar(
                    title = queue.getOrNull(queueIndex)?.title.orEmpty().ifBlank { request.title },
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasSubtitles = textTrackCount > 0,
                    subtitleLabel = subtitleLabel(subtitleIndex, textTrackCount, textTrackNames),
                    hasAudioTracks = audioTrackCount > 1,
                    audioLabel = audioLabel(audioIndex, audioTrackCount),
                    speed = speed,
                    speedPickerOpen = speedPickerOpen,
                    speedButtonFocus = speedButtonFocus,
                    qualityLabel = tierLabel(playingDef),
                    hasQualityOptions = tiers.size > 1,
                    qualityPanelOpen = qualityPanelOpen,
                    qualityButtonFocus = qualityButtonFocus,
                    queueIndex = queueIndex,
                    queueSize = queue.size,
                    queuePanelOpen = queuePanelOpen,
                    queueButtonFocus = queueButtonFocus,
                    playPauseFocus = playPauseFocus,
                    onPlayPause = { togglePlayPause() },
                    onBack10 = { seekBy(-SEEK_BACK_MS) },
                    onForward30 = { seekBy(SEEK_FORWARD_MS) },
                    onCycleSubtitle = { cycleSubtitle() },
                    onCycleAudio = { cycleAudio() },
                    onToggleQualityPanel = {
                        if (qualityPanelOpen) {
                            closeQualityPanel()
                        } else {
                            speedPickerOpen = false
                            queuePanelOpen = false
                            qualityPanelOpen = true
                            markInteraction()
                        }
                    },
                    onToggleSpeedPicker = {
                        if (speedPickerOpen) {
                            closeSpeedPicker()
                        } else {
                            qualityPanelOpen = false
                            queuePanelOpen = false
                            speedPickerOpen = true
                            markInteraction()
                        }
                    },
                    onToggleQueuePanel = {
                        if (queuePanelOpen) {
                            closeQueuePanel()
                        } else {
                            speedPickerOpen = false
                            qualityPanelOpen = false
                            queuePanelOpen = true
                            markInteraction()
                        }
                    },
                )
            }
        }

        // 选集抽屉画在最上层（连控制条一起压暗），遥控上下选、OK 跳播
        if (queuePanelOpen) {
            QueuePanel(
                queue = queue,
                currentIndex = queueIndex,
                onSelect = { index ->
                    startEpisode(index)
                    closeQueuePanel()
                },
            )
        }
    }
}

private fun subtitleLabel(index: Int, count: Int, names: List<String?> = emptyList()): String = when {
    count <= 0 -> "无字幕"
    index < 0 -> "字幕 关"
    else -> {
        // 有名（外挂字幕的 115 标题）就带上：几路字幕只有"2/3"分不清简繁
        val name = names.getOrNull(index)?.takeIf(String::isNotBlank)
        if (name.isNullOrBlank()) "字幕 ${index + 1}/$count"
        else "字幕 ${index + 1}/$count · ${name.take(12)}${if (name.length > 12) "…" else ""}"
    }
}

private fun audioLabel(index: Int, count: Int): String = when {
    count <= 0 -> "音轨"
    index < 0 -> "音轨 默认"
    else -> "音轨 ${index + 1}/$count"
}

/** 播放错误翻译成人话；HTTP 403 在这里出现多半就是直链过期/UA 不匹配 */
private fun describePlaybackError(error: PlaybackException): String {
    val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    return when {
        httpCode == 403 || httpCode == 401 ->
            "地址被拒绝（HTTP $httpCode）· 直链已失效，请在平板上重新投屏"

        httpCode != null -> "服务器返回 HTTP $httpCode"

        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "网络连接失败（检查电视的网络）"

        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "这台电视不支持该视频编码"

        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "封装格式不支持"

        else -> "播放失败（${error.errorCodeName}）"
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    return if (total >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    } else {
        String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}

@Composable
private fun ControlBar(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasSubtitles: Boolean,
    subtitleLabel: String,
    hasAudioTracks: Boolean,
    audioLabel: String,
    speed: Float,
    speedPickerOpen: Boolean,
    speedButtonFocus: FocusRequester,
    qualityLabel: String,
    hasQualityOptions: Boolean,
    qualityPanelOpen: Boolean,
    qualityButtonFocus: FocusRequester,
    queueIndex: Int,
    queueSize: Int,
    queuePanelOpen: Boolean,
    queueButtonFocus: FocusRequester,
    playPauseFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onBack10: () -> Unit,
    onForward30: () -> Unit,
    onCycleSubtitle: () -> Unit,
    onCycleAudio: () -> Unit,
    onToggleQualityPanel: () -> Unit,
    onToggleSpeedPicker: () -> Unit,
    onToggleQueuePanel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xE6000000)),
                ),
            )
            .padding(start = 44.dp, end = 44.dp, top = 40.dp, bottom = 30.dp),
    ) {
        Column {
            Text(
                text = title.ifBlank { "投屏播放" },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(positionMs), fontSize = 16.sp, color = Color(0xFFD6D8DE))
                Spacer(Modifier.width(14.dp))
                ProgressLine(
                    fraction = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(14.dp))
                Text(formatTime(durationMs), fontSize = 16.sp, color = Color(0xFFD6D8DE))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                // 按键整体居中：电视控制条两侧留白对称，不贴着左下角
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvButton(Icons.Filled.Replay10, "退 10 秒", onBack10)
                TvButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = if (isPlaying) "暂停" else "播放",
                    onClick = onPlayPause,
                    focusRequester = playPauseFocus,
                )
                TvButton(Icons.Filled.Forward30, "进 30 秒", onForward30)
                TvButton(
                    icon = Icons.Filled.Subtitles,
                    label = subtitleLabel,
                    enabled = hasSubtitles,
                    onClick = onCycleSubtitle,
                )
                TvButton(
                    icon = Icons.Filled.Audiotrack,
                    label = audioLabel,
                    enabled = hasAudioTracks,
                    onClick = onCycleAudio,
                )
                // 画质：档位列表来自电视自己调 115 接口（平板没带凭据时只有原盘、置灰）
                TvButton(
                    icon = Icons.Filled.HighQuality,
                    label = qualityLabel,
                    enabled = hasQualityOptions,
                    active = qualityPanelOpen,
                    focusRequester = qualityButtonFocus,
                    onClick = onToggleQualityPanel,
                )
                // 倍速：打开的档位面板挂在控制条上方（见 PlayScreen 里那层 AnimatedVisibility）
                TvButton(
                    icon = Icons.Filled.Speed,
                    label = speedButtonLabel(speed),
                    enabled = true,
                    active = speedPickerOpen,
                    focusRequester = speedButtonFocus,
                    onClick = onToggleSpeedPicker,
                )
                // 选集：抽屉在屏幕右侧，只对多集队列有意义
                TvButton(
                    icon = Icons.Filled.PlaylistPlay,
                    label = if (queueSize > 1) "选集 ${queueIndex + 1}/$queueSize" else "选集",
                    enabled = queueSize > 1,
                    active = queuePanelOpen,
                    focusRequester = queueButtonFocus,
                    onClick = onToggleQueuePanel,
                )
            }
        }
    }
}

/**
 * 倍速档位面板：一排胶囊，遥控器左右移动焦点、OK 选定。
 *
 * 打开时焦点直接落在离当前倍速最近的那档——电视上没人愿意先用方向键"找一遍"再按键。
 * 当前倍速不在档位表里（平板可以设到 4.0）时这一排都不点亮，但控制条按钮上的数字仍是真值。
 */
@Composable
private fun SpeedPicker(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    // 只在面板打开时算一次：挑档位的过程中档位不变，焦点不该跟着跑
    val initialIndex = remember { nearestSpeedIndex(currentSpeed) }

    LaunchedEffect(Unit) {
        // 等面板完成一次布局再要焦点（与呼出控制条同一个坑：未布局时 requestFocus 会被静默吞掉）
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101114))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            // 窄屏/低密度盒子上 8 档可能放不下：可横滑，焦点移过去会自动跟着滚
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("倍速", fontSize = 14.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(16.dp))
            PLAYBACK_SPEEDS.forEachIndexed { index, step ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                PickerChip(
                    label = speedChipLabel(step),
                    selected = isCurrentSpeed(currentSpeed, step),
                    focusRequester = if (index == initialIndex) initialFocus else null,
                    onClick = { onSelect(step) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "左右选档位 · OK 确认 · 返回关闭",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}

/** 选项胶囊（倍速/画质共用）：焦点态白框提亮，已选档位常亮一层主色；遥控 OK 直接选中 */
@Composable
private fun PickerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.30f)
                    selected -> Color(0xFF9FC3FF).copy(alpha = 0.32f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(
                width = 2.dp,
                color = when {
                    focused -> Color.White
                    selected -> Color(0xFF9FC3FF).copy(alpha = 0.65f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(50),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = Color.White.copy(alpha = if (selected) 1f else 0.82f),
        )
    }
}

/**
 * 画质面板：一排档位胶囊（原盘 / 1080P / 720P …），遥控左右选、OK 切换。
 *
 * 档位列表是电视自己调 115 接口拿的（平板随投屏带了凭据）；只有原盘时控制条上的「画质」键是灰的。
 */
@Composable
private fun QualityPicker(
    tiers: List<TvTier>,
    currentDef: Int,
    loading: Boolean,
    onSelect: (Int) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    // 打开时焦点落在正在播的那一档；列表之后补全也不挪焦点
    val initialIndex = remember { tiers.indexOfFirst { it.def == currentDef }.coerceAtLeast(0) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101114))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("画质", fontSize = 14.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(16.dp))
            tiers.forEachIndexed { index, tier ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                PickerChip(
                    label = tier.label,
                    selected = tier.def == currentDef,
                    focusRequester = if (index == initialIndex) initialFocus else null,
                    onClick = { onSelect(tier.def) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (loading) "正在获取其它清晰度…" else "左右选清晰度 · OK 切换 · 返回关闭",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}

/**
 * 选集抽屉：右侧竖排列表，遥控器上下选、OK 跳播、返回关闭。
 *
 * 行是普通 Column 而不是 LazyColumn：队列上限几十条，一次全排出来最省事，
 * 也保证方向键在"最后一条之后"不会因为懒加载还没组合而卡住。
 */
@Composable
private fun QueuePanel(
    queue: List<QueueItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val currentFocus = remember { FocusRequester() }

    // 打开时把当前集滚到靠上的位置：几十条的队列里，当前集可能远在屏幕外
    LaunchedEffect(Unit) {
        val rowPx = with(density) { QUEUE_ROW_HEIGHT.toPx() }
        runCatching { scroll.scrollTo((((currentIndex - 2).coerceAtLeast(0)) * rowPx).toInt()) }
        withFrameNanos { }
        runCatching { currentFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(440.dp)
                .background(Color(0xF2101114))
                .padding(vertical = 26.dp),
        ) {
            Text(
                "选集 · 第 ${currentIndex + 1}/${queue.size} 集",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
            ) {
                queue.forEachIndexed { index, item ->
                    QueueRow(
                        index = index,
                        title = item.title.ifBlank { "第 ${index + 1} 集" },
                        current = index == currentIndex,
                        focusRequester = if (index == currentIndex) currentFocus else null,
                        onClick = { onSelect(index) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "上下选择 · OK 播放 · 返回关闭",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** 选集列表的一行：焦点白框、当前集主色；标题长了省略 */
@Composable
private fun QueueRow(
    index: Int,
    title: String,
    current: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.22f)
                    current -> Color(0xFF9FC3FF).copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            )
            .border(
                width = 2.dp,
                color = when {
                    focused -> Color.White
                    current -> Color(0xFF9FC3FF).copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = QUEUE_ROW_HEIGHT)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(index + 1),
            fontSize = 13.sp,
            color = if (current) Color(0xFF9FC3FF) else Color.White.copy(alpha = 0.45f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            fontSize = 16.sp,
            color = if (current) Color.White else Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(10.dp))
            Text("正在播", fontSize = 12.sp, color = Color(0xFF9FC3FF))
        }
    }
}

/** 播放进度细线：电视上不做拖动（MVP），只做"一眼看清播到哪儿了" */
@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x4DFFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF9FC3FF)),
        )
    }
}

/**
 * 控制条按钮：焦点态用亮边框 + 提亮底色表达（电视上"当前选中谁"必须一眼可见），
 * 不可用的按钮压暗到 40%（比如片源没有字幕轨）。[active] 表示它对应的面板正展开着。
 */
@Composable
private fun TvButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val alpha = if (enabled) 1f else 0.4f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(
                    when {
                        focused -> Color.White.copy(alpha = 0.30f)
                        active -> Color(0xFF9FC3FF).copy(alpha = 0.26f)
                        else -> Color.White.copy(alpha = 0.10f)
                    },
                )
                .border(
                    width = 2.dp,
                    color = when {
                        focused -> Color.White
                        active -> Color(0xFF9FC3FF).copy(alpha = 0.65f)
                        else -> Color.Transparent
                    },
                    shape = CircleShape,
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { if (enabled) onClick() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f * alpha))
    }
}
