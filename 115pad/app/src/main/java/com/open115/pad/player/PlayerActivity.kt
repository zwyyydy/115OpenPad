package com.open115.pad.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.open115.pad.ui.theme.Open115Theme
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

private fun defLabel(def: Int): String = when (def) {
    1 -> "标清"; 2 -> "高清"; 3 -> "超清"; 4 -> "1080P"; 5 -> "4K"; 100 -> "原画"; else -> "清晰度 $def"
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pickCode = intent.getStringExtra(EXTRA_PICK_CODE)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        if (pickCode.isNullOrBlank()) {
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
                    initialPickCode = pickCode,
                    initialName = name,
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 当前条目：切集时改这两个状态，下方 LaunchedEffect(currentPickCode) 会自动整套重载
    var currentPickCode by remember { mutableStateOf(initialPickCode) }
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
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) 115OpenPad/0.1")
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

    suspend fun buildItem(def: Int): Pair<Int, MediaItem>? {
        val d = data ?: return null
        val entry = d.videoUrls.firstOrNull { it.definition == def } ?: return null
        val url = entry.url ?: return null
        var trackUrl = url
        if (currentAudio >= 0) {
            trackUrl += (if (url.contains('?')) "&" else "?") + "audio_track=$currentAudio"
        }
        val cfgs = if (useSubs && subsEnabled) (subtitles + onlineSubs).map { s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                .setMimeType(s.mime)
                .setLanguage(s.language)
                .setLabel(s.title)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        } else emptyList()
        val item = MediaItem.Builder()
            .setUri(trackUrl)
            // 115 的播放地址没有 .m3u8 后缀，必须显式声明 HLS 类型，
            // 否则 ExoPlayer 会按普通容器解析导致 PARSING_CONTAINER_UNSUPPORTED
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(cfgs)
            .build()
        return entry.definition to item
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
                            val fresh = parseVideoPlayResponse(container.openApi.videoPlay(currentPickCode))
                            data = fresh
                            val pos = player.currentPosition
                            val built = buildItem(currentDef)
                            if (built == null) {
                                error = "播放失败（${err.errorCodeName}）：地址刷新后仍不可用"
                                return@launch
                            }
                            player.setMediaItem(built.second, if (pos > 0) pos else 0L)
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
                // 预算用完（或非 403 错误）→ 走原有的字幕降级 → 终态报错链路
                if (useSubs && subtitles.isNotEmpty()) {
                    useSubs = false
                    scope.launch {
                        val pos = player.currentPosition
                        val built = buildItem(currentDef)
                        if (built != null) {
                            player.setMediaItem(built.second, if (pos > 0) pos else 0L)
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
            val pos = player.currentPosition
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

    // 首次进入与切集共用同一条加载链路：currentPickCode 一变就整套重载
    LaunchedEffect(currentPickCode, prefs) {
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
            val startPos = pendingStartMs ?: runCatching {
                parseVideoHistoryTime(container.openApi.videoHistoryGet(currentPickCode)) * 1000
            }.getOrNull() ?: 0L
            pendingStartMs = null

            val d = parsed
            val targetDef = d.userDef
                ?.takeIf { def -> d.videoUrls.any { it.definition == def } }
                ?: d.videoUrls.filter { it.definition != 100 }.maxOfOrNull { it.definition }
                ?: d.videoUrls.maxOfOrNull { it.definition }
                ?: 4
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

    fun switchQuality(def: Int) {
        scope.launch {
            val pos = player.currentPosition
            val built = buildItem(def) ?: return@launch
            val (d, item) = built
            currentDef = d
            player.setMediaItem(item, pos)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun switchAudio(index: Int) {
        scope.launch {
            currentAudio = index
            val pos = player.currentPosition
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
                val pos = player.currentPosition
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
                val pos = player.currentPosition
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
            if (landscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
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
            DisposableEffect(textureView) {
                player.setVideoTextureView(textureView)
                onDispose { player.clearVideoTextureView(textureView) }
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
            Box(
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
            // 手势层：锁定时整体禁用（单击/双击/长按/垂直音量亮度/横向 seek 全部拦截）
            PlayerGestureOverlay(
                modifier = Modifier.fillMaxSize(),
                enabled = !screenLocked,
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
            // 画面旋转键：右侧垂直居中，与左侧锁屏键水平对称；
            // 随控制排自动淡出/单击唤出，锁定状态强制隐藏禁用
            androidx.compose.animation.AnimatedVisibility(
                visible = controlRowVisible && !screenLocked,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                    modifier = Modifier.padding(end = 18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                videoRotation = (videoRotation + 90) % 360
                                setIndicator("画面旋转 ${videoRotation}°", null)
                                pulseControlRow()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ScreenRotation,
                            contentDescription = "画面旋转 90°",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
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
                                        DropdownMenuItem(
                                            text = { Text("搜索在线字幕…") },
                                            onClick = {
                                                subsMenuOpen = false
                                                onSubSearch()
                                            },
                                        )
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
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box {
                VideoSurface(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    onSubSearch = { showSubSearch = true },
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlRowVisible && !screenLocked,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        IconButton(onClick = { playlistOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "播放列表",
                                tint = Color.White,
                            )
                        }
                    }
                }
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
