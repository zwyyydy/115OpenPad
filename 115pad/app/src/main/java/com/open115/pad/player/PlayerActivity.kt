package com.open115.pad.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PanoramaPhotosphere
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.open115.pad.AppContainer
import com.open115.pad.appContainer
import com.open115.pad.data.JsonArray
import com.open115.pad.data.JsonNull
import com.open115.pad.data.JsonObject
import com.open115.pad.data.JsonPrimitive
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.VideoPlayData
import com.open115.pad.data.envData
import com.open115.pad.data.parseVideoHistoryTime
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.parseVideoPlayResponse
import com.open115.pad.data.parseFileDownloadUrl
import com.open115.pad.ui.theme.Open115Theme
import com.open115.pad.util.APP_USER_AGENT
import com.open115.pad.util.Format
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue

internal data class SubtitleCfg(val url: String, val mime: String, val language: String?, val title: String?)

/** 播放模式：顺序（播完下一集，末集停止）/ 单片循环 / 随机（剩余未播条目随机） */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单片循环"),
    SHUFFLE("随机播放"),
}

/**
 * 「原盘」档位的伪清晰度值。
 * 它不在 `video_url` 列表里，而是单独走 `open/ufile/downurl` 拿下载直链来播
 * （= 115master 的 Ultra 画质）。取值避开 115 真实档位（1~5）与原画（100）。
 */
private const val DEF_ORIGINAL_FILE = 999

/** 续播位置距片尾不足这么多毫秒就认为"已经播完"，从头开始（见 resumePos） */
private const val RESUME_END_MARGIN_MS = 1000L

private fun defLabel(def: Int): String = when (def) {
    1 -> "标清"; 2 -> "高清"; 3 -> "超清"; 4 -> "1080P"; 5 -> "4K"
    100 -> "原画"; DEF_ORIGINAL_FILE -> "原盘"; else -> "清晰度 $def"
}

/** 按文件名后缀给直链一个 mime；认不出来的返回 null，交给 ExoPlayer 嗅探容器 */
private fun mimeOfFileName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
    "mp4", "m4v", "mov" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> null
}

// ---- 播放地址自愈参数（403 类错误的"换地址续播"重试预算）----
/** 单轮故障最多换几次地址；用完才降级/报错 */
private const val REFRESH_MAX_ATTEMPTS = 4
/** 递退步长：第 n 次重试前多等 n * step，给上游/CDN 恢复时间，同时避开 115 接口频控 */
private const val REFRESH_BACKOFF_STEP_MS = 1200L
/** 距上次自愈超过此时长，视为"新一轮故障"，重新补满重试预算（长片播放场景） */
private const val REFRESH_NEW_ROUND_GAP_MS = 300_000L

/** 切集节流：连续狂点会让底层解码器反复销毁重建，500ms 内只受理一次 */
private const val EPISODE_SWITCH_THROTTLE_MS = 500L

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PICK_CODE = "pick_code"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_SOURCE_URI = "source_uri"

        /**
         * @param playlist 当前视图的播放列表（可为空：此时上一集/下一集均置灰）。
         *                 经 JSON 字符串传递；条目只含 pick_code 与标题，千集量级也只有百 KB 级。
         * @param index    当前条目在 playlist 中的下标。
         */
        fun intent(
            context: Context,
            pickCode: String,
            name: String,
            playlist: List<PlaylistEntry> = emptyList(),
            index: Int = 0,
        ): Intent {
            val json = runCatching {
                Json.encodeToString(ListSerializer(PlaylistEntry.serializer()), playlist)
            }.getOrNull()
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PICK_CODE, pickCode)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PLAYLIST, json)
                .putExtra(EXTRA_INDEX, index)
        }

        /**
         * 播本地/外部文件（已下载的文件、别的应用分享过来的视频）。
         *
         * ⚠️ 这类源**没有任何 115 接口可用**：播放地址、字幕列表、观看记录全都拿不到，
         * 所以加载链路是整条绕开的，不是把 pick_code 换成 uri 就行。
         * 代价是没有跨设备观看记录（只在本机记续播位置）、没有在线字幕、没有转码清晰度。
         *
         * 不传 mime：播放时让 ExoPlayer 嗅探容器，比外部声明的类型更可靠
         * （DownloadManager 常报 application/octet-stream，MimeTypeMap 又不认 mkv/m2ts）。
         */
        fun localIntent(context: Context, uri: String, name: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SOURCE_URI, uri)
                .putExtra(EXTRA_NAME, name)

        /**
         * 取要播的 Uri，两条来源都要认：
         * - **本应用自己发起的显式 Intent**（传输中心点已下载的文件）→ Uri 放在 extra 里，
         *   这种 Intent 没有 action，只看 action 会漏掉，直接 finish 掉什么都没发生
         * - **外部调用** → `ACTION_VIEW` 取 `data`（文件管理器点开、浏览器点视频链接），
         *   `ACTION_SEND` 取 `EXTRA_STREAM`（分享视频走这个）
         *
         * ⚠️ EXTRA_STREAM 必须用 IntentCompat 取：`getParcelableExtra(String)` 在 API 33+ 已废弃。
         */
        private fun Intent.sourceUriOrNull(): String? {
            getStringExtra(EXTRA_SOURCE_URI)?.takeIf { it.isNotBlank() }?.let { return it }
            return when (action) {
                Intent.ACTION_VIEW -> data?.toString()
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
                else -> null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 两种来源二选一：云盘（pick_code）或本地/外部（Uri）。都没有才退出 ——
        // 以前只看 pick_code，外部应用传 Uri 进来会被直接 finish 掉。
        val sourceUri = intent.sourceUriOrNull()
        val pickCode = intent.getStringExtra(EXTRA_PICK_CODE)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        if (sourceUri == null && pickCode.isNullOrBlank()) {
            finish()
            return
        }
        val playlist = intent.getStringExtra(EXTRA_PLAYLIST)?.let { raw ->
            runCatching {
                Json.decodeFromString(ListSerializer(PlaylistEntry.serializer()), raw)
            }.getOrNull()
        } ?: emptyList()
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
            .coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        setContent {
            Open115Theme {
                PlayerScreen(
                    container = appContainer,
                    initialPickCode = pickCode.orEmpty(),
                    initialName = name,
                    initialLocalUri = sourceUri,
                    playlist = playlist,
                    initialIndex = index,
                    onBack = { finish() },
                )
            }
        }
    }
}

/**
 * 软解优先的解码器选择器（设置页「软件解码」开启时使用）。
 *
 * 只做**排序**、不做过滤：过滤成"仅软解"在个别编码上会得到空列表（例如某些设备的 AV1
 * 只有硬解），那样是直接起播失败，比花屏更难排查。软解排前面 + 解码器回退兜底，
 * 软解不存在或失败时仍能落回硬解。
 */
private val SOFTWARE_FIRST_DECODER = MediaCodecSelector { mime, secure, tunneling ->
    val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
    val sorted = infos.sortedBy { it.hardwareAccelerated }
    // 候选与最终顺序是排障的关键线索（花屏/黑屏时要能确认到底走的哪个解码器）。
    // 只在解码器初始化时打一行：adb logcat -s PlayerDecode
    android.util.Log.d(
        "PlayerDecode",
        "软解优先 $mime 候选=[${infos.joinToString { "${it.name}(hw=${it.hardwareAccelerated})" }}]" +
            " 顺序=[${sorted.joinToString { it.name }}]",
    )
    sorted
}

private data class PlayerPrefValues(
    val cacheEnabled: Boolean,
    val cacheMaxMb: Int,
    val speedBoost: Float,
    val seekSeconds: Int,
    val miniProgress: Boolean,
    /** 软解优先（设置页可改，重进播放器生效） */
    val softwareDecode: Boolean,
    // 右上角状态栏四项（设置页「界面显示」分组）
    val showClock: Boolean,
    val showBattery: Boolean,
    val showNetSpeed: Boolean,
    val showSpecBadge: Boolean,
)

@Composable
fun PlayerScreen(
    container: AppContainer,
    initialPickCode: String,
    initialName: String,
    playlist: List<PlaylistEntry>,
    initialIndex: Int,
    onBack: () -> Unit,
    /** 本地/外部源（见 [PlayerActivity.localIntent]）。非空时整条云盘链路绕开 */
    initialLocalUri: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 本地/外部源。**不可变**：本地播放没有「切集」概念，播放队列恒为空。
     */
    val localUri = initialLocalUri

    // 当前条目：切集时改这两个状态，下方 LaunchedEffect(itemKey) 会自动整套重载
    var currentPickCode by remember { mutableStateOf(initialPickCode) }
    /**
     * 「按条目记住偏好」的 key：云盘用 pick_code，本地用 uri。
     * 直接拿 currentPickCode 当 key 的话，本地源恒为空串 ⇒ 所有本地片共享一份
     * 手动覆盖状态（VR 模式被一部片改过，下一部也被当成改过）。
     */
    val itemKey = localUri ?: currentPickCode
    var currentIndex by remember { mutableStateOf(initialIndex) }
    var currentName by remember { mutableStateOf(initialName) }
    /**
     * 可变播放队列：列表抽屉里删除条目会改这里（原始参数只在初始化时读一次）。
     * 删除当前播放项的顺延/防闪退逻辑见 deletePlaylistItem。
     */
    var playQueue by remember { mutableStateOf(playlist) }
    /**
     * 切集后的起播位置覆盖值：
     * - null → 沿用该视频自己的观看记录（首次从文件页进入时行为不变）
     * - 0L   → 切集时强制从头播（需求：切集后进度归零）。
     * 若希望切集也续播，把 switchEpisode 里的 `pendingStartMs = 0L` 去掉即可。
     */
    var pendingStartMs by remember { mutableStateOf<Long?>(null) }
    var lastEpisodeSwitchAt by remember { mutableStateOf(0L) }

    var data by remember { mutableStateOf<VideoPlayData?>(null) }
    var currentDef by remember { mutableStateOf(0) }
    var subtitles by remember { mutableStateOf<List<SubtitleCfg>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 播放中缓冲（不含首次加载：起播全屏加载由 loading 接管）；短暂抖动不打扰，延迟 400ms 才显示 */
    var rebuffering by remember { mutableStateOf(false) }
    var showBuffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(1f) }
    // -1 = 默认音轨；其余为 multitrack_list 下标，播放地址追加 audio_track=N
    var currentAudio by remember { mutableStateOf(-1) }
    // 字幕加载失败可能导致整个媒体准备失败：失败后自动降级为无字幕重试
    var useSubs by remember { mutableStateOf(true) }
    var controllerVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    // 进度显示三件套（毫秒）：已播 / 总时长 / 已缓冲，两种形态共用
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    // 正在拖拽交互式进度条：期间冻结进度轮询，避免与播放器内部进度互相拉扯
    var isDraggingSeek by remember { mutableStateOf(false) }
    // 在线字幕（迅雷搜索加载）与搜索框开关
    var onlineSubs by remember { mutableStateOf<List<SubtitleCfg>>(emptyList()) }
    var onlineSubOriginal by remember { mutableStateOf<ByteArray?>(null) }
    var onlineSubCfg by remember { mutableStateOf<SubtitleCfg?>(null) }
    var onlineSubExt by remember { mutableStateOf("srt") }
    var subOffsetMs by remember { mutableStateOf(0L) }
    var showSubSearch by remember { mutableStateOf(false) }
    var subsEnabled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var subsMenuOpen by remember { mutableStateOf(false) }

    // 右下角控制排：交互后显示 4 秒自动淡出
    var controlRowVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var controlRowUntil by remember { mutableStateOf(0L) }

    // 手势 HUD / 反馈状态
    var gestureHudSide by remember { mutableStateOf(0) } // 1 亮度(右) -1 音量(左) 0 无
    var gestureHudText by remember { mutableStateOf<String?>(null) }
    var gestureHudProgress by remember { mutableStateOf<Float?>(null) }
    var gestureHudAt by remember { mutableStateOf(0L) }
    var seekPreviewTarget by remember { mutableStateOf<Long?>(null) }
    var seekPreviewDur by remember { mutableStateOf(0L) }
    var seekPreviewDelta by remember { mutableStateOf(0) }
    var seekPreviewBoost by remember { mutableStateOf(false) }

    // ---- 播放模式（顺序/单片循环/随机）与画面旋转 ----
    var playMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(PlayMode.SEQUENTIAL) }
    // 已播完条目的 pick_code 集合：随机模式从"剩余未播"里选
    val playedKeys = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // 画面旋转角（0/90/180/270，顺时针）
    var videoRotation by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    // STATE_ENDED 信号（listener 内自增，LaunchedEffect 里按模式续播）
    var endedTick by remember { mutableStateOf(0) }
    // 视频帧宽高比来源（onVideoSizeChanged）：旋转后做等比适配用
    var vsVideoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var dtFx by remember { mutableStateOf<DoubleTapFx?>(null) }
    var speedCapsule by remember { mutableStateOf<String?>(null) }
    var controllerHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ---- 屏幕防误触锁 ----
    var screenLocked by remember { mutableStateOf(false) }
    var lockDimmed by remember { mutableStateOf(false) }
    var lockWakeAt by remember { mutableStateOf(0L) }
    // ---- 播放列表抽屉 ----
    var playlistOpen by remember { mutableStateOf(false) }

    // ---- VR 视角（180/360 立体素材实时反投影成平面观看）----
    /**
     * 当前 VR 模式；`null` = 关闭。
     * 关闭时渲染路径完全走原来的 TextureView，一行逻辑都不受影响。
     */
    var vrMode by remember { mutableStateOf<VrMode?>(null) }
    val vrState = remember { VrViewState() }
    /**
     * GL 视图实例。用 AtomicReference 是因为陀螺仪回调来自传感器线程，
     * 而视图的创建/销毁发生在组合里。
     */
    val vrViewRef = remember { java.util.concurrent.atomic.AtomicReference<VrViewportView?>(null) }
    val vrGyro = remember { VrGyroController(context) }
    var vrGyroOn by remember { mutableStateOf(false) }
    var vrHudText by remember { mutableStateOf<String?>(null) }
    var vrMenuOpen by remember { mutableStateOf(false) }
    /**
     * 滑杆显示值。必须单独放一份 Compose 状态：`VrViewState` 里的字段是普通变量
     * （故意不走快照系统，免得每帧都触发重组），Slider 是受控组件，
     * 读普通变量的话拖动时滑块根本不会动。
     */
    var vrPanniniUi by remember { androidx.compose.runtime.mutableFloatStateOf(VrViewState.DEFAULT_PANNINI) }
    /**
     * 竖屏 VR 视窗档位（**只在竖屏生效**，横屏恒满屏）。
     * 横屏不给档位：Pannini 已经压在长轴（水平）上，实测 4:3~21:9 最差边缘拉伸
     * 都锁在 1.65~1.70×，长宽比不是瓶颈，收窄只会白丢画面宽度。详见 [VrWindow]。
     */
    var vrWindow by remember { mutableStateOf(VrWindow.FULL) }
    /** 被用户手动改过设置的条目 pick_code：改过就不再让自动识别覆盖 */
    var vrTouchedFor by remember { mutableStateOf<String?>(null) }
    /** 持久化值："" = 自动识别，"off" = 明确关闭，否则是 VrMode.name */
    val vrStoredMode by container.playerPrefs.vrMode.collectAsState(initial = null)
    val vrStoredEye by container.playerPrefs.vrRightEye.collectAsState(initial = false)
    val vrStoredGyro by container.playerPrefs.vrGyro.collectAsState(initial = null)
    /** 边缘畸变抑制强度（Pannini d） */
    val vrStoredPannini by container.playerPrefs.vrPanniniD.collectAsState(initial = null)
    /** 竖屏 VR 视窗档位名（`VrWindow.name`） */
    val vrStoredWindow by container.playerPrefs.vrWindow.collectAsState(initial = null)
    /** 分辨率能不能猜出 VR 布局（决定 VR 按钮是否出现） */
    val vrGuess = remember(vsVideoSize) {
        VrDetector.guess(vsVideoSize.width, vsVideoSize.height)
    }

    /** 把当前视角推给 GL 线程：只换 uniform，不重建任何东西 */
    fun pushVrParams() {
        vrViewRef.get()?.updateParams(vrState.snapshot())
    }

    // 自动识别 + 手动兜底。
    //
    // ⚠️ 持久化的模式是「**当素材确实是 VR 时**用哪种布局」，不是「所有视频都开 VR」。
    //    早期版本漏了 `guess == null` 这一档，导致用户手动选过一次模式之后，
    //    那个模式被无条件套用到**后面每一条视频**（包括普通 16:9 视频）——
    //    表现就是"播完 VR 片再播普通片，VR 键还在、原手势全失效"。
    //    判定必须前置：分辨率猜不出 VR 布局 ⇒ 一律关闭。
    LaunchedEffect(vsVideoSize, vrStoredMode, itemKey) {
        val stored = vrStoredMode ?: return@LaunchedEffect
        if (vrTouchedFor == itemKey) return@LaunchedEffect
        val guess = VrDetector.guess(vsVideoSize.width, vsVideoSize.height)
        val next = when {
            stored == VR_MODE_OFF -> null
            guess == null -> null
            stored.isEmpty() -> guess
            else -> VrMode.entries.firstOrNull { it.name == stored } ?: guess
        }
        if (next != vrMode) {
            vrMode = next
            next?.let { m ->
                vrState.mode = m
                vrState.resetForNewMode()
                pushVrParams()
            }
        }
    }

    // 取眼 / 畸变抑制偏好随模式生效
    LaunchedEffect(vrMode, vrStoredEye, vrStoredPannini) {
        vrState.rightEye = vrStoredEye
        // 用户在当前这条片子里拖过滑杆就不再回写，否则拖动过程中会被覆盖打断
        if (vrTouchedFor != itemKey) {
            vrStoredPannini?.let {
                vrState.setPannini(it)
                vrPanniniUi = vrState.panniniD
            }
        }
        pushVrParams()
    }

    // 陀螺仪开关与持久化首值同步（只在未手动改过的条目上套用）
    LaunchedEffect(vrStoredGyro, itemKey) {
        val v = vrStoredGyro ?: return@LaunchedEffect
        if (vrTouchedFor != itemKey) vrGyroOn = v
    }

    // 视窗档位是全局偏好：不随条目走，也不存在"拖到一半被回写打断"的问题，
    // 所以直接单向同步即可，不需要 vrTouchedFor 那套保护
    LaunchedEffect(vrStoredWindow) {
        VrWindow.entries.firstOrNull { it.name == vrStoredWindow }?.let { vrWindow = it }
    }

    // 陀螺仪生命周期：只在 VR 模式 + 开关打开 + 设备确实有传感器时才注册。
    // key 用 gyroActive 而不是 (vrGyroOn, vrMode)：后者在「VR180 → 360」这类
    // 仍在 VR 内的模式切换时会重启传感器，而 start() 会把基准清零 ⇒ 视角被强制复位，
    // 用户会觉得"只是换了个投影，怎么朝向也跳了"。
    val gyroActive = vrGyroOn && vrMode != null && vrGyro.available
    DisposableEffect(gyroActive) {
        if (gyroActive) {
            vrGyro.displayRotation =
                (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
                    ?: android.view.Surface.ROTATION_0
            vrGyro.onRotate = { m ->
                vrState.applyGyro(m)
                // GLSurfaceView.requestRender 是线程安全的，可以直接从传感器线程调，
                // 不用绕主线程队列（陀螺仪 200Hz，绕一圈会明显滞后）
                pushVrParams()
            }
            vrGyro.start()
        }
        onDispose {
            vrGyro.onRotate = null
            vrGyro.stop()
        }
    }

    // 退出播放器时确保传感器不再持有监听
    DisposableEffect(Unit) {
        onDispose {
            vrGyro.stop()
            vrViewRef.set(null)
        }
    }

    /**
     * 抽屉列表的滚动状态：**必须提升到抽屉之外**。
     *
     * 抽屉收起时 AnimatedVisibility 会把内容移出组合，状态若建在抽屉内部，
     * 每次展开都是新实例 → 一律回到顶部（长列表下当前集被甩出视野，正是那个
     * "切集/删除后回弹到 Index 0"的 bug）。放这里再配 rememberSaveable，
     * 收起/展开、列表数据轻微重组都保留滚动偏移。
     */
    val playlistListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = LazyListState.Saver,
    ) { LazyListState() }

    /**
     * 跟随当前播放项：切集（自动播完续播 / 上下集 / 随机 / 列表点播 / 删前移）或刚展开
     * 抽屉时，把当前项平滑滚到视口正中。
     *
     * 三条防打架 / 防偏差规则：
     * ① 用户正在手动滑动（含松手后的惯性）时先让位——等它停下再跟，最多等 3 秒；
     *    等不到（用户还在翻）就放弃这一次，避免"人正往上翻、程序硬拉回去"。
     * ② 全程用 animateScrollToItem / animateScrollBy 平滑滚动，没有瞬移；
     *    滚动途中用户手指按下，Compose 的输入优先级会直接接管并取消动画。
     * ③ 抽屉刚展开时首帧的视口尺寸还没算准（实测会让目标项偏出一行），所以滚完
     *    按目标项的**实际位置**复核一次，偏差超过半行就用 animateScrollBy 校正；
     *    两轮封顶，避免来回抖动。
     */
    LaunchedEffect(playlistOpen, currentIndex, playQueue.size) {
        if (!playlistOpen || playQueue.isEmpty()) return@LaunchedEffect
        if (playlistListState.isScrollInProgress) {
            val stopped = withTimeoutOrNull(3000) {
                snapshotFlow { playlistListState.isScrollInProgress }.first { !it }
            }
            if (stopped == null) return@LaunchedEffect
        }
        // 抽屉刚出现时列表还没布局：拿不到行高、滚动也会被丢弃，等一帧再算
        if (playlistListState.layoutInfo.visibleItemsInfo.isEmpty()) {
            withTimeoutOrNull(500) {
                snapshotFlow { playlistListState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            }
        }
        val target = currentIndex.coerceIn(0, playQueue.lastIndex)
        repeat(2) { pass ->
            val info = playlistListState.layoutInfo
            // 行高取任一可见项（列表行同高）；一屏放得下就没有跟随可言，直接不做
            val itemH = info.visibleItemsInfo.firstOrNull()?.size ?: return@LaunchedEffect
            val viewportH = info.viewportSize.height
            if (itemH <= 0 || info.totalItemsCount * itemH <= viewportH) return@LaunchedEffect
            if (pass == 0) {
                // 先按"行高/视口高"估一个居中偏移滚过去（负值 = 目标项落在视口顶部之下）
                runCatching { playlistListState.animateScrollToItem(target, (itemH - viewportH) / 2) }
            }
            // 复核：目标项中心与视口中心的偏差，超过半行就补一次
            val cur = playlistListState.layoutInfo
            val t = cur.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
            val delta = (t.offset + t.size / 2) - cur.viewportSize.height / 2
            if (abs(delta) <= t.size / 2) return@LaunchedEffect
            runCatching { playlistListState.animateScrollBy(delta.toFloat()) }
        }
    }

    // ---- 播放列表删除的轻量提示（替代 snackbar，播放界面无 SnackbarHost）----
    var playerToast by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 播放地址签名有时效（实测约 30+ 分钟），且 HLS 分片签名过期同样表现为 403。
    // 自愈策略：允许连续换几次地址（带递退间隔），而不是一次不成就放弃——
    // 上游/CDN 抖动往往几秒内自愈，旧逻辑 30 秒冷却会把可恢复的抖动直接变成硬报错。
    var lastAddressRefreshAt by remember { mutableStateOf(0L) }
    var refreshAttempts by remember { mutableStateOf(0) }

    // 播放器参数先读取完再构建播放器，避免播放中途被重建打断
    var pv by remember { mutableStateOf<PlayerPrefValues?>(null) }
    /** 播放器使用的带宽计（remember(prefs) 块内赋值），状态栏网速读它的估算值 */
    var bandwidthMeterRef by remember { mutableStateOf<DefaultBandwidthMeter?>(null) }
    LaunchedEffect(Unit) {
        subsEnabled = container.playerPrefs.subtitlesEnabled.first()
        pv = PlayerPrefValues(
            cacheEnabled = container.playerPrefs.cacheEnabled.first(),
            cacheMaxMb = container.playerPrefs.cacheMaxMb.first(),
            speedBoost = container.playerPrefs.speedBoost.first(),
            seekSeconds = container.playerPrefs.seekSeconds.first(),
            miniProgress = container.playerPrefs.alwaysShowMiniProgress.first(),
            softwareDecode = container.playerPrefs.softwareDecode.first(),
            showClock = container.playerPrefs.showClock.first(),
            showBattery = container.playerPrefs.showBattery.first(),
            showNetSpeed = container.playerPrefs.showNetSpeed.first(),
            showSpecBadge = container.playerPrefs.showSpecBadge.first(),
        )
    }
    val prefs = pv
    if (prefs == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    val player = remember(prefs) {
        // 独立带宽计：状态栏实时网速的数据源（Builder.setBandwidthMeter 注入后全程累计估算）
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val httpFactory = DefaultHttpDataSource.Factory()
            // ⚠️ 必须用 APP_USER_AGENT：115 的下载直链与「申请它时的 UA」绑定（见 Util.kt），
            // 原盘档位拿的是下载直链，这里 UA 不一致会被 CDN 判 403。转码流没这个校验。
            .setUserAgent(APP_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val mediaFactory = if (prefs.cacheEnabled) {
            DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(PlayerCache.get(context, prefs.cacheMaxMb.toLong() * 1024L * 1024L))
                    .setUpstreamDataSourceFactory(upstream)
                    // 刻意【不】改写缓存 key。
                    // 早期为"同一会话内回退零秒播"把 query 里的签名参数去掉，代价是 HLS 播放列表
                    // 也命中旧缓存：换地址拿到的新列表因 key 相同而读不到，缓存继续吐出带【过期签名】
                    // 的旧分片地址 → 跳到尚未缓冲的区域必然 403，且换几次地址都救不回来。
                    // 实测（同文件同位置对照）：去掉 key 改写后 403 归零、正常起播。
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )
        } else {
            DefaultMediaSourceFactory(upstream)
        }
        bandwidthMeterRef = bandwidthMeter // 状态栏网速读它的估算值
        // 解码器：默认走系统默认顺序（硬解优先）；开了「软件解码」把设备自带的软解排到前面。
        // 两种模式都打开解码器回退：首选解码器报错时自动换下一个（硬解不兼容的片源，
        // 默认模式下也能落到软解，不至于直接播不了）。
        // 用 SdrOutputRenderersFactory 而不是 DefaultRenderersFactory：本播放器走 TextureView
        // 自合成画面，HDR 片源必须让解码器先转 SDR，否则会泛白成灰雾（见该文件注释）。
        val renderersFactory = SdrOutputRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .apply { if (prefs.softwareDecode) setMediaCodecSelector(SOFTWARE_FIRST_DECODER) }
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaFactory)
            .setBandwidthMeter(bandwidthMeter)
            .build()
    }

    // 手势指示器
    var indicator by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var indicatorAt by remember { mutableStateOf(0L) }
    val setIndicator: (String, Float?) -> Unit = { text, progress ->
        indicator = text to progress
        indicatorAt = System.currentTimeMillis()
    }
    LaunchedEffect(indicator, indicatorAt) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }

    /**
     * 续播位置贴着片尾时直接从头播。
     *
     * 115 的观看历史经常把进度记成"刚好播完"（例如 96.9s 的片子记为 97s），带这种位置
     * 起播/切档会 READY 之后立刻 ENDED → 黑屏，用户感知就是"走原盘会报错"，
     * 其实换任何清晰度都一样。距片尾不足 1 秒就归零。
     *
     * @param durationMs 总时长；未知（0 或负数）时原样返回，避免误伤。
     */
    fun resumePos(pos: Long, durationMs: Long = player.duration): Long =
        if (durationMs > 0 && pos >= durationMs - RESUME_END_MARGIN_MS) 0L else pos.coerceAtLeast(0L)

    /**
     * 取原始文件的下载直链（原盘档位的播放源）。
     * 失败返回 null，调用方负责提示/回退——不抛异常是因为它只影响一个可选档位，
     * 不该把整条起播链路打断。
     */
    suspend fun fetchOriginalUrl(pickCode: String): String? = runCatching {
        parseFileDownloadUrl(container.openApi.downUrl(pickCode))
    }.getOrNull()

    /**
     * @param mime 传 null 表示不声明类型，由 ExoPlayer 嗅探容器。
     *             原盘直链不能套 m3u8——那会让 ExoPlayer 按 HLS 去解析 mp4/mkv，直接失败。
     */
    fun buildMediaItem(url: String, mime: String?): MediaItem {
        val cfgs = if (useSubs && subsEnabled) (subtitles + onlineSubs).map { s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                .setMimeType(s.mime)
                .setLanguage(s.language)
                .setLabel(s.title)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        } else emptyList()
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .setSubtitleConfigurations(cfgs)
            .build()
    }

    suspend fun buildItem(def: Int): Pair<Int, MediaItem>? {
        val d = data ?: return null
        // 原盘：绕开 115 的转码/切片，直接播原始文件
        if (def == DEF_ORIGINAL_FILE) {
            val raw = fetchOriginalUrl(currentPickCode) ?: return null
            return DEF_ORIGINAL_FILE to buildMediaItem(raw, mimeOfFileName(d.fileName))
        }
        val entry = d.videoUrls.firstOrNull { it.definition == def } ?: return null
        var trackUrl = entry.url
        if (currentAudio >= 0) {
            trackUrl += (if (trackUrl.contains('?')) "&" else "?") + "audio_track=$currentAudio"
        }
        // 115 的转码地址没有 .m3u8 后缀，必须显式声明 HLS 类型，
        // 否则 ExoPlayer 会按普通容器解析导致 PARSING_CONTAINER_UNSUPPORTED
        return entry.definition to buildMediaItem(trackUrl, MimeTypes.APPLICATION_M3U8)
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // 播放结束信号：续播决策在 loadEpisodeAt 之后的 LaunchedEffect(endedTick) 里
                // （局部函数必须先声明后使用，listener 这里拿不到 switchEpisode）
                if (state == Player.STATE_ENDED) endedTick++
                // 缓冲态驱动"缓冲中"动效（起播时的 BUFFERING 由 loading 全屏接管）
                rebuffering = state == Player.STATE_BUFFERING
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                vsVideoSize = videoSize
            }

            override fun onPlayerError(err: PlaybackException) {
                val httpFail = err.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                val now = System.currentTimeMillis()
                // 距上次自愈足够久 → 视为新一轮故障，补满重试预算
                if (now - lastAddressRefreshAt > REFRESH_NEW_ROUND_GAP_MS) refreshAttempts = 0
                if (httpFail && refreshAttempts < REFRESH_MAX_ATTEMPTS) {
                    // 播放地址/分片签名过期（HTTP 403）→ 换新地址续播。
                    // 带重试预算 + 递退间隔：上游抖动常在几秒内自愈，
                    // 旧逻辑"30 秒内只换一次"会把可恢复的抖动直接变成硬报错。
                    lastAddressRefreshAt = now
                    refreshAttempts += 1
                    val attempt = refreshAttempts
                    scope.launch {
                        try {
                            // 首次立刻重试；之后递退等待，避免打爆 115 频控
                            if (attempt > 1) delay(attempt * REFRESH_BACKOFF_STEP_MS)
                            // 原盘档位的地址不是 videoPlay 给的：直接重取直链（buildItem 内部会再 downUrl 一次），
                            // 拿到带新签名的地址续播。其余档位要刷 videoPlay 才能拿到新的分片地址。
                            if (currentDef != DEF_ORIGINAL_FILE) {
                                val fresh = parseVideoPlayResponse(container.openApi.videoPlay(currentPickCode))
                                data = fresh
                            } else {
                                setIndicator("原盘地址已刷新", null)
                            }
                            val pos = resumePos(player.currentPosition)
                            val built = buildItem(currentDef)
                            if (built == null) {
                                error = "播放失败（${err.errorCodeName}）：地址刷新后仍不可用"
                                return@launch
                            }
                            player.setMediaItem(built.second, pos)
                            player.prepare()
                            player.playWhenReady = true
                        } catch (e: Exception) {
                            // 取址本身就失败（网络断了等）→ 立刻报错。
                            // 不能静默等下一次 onPlayerError：此时没投递新媒体，播放器停在 IDLE 不会再回调，
                            // 界面会卡住且没有任何提示。
                            error = "播放失败（${err.errorCodeName}）：${e.message ?: "地址刷新失败"}"
                        }
                    }
                    return
                }
                // 原盘档解码失败（典型：原文件是 HEVC Main10 / 4K 之类的高规格编码，
                // 设备的解码器声明能力不覆盖 —— ExoPlayer 给 DECODING_FAILED 且
                // format_supported=NO_EXCEEDS_CAPABILITIES）→ 回退到转码最高档。
                // 这类失败换直链地址没有意义：编码参数本身就播不了；而 115 的转码档恒为
                // 8bit AVC，设备基本都放得动。只自动回退一次，且把偏好改回非原盘，
                // 否则连播的每一集都会重复踩同一个坑。
                val decodeFail = err.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    err.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (decodeFail && currentDef == DEF_ORIGINAL_FILE) {
                    val fallbackDef = data?.videoUrls
                        ?.map { it.definition }
                        ?.filter { it != DEF_ORIGINAL_FILE }
                        ?.maxOrNull()
                    if (fallbackDef != null) {
                        scope.launch {
                            runCatching { container.playerPrefs.setPreferOriginal(false) }
                            val pos = resumePos(player.currentPosition)
                            val built = buildItem(fallbackDef)
                            if (built == null) {
                                error = "播放失败（${err.errorCodeName}）：原盘与转码均不可用"
                                return@launch
                            }
                            currentDef = fallbackDef
                            player.setMediaItem(built.second, pos)
                            player.prepare()
                            player.playWhenReady = true
                            setIndicator("设备不支持原盘编码，已切回${defLabel(fallbackDef)}", null)
                        }
                        return
                    }
                }
                // 预算用完（或非 403 错误）→ 走原有的字幕降级 → 终态报错链路
                if (useSubs && subtitles.isNotEmpty()) {
                    useSubs = false
                    scope.launch {
                        val pos = resumePos(player.currentPosition)
                        val built = buildItem(currentDef)
                        if (built != null) {
                            player.setMediaItem(built.second, pos)
                            player.prepare()
                            player.playWhenReady = true
                        } else {
                            error = "播放失败（${err.errorCodeName}）"
                        }
                    }
                } else {
                    val cause = err.cause?.message?.let { " / $it" } ?: ""
                    error = "播放失败（${err.errorCodeName}）：${err.message ?: "未知错误"}$cause"
                }
            }
        }
        player.addListener(listener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        })
        // 调试日志：adb logcat -s EventLogger 可看完整播放器事件
        player.addAnalyticsListener(EventLogger())
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun setSubsEnabled(v: Boolean) {
        subsEnabled = v
        scope.launch { container.playerPrefs.setSubtitlesEnabled(v) }
        // 重建媒体应用字幕显隐（记住进度）
        scope.launch {
            val pos = resumePos(player.currentPosition)
            val built = buildItem(currentDef)
            if (built != null) {
                player.setMediaItem(built.second, pos)
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    fun pulseControlRow() {
        controlRowUntil = System.currentTimeMillis() + 4000
        controlRowVisible = true
    }

    /**
     * 切换上一集 / 下一集。
     * @param delta -1 = 上一集，+1 = 下一集
     *
     * 边界与防抖：
     * - 越界直接忽略（按钮此时也已置灰，这里再兜一层，防住外部误调）
     * - 500ms 节流：连续狂点会让底层解码器反复销毁重建
     *
     * 切集动作：改 currentPickCode / currentIndex 触发整套重载；期间立即静音并清空进度显示，
     * 会话级状态（自愈重试预算、在线字幕、字幕偏移、音轨选择）回到初始，避免串集；
     * 最后重置控制栏的 4 秒淡出计时（点击即视为活跃交互）。
     */
    /** 切到队列指定下标并整套重置会话状态（上/下一集、列表点播、删除顺延共用）。 */
    fun loadEpisodeAt(index: Int) {
        currentIndex = index
        currentPickCode = playQueue[index].pc
        currentName = playQueue[index].fn

        // 需求：切集后进度归零、总时长等待元数据、缓冲中
        pendingStartMs = 0L
        positionMs = 0L
        durationMs = 0L
        bufferedMs = 0L
        isDraggingSeek = false
        error = null
        loading = true

        // 会话级状态清零，避免把上一集的痕迹带过来
        refreshAttempts = 0
        lastAddressRefreshAt = 0L
        useSubs = true
        subtitles = emptyList()
        onlineSubs = emptyList()
        onlineSubOriginal = null
        onlineSubCfg = null
        subOffsetMs = 0L
        subsMenuOpen = false
        // 各集音轨数量不同，沿用旧下标可能拼出无效的 audio_track，回到默认音轨
        currentAudio = -1

        // 立即停掉当前解码，避免取流期间还在放上一集的声音
        player.stop()
        pulseControlRow()
    }

    fun switchEpisode(delta: Int) {
        val next = currentIndex + delta
        if (next !in playQueue.indices) return
        val now = System.currentTimeMillis()
        if (now - lastEpisodeSwitchAt < EPISODE_SWITCH_THROTTLE_MS) return
        lastEpisodeSwitchAt = now
        loadEpisodeAt(next)
    }

    // 播放结束：按播放模式自动续播。
    // 顺序 → 下一集（末集停止）；单片循环 → seek 回 0 重播；随机 → 剩余未播条目随机。
    LaunchedEffect(endedTick) {
        if (endedTick == 0) return@LaunchedEffect
        if (!playedKeys.contains(currentPickCode)) playedKeys.add(currentPickCode)
        when (playMode) {
            PlayMode.REPEAT_ONE -> {
                player.seekTo(0)
                player.play()
            }
            PlayMode.SEQUENTIAL -> {
                if (currentIndex < playQueue.lastIndex) switchEpisode(1)
                else setIndicator("已是最后一集", null)
            }
            PlayMode.SHUFFLE -> {
                val candidates = playQueue.indices.filter { i ->
                    i != currentIndex && playedKeys.none { it == playQueue[i].pc }
                }
                if (candidates.isEmpty()) setIndicator("随机播放已播完全部", null)
                else loadEpisodeAt(candidates.random())
            }
        }
    }

    // 首次进入与切集共用同一条加载链路：itemKey 一变就整套重载
    LaunchedEffect(itemKey, prefs) {
        // ---- 本地/外部源：整条绕开 115 接口 ----
        // 播放地址、字幕列表、观看记录全都要 pick_code，本地文件一个都拿不到，
        // 所以这里直接起播。下面那些由 data 驱动的 UI（清晰度、音轨）靠 data 保持
        // null 自然隐藏，不用另外加开关。
        if (localUri != null) {
            val resumeMs = runCatching { container.playerPrefs.localResumeOf(localUri) }
                .getOrDefault(0L)
            runCatching {
                player.setMediaItem(
                    // mime 交给 mimeOfFileName：认得出的给准确值，认不出的给 null 让
                    // ExoPlayer 嗅探容器（和「原盘」直链同一条路，mkv/m2ts 都能吃）
                    buildMediaItem(localUri, mimeOfFileName(currentName)),
                    resumePos(resumeMs),
                )
                player.prepare()
                player.playWhenReady = true
            }.onFailure { e ->
                // 最常见的是 Uri 读权限失效（ACTION_VIEW 授的读权限不跨进程死亡，
                // 冷启动恢复后就打不开了）。要提示而不是崩。
                error = "无法读取该文件：${e.message}"
            }
            loading = false
            pulseControlRow()
            return@LaunchedEffect
        }
        try {
            val parsed = parseVideoPlayResponse(container.openApi.videoPlay(currentPickCode))
            data = parsed
            // 字幕列表（失败不影响播放）
            runCatching {
                val sub = container.openApi.videoSubtitles(currentPickCode)
                val list = sub.envData()?.get("list") as? JsonArray ?: return@runCatching
                subtitles = list.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    val url = s("url") ?: return@mapNotNull null
                    val type = s("type")?.lowercase()
                    SubtitleCfg(
                        url = url,
                        mime = if (type == "srt") "application/x-subrip" else "text/vtt",
                        language = s("language"),
                        title = s("title"),
                    )
                }
            }
            // 起播位置：切集时由 pendingStartMs 强制为 0（需求：进度归零）；
            // 否则沿用观看记录续播。无观看记录时接口返回 data:[]，由解析器归一为 0
            val forcedStart = pendingStartMs
            val rawStart = forcedStart ?: runCatching {
                parseVideoHistoryTime(container.openApi.videoHistoryGet(currentPickCode)) * 1000
            }.getOrNull() ?: 0L
            pendingStartMs = null
            // 续播位置贴着片尾时从头播（时长用接口给的 playLong，单位秒）。
            // 切集时的 0 是业务强制值，不走这个逻辑。
            val startPos = if (forcedStart != null) forcedStart
            else resumePos(rawStart, parsed.playLong * 1000)

            val d = parsed
            // 用户在画质菜单里主动选过「原盘」→ 后续每集都沿用直链。
            // 这里用 flow.first() 直读而不是 collectAsState：后者首帧是默认值 false，
            // 会让"记住的原盘偏好"在第一集失效。
            val targetDef = if (container.playerPrefs.preferOriginal.first() &&
                fetchOriginalUrl(currentPickCode) != null
            ) {
                DEF_ORIGINAL_FILE
            } else {
                d.userDef
                    ?.takeIf { def -> d.videoUrls.any { it.definition == def } }
                    ?: d.videoUrls.filter { it.definition != 100 }.maxOfOrNull { it.definition }
                    ?: d.videoUrls.maxOfOrNull { it.definition }
                    ?: 4
            }
            val built = buildItem(targetDef)
            if (built == null) {
                error = "没有可用的播放地址（部分清晰度需要会员）"
                loading = false
                return@LaunchedEffect
            }
            val (def, item) = built
            currentDef = def
            player.setMediaItem(item, startPos)
            player.prepare()
            player.playWhenReady = true
            loading = false
            pulseControlRow()
        } catch (e: Exception) {
            error = "网络错误：${e.message}"
            loading = false
        }
    }

    /**
     * 进度轮询（250ms）。拖拽交互式进度条期间整体跳过更新：
     * 此刻进度条显示的是手指位置，若继续写入播放器真实进度，会出现"拖到一半又被拽回去"的抖动。
     * 抬手时由 onSeekFinished 直接把目标位置写入 positionMs，下一轮轮询再与真实进度对齐。
     */
    LaunchedEffect(player) {
        while (true) {
            if (!isDraggingSeek) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    // 定期上报播放进度
    LaunchedEffect(currentPickCode, prefs) {
        while (isActive && data != null) {
            delay(10_000)
            val pos = player.currentPosition / 1000
            if (pos > 0) {
                val ended = player.playbackState == Player.STATE_ENDED
                runCatching { container.openApi.videoHistorySave(currentPickCode, pos, if (ended) 1 else 0) }
            }
        }
    }

    // 本地源的续播位置：没有云端观看记录可用，只能在本机记一份。
    // 不复用上面那条上报链路 —— 它靠 `data != null` 把关（本地源恒为 null，天然跳过），
    // key 也是 pick_code。
    LaunchedEffect(localUri) {
        val uri = localUri ?: return@LaunchedEffect
        while (isActive) {
            delay(10_000)
            val pos = player.currentPosition
            if (pos > 0) runCatching { container.playerPrefs.setLocalResume(uri, pos) }
        }
    }

    fun switchQuality(def: Int) {
        scope.launch {
            val pos = resumePos(player.currentPosition)
            val built = buildItem(def)
            if (built == null) {
                // 原盘直链取不到（无下载权限/接口频控/签名异常）时保持当前档位继续播，
                // 只给个瞬时提示——切画质失败不该把正在看的画面打断。
                setIndicator(
                    if (def == DEF_ORIGINAL_FILE) "原盘直链获取失败" else "该清晰度不可用",
                    null,
                )
                return@launch
            }
            val (d, item) = built
            currentDef = d
            // 记住档位偏好：选了原盘就一直用原盘，选回转码档位则清除
            runCatching { container.playerPrefs.setPreferOriginal(d == DEF_ORIGINAL_FILE) }
            player.setMediaItem(item, pos)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun switchAudio(index: Int) {
        scope.launch {
            currentAudio = index
            val pos = resumePos(player.currentPosition)
            val built = buildItem(currentDef) ?: return@launch
            val (_, item) = built
            player.setMediaItem(item, pos)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun setSpeed(s: Float) {
        currentSpeed = s
        player.setPlaybackSpeed(s)
        setIndicator("倍速 x$s", null)
    }

    /** 清晰度显示名：优先用接口返回的映射，缺失时用内置名 */
    fun labelOf(def: Int): String =
        data?.definitionLabels?.get(def)?.takeIf { it.isNotBlank() } ?: defLabel(def)

    /** 加载在线搜索的字幕：下载到本地缓存后作为外挂字幕重建媒体，记住进度 */
    fun loadOnlineSub(cfg: SubtitleCfg, bytes: ByteArray, ext: String) {
        scope.launch {
            try {
                onlineSubOriginal = bytes
                onlineSubCfg = cfg
                onlineSubExt = ext.ifBlank { "srt" }
                subOffsetMs = 0
                val dir = java.io.File(context.cacheDir, "online_subs").apply { mkdirs() }
                val file = java.io.File(dir, "sub_${System.currentTimeMillis()}.${onlineSubExt}")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                subsEnabled = true
                scope.launch { container.playerPrefs.setSubtitlesEnabled(true) }
                onlineSubs = listOf(cfg.copy(url = android.net.Uri.fromFile(file).toString()))
                useSubs = true
                val pos = resumePos(player.currentPosition)
                val built = buildItem(currentDef) ?: return@launch
                player.setMediaItem(built.second, pos)
                player.prepare()
                player.playWhenReady = true
                // 重建媒体会触发 PlayerView 自动弹控制器，压掉微调按钮，这里主动收起
            } catch (e: Exception) {
                error = "字幕加载失败：${e.message}"
            }
        }
    }

    /**
     * 从播放队列删除指定条目（物理删除源文件 → 移入网盘回收站）。
     *
     * 顺延保护：
     * - 删的是当前播放项：自动顺延下一集（最后一集则播前一集）；队列删空 → 停止播放并退出播放器
     * - 删的是非当前项：仅从队列剔除，当前播放不受影响
     * - 接口报错时列表数据根本不会改动（先调接口、成功才改状态），无需额外回滚
     */
    fun deletePlaylistItem(index: Int) {
        val entry = playQueue.getOrNull(index) ?: return
        val fid = entry.fid
        if (fid.isNullOrBlank()) {
            toast("该条目缺少文件 ID，无法删除")
            return
        }
        scope.launch {
            try {
                val resp = container.openApi.deleteFiles(fileIds = fid, parentId = null)
                if (!resp.envOk()) {
                    toast("删除失败：" + (resp.envMsg() ?: "接口返回失败"))
                    return@launch // 失败不改队列，播放不中断
                }
                val newList = playQueue.toMutableList().apply { removeAt(index) }
                val wasCurrent = index == currentIndex
                playQueue = newList
                when {
                    newList.isEmpty() -> {
                        toast("播放列表已空，退出播放")
                        onBack()
                    }
                    wasCurrent -> {
                        // 顺延：优先同一位置（原下一集）；已是最后一集则播前一集
                        loadEpisodeAt(index.coerceAtMost(newList.lastIndex))
                        toast("已移入网盘回收站")
                    }
                    index < currentIndex -> {
                        currentIndex -= 1 // 删除的是当前之前的条目：下标左移保持指向不变
                        toast("已移入网盘回收站")
                    }
                    else -> toast("已移入网盘回收站")
                }
            } catch (e: Exception) {
                toast("删除失败：" + (e.message ?: "网络错误"))
            }
        }
    }

    fun showController() {
        controllerHideJob?.cancel()
        controllerHideJob = scope.launch {
            delay(3000) // 3 秒无操作自动淡出
            }
    }

    fun hideControllerNow() {
        controllerHideJob?.cancel()
    }

    fun setGestureHud(side: Int, text: String, progress: Float?) {
        gestureHudSide = side
        gestureHudText = text
        gestureHudProgress = progress
        gestureHudAt = System.currentTimeMillis()
    }

    fun lockScreen() {
        screenLocked = true
        lockWakeAt = System.currentTimeMillis()
        // 上锁立即隐藏全部覆盖层：控制排、迷你进度条、顶栏（顶栏的显隐由 screenLocked 驱动）
        controlRowVisible = false
        controlRowUntil = 0L
        hideControllerNow()
    }

    fun unlockScreen() {
        screenLocked = false
        lockDimmed = false
        pulseControlRow()
    }


    LaunchedEffect(gestureHudAt) {
        if (gestureHudAt > 0L) {
            delay(900)
            gestureHudSide = 0
            gestureHudText = null
            gestureHudProgress = null
        }
    }

    LaunchedEffect(controlRowUntil) {
        if (controlRowUntil > 0L) {
            val remain = controlRowUntil - System.currentTimeMillis()
            if (remain > 0) delay(remain)
            if (System.currentTimeMillis() >= controlRowUntil) controlRowVisible = false
        }
    }

    // ---- 状态栏数据（右上角微型指示）----
    var netSpeedText by remember { mutableStateOf("0 KB/s") }
    var clockText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableStateOf<Int?>(null) }
    var specBadge by remember { mutableStateOf<String?>(null) }

    // 锁定态 3 秒无操作 → 锁按钮降至 25% 透明度；任意点击唤醒（lockWakeAt 变化即重置）
    LaunchedEffect(screenLocked, lockWakeAt) {
        if (screenLocked) {
            lockDimmed = false
            delay(3000)
            if (screenLocked) lockDimmed = true
        } else {
            lockDimmed = false
        }
    }

    // 实时网速：带宽计的估算值（bits/s → 人类可读），每秒刷新
    LaunchedEffect(bandwidthMeterRef) {
        val meter = bandwidthMeterRef ?: return@LaunchedEffect
        while (true) {
            val bytesPerSec = (meter.bitrateEstimate / 8.0).coerceAtLeast(0.0)
            netSpeedText = when {
                bytesPerSec >= 1024.0 * 1024.0 ->
                    String.format(java.util.Locale.CHINA, "%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
                else ->
                    String.format(java.util.Locale.CHINA, "%.0f KB/s", bytesPerSec / 1024.0)
            }
            delay(1000)
        }
    }

    // 时钟（每分钟内 10s 轮询足够）+ 电量（系统粘性广播，无需注册接收器）
    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val batteryFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        while (true) {
            clockText = fmt.format(java.util.Date())
            runCatching {
                val intent = context.registerReceiver(null, batteryFilter)
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
            }
            delay(15_000)
        }
    }

    // 规格胶囊：分辨率 / HDR 传输特性 / 编码。
    // 触发源必须是 vsVideoSize（监听器 onVideoSizeChanged 写入的 Compose 状态）：
    // 早先用 snapshotFlow { player.videoSize } 是错的——Player 的属性不是快照状态，
    // snapshotFlow 求值一次后不会再触发，而首次求值时首帧还没解码（0×0），
    // 于是 specBadge 永远是 null，胶囊整条渲染不出来。
    LaunchedEffect(vsVideoSize) {
        val vs = vsVideoSize
        if (vs.width <= 0 || vs.height <= 0) {
            specBadge = null
            return@LaunchedEffect
        }
        val vf = player.videoFormat
        val longEdge = maxOf(vs.width, vs.height)
        val res = when {
            longEdge >= 3800 -> "4K"
            longEdge >= 2500 -> "2K"
            longEdge >= 1900 -> "1080P"
            longEdge >= 1200 -> "720P"
            else -> vs.width.toString() + "x" + vs.height
        }
        // media3 1.4.1 的 Format 没有 colorTransfer 字段：HDR 在 colorInfo 里，
        // 编码在 codecs 字符串里（如 hvc1.1.6 / avc1.640033）
        val hdr = when (vf?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> " HDR10"
            C.COLOR_TRANSFER_HLG -> " HLG"
            else -> ""
        }
        val codecs = (vf?.codecs ?: "").lowercase()
        val codec = when {
            "dvh1" in codecs || "dva1" in codecs -> "DV"
            "hvc1" in codecs || "hev1" in codecs -> "HEVC"
            "avc1" in codecs || "avc3" in codecs -> "AVC"
            "av01" in codecs -> "AV1"
            else -> ""
        }
        specBadge = (res + hdr).let { if (codec.isNotBlank()) "$it · $codec" else it }
    }

    // 缓冲动效的显隐：连续缓冲超过 400ms 才出现，避免每次细微卡顿都闪一下；
    // 缓冲一结束立刻淡出（LaunchedEffect 换 key 会取消上一次的 delay）
    LaunchedEffect(rebuffering) {
        if (rebuffering) {
            delay(400)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }

    /**
     * 字幕时间轴微调：基于原始字节平移时间轴后重建媒体。
     * 还没有原始字节时（仅挂了 115 官方字幕），先把官方字幕下载到本地作为基准，
     * 之后官方字幕与在线搜索字幕走同一套"本地副本 + 平移"链路。
     */
    fun applySubOffset(deltaMs: Long) {
        scope.launch {
            try {
                if (onlineSubOriginal == null) {
                    val first = subtitles.firstOrNull() ?: run {
                        toast("当前没有可微调的字幕")
                        return@launch
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        downloadSubtitleBytes(container.okHttpClient, first.url)
                    }
                    onlineSubOriginal = bytes
                    onlineSubCfg = first
                    onlineSubExt = if (first.mime == "text/vtt") "vtt" else "srt"
                    subOffsetMs = 0L
                    // 官方字幕改走本地副本，避免原链接与副本同时挂载显示两份字幕
                    subtitles = emptyList()
                }
                subsEnabled = true
                scope.launch { container.playerPrefs.setSubtitlesEnabled(true) }
                subOffsetMs = (subOffsetMs + deltaMs).coerceIn(-600000L, 600000L)
                val original = onlineSubOriginal ?: return@launch
                val shifted = withContext(Dispatchers.IO) {
                    shiftSubtitleTimestamps(original, subOffsetMs)
                }
                val dir = java.io.File(context.cacheDir, "online_subs").apply { mkdirs() }
                val file = java.io.File(dir, "sub_${System.currentTimeMillis()}.${onlineSubExt}")
                withContext(Dispatchers.IO) { file.writeBytes(shifted) }
                onlineSubs = listOf(
                    (onlineSubCfg ?: SubtitleCfg("", mimeForSubtitleExt(onlineSubExt), null, null))
                        .copy(url = android.net.Uri.fromFile(file).toString()),
                )
                val pos = resumePos(player.currentPosition)
                val built = buildItem(currentDef) ?: return@launch
                player.setMediaItem(built.second, pos)
                player.prepare()
                player.playWhenReady = true
                setIndicator(
                    "字幕偏移 " + (if (subOffsetMs >= 0) "+" else "") + subOffsetMs / 1000.0 + "s",
                    null,
                )
            } catch (e: Exception) {
                error = "字幕调整失败：${e.message}"
            }
        }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(landscape) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 横竖屏一致：播放页全沉浸，状态栏+导航栏都藏；
            // 从屏幕边缘上/下滑可临时唤出（半透明覆盖，不挤占布局）
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            val window = (context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 退到后台暂停
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    @Composable
    fun VideoSurface(modifier: Modifier, onSubSearch: () -> Unit) {
        BoxWithConstraints(modifier) {
            val vsDensity = LocalDensity.current
            val vsW = constraints.maxWidth
            val vsH = constraints.maxHeight
            val rotated = videoRotation % 180 == 90

            // ---- 视频渲染：TextureView（参与 View 变换，graphicsLayer 旋转真实生效；
            //      SurfaceView 的内容由 SurfaceFlinger 合成，旋转不作用）----
            // 帧宽高比（未知按 16:9）。旋转 90/270 时视觉比例是原比例的转置，
            // 等比适配按转置后的容器约束求缩放系数，TextureView 布局尺寸 = 帧尺寸 × s，
            // 保证任何角度下画面等比显示不拉伸。
            val vw = vsVideoSize.width.takeIf { it > 0 } ?: 16
            val vh = vsVideoSize.height.takeIf { it > 0 } ?: 9
            val (fitW, fitH) = if (rotated) vsH to vsW else vsW to vsH
            val scale = minOf(fitW.toFloat() / vw, fitH.toFloat() / vh)
            val surfaceW = vw * scale
            val surfaceH = vh * scale

            val textureView = remember {
                android.view.TextureView(context).apply { isOpaque = true }
            }
            if (vrMode != null) {
                // ---- VR 模式：画面交给 GL 实时反投影 ----
                // ⚠️ GLSurfaceView 是 SurfaceView 子类，内容由 SurfaceFlinger 合成、
                //    不参与 View 变换，所以这条路上既不能靠 graphicsLayer 旋转，
                //    也不需要等比适配——视窗本来就是满屏的。90/270 的旋转改由
                //    着色器承担（VrParams.camRot 里带 roll），反正本来就在重投影。
                AndroidView(
                    factory = { ctx ->
                        VrViewportView(ctx).also { v ->
                            vrViewRef.set(v)
                            v.onSurfaceReady = { s -> player.setVideoSurface(s) }
                        }
                    },
                    update = { v -> v.updateParams(vrState.snapshot()) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DisposableEffect(textureView) {
                    player.setVideoTextureView(textureView)
                    onDispose { player.clearVideoTextureView(textureView) }
                }
            }
            // 退出 VR：必须把输出面切回 TextureView。GL 视图销毁时它持有的 Surface
            // 一并失效，不切回去画面直接变黑（解码照跑，只是没有输出面了）。
            //
            // ⚠️ key 必须用 `inVr`（布尔），**不能直接用 `vrMode`**：
            //    DisposableEffect 换 key 时会拿**旧值**跑 onDispose。用 vrMode 当 key 的话，
            //    从「VR180 左右」切到「360 左右」这种**仍在 VR 内**的变更也会触发 onDispose，
            //    而旧值非空 ⇒ 它会误判成"要退出 VR"，把输出面切回 TextureView 并清空 vrViewRef。
            //    此时 GL 视图的实例身份没变、AndroidView 的 factory 不会重跑，
            //    `onSurfaceReady` 再也不会触发 ⇒ 视频流断掉、画面冻在最后一帧（表现为"卡死"）。
            //    用布尔当 key，只有真正进出 VR 才会触发。
            val inVr = vrMode != null
            DisposableEffect(inVr) {
                onDispose {
                    if (inVr) {
                        vrViewRef.set(null)
                        player.clearVideoSurface()
                        player.setVideoTextureView(textureView)
                    }
                }
            }
            // ---- 视频与外挂字幕同组：同尺寸、同 graphicsLayer 旋转——
            //      字幕锚定在画面底部，随画面一起旋转；字号不随视图尺寸缩放 ----
            // 字幕字号 / 垂直位置：设置页可调，实时生效
            val subtitleTextSize by container.playerPrefs.subtitleTextSize
                .collectAsState(initial = 18f)
            val subtitleBottomPercent by container.playerPrefs.subtitleBottomPercent
                .collectAsState(initial = 0)
            val subtitleView = remember {
                androidx.media3.ui.SubtitleView(context).apply {
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
                    setApplyEmbeddedStyles(false)
                }
            }
            DisposableEffect(subtitleView) {
                val cueListener = object : Player.Listener {
                    override fun onCues(cues: List<Cue>) {
                        subtitleView.setCues(cues)
                    }
                }
                player.addListener(cueListener)
                onDispose { player.removeListener(cueListener) }
            }
            if (vrMode == null) Box(
                Modifier
                    .align(Alignment.Center)
                    // 必须用 requiredSize 而非 size：旋转 90/270 时按帧比例算出的布局尺寸是
                    // 转置的（1080×2370 的竖屏片在 2560×1600 屏上要 1167×2560），size() 会被
                    // 父约束直接夹到 1600 高，布局比例随之从 0.46 变成 0.73，TextureView 把画面
                    // 拉伸填满 → 旋转后画面形变。requiredSize 允许子节点超出父约束并居中。
                    .requiredSize(with(vsDensity) { surfaceW.toDp() }, with(vsDensity) { surfaceH.toDp() })
                    .graphicsLayer { rotationZ = videoRotation.toFloat() },
            ) {
                AndroidView(factory = { _ -> textureView }, modifier = Modifier.fillMaxSize())
                AndroidView(
                    factory = { _ -> subtitleView },
                    update = { sv ->
                        sv.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize()
                        // 垂直位置：整幅字幕层随画面高度按百分比上移。不用 SubtitleView 的
                        // bottomPaddingFraction——该参数只在 cue 未自带行位置（Cue.line 为
                        // DIMEN_UNSET）时生效，115 官方字幕带行位置时会被完全忽略（实测拉滑块
                        // 字幕纹丝不动）；整层位移与字幕格式、行位置无关。
                        .graphicsLayer { translationY = -surfaceH * subtitleBottomPercent / 100f }
                        .padding(bottom = 4.dp),
                )
            } else {
                // VR：视窗满屏，字幕直接贴底叠一层，不参与旋转也不做等比适配
                AndroidView(
                    factory = { _ -> subtitleView },
                    update = { sv ->
                        sv.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize()
                        .graphicsLayer { translationY = -vsH * subtitleBottomPercent / 100f }
                        .padding(bottom = 4.dp),
                )
            }
            // 缓冲动效：叠在画面上但**不拦手势**（没有 clickable，事件照旧穿透到手势层），
            // 锁屏时也保留——画面停住时用户最需要知道"是在缓冲还是卡死了"
            androidx.compose.animation.AnimatedVisibility(
                visible = showBuffering,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BufferingPill() }
            }
            // VR 手势层：只在 VR 模式生效，与下面那套普通手势**互斥**（见两边的 enabled 条件）
            if (vrMode != null) {
                VrGestureLayer(
                    modifier = Modifier.fillMaxSize(),
                    state = vrState,
                    onViewChanged = { pushVrParams() },
                    onToggleController = {
                        if (controlRowVisible) {
                            controlRowVisible = false
                            controlRowUntil = 0L
                        } else {
                            pulseControlRow()
                        }
                    },
                    onDoubleTap = { if (player.isPlaying) player.pause() else player.play() },
                    onHud = { vrHudText = it },
                )
            }
            // 手势层：锁定时整体禁用（单击/双击/长按/垂直音量亮度/横向 seek 全部拦截）。
            // **VR 模式下同样整体禁用**：此时画面是实时反投影的视窗，横拖要转视角、
            // 竖拖要抬低头，会和"横滑快进 / 左半屏调亮度 / 右半屏调音量"直接打架——
            // 两套同时生效的结果是拖动时亮度乱跳、进度条跟着乱 seek。
            PlayerGestureOverlay(
                modifier = Modifier.fillMaxSize(),
                enabled = !screenLocked && vrMode == null,
                player = player,
                config = DoubleTapConfig(
                    leftSeconds = prefs.seekSeconds,
                    rightSeconds = prefs.seekSeconds,
                ),
                longPressSpeed = prefs.speedBoost,
                onToggleController = {
                    // 自带控制条已停用：单击切换右下角控制排的显隐
                    if (controlRowVisible) {
                        controlRowVisible = false
                        controlRowUntil = 0L
                    } else {
                        pulseControlRow()
                    }
                },
                onSeekPreview = { target, delta, boosting ->
                    seekPreviewTarget = target
                    seekPreviewDur = player.duration.coerceAtLeast(0L)
                    seekPreviewDelta = delta
                    seekPreviewBoost = boosting
                },
                onSeekCommit = { target ->
                    player.seekTo(target)
                    seekPreviewTarget = null
                },
                onSeekPreviewCancel = { seekPreviewTarget = null },
                onBrightness = { v ->
                    pulseControlRow()
                    setGestureHud(1, "亮度 ${(v * 100).toInt()}%", v)
                },
                onVolume = { cur, max ->
                    pulseControlRow()
                    setGestureHud(-1, "音量 ${cur * 100 / max}%", cur / max.toFloat())
                },
                onSpeedHud = { text ->
                    pulseControlRow()
                    speedCapsule = text
                },
                onDoubleTapFx = { side, label ->
                    pulseControlRow()
                    dtFx = DoubleTapFx(side, label, System.currentTimeMillis())
                },
            )
            // ---- 屏幕防误触锁（模块 A）----
            if (screenLocked) {
                // 全屏唤醒层：吞掉一切点击，只负责把锁按钮唤醒到全透明度。
                // 手势层已整体禁用，这里再兜一层，保证锁定态下画面绝对静默。
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { lockWakeAt = System.currentTimeMillis() },
                )
            }
            // 锁按钮：左侧垂直居中，圆形半透明磨砂底。
            // 未锁定时随控制排 4 秒淡出/单击唤出；锁定时常驻（仅透明度变化）
            androidx.compose.animation.AnimatedVisibility(
                visible = screenLocked || controlRowVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
            Surface(
                color = Color.Black.copy(
                    alpha = if (screenLocked && lockDimmed) 0.25f else 0.55f,
                ),
                shape = CircleShape,
                modifier = Modifier
                    .padding(start = 18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (screenLocked) unlockScreen() else lockScreen() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (screenLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                        contentDescription = if (screenLocked) "解锁屏幕" else "锁定屏幕",
                        tint = Color.White.copy(
                            alpha = if (screenLocked && lockDimmed) 0.5f else 1f,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            }
            // 右侧竖排：VR 开关（识别到 VR 素材才出现）+ 旋转/复位。
            // VR 模式下这个位置变成「复位视角」：视窗本来就满屏，画面旋转对 VR 没意义，
            // 而陀螺仪无磁力计必然缓慢漂移，恰恰最需要一个快速回正的入口。
            androidx.compose.animation.AnimatedVisibility(
                visible = controlRowVisible && !screenLocked,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 18.dp),
                    ) {
                        // VR 键**常驻**（不再只在识别到 VR 素材时出现）。
                        // 自动识别必然有猜不到的情况——宽高比 2 既可能是 VR180 左右、
                        // 也可能是 360 单目，还有带黑边/非标裁剪的素材。
                        // 按钮一旦隐藏，手动兜底这条路就断了，用户只能干看着。
                        Surface(
                            color = if (vrMode != null) VrActiveColor
                            else Color.Black.copy(alpha = 0.55f),
                            shape = CircleShape,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        vrMenuOpen = !vrMenuOpen
                                        pulseControlRow()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.PanoramaPhotosphere,
                                    contentDescription = "VR 视角",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = CircleShape,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (vrMode != null) {
                                            vrState.resetView()
                                            vrGyro.recenter()
                                            pushVrParams()
                                            setIndicator("视角已复位", null)
                                        } else {
                                            videoRotation = (videoRotation + 90) % 360
                                            setIndicator("画面旋转 ${videoRotation}°", null)
                                        }
                                        pulseControlRow()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (vrMode != null) Icons.Outlined.MyLocation
                                    else Icons.Outlined.ScreenRotation,
                                    contentDescription = if (vrMode != null) "复位视角" else "画面旋转 90°",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            // 亮度 HUD：手势在左侧，指示条反向显示在右侧
            if (gestureHudSide == 1 && gestureHudText != null) {
                GestureHudSide(
                    icon = Icons.Outlined.LightMode,
                    label = gestureHudText!!,
                    progress = gestureHudProgress ?: 0f,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 40.dp),
                )
            }
            // 音量 HUD：手势在右侧，指示条反向显示在左侧
            if (gestureHudSide == -1 && gestureHudText != null) {
                GestureHudSide(
                    icon = Icons.Outlined.VolumeUp,
                    label = gestureHudText!!,
                    progress = gestureHudProgress ?: 0f,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 40.dp),
                )
            }
            // 双击水波纹 + 步长反馈
            dtFx?.let { fx -> DoubleTapFxOverlay(fx, Modifier.fillMaxSize()) }
            // 长按倍速胶囊（顶部居中）
            speedCapsule?.let { text ->
                SpeedCapsuleHud(
                    text,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp),
                )
            }
            // VR 手势提示（顶部居中，比倍速胶囊低一点避免重叠）
            if (vrMode != null) {
                VrHud(
                    vrHudText,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 108.dp),
                )
            }
            // 进度预览（居中，抬手才真正 seek）
            if (seekPreviewTarget != null) {
                SeekPreviewOverlay(
                    targetMs = seekPreviewTarget!!,
                    durMs = seekPreviewDur,
                    deltaSec = seekPreviewDelta,
                    boosting = seekPreviewBoost,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // 控制栏：形态 A 交互式进度条 + 按钮排；单击唤出，4 秒无操作整体淡出
            AnimatedVisibility(
                visible = controlRowVisible && !screenLocked,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // VR 模式菜单：挂在控制排顶部（贴底居中）。此前它挂在右侧圆钮旁、
                    // 整排横在画面正中，非常突兀；贴底之后与进度条/按钮排同生共死，
                    // 4 秒淡出行为完全一致，也不遮画面中心。
                    if (vrMenuOpen) {
                        VrModeMenu(
                            current = vrMode,
                            autoMode = vrStoredMode.isNullOrEmpty(),
                            rightEye = vrStoredEye,
                            gyroOn = vrGyroOn,
                            gyroAvailable = vrGyro.available,
                            panniniD = vrPanniniUi,
                            windowMode = vrWindow,
                            showWindowOptions = !landscape,
                            onAuto = {
                                // 清掉"用户手动改过"的标记，并把偏好置空 ⇒ 交回自动识别
                                vrTouchedFor = null
                                vrMode = null
                                scope.launch { container.playerPrefs.setVrMode("") }
                                pulseControlRow()
                            },
                            onPannini = { d ->
                                vrTouchedFor = itemKey
                                vrPanniniUi = d
                                vrState.setPannini(d)
                                pushVrParams()
                                // 滑杆拖一下控制排就续命一次，否则拖到一半整排淡出
                                pulseControlRow()
                            },
                            onPanniniCommit = { d ->
                                scope.launch { container.playerPrefs.setVrPanniniD(d) }
                            },
                            onWindow = { w ->
                                vrWindow = w
                                scope.launch { container.playerPrefs.setVrWindow(w.name) }
                                // 视窗尺寸变化会让 GL 面 resize（走 onSurfaceChanged
                                // → requestRender），投影参数不用重推；这里续命只是
                                // 为了让用户点完档位控制排别立刻淡出
                                pulseControlRow()
                            },
                            onPick = { m ->
                                vrTouchedFor = itemKey
                                vrMode = m
                                m?.let { vrState.mode = it }
                                vrState.resetForNewMode()
                                scope.launch { container.playerPrefs.setVrMode(m?.name ?: VR_MODE_OFF) }
                                pushVrParams()
                                pulseControlRow()
                            },
                            onToggleEye = {
                                vrTouchedFor = itemKey
                                val v = !vrStoredEye
                                vrState.rightEye = v
                                scope.launch { container.playerPrefs.setVrRightEye(v) }
                                pushVrParams()
                                pulseControlRow()
                            },
                            onToggleGyro = {
                                vrTouchedFor = itemKey
                                val v = !vrGyroOn
                                vrGyroOn = v
                                vrState.gyroEnabled = v
                                if (v) vrGyro.recenter()
                                scope.launch { container.playerPrefs.setVrGyro(v) }
                                pushVrParams()
                                pulseControlRow()
                            },
                            onRecenter = {
                                vrState.resetView()
                                vrGyro.recenter()
                                pushVrParams()
                                pulseControlRow()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // 状态行（时长 / 当前画质 / 缓存 / 解码方式）：挂在控制排上方。
                    // 原先这行挂在根 Box 的默认 TopStart 位置，横屏下被顶栏和画面盖住、
                    // 实际从来看不到，挪到最容易看见的进度条上方。
                    if ((data?.playLong ?: 0) > 0) {
                        Text(
                            // 各段单独拼接：`"a" + if (x) "b" else "c" + if (y) ...` 会被解析成
                            // else 分支吞掉后半段，多一个条件就会静默丢内容
                            buildString {
                                append("时长 ${Format.duration(data!!.playLong)}")
                                append(" · 当前 ${labelOf(currentDef)}")
                                append(if (prefs.cacheEnabled) " · 缓存开" else " · 缓存关")
                                append(if (prefs.softwareDecode) " · 软解" else " · 硬解")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
                        )
                    }
                    InteractiveSeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        onSeekStart = {
                            // 拖拽期间冻结进度轮询，并暂停 4 秒自动淡出倒计时
                            isDraggingSeek = true
                            controlRowUntil = 0L
                        },
                        onSeekFinished = { target ->
                            // 抬手才提交唯一一次 seekTo，随后重新开始 4 秒倒计时
                            player.seekTo(target)
                            positionMs = target
                            isDraggingSeek = false
                            pulseControlRow()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        // 左：上一集/下一集；右：播放控制群。两组靠 Spacer(weight) 分居两端，共用同一水平基线
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 播放模式切换：顺序(repeat) → 单片循环(repeat_one) → 随机(shuffle)
                                IconButton(onClick = {
                                    playMode = when (playMode) {
                                        PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
                                        PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
                                        PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
                                    }
                                    setIndicator(playMode.label, null)
                                    pulseControlRow()
                                }) {
                                    Icon(
                                        when (playMode) {
                                            PlayMode.SEQUENTIAL -> Icons.Outlined.Repeat
                                            PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
                                            PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
                                        },
                                        contentDescription = "播放模式：${playMode.label}",
                                        tint = if (playMode == PlayMode.SEQUENTIAL) Color.White.copy(alpha = 0.75f)
                                        else Color(0xFFFFC107),
                                    )
                                }
                                EpisodeButton(
                                    icon = Icons.Outlined.SkipPrevious,
                                    description = "上一集",
                                    enabled = playQueue.isNotEmpty() && currentIndex > 0,
                                    onClick = { switchEpisode(-1) },
                                )
                                Spacer(Modifier.width(16.dp))
                                EpisodeButton(
                                    icon = Icons.Outlined.SkipNext,
                                    description = "下一集",
                                    enabled = playQueue.isNotEmpty() && currentIndex < playQueue.lastIndex,
                                    onClick = { switchEpisode(1) },
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                Modifier
                                    .padding(horizontal = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    if (player.isPlaying) player.pause() else player.play()
                                }) {
                                    Icon(
                                        if (player.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        "播放/暂停",
                                        tint = Color.White,
                                    )
                                }
                                // 画质：始终显示（单档片源也让用户知道当前画质，点开可确认档位）
                                if (data != null) {
                                    Box {
                                        TextButton(onClick = { qualityMenuOpen = true }) {
                                            Text(
                                                labelOf(currentDef),
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                        DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
                                            data?.videoUrls?.forEach { entry ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            labelOf(entry.definition) +
                                                                if (entry.definition == currentDef) " ✓" else "",
                                                        )
                                                    },
                                                    onClick = {
                                                        qualityMenuOpen = false
                                                        switchQuality(entry.definition)
                                                    },
                                                )
                                            }
                                            // 原盘：不走 115 转码，直接用下载直链播原始文件。
                                            // 画质上限最高（保留全部音轨/字幕轨），但没有自适应码率、带宽占用高。
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "原盘" +
                                                            if (currentDef == DEF_ORIGINAL_FILE) " ✓" else "",
                                                    )
                                                },
                                                onClick = {
                                                    qualityMenuOpen = false
                                                    switchQuality(DEF_ORIGINAL_FILE)
                                                },
                                            )
                                        }
                                    }
                                }
                                // 音轨
                                if ((data?.multitrackList?.size ?: 0) > 1) {
                                    Box {
                                        TextButton(onClick = { audioMenuOpen = true }) {
                                            Text(
                                                "音轨",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                        DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("默认音轨" + if (currentAudio == -1) " ✓" else "") },
                                                onClick = {
                                                    audioMenuOpen = false
                                                    switchAudio(-1)
                                                },
                                            )
                                            data?.multitrackList?.forEachIndexed { i, title ->
                                                DropdownMenuItem(
                                                    text = { Text("音轨${i + 1}：$title" + if (currentAudio == i) " ✓" else "") },
                                                    onClick = {
                                                        audioMenuOpen = false
                                                        switchAudio(i)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                // 字幕：搜索在线字幕 / 显示开关
                                Box {
                                    IconButton(onClick = { subsMenuOpen = true }) {
                                        Icon(
                                            if (subsEnabled) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                                            "字幕",
                                            tint = Color.White,
                                        )
                                    }
                                    DropdownMenu(expanded = subsMenuOpen, onDismissRequest = { subsMenuOpen = false }) {
                                        // 在线字幕搜索是按 pick_code 去 115 搜的，本地源没有这个身份，
                                        // 留着点了必然失败，干脆不显示（显示开关仍然保留）
                                        if (localUri == null) {
                                            DropdownMenuItem(
                                                text = { Text("搜索在线字幕…") },
                                                onClick = {
                                                    subsMenuOpen = false
                                                    onSubSearch()
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(if (subsEnabled) "显示字幕 ✓" else "显示字幕（关）") },
                                            onClick = {
                                                subsMenuOpen = false
                                                setSubsEnabled(!subsEnabled)
                                            },
                                        )
                                        HorizontalDivider()
                                        // 时间轴微调：整行点击不关闭菜单，方便连续点按 ±0.5s 找准对齐点
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        if (subOffsetMs == 0L) "时间轴微调"
                                                        else "偏移 " + (if (subOffsetMs > 0) "+" else "") +
                                                            subOffsetMs / 1000.0 + "s",
                                                    )
                                                    Spacer(Modifier.weight(1f))
                                                    TextButton(onClick = { applySubOffset(-500L) }) {
                                                        Text("-0.5s")
                                                    }
                                                    TextButton(onClick = { applySubOffset(+500L) }) {
                                                        Text("+0.5s")
                                                    }
                                                }
                                            },
                                            onClick = {},
                                            enabled = onlineSubOriginal != null || subtitles.isNotEmpty(),
                                        )
                                        if (subOffsetMs != 0L) {
                                            DropdownMenuItem(
                                                text = { Text("偏移归零") },
                                                onClick = { applySubOffset(-subOffsetMs) },
                                            )
                                        }
                                    }
                                }
                                // 倍速
                                Box {
                                    TextButton(onClick = { speedMenuOpen = true }) {
                                        Text(
                                            "倍速" + if (currentSpeed == 1f) "" else " x$currentSpeed",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s ->
                                            DropdownMenuItem(
                                                text = { Text("x$s" + if (currentSpeed == s) " ✓" else "") },
                                                onClick = {
                                                    speedMenuOpen = false
                                                    setSpeed(s)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 形态 B：常驻底部迷你进度条。控制栏隐藏时淡入，与形态 A 互斥，不会同时出现两条进度
            AnimatedVisibility(
                visible = prefs.miniProgress && !controlRowVisible && !screenLocked,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                MiniProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                )
            }

            indicator?.let { (text, progress) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (loading) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Color.White) }
        return
    }
    if (error != null) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = Color.White)
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        }
        return
    }

    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                data?.fileName ?: currentName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 模块 C：右上角多功能状态指示栏（仅渲染设置里开启的项目）
            PlayerStatusRow(
                netSpeedText = netSpeedText,
                clockText = clockText,
                batteryPct = batteryPct,
                specText = specBadge,
                showNetSpeed = prefs.showNetSpeed,
                showClock = prefs.showClock,
                showBattery = prefs.showBattery,
                showSpec = prefs.showSpecBadge,
            )
            // 模块 B：播放列表入口（同一行最右侧）
            IconButton(onClick = { playlistOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "播放列表",
                    tint = Color.White,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
    if (landscape) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            VideoSurface(Modifier.fillMaxSize(), onSubSearch = { showSubSearch = true })
            // 顶栏（返回/标题/状态栏/播放列表）整体随控制排显隐：4 秒无操作淡出，单击唤出
            AnimatedVisibility(
                visible = controlRowVisible && !screenLocked,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                topBar(Modifier.fillMaxWidth())
            }
        }
    } else {
        // ---- 竖屏布局 ----
        // ① 横屏片：满宽、高度按比例，**整体垂直居中**（不再钉在屏幕顶部）；
        // ② 竖屏片：直接占满状态栏以下的可用高度、宽度按比例收窄——等效全屏
        //    且不裁切内容（左右仅留极窄黑边）；③ VR 仍满屏；④ 标题/时长/集数
        //  chips 改为贴屏幕底部的悬浮层（渐变遮罩），控制排弹出或锁屏时隐藏，
        //    避免与压在视频底沿的进度条/按钮排互相遮挡。
        // 系统栏已全沉浸（上方 DisposableEffect），无需再让出状态栏/导航栏空间
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val density = LocalDensity.current
            val maxW = constraints.maxWidth
            val maxH = constraints.maxHeight
            val vw = vsVideoSize.width.takeIf { it > 0 } ?: 16
            val vh = vsVideoSize.height.takeIf { it > 0 } ?: 9
            val boxW: Int
            val boxH: Int
            if (vrMode != null) {
                // VR：反投影视窗。横屏满屏；竖屏按档位收窄**长边**（垂直方向）。
                // 视窗越方、黑边越多，能看到的水平范围越宽 —— 满屏 67°×121° /
                // 3:4 的 74°×106° / 1:1 的 90°×79°（fovDeg=90、D=1），三档最差边缘
                // 拉伸都在 1.66~1.75×，所以这里选的是**取景范围**而不是画质。
                // 竖屏长边之所以不再畸变，是因为 Pannini 的轴向自动跟随了它
                // （见 VrViewportView 的 vertAxis）。
                val wa = vrWindow.portraitAspect
                when {
                    wa == null -> {
                        boxW = maxW
                        boxH = maxH
                    }
                    // 视窗形状比屏幕更"宽"（常见：9:16 屏选 3:4 / 1:1）⇒ 占满宽度、收窄高度
                    maxW.toFloat() / maxH <= wa -> {
                        boxW = maxW
                        boxH = (maxW / wa).roundToInt().coerceIn(1, maxH)
                    }
                    // 屏幕本身比档位还方（折叠屏内屏之类）⇒ 占满高度、收窄宽度
                    else -> {
                        boxH = maxH
                        boxW = (maxH * wa).roundToInt().coerceIn(1, maxW)
                    }
                }
            } else {
                val hAtFullW = (maxW.toFloat() * vh / vw).roundToInt()
                if (hAtFullW <= maxH) {
                    // 横屏片 / 方片：满宽，高度按比例，垂直居中
                    boxW = maxW
                    boxH = hAtFullW
                } else {
                    // 竖屏片：占满可用高度，宽度按比例收窄（不裁切，等效全屏）
                    boxH = maxH
                    boxW = (maxH.toFloat() * vw / vh).roundToInt().coerceIn(1, maxW)
                }
            }
            VideoSurface(
                Modifier
                    .align(Alignment.Center)
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() }),
                onSubSearch = { showSubSearch = true },
            )
            // 底部悬浮信息层：标题 + 时长画质 + 集数 chips（有队列才显示）。
            // 控制排的进度条和按钮排压在画面底沿，与这块是同一区域，二者互斥显隐。
            AnimatedVisibility(
                visible = !controlRowVisible && !screenLocked,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        data?.fileName ?: currentName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            val playLong = data?.playLong ?: 0L
                            if (playLong > 0) append("时长 ${Format.duration(playLong)} · ")
                            append(labelOf(currentDef))
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playQueue.size > 1) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "播放列表 (${playQueue.size})",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(playQueue) { i, entry ->
                                val isCurrent = i == currentIndex
                                Surface(
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        entry.fn.substringBeforeLast('.'),
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = 0.85f),
                                        fontWeight = if (isCurrent) FontWeight.Medium else null,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .widthIn(max = 180.dp)
                                            .clickable {
                                                if (i != currentIndex) {
                                                    loadEpisodeAt(i)
                                                    toast("已切换：" + entry.fn)
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 顶栏：竖屏此前完全没有返回键，与横屏同源、同样随控制排显隐
            AnimatedVisibility(
                visible = controlRowVisible && !screenLocked,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            ) {
                topBar(Modifier.fillMaxWidth())
            }
        }
    }

        // ---- 模块 B：播放列表抽屉（右侧滑出，约 35% 宽，暗色半透明）----
        // 遮罩：点列表外区域收起
        AnimatedVisibility(
            visible = playlistOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { playlistOpen = false },
            )
        }
        // 面板：从右侧滑入
        AnimatedVisibility(
            visible = playlistOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(220),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(200),
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.35f).dp
            Surface(
                color = Color(0xE6101014),
                modifier = Modifier.fillMaxHeight().width(drawerWidth),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "播放列表 (" + playQueue.size + ")",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { playlistOpen = false }) {
                            Icon(Icons.Outlined.Close, "收起", tint = Color.White)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    LazyColumn(Modifier.fillMaxSize(), state = playlistListState) {
                        itemsIndexed(playQueue) { i, entry ->
                            SwipeToDeleteRow(
                                modifier = Modifier.fillMaxWidth(),
                                onDelete = { deletePlaylistItem(i) },
                            ) {
                                val isCurrent = i == currentIndex
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        // 先铺不透明底色再叠加高亮：当前行高亮是半透明的，
                                        // 直接铺会透出底层红色删除层（垃圾桶与播放指示重叠）
                                        .background(Color(0xFF17171B))
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                            else Color.Transparent,
                                        )
                                        .clickable {
                                            if (i != currentIndex) {
                                                loadEpisodeAt(i)
                                                toast("已切换：" + entry.fn)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isCurrent) {
                                        PlayingBarsIndicator()
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Text(
                                            (i + 1).toString(),
                                            color = Color.White.copy(alpha = 0.45f),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.width(20.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        entry.fn,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.88f),
                                        fontWeight = if (isCurrent) FontWeight.Medium else null,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "播放中",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSubSearch) {
        SubtitleSearchDialog(
            client = container.okHttpClient,
            api = container.openApi,
            videoDurationMs = durationMs,
            initialKeyword = (data?.fileName ?: currentName).substringBefore('.').ifBlank { currentName },
            uploadTargetCid = data?.parentId?.takeIf { it.isNotBlank() },
            uploadBaseName = (data?.fileName ?: currentName).substringBeforeLast('.'),
            onLoad = { cfg, bytes, ext ->
                showSubSearch = false
                loadOnlineSub(cfg, bytes, ext)
            },
            onDismiss = { showSubSearch = false },
        )
    }
}

/**
 * 上一集 / 下一集按钮。
 * 不可用时透明度降到 35% 且点击无响应（IconButton(enabled=false) 本身也会关掉 Ripple）；
 * 可用时保留标准 Ripple 点击态。
 */
@Composable
private fun EpisodeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.35f),
    ) {
        Icon(icon, description, tint = Color.White)
    }
}

@Composable
private fun OffsetChip(text: String, onClick: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 播放器右上角状态指示栏（模块 C）：实时网速 / 当前时间 / 电量 / 视频规格胶囊。
 * 只渲染设置页「界面显示」里开启的项目；四项全关时整体不占位。
 */
@Composable
internal fun PlayerStatusRow(
    netSpeedText: String,
    clockText: String,
    batteryPct: Int?,
    specText: String?,
    showNetSpeed: Boolean,
    showClock: Boolean,
    showBattery: Boolean,
    showSpec: Boolean,
) {
    val hasSpeed = showNetSpeed && netSpeedText.isNotBlank()
    val hasClock = showClock && clockText.isNotBlank()
    val hasBattery = showBattery && batteryPct != null
    val hasSpec = showSpec && !specText.isNullOrBlank()
    if (!hasSpeed && !hasClock && !hasBattery && !hasSpec) return

    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSpeed) {
                Text(
                    netSpeedText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (hasClock) {
                Text(
                    clockText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (hasBattery && batteryPct != null) {
                val pct = batteryPct!!
                // 微型电池：外框 + 按百分比填充 + 正极凸点（自绘，避免依赖扩展图标集）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 15.dp, height = 7.dp)
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.85f),
                                RoundedCornerShape(2.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                                .background(
                                    when {
                                        pct <= 20 -> MaterialTheme.colorScheme.error
                                        pct <= 50 -> Color(0xFFFFB300)
                                        else -> Color.White
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.width(1.dp))
                    Box(
                        Modifier
                            .size(width = 2.dp, height = 3.dp)
                            .background(Color.White.copy(alpha = 0.85f)),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        pct.toString() + "%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (hasSpec) {
                Surface(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        specText ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * 缓冲中动效：半透明胶囊 + 一段旋转的弧 + 文案。
 *
 * 手绘单段弧（而不是 CircularProgressIndicator）是为了轻：播放页每帧都在解码，
 * 指示器只画一条 100° 的弧 + 呼吸透明度，视觉上也更贴合深色画面。
 */
@Composable
private fun BufferingPill() {
    val transition = rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "bufferingAngle",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(680, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bufferingAlpha",
    )
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                drawArc(
                    color = Color.White.copy(alpha = alpha),
                    startAngle = angle,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.4.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "缓冲中…",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 播放中的动态均衡器图标（三根跳动的音量柱） */
@Composable
private fun PlayingBarsIndicator(
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "playing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "phase",
    )
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        listOf(0.55f, 1f, 0.72f).forEachIndexed { i, base ->
            val wave = 0.45f + 0.55f * (((phase + i * 0.33f) % 1f))
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .size(width = 3.dp, height = (10 * base * wave).dp)
                    .background(color),
            )
        }
    }
}

/** VR 生效时开关按钮的高亮色（暗金，黑底画面上醒目但不刺眼） */
private val VrActiveColor = Color(0xFF8A6A16)

/**
 * VR 模式选择条（横排胶囊）。
 *
 * 为什么不用下拉菜单 / Dialog：播放器是横屏，控制排 4 秒无操作就整体淡出，
 * 弹层挂着的时候控制排一淡出，弹层就会孤零零留在画面上（或者被一起隐藏后
 * 用户以为点空了）。做成同一排里的内联胶囊，跟控制排同生共死，行为最可预期。
 *
 * 「关闭」与四个模式是互斥单选；取眼 / 陀螺仪是两个独立开关；复位是动作。
 */
@Composable
internal fun VrModeMenu(
    current: VrMode?,
    autoMode: Boolean,
    rightEye: Boolean,
    gyroOn: Boolean,
    gyroAvailable: Boolean,
    panniniD: Float,
    windowMode: VrWindow,
    /** 视窗档位只在竖屏有意义（横屏长宽比不是瓶颈，见 [VrWindow]），横屏不显示这一行 */
    showWindowOptions: Boolean,
    onPick: (VrMode?) -> Unit,
    onAuto: () -> Unit,
    onToggleEye: () -> Unit,
    onToggleGyro: () -> Unit,
    onRecenter: () -> Unit,
    onPannini: (Float) -> Unit,
    onPanniniCommit: (Float) -> Unit,
    onWindow: (VrWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 低分辨率横屏下 8 个胶囊可能超宽，允许横向滚动兜底
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                // 「自动」必须单独给一个入口：否则用户一旦选过「关闭」，
                // 自动识别就被永久关掉、没有任何回头的路。
                VrChip("自动", autoMode) { onAuto() }
                VrChip("关闭", !autoMode && current == null) { onPick(null) }
                // 顺序即日常使用频率：VR180 远多于 360
                VrMode.entries.forEach { m ->
                    VrChip(m.label, current == m) { onPick(m) }
                }
                VrChip(if (rightEye) "右眼" else "左眼", false) { onToggleEye() }
                VrChip(
                    label = when {
                        !gyroAvailable -> "无陀螺仪"
                        gyroOn -> "陀螺仪开"
                        else -> "陀螺仪关"
                    },
                    selected = gyroOn && gyroAvailable,
                    enabled = gyroAvailable,
                    onClick = onToggleGyro,
                )
                VrChip("复位", false) { onRecenter() }
            }
            Spacer(Modifier.height(4.dp))
            // 边缘畸变抑制：0 = 直线透视，1 = 标准 Pannini，越大越接近柱面。
            // 纵向不随它变，所以拖的时候画面中心大小不动，只改边缘 —— 好判断。
            // 整行在菜单里居中：此前贴左 + 滑杆只有 160dp，右侧大片空白，
            // 整个菜单的视觉重心被推向一侧，看起来就像整体没居中。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    "边缘畸变抑制",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Slider(
                    value = panniniD,
                    onValueChange = onPannini,
                    onValueChangeFinished = { onPanniniCommit(panniniD) },
                    valueRange = 0f..VrViewState.MAX_PANNINI,
                    modifier = Modifier.width(300.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (panniniD < 0.05f) "关"
                    else String.format(java.util.Locale.US, "%.1f", panniniD),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.width(26.dp),
                )
            }
            // 视窗档位（仅竖屏）：收窄长边把 Pannini 压不到的垂直畸变压下来。
            // 横屏不显示 —— 横屏的长宽比不是瓶颈（实测 4:3~21:9 最差拉伸都锁在
            // 1.65~1.70×），收窄只会白丢画面宽度，放一个在当前方向下不起作用的
            // 开关比不放更糟。放在单独一行而不是并进上面那排：竖屏屏窄，
            // 11 个胶囊必然横向溢出，得滚动才够得着。
            if (showWindowOptions) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        "视窗",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    VrWindow.entries.forEach { w ->
                        VrChip(w.label, windowMode == w) { onWindow(w) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VrChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = when {
            !enabled -> Color.White.copy(alpha = 0.10f)
            selected -> Color.White
            else -> Color.White.copy(alpha = 0.15f)
        },
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onClick() },
    ) {
        Text(
            label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                selected -> Color(0xFF1B1B1B)
                else -> Color.White
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}
