package com.open115.pad.ui.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.SearchHistory
import com.open115.pad.data.media.ActorAvatarRef
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.PinyinText
import com.open115.pad.data.media.SEARCH_PAGE_SIZE
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieRecommender
import com.open115.pad.data.media.QueuedScan
import com.open115.pad.data.media.ScanMode
import com.open115.pad.data.media.MediaPrefs
import com.open115.pad.data.media.WatchHistoryRow
import com.open115.pad.data.media.WorksSort
import com.open115.pad.data.media.autoScanModeOrDefault
import com.open115.pad.data.media.decodePickCodes
import com.open115.pad.data.media.label
import com.open115.pad.player.PlayerActivity
import com.open115.pad.player.PlayerCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.open115.pad.ui.components.EmptyState
import com.open115.pad.ui.components.GlassCard
import com.open115.pad.ui.components.MetaChip
import com.open115.pad.ui.components.SearchHistoryRow
import com.open115.pad.ui.components.auroraBackdrop
import com.open115.pad.ui.components.dissolve
import com.open115.pad.ui.history.displaySubtitle
import com.open115.pad.ui.history.displayTitle
import com.open115.pad.ui.history.isFinished
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.AppSwitch
import com.open115.pad.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import com.open115.pad.ui.components.FadeAlertDialog
import com.open115.pad.ui.components.FadeIn

// 媒体库各页之间淡入淡出溶解的时长（列表↔海报墙、浮层栈进出共用）
private const val DISSOLVE_IN_MS = 500
private const val DISSOLVE_OUT_MS = 450

/**
 * 海报共享元素的位移/缩放曲线：显式三次贝塞尔"快出慢收"，不用默认弹簧 ——
 * 弹簧在长距离上末段会回弹一下，和页面淡变叠在一起像"冲过头再顿住"。
 * 420ms 比默认稍长，给眼睛留出跟上"这张海报从墙上飞过来"的时间；
 * 进出共用这条曲线，退回时才能原路缩回去。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val PosterBounds = BoundsTransform { _, _ ->
    tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}

/**
 * "这一层浮层是从哪一张海报点开的"：mediaKey 用来在重建后的列表里认出那张卡，
 * sharedKey 是两侧共享元素的对上号。
 *
 * 浮层栈只渲染栈顶，下层页面在转场结束后会被销毁；停留久一点再退回时它是全新组合，
 * 页面内的 pending 状态早就没了 —— 所以这份记录必须放在页面之外，否则只有"秒退"才有
 * 海报缩回动画，稍久一点就只剩淡出（用户报的效果不统一）。
 */
private data class OpenedPoster(val mediaKey: String, val sharedKey: String)

/**
 * 一层详情页的"页面外状态"：滚动位置 + 推荐批次。页面被下一层详情盖住时会销毁，
 * 退回重建必须接回这里 —— 滚动位置不接回，推荐行在屏幕外，共享元素没有可落的位置；
 * 推荐列表不接回，推荐行要等 Room 查完才出现，目标卡会晚于转场才注册（只剩淡出）。
 */
private data class DetailPageState(
    val scroll: Int = 0,
    val shuffleTick: Int = 0,
    val recs: List<MovieRecommender.Recommendation> = emptyList(),
)

/**
 * 库卡片"脸面"的缓存键：封面图列表与部数。
 *
 * 为什么需要缓存：库列表是 LazyColumn，快速上下滑时卡片会被**销毁再重建**。
 * 没有缓存时每次重建都从空值起步、重新查一遍 Room + 列一遍海报缓存目录 ——
 * 用户看到的就是"滑过去再滑回来，明明看过的封面又要重新加载一遍"（部数徽标也会消失再出现）。
 * 键里带上 refreshTick / scanFinishedAt，扫描或删片后自动换一代，不会拿旧数据。
 *
 * [covers] 是封面要几张（1×1 卡 1 张、1×2 卡 2 张、1×3 卡 6 张）；部数用 0（与封面区分开）。
 */
private data class LibraryFaceKey(
    val id: Long,
    val refreshTick: Int,
    val scanFinishedAt: Long,
    val covers: Int,
)

/**
 * 媒体库页（类 Yamby）：左侧独立入口。
 * 「新建媒体库」→ 输名字 + 从云盘目录树选路径 → 保存为库；
 * 每个库可手动扫描（带进度、可续跑）或删除。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun MediaLibraryScreen(api: com.open115.pad.data.OpenApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.appContainer
    val dao = container.mediaDatabase.mediaDao()
    // 手机（窄屏）排版开关：搜索框 + 3 个图标在 411dp 宽下会互相挤压，按这个标志切紧凑形态
    // （搜索结果网格的列宽也跟着它）；平板（>=600dp）保持原样
    val screenW = LocalConfiguration.current.screenWidthDp
    val compact = screenW < 600
    // 宽屏首页：「最近新增 / 继续观看」的横排海报用大一号的卡
    val wideHome = screenW >= 1000
    // 横排海报的宽度：「最近新增 / 继续观看」在宽屏上用大一号的卡
    val tileWidth = if (wideHome) 168.dp else 108.dp

        val libraries by dao.libraries().collectAsState(initial = emptyList())
/**
     * 拖动排序：松手先把新顺序摆在界面上（乐观），再落盘；Room 的 Flow 追上来后清掉临时顺序。
     *
     * 为什么要有这一层：`libraries()` 是 Flow，写入到回流有一次往返，直接等它会让"松手→卡片归位"
     * 中间空一拍。临时顺序与库里实际顺序**内容一致**时立刻交还，所以不会长期盖着真数据。
     */
    var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
    val orderedLibs = remember(libraries, pendingOrder) {
        val order = pendingOrder ?: return@remember libraries
        val byId = libraries.associateBy { it.id }
        val mapped = order.mapNotNull { byId[it] }
        // 拖动期间列表可能被改（新建/删库/扫描收尾）：对不上就退回库里自己的顺序，别少画几张卡
        if (mapped.size == libraries.size) mapped else libraries
    }

    /**
     * 每张库卡的**大小与封面数**：用户在「⋯ → 卡片大小」里定义的（持久化在 [MediaPrefs]）。
     *
     * 没有条目的库 = 自动（老规则：奇数个库时第一张当通栏主推位）。手机是单列，
     * 一律 1×1、一张封面 —— "pad 模式才可定义"（见 [libraryTiles]）。
     */
    val cardSizes by container.mediaPrefs.libraryCardSpans.collectAsState(initial = emptyMap())
    /** 有没有库定义过固定大小：有的话 pad 网格改成**一行三等分**（见下面的 [libColumns]） */
    val anyManualSize = orderedLibs.any { cardSizes[it.id] in 1..3 }

    /**
     * 网格列数。
     *
     * ★ **横屏 + 定义过卡片大小时一行三等分**：1×1 = 1/3、1×2 = 2/3、1×3 = 整行 ——
     *   不然 2 列屏上 1×2 / 1×3 都只能占满整行，两者看着一模一样（用户报的问题）。
     * ★ **竖屏 pad（600~859dp）也两列**：原来落到 1 列，一张卡横贯整屏、右边全是空的
     *   （竖屏实测很难看）；竖屏只有 2 格宽，1×2/1×3 都占满整行，靠封面数和卡片高度区分。
     *   没定义过时保持老密度（平板 2 列 / 宽屏 3 列，不超过库的总数）。
     */
    val libColumns = when {
        orderedLibs.isEmpty() -> 1
        compact -> 1
        anyManualSize && screenW >= 860 -> 3
        else -> minOf(
            when {
                screenW >= 1240 -> 3
                screenW >= 600 -> 2
                else -> 1
            },
            orderedLibs.size,
        )
    }

    /**
     * 网格是不是"窄卡"布局（1 格 < 430dp）：竖屏两列 ≈333、横屏三等分 ≈360 都算窄，
     * 横屏两列 ≈553 不算。窄卡才需要统一高度 + 标签单行（见 LibraryCard 的 uniformHeight）。
     */
    val narrowLayout = isNarrowLibraryGrid(screenW, libColumns, compact)

    /** 1 格宽度 / 一张卡宽度（dp）：封面张数要不要折行由后者决定（见 libraryCoverColumns） */
    val unitWidth = libraryUnitWidth(screenW, libColumns)
    LaunchedEffect(libraries, pendingOrder) {
        val pending = pendingOrder ?: return@LaunchedEffect
        if (libraries.map { it.id } == pending) pendingOrder = null
    }
    val dragHost = remember { LibraryDragHost() }

    val libTiles = remember(orderedLibs, libColumns, compact, cardSizes) {
        libraryTiles(orderedLibs.map { it.id }, libColumns, compact, cardSizes)
    }
    /** 按 span 装行：1×2 + 1×1 能拼成一整行（见 [packLibraryTiles]） */
    val libRows = remember(libTiles, libColumns) { packLibraryTiles(libTiles, libColumns) }
    val libsById = remember(orderedLibs) { orderedLibs.associateBy { it.id } }
    /** 每张卡要几张封面：封面的预取与渲染必须同一份口径 */
    val coversById = remember(libTiles) { libTiles.associate { it.id to it.covers } }

    /** 松手落定：算新顺序 → 乐观生效 → 落盘（空拖动/原地放回都直接返回，不白写库） */
    fun dropLibrary(fromId: Long, hoverId: Long) {
        val ids = orderedLibs.map { it.id }
        val next = MediaLibraryEntity.reorderIds(
            ids = ids,
            from = ids.indexOf(fromId),
            hover = ids.indexOf(hoverId),
        )
        if (next == ids) return
        pendingOrder = next
        scope.launch { dao.reorderLibraries(next) }
    }

    val scanProgress by container.mediaScanner.progress.collectAsState()
    // 排队中的扫描任务（扫描中也能继续建 —— 顺序执行）。队首就是下一个要跑的
    val pendingScans by container.mediaScanner.pending.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    var editTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    // 点库卡片进海报墙，再点海报进详情（两级都盖在本页之上，返回逐层退）
    var openedLib by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    /**
     * 详情页之上的浮层**栈**：详情 → 同演员/同标签的作品 → 详情 → …
     *
     * 早先只有一个 `detail` 变量（详情页之上叠不了东西），点演员要跳转就放不下了。
     * 用栈也是**导航语义**：返回键逐层退，跟用户点进来的顺序一致。
     *
     * 只渲染栈顶那一层（下面几层不保持组合）：回到上一层时它的数据重查一次 ——
     * 都是本地 Room 查询（毫秒级），换来的是内存不随层数增长。
     */
    val overlays = remember { mutableStateListOf<MediaOverlay>() }
    /** 浮层 id → 从它这里点开的那张海报：转场销毁重建后靠它把同一张重新挂上共享元素（见 [OpenedPoster]） */
    val openedPoster = remember { mutableStateMapOf<Long, OpenedPoster>() }
    /** 详情页的滚动位置与推荐批次（浮层 id → 状态）：重建后接回，退回才有飞回去的落点 */
    val detailPageCache = remember { mutableMapOf<Long, DetailPageState>() }
    /** 作品页网格位置（浮层 id → 首项/偏移）：详情盖上来时页面销毁，退回要停在原处 */
    val worksGridCache = remember { mutableMapOf<Long, Pair<Int, Int>>() }
    /** 哪些详情浮层已经滚动过：滚动后它的海报不再挂共享元素（覆盖层里的图不跟滚动） */
    var posterShareOff by remember { mutableStateOf<Long?>(null) }
    /**
     * 正在编辑兜底海报裁剪的背景图 pickCode（null = 没开）。
     *
     * 状态放在媒体库页这一层、浮层也画在 SharedTransitionLayout **之后**：详情页那张海报
     * 匹配着墙上的卡，会被画进共享元素的顶层覆盖层里 —— 浮层画在详情页内部的话，
     * 海报会压在编辑界面上（实测左边露出一截旧海报）。
     */
    var cropEditPick by remember { mutableStateOf<String?>(null) }
    /**
     * 从墙上点开的那张海报（mediaKey）：浮层栈非空期间，墙上这张交给共享元素接管、原地隐藏。
     *
     * 为什么不能直接用普通 [androidx.compose.animation.SharedTransitionScope.sharedElement]：
     * 墙在**另一个** AnimatedContent 里，点开详情时它自己的转场是静止的。Compose 只给正在
     * 转场的一侧驱动边界动画 —— 实测静止那一侧会被直接拉到终点：卡原地消失、图直接出现在
     * 详情页海报的位置，中间没有飞行（用户报的"海报墙进详情没有动效"）。
     * 换成 caller-managed 可见性后，visible 翻转会让这张卡自己跑一个过渡，飞行动画才有源。
     */
    var wallPosterInFlight by remember { mutableStateOf<String?>(null) }
    /**
     * 墙上共享元素的"代次"：退回墙时 +1。
     *
     * 墙是一直活着的（浮层盖在它上面，页面没销毁），它的共享元素节点也是老的 ——
     * 返回时只把 visible 翻回 true 还不够：老节点不会被重新放置，边界动画没有启动的机会，
     * 结果就是详情页的大海报原地淡出、墙上的卡直接出现（作品页返回能用是因为那页整个重建了）。
     * 换一个 key 让节点销毁重建，效果等同于"页面重建"，返回的收缩动画才会跑。
     */
    var wallPosterEpoch by remember { mutableStateOf(0) }
    // 作品页最近一次的数据（kind+name → cards）：栈只渲染栈顶，详情盖上来时作品页实例被销毁，
    // 退回是全新组合。它纯黑底起步、背景图也取自 cards，不喂初值的话头几帧是"黑底空网格"，
    // 叠上详情页的淡出就是肉眼可见的闪黑。查完写回（onCardsLoaded），重建时当首帧初值。
    val worksCache = remember { mutableMapOf<Pair<WorksKind, String>, List<MovieCard>>() }
    /**
     * 库卡片的封面 / 部数缓存（键见 [LibraryFaceKey]）。
     *
     * 用普通 HashMap 而不是 SnapshotStateMap：它只在组合时被读一次当**首帧初值**，
     * 结果回来由各自的 produceState 推给 UI —— 不需要让一次写入把整屏卡片全部重组。
     */
    val libraryCoverCache = remember { HashMap<LibraryFaceKey, List<String>>() }
    val libraryCountCache = remember { HashMap<LibraryFaceKey, Int>() }
    // 入栈/出栈各包一层：转场目前是方向无关的淡入淡出，这两个只是把"改栈"收口到一处，
    // 将来若要换回带方向的滑动，方向标志在这里加回即可。
    fun pushOverlay(o: MediaOverlay) { overlays.add(o) }
    fun popOverlay() {
        overlays.removeLastOrNull()
        // 退回墙：清掉"在飞的那张"并把墙上的共享元素节点换一代。
        // 只翻 visible 不够：墙一直活着，老节点不会被重新放置，边界动画没有启动的机会
        // （实测详情页的大海报原地淡出、墙上的卡直接出现）；换 key 让节点重建才有返回动画。
        if (overlays.isEmpty()) {
            wallPosterInFlight = null
            wallPosterEpoch++
        }
    }
    /**
     * 海报墙的刷新信号，**墙和详情页共用**。
     *
     * 详情页是盖在墙上面的浮层，墙并没有被销毁 —— 在详情页里删掉一条之后返回，
     * 墙还捧着删除前的列表（实测：DB 里已经 17 部，墙上仍显示 8 部）。
     * 所以删除成功要由这一层往下传一个"变了"的信号，两边都跟着它重查。
     */
    var wallTick by remember { mutableStateOf(0) }

    /**
     * 后台把每个库的**部数 + 封面**先算出来塞进缓存。
     *
     * 快速滑动时卡片是"滑到才组合"的：每张都现查 Room（部数/候选海报）+ 现列一遍海报缓存目录
     * （`cachedAmong` 里 dir.list + 逐个 stat），卡片多了就来不及。这里按顺序先备好，
     * 滑到哪张基本都能直接命中缓存，首帧就有图（Coil 再从内存缓存取位图）。
     *
     * 每 4 个库让出一帧：几百个库时也不能把滑动/启动卡住。查询本身都是 suspend，
     * 文件遍历在 IO 线程（见 cachedAmong），主线程只做调度。
     */
    LaunchedEffect(orderedLibs, libTiles, wallTick, scanProgress.finishedAt) {
        if (orderedLibs.isEmpty()) return@LaunchedEffect
        val tick = wallTick
        val at = scanProgress.finishedAt
        // 换一代就清掉上一代的条目：键里带着 tick/时间，旧条目不会再被读到，留着只是白占内存
        libraryCoverCache.keys.removeAll { it.refreshTick != tick || it.scanFinishedAt != at }
        libraryCountCache.keys.removeAll { it.refreshTick != tick || it.scanFinishedAt != at }
        orderedLibs.forEachIndexed { i, lib ->
            // 封面张数跟着这张卡的定义走（1×3 要 6 张）：缓存键里带 covers，换了大小会重新取
            val covers = coversById[lib.id] ?: 1
            val countKey = LibraryFaceKey(lib.id, tick, at, 0)
            if (libraryCountCache[countKey] == null) {
                libraryCountCache[countKey] = lib.rootPaths.sumOf { dao.movieCountIn(it) }
            }
            val coverKey = LibraryFaceKey(lib.id, tick, at, covers)
            if (libraryCoverCache[coverKey] == null) {
                val candidates = lib.rootPaths.flatMap { dao.posterSamplesInPath(it) }.distinct()
                val models = container.imageUrlResolver.cachedAmong(candidates, container.cacheDir, covers)
                // 空结果不缓存：库可能是"还没有缓存海报"，等用户浏览过海报墙就该补上新封面
                if (models.isNotEmpty()) libraryCoverCache[coverKey] = models
            }
            if (i % 4 == 3) delay(16)
        }
    }

    /**
     * 这一页的搜索：与海报墙**同一个查询**（片名/演员/标签，跨所有媒体库）。
     * 有搜索词时列表换成结果网格，点结果直接开详情浮层（走上面那个浮层栈）。
     */
    var query by remember { mutableStateOf("") }
    // 搜索结果跟着"作品排序"设置走（与海报墙/作品页同一个设置，不在这里再放一个菜单）
    val sort by container.mediaPrefs.worksSort.collectAsState(initial = WorksSort.DEFAULT)
    // 分页：先要 60 条，滚到底再加一页（不再一次拉全库）
    var searchLimit by remember(query) { mutableIntStateOf(SEARCH_PAGE_SIZE) }
    val hits by produceState<List<MovieCard>?>(initialValue = null, query, sort, searchLimit) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        // 只有第一页防抖；翻页是用户已经滚到底的明确动作，不该再等
        if (searchLimit == SEARCH_PAGE_SIZE) delay(250)
        value = dao.searchAll(
            keyword = query.trim(),
            pinyin = PinyinText.normalizeQuery(query),
            limit = searchLimit,
            offset = 0,
            sort = sort.sql,
        )
    }
    // 历史：输入停 900ms 落一条（merge 会顶掉"庆"→"庆余"这种中间态）
    val searchHistory by container.searchHistory.media.collectAsState(initial = emptyList())
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isNotBlank()) {
            delay(900)
            container.searchHistory.add(SearchHistory.Scope.MEDIA, q)
        }
    }
    // 结果网格滚到底 → 再要一页（total >= limit 才说明可能还有）
    val searchGridState = rememberLazyGridState()
    LaunchedEffect(searchGridState, query, sort) {
        snapshotFlow {
            val last = searchGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(last, hits?.size ?: 0, searchLimit)
        }.collect { (last, total, limit) ->
            if (query.isNotBlank() && total > 0 && total >= limit && last >= total - 8) {
                searchLimit += SEARCH_PAGE_SIZE
            }
        }
    }
    // 返回键：正在搜索就先清搜索（与海报墙一致）
    androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) { query = "" }

    // ---- 首页化的两个横排（只在非搜索态出现） ----

    /** 继续观看：观影历史里"开了头还没看完"的前 12 条（判据与观影历史页同一个 isFinished）。
     *  ★ 只要还在库里的（[WatchHistoryRow.inLibrary]）：片删了（详情页删、扫描陈旧清理）就不再出现——
     *    观影历史页那边故意连孤儿行也显示（退回用文件名认），横排上它只是一张没头没名的死卡 */
    val historyRows by dao.watchHistoryRows(60).collectAsState(initial = emptyList())
    val continueRows = remember(historyRows) {
        historyRows.filter { it.inLibrary && !isFinished(it) && it.positionMs > 0 }.take(12)
    }

    /**
     * 最近新增：扫描记录里的 newKeys 跨库摊平、同一部片取最新一次、按时间倒序取前 24。
     *
     * 为什么走 scan_log 而不是 movies.dateAdded：dateAdded 是 nfo 里刮削器写的"入库时间"，
     * 老资源可能是几年前的时间戳；scan_log 的 newKeys 是"这一轮扫描**真的**发现了它"，
     * 语义就是「最近新增」，和扫描记录浮层看到的完全一致。
     */
    val scanLogRows by dao.scanLogs(40).collectAsState(initial = emptyList())
    val recentPairs = remember(scanLogRows) {
        scanLogRows
            .flatMap { log -> log.newKeyList.map { it to log.at } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .map { (key, ats) -> key to (ats.maxOrNull() ?: 0L) }
            .sortedByDescending { it.second }
            .take(24)
    }
    val recentCards by produceState<List<MovieCard>>(emptyList(), recentPairs) {
        val byKey = dao.movieCardsByKeys(recentPairs.map { it.first }).associateBy { it.mediaKey }
        value = recentPairs.mapNotNull { byKey[it.first] }
    }

    /**
     * 每个库**最近一轮**扫描新增了几部：卡片上本来只有"上次扫描 7 小时前"，看不出这轮到底
     * 捞到新片没有。数据从上面那份扫描记录里现算（不再发一次查询），库里没有新片就不显示这枚胶囊。
     */
    val recentAdded = remember(scanLogRows) {
        scanLogRows.groupBy { it.libraryId }
            .mapValues { (_, logs) -> logs.maxByOrNull { it.at }?.newKeyList?.size ?: 0 }
    }

    /**
     * 「最近新增」每张卡的**来源库名**（卡片右上角那枚小标签）。
     *
     * 从扫描记录里顺手带过来：同一条记录里就写着 libraryName，不用给 MovieCard 加一列、
     * 也不用再查一次库表。取值规则与 recentPairs 一致（同一个 key 取最新那条记录）——
     * 两处不一致会出现"卡进的是 A 库、标签写着 B 库"。
     */
    val recentLibOf = remember(scanLogRows) {
        scanLogRows
            .flatMap { log -> log.newKeyList.map { key -> key to log } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, logs) -> logs.maxByOrNull { it.at }?.libraryName.orEmpty() }
    }

    /**
     * 发起一个库的扫描（增量/全量共用，按钮收进了库卡片的「⋯」菜单）。
     *
     * **只是排队**（不 suspend、也不借界面的 scope）：正在扫别的库时这一条排在队尾等着，
     * 扫完自动开始 —— 早先是扫描中把按钮置灰、点了也白点。扫描本身跑在扫描器自己的
     * worker scope 里，离开这一页也不会被取消。
     */
    fun startScan(lib: MediaLibraryEntity, mode: ScanMode) {
        container.mediaScanner.enqueue(lib, mode)
    }

    /**
     * 批量刮削的入口：先数"这个库里有多少部缺 nfo 的片"（一次本地查询），数字摆进确认框，
     * 用户点了才真的开跑。数量为 0 就不给点 —— 免得白跑一趟。
     */
    var scrapeAsk by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    var scrapeCount by remember { mutableStateOf(-1) }
    var scrapeExtras by remember { mutableStateOf(false) }
    // 补充模式（默认开）：已有 nfo 的片照旧跳过；没有 nfo 但已有海报/背景的，也不再覆盖
    var scrapeSupplement by remember { mutableStateOf(true) }

    fun askScrape(lib: MediaLibraryEntity) {
        scrapeAsk = lib
        scrapeCount = -1
        scrapeExtras = false
        scrapeSupplement = true
        scope.launch {
            scrapeCount = runCatching { container.metadataScraper.countTargets(lib) }.getOrDefault(0)
        }
    }

    /** 「继续观看」的卡片点了直接续播（与观影历史页同一套意图：本机源走 localIntent） */
    fun playRow(row: WatchHistoryRow) {
        val start = if (isFinished(row)) 0L else row.positionMs
        val title = displayTitle(row)
        val sub = displaySubtitle(row)
        val label = if (sub.isBlank()) title else "$title · $sub"
        context.startActivity(
            if (row.local) {
                PlayerActivity.localIntent(context, row.itemKey, label, start)
            } else {
                PlayerActivity.intent(context, row.itemKey, label, startMs = start, recordHistory = true)
            },
        )
    }

    /**
     * 页面背景用的那张 fanart：从所有库的候选里挑**第一张已经在本地**的（零请求）。
     *
     * 与海报墙的背景轮播只差一条，但是硬性的：**这里不下载**。墙是无人值守在换图，
     * 每张没缓存的 fanart 一次 downurl + 一次下载它认了（见 MediaPosterWall 的 BACKDROP_MAX 注释）；
     * 库列表页是入口页，只挑已经在落盘缓存里的那张，命不中就退回极光底。
     *
     * 键只取 libraries：加/删库才重新挑。同一台机器上挑出来的永远是同一张（不洗牌），
     * 免得每次进页面背景都在变 —— 那一页要的是"有层次"，不是"在动"。
     */
    val backdrop by produceState<Any?>(initialValue = null, libraries) {
        val codes = libraries.flatMap { lib -> lib.rootPaths.flatMap { dao.backdropsInPath(it) } }
        value = container.imageUrlResolver.cachedAmong(codes, container.cacheDir).firstOrNull()
    }

    // 极光底：几团很淡的径向渐变，给毛玻璃卡片一点"透出来的东西"（纯绘制、零请求）。
    // 它同时是背景图那层的**兜底**：一张 fanart 都没缓存时就只剩它，所以浓度不能太低
    // 海报 ↔ 详情共用一个 SharedTransitionLayout：进详情那张海报从墙上/搜索结果卡"飞"到详情页，
    // 退回来再飞回去。两个 AnimatedContent 各自提供 scope，key 用 mediaKey 对上。
    // ★ 它必须包住**本页内容**（搜索结果网格在这里）：搜索结果卡的共享元素修饰要在 scope 里
    //   才构得出来 —— 早先它只包两个 AnimatedContent，列表页的搜索结果卡够不着，
    //   点结果进详情就只剩淡入淡出（用户报的效果不统一）。
    SharedTransitionLayout {
    Box(Modifier.fillMaxSize().auroraBackdrop()) {
    backdrop?.let { model ->
        // 只压暗、**不模糊**。★ 试过 `Modifier.blur`：全屏图加 RenderEffect 直接把 MEmu 的
        //   图形栈压死了（宿主机崩溃日志落在 libOpenglRender2.dll + AMD 的 amdlvk64.dll 上，
        //   客人机 7 秒没心跳）。海报墙上那张同尺寸的图不带模糊、一直是好的，所以问题就在
        //   这一层离屏 RenderEffect 上 —— 背景图这点氛围不值这个代价，何况 blur 只对 API 31+
        //   生效，老机器上仍然是"没糊"的形态。所以用压暗一条路走到底：
        //   图在最亮处也只剩两成亮度（255×0.2≈51，和极光底同一量级），细节看得见但抢不走注意力。
        // 下沿溶进底色（同海报墙）——不溶的话图的下边缘会在屏幕上切出一条硬边
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().dissolve(0.05f, 0.85f),
        )
        // 压暗：上面是深色玻璃卡 + 小字（比海报墙的字更小），不压狠一点卡片边界和标题一起糊掉。
        // 浓度是**靠读数调出来的**，不是拍的：0.72 那档背景上还是能看清"何本も"这类大字、
        // 抢标题；压到 0.86~0.94 之后整页比极光底还暗一档，只剩下照片的明暗肌理
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.88f),
                    0.45f to Color.Black.copy(alpha = 0.86f),
                    1.00f to Color.Black.copy(alpha = 0.94f),
                ),
            ),
        )
    }
    AdaptiveBody(modifier = Modifier.fillMaxSize(), maxWidth = if (wideHome) 1240.dp else 960.dp) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：标题 + 新建
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Movie, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                // 窄屏不给标题留位置：搜索框 + 3 个图标才是这一行必须完整的部分，
                // 硬塞标题会把最后两个图标挤出屏幕（旧版在手机上就是这样）
                if (!compact) {
                    Text(
                        if (query.isBlank()) "媒体库" else "全局搜索「$query」",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 毛玻璃搜索框（这一页没有背景图可透，退化成半透明胶囊）。
                // 窄屏弹性占满剩余宽度（固定 200~340dp 会和图标抢地方）
                FrostedSearchField(
                    value = query,
                    onValueChange = { query = it },
                    backdrop = null,
                    modifier = (
                        if (compact) Modifier.weight(1f)
                        else Modifier.widthIn(min = 200.dp, max = 340.dp)
                        ).padding(end = 4.dp),
                )
                // 三个入口收进一个胶囊：裸放在这一行里时它们和搜索框平铺，每个都像一件独立的主操作，
                // 主次不分。组内用细分隔线断开（新建是这一页的主操作，放最右）
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 记录：扫描 / 迁移 / 观影历史三条流水收在一个浮层里（页头三个页签）。
                    // 三条都是"媒体库这条线自己的账"，一个入口既够用、也省顶栏的位置
                    IconButton(onClick = { pushOverlay(MediaOverlay.Records) }) {
                        Icon(Icons.Outlined.History, contentDescription = "记录")
                    }
                    TopActionDivider()
                    // 标签球：把全库的演员 + 标签摆成一个球（画质/编码类标签剔除），
                    // 点一个词进它的作品页 —— 是"逛库"的入口，和下面两个"看记录"的分开摆
                    IconButton(onClick = { pushOverlay(MediaOverlay.TagBall) }) {
                        Icon(Icons.Outlined.BubbleChart, contentDescription = "标签球")
                    }
                    TopActionDivider()
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建媒体库")
                    }
                }
            }

            // 搜索框为空时铺最近搜索（输入中让位给结果）
            if (query.isBlank()) {
                SearchHistoryRow(
                    history = searchHistory,
                    onPick = { query = it },
                    onClear = { scope.launch { container.searchHistory.clear(SearchHistory.Scope.MEDIA) } },
                )
            }

            // 全局扫描进度 + 跳过。跳过**在目录/簇边界生效**（不会打断正在进行的那个请求），
            // 所以按钮点下去会先变成"跳过中…"，等当前这一小步跑完才真的跳。
            if (scanProgress.running) {
                ScanProgressCard(
                    progress = scanProgress,
                    // 后面还有排队的：这个键是"跳过这一轮，接着跑下一个"（语义变了，文案也要跟着）
                    hasQueued = pendingScans.isNotEmpty(),
                    onSkip = { container.mediaScanner.skipCurrent() },
                )
            } else if (scanProgress.stopped) {
                // 停止后说清两件事：进度没丢、怎么继续（续扫走增量，它按 scan_state 跳过已扫完的目录）。
                // 停止是**连排队一起停**的，撤掉几个也要说 —— 不然排了 5 个库只看到"已停止"，
                // 会以为剩下的还在等着跑。
                Text(
                    // listOfNotNull + joinToString 拼：字符串拼接里的 if 会吞掉后面几段（踩过的坑）
                    listOfNotNull(
                        "扫描已停止（已完成 ${scanProgress.doneDirs}/${scanProgress.totalDirs} 个目录，" +
                            "已索引 ${scanProgress.moviesIndexed} 部）",
                        if (scanProgress.cancelledQueued > 0) {
                            "已取消排队中的 ${scanProgress.cancelledQueued} 个任务"
                        } else {
                            null
                        },
                        "用「增量扫描」可从断点继续",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 排队中的任务：一行一个库，顺序执行。每行都能单独撤（×）——
            // 「全部停止」是一停一批（连正在跑的那个），只想跳过当前那个用上面进度卡的「跳过当前」。
            if (pendingScans.isNotEmpty()) {
                ScanQueueCard(
                    tasks = pendingScans,
                    onCancel = { libraryId -> container.mediaScanner.cancelQueued(libraryId) },
                    onStopAll = { container.mediaScanner.stopAll() },
                )
            }

            // 搜索态：结果网格（跨库）。点结果直接开详情浮层，返回逐层退
            val results = hits
            if (results != null) {
                Text(
                    "片名 / 拼音 / 演员 / 标签 · 已加载 ${results.size} 部 · 全部媒体库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                if (results.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "全部媒体库里都没有匹配「$query」的片名 / 拼音 / 演员 / 标签",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        // 窄屏用更小的列宽 → 手机上排 3 列（130dp 在 411dp 宽下只排得下 2 列，卡片偏大）
                        columns = GridCells.Adaptive(minSize = if (compact) 110.dp else 130.dp),
                        state = searchGridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(results, key = { it.mediaKey }) { card ->
                            PosterCard(
                                card = card,
                                onClick = {
                                    // 与海报墙同一条路：记下这张，浮层栈非空期间它让位给共享元素。
                                    // sharedKey 用独立命名空间（墙上是 "poster-…"）：列表↔墙转场的
                                    // 那几帧两侧可能在同时组合，同 movie 撞 key 会留残影
                                    wallPosterInFlight = card.mediaKey
                                    pushOverlay(
                                        MediaOverlay.Detail(
                                            card, results, results.indexOf(card),
                                            sharedKey = "search-${card.mediaKey}",
                                        ),
                                    )
                                },
                                // 海报从搜索结果卡"飞"进详情页（早先这条路只有淡入淡出，用户报过）。
                                // 机制与海报墙那侧一字不差：本页在浮层栈打开期间一直活着（只是被盖住），
                                // 普通 sharedElement 拿不到边界动画驱动，得用 caller-managed 可见性；
                                // epoch 是返回时换一代让节点重建，返回的收缩动画才有首次放置
                                posterModifier = key(wallPosterEpoch) {
                                    Modifier.sharedElementWithCallerManagedVisibility(
                                        rememberSharedContentState("search-${card.mediaKey}"),
                                        visible = wallPosterInFlight != card.mediaKey || overlays.isEmpty(),
                                        boundsTransform = PosterBounds,
                                    )
                                },
                            )
                        }
                    }
                }
            } else if (libraries.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "还没有媒体库",
                    hint = "选择云盘里的 Emby 资源目录，扫描后影片就会出现在这里",
                    action = {
                        androidx.compose.material3.Button(onClick = { showCreate = true }) {
                            Text("新建媒体库")
                        }
                    },
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    // 库卡片按**每库定义的大小**装行（1×1 / 1×2 / 1×3，见 libraryTiles）：
                    // 定义过大小后 pad 网格一行**三等分** —— 1×1 占 1/3、1×2 占 2/3、1×3 占满，
                    // 所以 1×2 和 1×3 有实实在在的宽度差别；没定义过则保持老密度。
                    // 按行铺而不是用 LazyVerticalGrid：网格和下面两条横排（最近新增 / 继续观看）
                    // 没法在同一个滚动容器里混排，按行 + span 权重就够。
                    items(libRows, key = { row -> row.first().id }) { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { tile ->
                                val lib = libsById[tile.id] ?: return@forEach
                                // 6 张封面在窄卡上折成 3+3（两行），否则文字被挤没；折行后卡片要更高，
                                // 因此**不能**再套统一高度（那一行只有它自己，不影响 1×1 的整齐）
                                val coverCols = libraryCoverColumns(
                                    tile.covers,
                                    libraryCardWidth(screenW, libColumns, tile.span),
                                )
                                LibraryCell(
                                    // 宽度按 span 分：同一张 1×2 在每行里都一样宽
                                    modifier = Modifier.weight(tile.span.toFloat()),
                                    lib = lib,
                                    covers = tile.covers,
                                    coverColumns = coverCols,
                                    cardSize = cardSizes[lib.id] ?: 0,
                                    showCardSize = !compact,
                                    // 窄卡（竖屏两列 / 横屏三等分）才收窄路径、统一高度；
                                    // 封面折成两行的卡本来就更高，不套统一高度
                                    narrow = narrowLayout && tile.span == 1,
                                    uniformHeight = narrowLayout && tile.covers <= coverCols,
                                    onSetCardSize = { size ->
                                        scope.launch { container.mediaPrefs.setLibraryCardSpan(lib.id, size) }
                                    },
                                    dao = dao,
                                    container = container,
                                    coverCache = libraryCoverCache,
                                    countCache = libraryCountCache,
                                    refreshTick = wallTick,
                                    scanFinishedAt = scanProgress.finishedAt,
                                    // 「扫描中 / 排队中」都是**按库**判的（早先只有一个全局 running，
                                    // 一个库在扫就把所有库的扫描按钮一起置灰 —— 队列化之后没这个问题了）
                                    scanning = scanProgress.running && scanProgress.libraryId == lib.id,
                                    queued = pendingScans.any { it.libraryId == lib.id },
                                    recentAdded = recentAdded[lib.id] ?: 0,
                                    canDrag = orderedLibs.size > 1,
                                    host = dragHost,
                                    onOpen = { openedLib = lib },
                                    onScan = { mode -> startScan(lib, mode) },
                                    onScrape = { askScrape(lib) },
                                    onEdit = { editTarget = lib },
                                    onDelete = { deleteTarget = lib },
                                    onDrop = { fromId, hoverId -> dropLibrary(fromId, hoverId) },
                                )
                            }
                            // 行尾不满时**留空位**而不是把卡片拉宽：用户定义的 1×2 在每行都该是 2/列数 宽
                            repeat((libColumns - row.sumOf { it.span }).coerceAtLeast(0)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    // 最近新增：扫描发现的跨库新片，海报横滑，点开详情（「查看全部」进扫描记录）
                    if (recentCards.isNotEmpty()) {
                        item(key = "recent-header") {
                            SectionHeaderRow(
                                "最近新增",
                                actionText = "查看全部",
                                onAction = { pushOverlay(MediaOverlay.Records) },
                            )
                        }
                        item(key = "recent-row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(recentCards, key = { it.mediaKey }) { card ->
                                    PosterTile(
                                        card = card,
                                        libraryName = recentLibOf[card.mediaKey],
                                        width = tileWidth,
                                        onClick = {
                                            pushOverlay(
                                                MediaOverlay.Detail(card, recentCards, recentCards.indexOf(card)),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 继续观看：开了头还没看完的，点了接着上次的位置播
                    if (continueRows.isNotEmpty()) {
                        item(key = "continue-header") { SectionHeaderRow("继续观看") }
                        item(key = "continue-row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(continueRows, key = { it.itemKey }) { row ->
                                    ContinueTile(row = row, width = tileWidth, onClick = { playRow(row) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        LibraryEditDialog(
            api = api,
            title = "新建媒体库",
            existing = null,
            onDismiss = { showCreate = false },
            onSave = { name, cids, paths, rate, autoScan, autoMode, minSize, interval, tiered, restMin ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.insertLibrary(
                        MediaLibraryEntity(
                            name = name, rootCid = cid, rootPath = path,
                            createdAt = System.currentTimeMillis(), rateLimitMs = rate,
                            autoScanOnStart = autoScan, minVideoSizeMb = minSize,
                            autoScanIntervalHours = interval,
                            // 存的是这次选的方式；开关关着时它没有意义，但留着不碍事
                            // （下次打开对话框会回填上次的选择）
                            autoScanMode = autoMode.name,
                            rateTiered = tiered,
                            tierRestMinutes = restMin,
                        ),
                    )
                    showCreate = false
                }
            },
        )
    }

    editTarget?.let { lib ->
        LibraryEditDialog(
            api = api,
            title = "编辑媒体库",
            existing = lib,
            onDismiss = { editTarget = null },
            onSave = { name, cids, paths, rate, autoScan, autoMode, minSize, interval, tiered, restMin ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.updateLibrary(
                        lib.id, name, cid, path, rate, autoScan, minSize, interval,
                        autoMode.name, tiered, restMin,
                    )
                    editTarget = null
                }
            },
        )
    }

    // （SharedTransitionLayout 在上面：它现在包着本页内容 + 下面两个 AnimatedContent，
    //   搜索结果网格的共享元素也要在它的 scope 里，见页面开头那段说明。）
    // 库列表 ↔ 海报墙：同款淡入淡出溶解（点库卡片进、返回退都淡变，不再硬切）。
    // 墙盖在列表之上、浮层栈之下；进详情时 openedLib 不变、墙不动，只有下面浮层那个 AnimatedContent 在淡。
    AnimatedContent(
        targetState = openedLib,
        transitionSpec = {
            fadeIn(tween(DISSOLVE_IN_MS, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(DISSOLVE_OUT_MS, easing = LinearOutSlowInEasing)) using SizeTransform(clip = false)
        },
        contentKey = { it?.id ?: -1L },
    ) { lib ->
        Box(Modifier.fillMaxSize()) {
            if (lib != null) {
                // 这个库的扫描跑完 → 墙重查一次。
                //
                // 墙只看 refreshKey（wallTick），而索引还会被**别的链路**改：文件页刮削回写排的
                // 定向重扫、文件页的移入/移出/删除触发的重扫。那时库里已经变了（新片、新元数据、
                // 少了几部），墙却还挂着旧卡片 —— 用户看到的就是"刮削完媒体库没更新"。
                // 只认这个库自己那一轮（刚扫完的是别的库就不必白查一次）。
                LaunchedEffect(scanProgress.finishedAt, lib.id) {
                    if (scanProgress.finishedAt > 0 && scanProgress.libraryId == lib.id) wallTick++
                }
                PosterWallScreen(
                    library = lib,
                    dao = dao,
                    refreshKey = wallTick,
                    onChanged = { wallTick++ },
                    onBack = { openedLib = null },
                    onOpenMovie = { card, list, index ->
                        // 记下这一张：浮层栈非空期间墙上它让位给共享元素（见 wallPosterInFlight）
                        wallPosterInFlight = card.mediaKey
                        pushOverlay(MediaOverlay.Detail(card, list, index))
                    },
                    posterShared = { card ->
                        // ★ 这里不能用普通 sharedElement：墙所在的 AnimatedContent 此时是静止的，
                        //   共享元素的边界动画拿不到驱动（会被直接摆到终点）。caller-managed 可见性
                        //   让这张卡在 visible 翻转时自己跑一个过渡；退回（栈空）立刻翻回可见。
                        //   外面再套一层 key：退回时换一代、节点重建，返回动画才有首次放置。
                        key(wallPosterEpoch) {
                            Modifier.sharedElementWithCallerManagedVisibility(
                                rememberSharedContentState("poster-${card.mediaKey}"),
                                visible = wallPosterInFlight != card.mediaKey || overlays.isEmpty(),
                                boundsTransform = PosterBounds,
                            )
                        }
                    },
                )
            }
        }
    }

    // 浮层栈：只渲染栈顶。返回 = 出栈，进详情/进"同演员作品" = 入栈。进出都是淡入淡出溶解。
    AnimatedContent(
        targetState = overlays.lastOrNull(),
        transitionSpec = {
            // alpha 淡变是合成器最便宜的活、基本不掉帧（整屏平移在低端机必掉帧，换缓动都治不了本）。
            //
            // ★ 两边对称淡出/淡入是**经黑的假溶解**，录屏逐帧+截屏连拍实测（"退回作品页黑一下"）：
            //   浮层页全是不透明纯黑底（作品页 Scaffold 是 Color.Black、详情页黑底），新页整层
            //   淡入时它的黑底先上屏、背景图/海报要再等一两百毫秒才画出来；旧页若同时淡完，
            //   中段整屏就只剩黑底（亮度实测 0.0）。所以**旧页退场放慢一倍**：它垫在底下一直
            //   画到新页不透明盖严为止，黑窗被旧页画面和渐显的新页内容填满，没有全黑帧。
            //   （不能用 ExitTransition.None 垫底——那在 AnimatedContent 里是"零时长退场"，
            //   旧页第一帧就被拆掉，实测露出的是更下面的海报墙。）
            //   例外：退到栈空（target=null，下面是海报墙）没有新页盖，保持对称淡出。
            //   曲线：新页「快起缓收」、旧页「线性起步」（前半段保持不透明、后段才收），
            //   比对称线性更顺，也不会让旧页过早透出下面的黑底。
            if (targetState == null) {
                fadeIn(tween(DISSOLVE_IN_MS, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(DISSOLVE_OUT_MS, easing = LinearOutSlowInEasing)) using SizeTransform(clip = false)
            } else {
                fadeIn(tween(DISSOLVE_IN_MS, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(DISSOLVE_OUT_MS * 2, easing = LinearOutSlowInEasing)) using SizeTransform(clip = false)
            }
        },
        // key 只认浮层实例自己的 id（创建后不变）：入栈/出栈换了实例 → id 变 → 走转场；
        // 刮削写完原地换卡（overlays[at]=… 带着原 id）→ key 不变 → 只更新内容、不重播动画。
        // ★ 千万别把栈深掺进来——出栈那一帧栈深就变了，转场里的旧页会被当成新内容重建（见 MediaOverlay.id 注释）
        contentKey = { top -> top?.id ?: -1L },
    ) { top -> Box(Modifier.fillMaxSize()) { when (top) {
        is MediaOverlay.Detail -> {
            // 三种状态要分开看：
            // ① 当前栈顶：正常挂（进详情时它是目标，退回下一层时它是目标）；
            // ② **已经出栈**（退场中的那一页）：也要挂 —— 它是退回动画的**源**，不挂就只有淡出。
            //    原来只放行"栈空"这一种，于是详情→详情退回时海报从不抬起来（只有秒退偶尔撞上）；
            // ③ 还在栈里但不是栈顶（详情→详情刚压上去时的旧页）：**不挂** —— 它的 key 会跟
            //    底下海报墙上的同一张撞上，转场结束后留一张残影（用户截图里的"图片重合"）。
            val inStack = overlays.any { it === top }
            val tagPoster = (!inStack || top === overlays.lastOrNull()) &&
                posterShareOff != top.id
            val pageState = detailPageCache[top.id]
            MediaDetailScreen(
            card = top.card,
            playlist = top.list,
            index = top.index,
            dao = dao,
            // 退回时要把共享元素重新挂到那张推荐卡上（页面重建后 pending 状态已经没了）
            savedRecKey = openedPoster[top.id]?.takeIf { it.sharedKey.startsWith("rec-") }?.mediaKey,
            // 重建时接回滚动位置与推荐列表：推荐行要在首帧就存在、且落在视野里，海报才有地方飞回去
            initialScroll = pageState?.scroll ?: 0,
            onScrollSave = { y ->
                detailPageCache[top.id] = (detailPageCache[top.id] ?: DetailPageState()).copy(scroll = y)
            },
            initialShuffleTick = pageState?.shuffleTick ?: 0,
            initialRecommendations = pageState?.recs.orEmpty(),
            onRecommendationsLoaded = { tick, list ->
                detailPageCache[top.id] = (detailPageCache[top.id] ?: DetailPageState()).copy(shuffleTick = tick, recs = list)
            },
            onDeleted = { wallTick++ },
            onOpenWorks = { kind, name -> pushOverlay(MediaOverlay.Works(kind, name)) },
            // 点详情底部的推荐 → 再压一层详情；传的是那一批推荐，播完自动接下一部就在推荐里走
            // 从推荐进下一部：带上"父片-子片"的共享元素 key，海报从推荐卡飞过去
            onOpenMovie = { c, l, i ->
                val key = "rec-${top.card.mediaKey}-${c.mediaKey}"
                // 记在本页之外：新详情盖上来后这层会被销毁，退回时靠它把这张推荐卡重新挂上
                openedPoster[top.id] = OpenedPoster(c.mediaKey, key)
                pushOverlay(MediaOverlay.Detail(c, l, i, sharedKey = key))
            },
            // 刮削写完：mediaKey 可能已经变了（文件前缀 → tmdb-<id>，扫描器删旧行建新行），
            // 详情页捧的还是旧卡 —— 按**不随 nfo 变的视频 pick_code** 把这条片重新认回来，
            // 换掉栈顶这张卡（produceState 以 mediaKey 为 key，换了卡才会拿新数据重查）
            onScraped = { newKey ->
                wallTick++
                scope.launch {
                    val fixed = container.metadataScraper.resolveCard(top.card.videoPickCode, newKey)
                    val at = overlays.indexOf(top)
                    if (fixed != null && at >= 0) {
                        // 带着原 id 原地换卡：contentKey 不变，只换内容、不重播进出场动画
                        overlays[at] = MediaOverlay.Detail(fixed, top.list, top.index, top.id)
                    }
                }
            },
            onBack = { popOverlay() },
            onEditPoster = { pick -> cropEditPick = pick },
            onUserScroll = { posterShareOff = top.id },
            posterModifier = if (tagPoster) {
                Modifier.sharedElement(
                    rememberSharedContentState(top.sharedKey ?: "poster-${top.card.mediaKey}"),
                    this@AnimatedContent,
                    boundsTransform = PosterBounds,
                )
            } else {
                Modifier
            },
            recommendationShared = { childKey ->
                Modifier.sharedElement(
                    rememberSharedContentState("rec-${top.card.mediaKey}-$childKey"),
                    this@AnimatedContent,
                    boundsTransform = PosterBounds,
                )
            },
            )
        }

        is MediaOverlay.Works -> {
            val worksKey = top.kind to top.name
            MediaWorksScreen(
                kind = top.kind,
                name = top.name,
                dao = dao,
                refreshKey = wallTick,
                onChanged = { wallTick++ },
                onBack = { popOverlay() },
                onOpenMovie = { card, list, index ->
                    val key = "works-${top.id}-${card.mediaKey}"
                    openedPoster[top.id] = OpenedPoster(card.mediaKey, key)
                    pushOverlay(MediaOverlay.Detail(card, list, index, sharedKey = key))
                },
                // 只给"从这一页点开的那一张"挂共享元素：整页都挂的话，没有对应目标的那些
                // 会在转场结束后留下残留图层（推荐卡踩过同一个坑）。
                posterShared = { card ->
                    val opened = openedPoster[top.id]
                    if (opened != null && opened.mediaKey == card.mediaKey) {
                        Modifier.sharedElement(
                            rememberSharedContentState(opened.sharedKey),
                            this@AnimatedContent,
                            boundsTransform = PosterBounds,
                        )
                    } else {
                        Modifier
                    }
                },
                initialCards = worksCache[worksKey].orEmpty(),
                onCardsLoaded = { worksCache[worksKey] = it },
                // 退回重建时停在原来的网格位置：点开的那张要在视野里，海报才飞得回去
                initialGridIndex = worksGridCache[top.id]?.first ?: 0,
                initialGridScrollOffset = worksGridCache[top.id]?.second ?: 0,
                onGridSave = { i, o -> worksGridCache[top.id] = i to o },
            )
        }

        is MediaOverlay.Records -> MediaRecordsScreen(
            dao = dao,
            onOpenMovie = { card, list, index -> pushOverlay(MediaOverlay.Detail(card, list, index)) },
            onBack = { popOverlay() },
        )

        is MediaOverlay.TagBall -> MediaTagBallScreen(
            dao = dao,
            // 球上点一个词 = 压一层作品页（与详情页点演员/标签走同一条路、同一个查询）
            onOpenWorks = { kind, name -> pushOverlay(MediaOverlay.Works(kind, name)) },
            onBack = { popOverlay() },
        )

        null -> Unit
    }
    }
    }

    // 拖动幻影：只在拎起来时挂。挂在 Popup 里所以独立于列表，列表滚动也不影响它的坐标
    dragHost.draggingId?.let { id ->
        libraries.firstOrNull { it.id == id }?.let { lib ->
            LibraryDragGhost(
                host = dragHost,
                lib = lib,
                dao = dao,
                refreshTick = wallTick,
                scanFinishedAt = scanProgress.finishedAt,
                countCache = libraryCountCache,
            )
        }
    }

    deleteTarget?.let { lib ->
        // 这个库正在被扫、或者排在队列里等着扫吗？两种都得先撤掉再删 —— 否则扫描会在删除之后
        // 继续把条目写回来，留下的孤儿行既没有库能进、也不会再被任何一次扫描清理（那个路径已经没有库了）。
        // 判据用 libraryId（每轮扫描都带着它）：早先只有 rootCid，库多根时对不齐。
        val scanningThis = scanProgress.running && scanProgress.libraryId == lib.id
        val queuedThis = pendingScans.any { it.libraryId == lib.id }
        FadeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除媒体库") },
            text = {
                Text(
                    // listOfNotNull + joinToString 拼：字符串拼接里的 if 会吞掉后面几段（踩过的坑）
                    listOfNotNull(
                        "删除「${lib.name}」？只删除索引记录，不会动云盘里的文件。",
                        if (scanningThis || queuedThis) {
                            "\n该库" + (if (scanningThis) "正在扫描" else "还在排队等着扫描") +
                                "，删除会把它撤下来（已扫完的目录不会白跑）。"
                        } else {
                            null
                        },
                    ).joinToString(""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { deleteLibraryFully(container, dao, lib) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    // 刮削批量面板：数据都在 scraper 的会话里（切页回来还在、跑完自己停），这里只负责盖在最上层
    val scrapeSession by container.metadataScraper.session.collectAsState()
    // 剧照张数（设置 → 实验室；0 = 不限制）：确认框的勾选文案跟着当前设置走
    val scrapeStillsMax by container.mediaPrefs.scrapeStillsMax.collectAsState(initial = MediaPrefs.DEFAULT_SCRAPE_STILLS_MAX)
    // 批量跑完把海报墙/库卡片的数字刷一次（数据是被别的引擎改的，墙只看 refreshKey）
    LaunchedEffect(scrapeSession?.finished) {
        if (scrapeSession?.finished == true) wallTick++
    }
    if (scrapeSession != null) {
        FadeIn(Modifier.fillMaxSize()) {
            ScrapeBatchPanel(scraper = container.metadataScraper, onClose = { container.metadataScraper.closeSession() })
        }
    }

    scrapeAsk?.let { lib ->
        FadeAlertDialog(
            onDismissRequest = { scrapeAsk = null },
            title = { Text("补全缺失元数据（刮削）") },
            text = {
                Column {
                    Text(
                        // listOfNotNull + joinToString：字符串拼接里的 if 会吞掉后面几段（踩过的坑）
                        listOfNotNull(
                            "为「${lib.name}」里没有 nfo 的影片搜元数据（TMDB），确认后写回云端（nfo + 海报 + 背景图）。",
                            "已经有 nfo 的影片一律跳过，不动流水线刮好的成果。",
                            when {
                                scrapeCount < 0 -> "\n正在统计待补数量…"
                                scrapeCount == 0 -> "\n这个库里没有需要补元数据的影片。"
                                else -> "\n待补 $scrapeCount 部。搜完先在面板里核对候选、勾选，再点「写入选中」。"
                            },
                        ).joinToString("\n"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = scrapeSupplement,
                            onCheckedChange = { scrapeSupplement = it },
                        )
                        Text(
                            "补充模式：只补缺失的字段/图片；没有 nfo 但已有海报/背景的也不覆盖",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = scrapeExtras,
                            onCheckedChange = { scrapeExtras = it },
                        )
                        Text(
                            "同时刮剧照（每部${if (scrapeStillsMax == MediaPrefs.SCRAPE_STILLS_UNLIMITED) "不限制张数，源里全部按赞数排序取回" else "按赞数取前 $scrapeStillsMax 张"}存各自专属目录，会多花不少时间）",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = scrapeCount > 0,
                    onClick = {
                        val target = scrapeAsk ?: return@TextButton
                        val extras = scrapeExtras
                        val supplement = scrapeSupplement
                        scrapeAsk = null
                        container.metadataScraper.startBatch(target, withStills = extras, supplement = supplement)
                    },
                ) { Text("开始搜索") }
            },
            dismissButton = { TextButton(onClick = { scrapeAsk = null }) { Text("取消") } },
        )
    }
    }   // 极光底 Box 收尾
    }   // SharedTransitionLayout 收尾（从极光 Box 之前包到这：页面内容 + 两个 AnimatedContent 都在它的 scope 里）

    // 兜底海报裁剪浮层：画在整个媒体库页的最外层（在 SharedTransitionLayout 之后），
    // 才能盖住共享元素覆盖层里的那张海报。
    cropEditPick?.let { pick ->
        PosterCropOverlay(
            backgroundPickCode = pick,
            resolver = container.imageUrlResolver,
            cacheDir = container.cacheDir,
            onDismiss = { cropEditPick = null },
            onSaved = { cropEditPick = null },
        )
    }
}

/**
 * 彻底删除一个媒体库：撤掉它的扫描（正在跑的停掉、排队里的移出）→ 清索引 → 清该库对应的落盘缓存。
 *
 * 缓存范围：nfo 文本、海报/背景/缩略图（media_img）、剧照、画廊里看过的原图（huge_img）、
 * 视频预览图，以及**只被这个库引用**的演员头像 —— 一律按 pick_code 精确清，不碰别的库。
 *
 * 顺序不能反：
 * - 先停扫描再清索引 —— 扫描是协程，清完索引它还会继续 upsert，条目又回来了；
 *   所以要**等它真的收尾**再动数据库（见 [MediaScanner.awaitStopped]）。
 *   等待是有界的：停止在目录/簇边界生效，上界约等于一次请求 + 一个限速间隔。
 * - 先收集 pick_code 再清索引 —— 索引一删就查不到该清哪些缓存文件了。
 *
 * 用 [MediaScanner.stopLibrary] 而不是 requestStop：后者是界面上的「停止」（一停一批、
 * 连队列一起清），删一个库不该把用户排着的**其他库**一起撤掉。
 */
private suspend fun deleteLibraryFully(
    container: com.open115.pad.AppContainer,
    dao: com.open115.pad.data.media.MediaDao,
    lib: MediaLibraryEntity,
) {
    // 返回值 = 真的停了正在跑的那一轮（排队里的那条不管有没有在跑都已经被撤掉）
    if (container.mediaScanner.stopLibrary(lib.id)) container.mediaScanner.awaitStopped()

    val paths = lib.rootPaths
    val nfoCodes = mutableListOf<String>()
    val imageCodes = mutableListOf<String>()
    val videoCodes = mutableListOf<String>()
    // 这个库的影片用到、且**有头像**的演员。清完索引就问不出"这个库用了谁"了，先收着。
    val avatarsInPath = mutableListOf<ActorAvatarRef>()
    // 逐根处理而不是拼 5 参数 SQL：多根库本来就少见，循环更简单也不会踩 SQLite 变量上限
    paths.forEach { prefix ->
        dao.pickCodesInPath(prefix).forEach { p ->
            p.nfoPickCode?.let(nfoCodes::add)
            p.posterPickCode?.let(imageCodes::add)
            p.fanartPickCode?.let(imageCodes::add)
            p.thumbPickCode?.let(imageCodes::add)
            p.videoPickCode?.let(videoCodes::add)
            // 剧照也按 pick_code 缓存（详情页看过的），漏了它就成了查不到的孤儿字节
            decodePickCodes(p.extraFanartPickCodes).forEach(imageCodes::add)
        }
        avatarsInPath += dao.actorAvatarsInPath(prefix)
        dao.deleteLibraryContent(prefix)
    }
    dao.deleteScanLogsForLibrary(lib.id)
    dao.deleteLibrary(lib.id)
    // 那条"扫描已停止（已完成 N/M 个目录）"提示指向的库已经没了，清掉它
    container.mediaScanner.clearStoppedNotice()

    // 清缓存。**按 pick_code 精确失效**，不能用粗粒度前缀：
    // nfo 的 key 是 `nfo|v<解析器版本>|<pickCode>|<upt>`，清 "nfo|" 会把别的库的 nfo 一起清掉。
    container.mediaCache.invalidateAll(nfoCodes.distinct().map { MediaScanner.nfoCachePrefix(it) })
    container.imageUrlResolver.evictPosterCache(imageCodes, container.cacheDir)
    // 预览图缓存（封面 + 预览条）按视频 pick_code 键，不删的话整库永久变垃圾（只能等上限淘汰）
    if (videoCodes.isNotEmpty()) {
        container.videoPreviews.deleteFor(videoCodes)
        // 播放缓存：直链档按 pick_code 清；转码分片 key 是文件 sha1，库里没存，留给上限淘汰
        val freed = withContext(Dispatchers.IO) { PlayerCache.purge(videoCodes) }
        if (freed > 0) com.open115.pad.util.AppLog.i("PlayerCacheIO", "删库：清掉播放缓存 ${freed / 1024 / 1024}MB")
    }

    // 演员头像：**只被这个库引用**的演员，头像引用与落盘字节一起清（别的库还在用的留着复用）。
    // 判据放在清完索引之后 —— 那时 movie_actors 里剩下的关联都来自别的库。
    // 云端 `.actors/` 文件不动（本操作就不删云盘文件），以后重新建库扫一次会自动再挂上。
    val avatarCandidates = avatarsInPath.distinctBy { it.id }
    if (avatarCandidates.isNotEmpty()) {
        val stillLinked = avatarCandidates.map { it.id }.chunked(300)
            .flatMap { dao.actorIdsStillLinked(it) }.toSet()
        val doomedAvatars = avatarCandidates.filterNot { it.id in stillLinked }
        if (doomedAvatars.isNotEmpty()) {
            dao.clearActorAvatars(doomedAvatars.map { it.id })
            container.imageUrlResolver.evictPosterCache(
                doomedAvatars.map { it.avatarPickCode },
                container.cacheDir,
            )
        }
    }
}

/**
 * 「自动扫描」这一段的文案：关着就空着；开着分"每次启动"与"≤每 N 小时"两种。
 *
 * 只返回这一段本身（不带「 · 」分隔符，拼接交给调用方的 joinToString）。
 * 早先这段藏在一个大字符串拼接里 —— `a + if (c) x else "" + if (d) y else ""`
 * 会解析成 `a + if (c) x else ("" + if (d) y else "")`，条件成立时后面几段全被吞掉
 * （「限速」一有值，「过滤」就再也显示不出来）；现在整行走 listOfNotNull + joinToString，
 * 这类坑没有存身之处。
 */
private fun ratePlanText(lib: MediaLibraryEntity): String? = listOfNotNull(
    when {
        lib.rateTiered -> "阶梯限速"
        lib.rateLimitMs > 0 -> "限速 ${lib.rateLimitMs}ms"
        else -> null
    },
    if (lib.tierRestMinutes > 0) "每100次列目录休 ${lib.tierRestMinutes} 分" else null,
).joinToString(" · ").ifEmpty { null }

private fun autoScanText(lib: MediaLibraryEntity): String {
    if (!lib.autoScanOnStart) return ""
    // 方式也写出来：用户选的是快速还是增量，卡片上要看得见（省请求还是求稳是两种预期）
    val mode = "自动${lib.autoScanModeOrDefault.label}扫描"
    return if (lib.autoScanIntervalHours > 0) "$mode ≤每 ${lib.autoScanIntervalHours}h" else mode
}

/**
 * 库内影片数：卡片上的「N 部」徽标，进库前就知道有没有内容（多根库取各根之和）。
 * 还没算出来返回 null（首帧不显示徽标，别闪一个"0 部"出来）。
 *
 * [refreshKey] 必须传一个"扫描结束后会变"的值（这里传 scanProgress.finishedAt）：
 * produceState 只在 key 变化时重算，而 rootPaths 在扫描前后是不变的 ——
 * 只以它为 key 的话，刚扫完的库会一直显示扫描前的数字（"还没有影片"）。
 *
 * [cache] 是页面级缓存：LazyColumn 回收重建卡片时先用上次的结果，部数徽标不会消失再出现；
 * 键里同时带上 [refreshTick]（删片/刮削也会变），保证不会拿旧数字。
 */
@Composable
private fun LibraryCountState(
    dao: com.open115.pad.data.media.MediaDao,
    lib: MediaLibraryEntity,
    refreshTick: Int,
    refreshKey: Long,
    cache: MutableMap<LibraryFaceKey, Int>,
): Int? {
    val key = remember(lib.id, refreshTick, refreshKey) {
        LibraryFaceKey(lib.id, refreshTick, refreshKey, 0)
    }
    val count by produceState(initialValue = cache[key] ?: -1, key) {
        cache[key]?.let {
            value = it
            return@produceState
        }
        val n = lib.rootPaths.sumOf { dao.movieCountIn(it) }
        cache[key] = n
        value = n
    }
    return if (count < 0) null else count
}

/**
 * 一张库卡在 pad 网格里的排布单元。
 *
 * [size] = 用户定义的大小（1/2/3 列；自动主推位时等于当前列数）；[span] = **实际占的列数**
 * （不会超过当前行有几列 —— 列数不足时会夹到列数）；[covers] = 铺几张封面。
 */
internal data class LibraryTile(val id: Long, val size: Int, val span: Int, val covers: Int)

/** 占 [size] 列时铺几张封面（用户定的映射）：1→1、2→2、3→**6**（1×3 一行六张） */
internal fun coversForLibrarySize(size: Int): Int = if (size >= 3) 6 else size.coerceIn(1, 2)

/**
 * 3 列及以上网格里卡片的**统一高度**（封面 112dp + 上下 padding 14×2）。
 *
 * 1×1 卡在 1/3 宽时，路径/标签换行会把卡撑得高矮不齐 —— 统一钉死这个高度，
 * 内容按 [LibraryCard] 的窄卡规则收进 112dp 里（见 narrow / uniformHeight）。
 */
private val LIBRARY_CARD_FIXED_HEIGHT = 140.dp

/**
 * pad 网格里 1 格占多宽（dp）：行宽（屏宽减两侧 16）减列间距后均分。
 *
 * 纯逻辑，单测钉在 `LibraryCardLayoutTest` —— 竖屏两列 / 横屏三等分的卡宽都从这里算。
 */
internal fun libraryUnitWidth(screenWidthDp: Int, columns: Int): Int {
    val cols = columns.coerceAtLeast(1)
    return ((screenWidthDp - 32 - 12 * (cols - 1)) / cols).coerceAtLeast(120)
}

/** 一张卡的实际宽度（dp）：span 格宽 + 中间的列间距 */
internal fun libraryCardWidth(screenWidthDp: Int, columns: Int, span: Int): Int {
    val s = span.coerceAtLeast(1)
    return libraryUnitWidth(screenWidthDp, columns) * s + 12 * (s - 1)
}

/** 网格是不是窄卡布局（1 格 < 430dp）：决定统一高度 / 标签单行 / 窄卡只留一条路径 */
internal fun isNarrowLibraryGrid(screenWidthDp: Int, columns: Int, compact: Boolean): Boolean =
    !compact && columns >= 2 && libraryUnitWidth(screenWidthDp, columns) < 430

/**
 * 6 张封面能不能**一行**放下（用户要的 1×3 = 一行六张）。
 *
 * 一行封面 = 6×84 + 5×6 = 534dp，右边还要给文字列和「⋯」留约 270dp ——
 * 卡片至少 ~800dp 才放得下；不够就折成 **3 张一行 × 2 行**，否则竖屏下
 * 1×3 卡的文字会被挤成一条缝（真机竖屏实测）。
 */
internal fun libraryCoverColumns(covers: Int, cardWidthDp: Int): Int =
    if (covers >= 6 && cardWidthDp < 800) 3 else covers.coerceAtLeast(1)

/**
 * 算出 pad 网格里每张库卡的占列数与封面数（纯逻辑，单测见 `LibraryCardLayoutTest`）。
 *
 * - 手机（[compact]）：一律 1×1、一张封面 —— "pad 模式才可定义"
 * - **没有任何库设过大小**：保持老规则（只有一个库、或"提走一张刚好排满整行"时，
 *   第一张当通栏主推位），其余 1×1。主推位的封面数也按同一套映射：2 列→2 张、3 列→6 张
 * - **只要有一个库设过**：全部按手动来（没设过的算 1×1）—— 规则简单可预期，
 *   不会出现"自动主推位"和用户定义的大小互相打架
 */
internal fun libraryTiles(
    libIds: List<Long>,
    columns: Int,
    compact: Boolean,
    explicitSizes: Map<Long, Int>,
): List<LibraryTile> {
    if (libIds.isEmpty()) return emptyList()
    // 手机是单列：不管存过什么，一律 1×1、一张封面（"pad 模式才可定义"）
    if (compact) return libIds.map { LibraryTile(it, 1, 1, 1) }
    val cols = columns.coerceAtLeast(1)
    val manual = libIds.any { explicitSizes[it] in 1..3 }
    if (!manual) {
        // 自动：只有一个库时它本来就占满整行（[libColumns] 会退到 1），按 1×2 的封面数铺；
        // 否则"提走一张刚好排满整行"时第一张当通栏主推位
        val hero = libIds.size == 1 || (cols > 1 && libIds.size % cols == 1)
        if (!hero) return libIds.map { LibraryTile(it, 1, 1, 1) }
        val heroSize = if (cols == 1) 2 else cols
        return libIds.mapIndexed { i, id ->
            if (i == 0) LibraryTile(id, heroSize, cols, coversForLibrarySize(heroSize))
            else LibraryTile(id, 1, 1, 1)
        }
    }
    return libIds.map { id ->
        val size = explicitSizes[id]?.takeIf { it in 1..3 } ?: 1
        LibraryTile(id, size, minOf(size, cols), coversForLibrarySize(size))
    }
}

/**
 * 按顺序把卡片装进一行行（贪心：放不下就换行）。
 *
 * 卡片宽度按 [LibraryTile.span] 的权重分配，所以 1×2 + 1×1 能拼成一整行；
 * 行尾不满由 UI 补 spacer（不拉伸卡片 —— 同一张 1×2 在不同行里宽度不该变）。
 */
internal fun packLibraryTiles(tiles: List<LibraryTile>, columns: Int): List<List<LibraryTile>> {
    val cols = columns.coerceAtLeast(1)
    val rows = mutableListOf<List<LibraryTile>>()
    var row = mutableListOf<LibraryTile>()
    var used = 0
    for (tile in tiles) {
        val span = tile.span.coerceIn(1, cols)
        if (row.isNotEmpty() && used + span > cols) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += tile
        used += span
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

/** 「卡片大小」菜单/弹窗里的显示名（0 = 自动） */
private fun libraryCardSizeLabel(size: Int): String = when (size) {
    1 -> "1×1"
    2 -> "1×2"
    3 -> "1×3"
    else -> "自动"
}

/**
 * 库列表里的**一个格子**：包一层拖动手势，里面就是 [LibraryCard]。
 *
 * 抽出来是因为调用点有两个（通栏那张 / 成对那两张），参数十几个 —— 各写一遍迟早漏掉某个回调
 * （漏了 onDelete 就是"删不掉"，而且编译不报错）。
 */
@Composable
private fun LibraryCell(
    modifier: Modifier,
    lib: MediaLibraryEntity,
    covers: Int,
    dao: com.open115.pad.data.media.MediaDao,
    container: com.open115.pad.AppContainer,
    /** 页面级封面/部数缓存（LazyColumn 回收重建时首帧直接命中） */
    coverCache: MutableMap<LibraryFaceKey, List<String>>,
    countCache: MutableMap<LibraryFaceKey, Int>,
    /** 内容变了要重查的信号（删片、扫描落了新海报都算）：由页面传，组件自己拿不到 */
    refreshTick: Int,
    scanFinishedAt: Long,
    scanning: Boolean,
    queued: Boolean,
    recentAdded: Int,
    /** 库少于两个时没得排序，直接不挂拖动手势 */
    canDrag: Boolean,
    host: LibraryDragHost,
    /** pad 网格里用户定义的大小（0 = 自动）；手机恒 0 */
    cardSize: Int,
    /** 是否给「⋯」菜单挂上「卡片大小」（仅 pad 网格：手机只有单列，没得选） */
    showCardSize: Boolean,
    /** 3 列网格里占 1 格的窄卡（路径只留一条） */
    narrow: Boolean,
    /** 3 列网格：统一卡片高度、标签只排一行 */
    uniformHeight: Boolean,
    /** 封面一行铺几张（6 张在窄卡上折成 3+3 两行） */
    coverColumns: Int,
    onSetCardSize: (Int) -> Unit,
    onOpen: () -> Unit,
    onScan: (ScanMode) -> Unit,
    /** 批量「补全缺失元数据」（TMDB 刮削），见 LibraryCard 的同名参数 */
    onScrape: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDrop: (fromId: Long, hoverId: Long) -> Unit,
) {
    LibraryDragBox(
        modifier = modifier,
        host = host,
        lib = lib,
        enabled = canDrag,
        onDrop = onDrop,
    ) {
        LibraryCard(
            lib = lib,
            dao = dao,
            container = container,
            coverCache = coverCache,
            countCache = countCache,
            refreshTick = refreshTick,
            scanFinishedAt = scanFinishedAt,
            scanning = scanning,
            queued = queued,
            recentAdded = recentAdded,
            covers = covers,
            coverColumns = coverColumns,
            cardSize = cardSize,
            showCardSize = showCardSize,
            narrow = narrow,
            uniformHeight = uniformHeight,
            onSetCardSize = onSetCardSize,
            // 拎在手上的那张淡下去、被压住的那张亮一圈：拖动时"我从哪儿拿、要放哪儿"看得见
            dimmed = host.draggingId == lib.id,
            highlighted = host.hoverId == lib.id && host.draggingId != lib.id,
            onOpen = onOpen,
            onScan = onScan,
            onScrape = onScrape,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 库卡片：左边一张封面（[LibraryCover]，固定尺寸），右边文字（标题 + 部数徽标 + 路径 + 元信息胶囊）。
 *
 * 封面是**固定尺寸**的，而且它是卡上最高的元素 —— 卡片高度就由它定下来，宽屏两列并排时
 * 两张卡天然等高（早先右侧海报条按内容浮动，一页里卡片高矮不齐）。文字列的竖向预算就是
 * 封面高 112dp：标题 24 + 路径 1~2 行 + 胶囊 1~2 行，正常都在预算内（见 [LibraryMetaChips]）。
 *
 * 操作（增量/全量扫描、编辑、删除）收进右上角「⋯」菜单：原先三个图标按钮常驻，
 * 删除（破坏性）和扫描并列，平板上误触的代价太高。
 *
 * 两个扫描菜单项**不再因为"别的库在扫"而置灰**：扫描是排队的，任何时候点都有效
 * （正在扫这个库时再点 = 排在它后面再扫一遍）。
 */
@Composable
private fun LibraryCard(
    lib: MediaLibraryEntity,
    dao: com.open115.pad.data.media.MediaDao,
    container: com.open115.pad.AppContainer,
    coverCache: MutableMap<LibraryFaceKey, List<String>>,
    countCache: MutableMap<LibraryFaceKey, Int>,
    /** 内容变了要重查的信号（删片、扫描落了新海报都算），见 LibraryCover / LibraryCountState */
    refreshTick: Int,
    scanFinishedAt: Long,
    /** 正在扫这个库 */
    scanning: Boolean,
    /** 这个库排在队列里等着扫 */
    queued: Boolean,
    /** 最近一轮扫描新增了几部（0 = 这轮没捞到新片，卡上就不显示这枚胶囊） */
    recentAdded: Int,
    /** 封面位铺几张：1×1 卡 1 张、1×2 卡 2 张、1×3 卡 **6 张**（见 coversForLibrarySize） */
    covers: Int = 1,
    /** 3 列及以上网格里的窄卡（占 1 格）：路径只留一条，免得把卡撑高 */
    narrow: Boolean = false,
    /** 3 列及以上网格：卡片统一高度（[LIBRARY_CARD_FIXED_HEIGHT]），标签行只排一行 */
    uniformHeight: Boolean = false,
    /** pad 网格里用户定义的大小（0 = 自动）；手机恒 0 */
    cardSize: Int = 0,
    /** 是否显示「卡片大小」菜单项（仅 pad 网格） */
    showCardSize: Boolean = false,
    /** 封面一行铺几张（6 张在窄卡上折成 3+3 两行，见 libraryCoverColumns） */
    coverColumns: Int = 1,
    onSetCardSize: (Int) -> Unit = {},
    /** 正被拖在手上（整块淡下去，主体交给跟着手指的幻影） */
    dimmed: Boolean = false,
    /** 拖动落点（亮一圈主色描边） */
    highlighted: Boolean = false,
    onOpen: () -> Unit,
    onScan: (ScanMode) -> Unit,
    /** 批量「补全缺失元数据」（TMDB 刮削）：先数数量再开跑，见页面里的确认框 */
    onScrape: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sizePickerOpen by remember { mutableStateOf(false) }
    val count = LibraryCountState(dao, lib, refreshTick, scanFinishedAt, countCache)
    GlassCard(
        modifier = modifier.fillMaxWidth().then(
            // 3 列以上统一高度：1×1 卡在 1/3 宽时标签会换行，不钉死就高矮不齐
            if (uniformHeight) Modifier.height(LIBRARY_CARD_FIXED_HEIGHT) else Modifier,
        ),
        onClick = onOpen,
        dimmed = dimmed,
        highlighted = highlighted,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryCover(
                lib, dao, container, refreshTick, scanFinishedAt, count,
                want = covers, tilesPerRow = coverColumns, cache = coverCache,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 标题要给两枚徽标让位：卡片窄了（宽屏两列）之后长库名会把徽标顶出卡外
                    Text(
                        lib.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (count != null) {
                        Spacer(Modifier.width(8.dp))
                        CountPill(count)
                    }
                    // 扫描状态直接标在卡片上：点了扫描之后**必须**看得见它是排在队里还是正在跑，
                    // 不然"点完没反应"和"没点上"分不出来
                    if (scanning || queued) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill(if (scanning) "扫描中" else "排队中")
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 路径降权（弱一档字色 + folder 图标）：卡上该先看到库名和部数，路径只是
                // "这个库指向哪儿"的注脚。完整路径在「编辑」里。
                // 3 列网格的 1/3 宽卡只留一条（多一条就把卡撑出统一高度）；宽度够时最多铺 2 行，
                // 其余折成一句提示。
                val shownPaths = lib.rootPaths.take(if (narrow) 1 else 2)
                shownPaths.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!narrow && lib.rootPaths.size > shownPaths.size) {
                    Text(
                        "还有 ${lib.rootPaths.size - shownPaths.size} 个目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 与上面路径文字左对齐（跨过 12dp 图标 + 4dp 间距）
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 3 列网格里标签**只排一行**：1/3 宽的卡上它们会换成 3~4 行，卡片高度就被撑歪了
                LibraryMetaChips(lib, recentAdded, maxLines = if (uniformHeight) 1 else Int.MAX_VALUE)
            }
            Spacer(Modifier.width(4.dp))
            // 「⋯」贴顶：它是这张卡的操作，跟标题同一行更顺眼（封面和文字列是垂直居中的）
            Box(Modifier.align(Alignment.Top)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 三个扫描项永远可点：扫描中再点就是**排队**（同库再点 = 替换掉排队里的那一条）
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (queued) "快速扫描（已在队列中，点此按新方式重排）"
                                else "快速扫描（没变的影片目录连列都不列）",
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onScan(ScanMode.Fast)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (queued) "增量扫描（已在队列中，点此按新方式重排）"
                                else "增量扫描（逐个目录核对；上次停止后也用它继续）",
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onScan(ScanMode.Incremental)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (queued) "全量扫描（已在队列中，点此改成全量）" else "全量扫描（重新索引全部）")
                        },
                        onClick = {
                            menuOpen = false
                            onScan(ScanMode.Full)
                        },
                    )
                    // pad 才有"卡片大小"：手机是单列，没有可定义的宽度
                    if (showCardSize) {
                        DropdownMenuItem(
                            text = { Text("卡片大小（${libraryCardSizeLabel(cardSize)}）") },
                            onClick = {
                                menuOpen = false
                                sizePickerOpen = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("补全缺失元数据（刮削）") },
                        onClick = {
                            menuOpen = false
                            onScrape()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }

    if (sizePickerOpen) {
        LibraryCardSizeDialog(
            libName = lib.name,
            selected = cardSize,
            onDismiss = { sizePickerOpen = false },
            onPick = { size ->
                sizePickerOpen = false
                onSetCardSize(size)
            },
        )
    }
}

/**
 * 「卡片大小」选择器（仅 pad）：自动 / 1×1 / 1×2 / 1×3。
 *
 * 选任意一个固定大小都会关掉"奇数个库时提第一张当主推位"的自动规则
 * （见 [libraryTiles]）—— 两条规则同时生效只会让排布变得猜不透。
 */
@Composable
private fun LibraryCardSizeDialog(
    libName: String,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    // 大小 → 名字 + 一句说明（说明里带上"占多宽 + 几张封面"，用户选之前就知道会变成什么样）。
    // "1 格 / 2 格"是网格单位：横屏三等分时 1 格 = 1/3；竖屏是两列，2 格和 3 格都占满整行。
    val choices = listOf(
        Triple(0, "自动", "奇数个库时把第一张提成通栏主推位（原来的规则）"),
        Triple(1, "1×1", "最小：占 1 格，铺 1 张封面"),
        Triple(2, "1×2", "中：占 2 格，铺 2 张封面"),
        Triple(3, "1×3", "最大：占满整行，铺 6 张封面"),
    )
    FadeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$libName」卡片大小") },
        text = {
            Column {
                choices.forEach { (size, label, hint) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(size) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == size, onClick = { onPick(size) })
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 库封面：优先用这个库里**已缓存**的海报，没有就画生成式封面（[GeneratedCover]）。
 *
 * 尺寸固定（84 × 112，3:4）—— 它是卡上最高的元素，卡片高度因此被它钉死（112 + 2×14 = 140dp），
 * 两列并排时天然等高；这一版把它从 72×96 放大，同时给文字列多留出放下胶囊行的竖向预算。
 *
 * 「已缓存」是硬条件（[com.open115.pad.data.ImageUrlResolver.cachedPosterIfPresent]，
 * 纯文件判断、零请求）：这一页是入口页，一进来就要画几个库的图，不能背着
 * "未命中 = 一次直链解析 + 一次下载"的开销，更不该为铺卡片去打 115 接口。
 * 但早先"宁缺毋滥、没图就整条不画"的做法会让一页里混着"有图的卡"和"纯文字的卡"两种规格，
 * 所以现在没图也有生成式封面补位。海报会在扫描预取 / 逛海报墙时自然落盘，下次进来就换上了。
 */
@Composable
private fun LibraryCover(
    lib: MediaLibraryEntity,
    dao: com.open115.pad.data.media.MediaDao,
    container: com.open115.pad.AppContainer,
    refreshTick: Int,
    scanFinishedAt: Long,
    count: Int?,
    /** 铺几张：1×1 卡 1 张、1×2 卡 2 张、1×3 卡 **6 张**（见 coversForLibrarySize） */
    want: Int = 1,
    /** 一行铺几张：6 张在窄卡上由 [libraryCoverColumns] 折成 3+3（两行），否则会把文字挤没 */
    tilesPerRow: Int = want,
    /** 页面级缓存：卡片被 LazyColumn 回收重建时首帧直接拿到上次的结果 */
    cache: MutableMap<LibraryFaceKey, List<String>>,
) {
    val key = remember(lib.id, refreshTick, scanFinishedAt, want) {
        LibraryFaceKey(lib.id, refreshTick, scanFinishedAt, want)
    }
    val models by produceState<List<String>>(initialValue = cache[key].orEmpty(), key) {
        cache[key]?.let {
            value = it
            return@produceState
        }
        val candidates = lib.rootPaths.flatMap { dao.posterSamplesInPath(it) }.distinct()
        val loaded = container.imageUrlResolver.cachedAmong(candidates, container.cacheDir, want)
        // 空结果不缓存：可能只是"还没缓存过海报"，用户看过海报墙后应该能补上
        if (loaded.isNotEmpty()) cache[key] = loaded
        value = loaded
    }
    val shape = RoundedCornerShape(12.dp)
    val tile = Modifier.size(width = 84.dp, height = 112.dp)
    if (models.isEmpty()) {
        // 一张都没缓存：退回生成式封面。**只铺一张**（按 want 铺三块同样的渐变反而像坏了）
        Box(tile.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            GeneratedCover(lib.name, count)
        }
        return
    }
    // 按 [tilesPerRow] 摆成若干行：1×3 在宽卡上是一行 6 张；竖屏/窄卡上折成 3+3，
    // 封面块宽度被限制在 3 格（264dp）以内，右边的文字列才留得下。
    val perRow = tilesPerRow.coerceIn(1, want.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        models.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { model ->
                    Box(tile.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** 没有缓存海报的库的兜底封面：主色渐变 + 库名首字 + 部数 */
@Composable
private fun GeneratedCover(name: String, count: Int?) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.14f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                name.trim().take(1).ifBlank { "库" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
            )
            if (count != null) {
                Text(
                    "$count 部",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
        }
    }
}

/** 「N 部」徽标：部数是这张卡上最该一眼看到的数字（原先埋在元信息行中间） */
@Composable
private fun CountPill(count: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val pill = Modifier
        .background(primary.copy(alpha = 0.14f), RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 2.dp)
    if (count == 0) {
        Text("还没有影片", style = MaterialTheme.typography.labelMedium, color = primary, modifier = pill)
        return
    }
    // 数字提一档字号 + 加粗、单位缩小：同号时"12 部"读起来是一坨，分开之后这个数才是
    // 卡上第一个被看到的
    Row(pill, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$count",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = primary,
        )
        Spacer(Modifier.width(2.dp))
        Text("部", style = MaterialTheme.typography.labelSmall, color = primary.copy(alpha = 0.8f))
    }
}

/**
 * 「扫描中 / 排队中」徽标。
 *
 * 用**次要色**（不是主色）：它跟「N 部」并排，两个都用主色会分不清哪个是数字哪个是状态；
 * 而且这是个临时状态，不该比影片数更抢眼。
 */
@Composable
private fun StatusPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 库卡底部的元信息胶囊行：一条事实一枚小胶囊，按宽度自动换行（FlowRow）。
 *
 * 早先是四段灰字用「 · 」拼成一行 —— 同一灰度同一字号，扫读要逐字读，也分不出
 * 哪个是状态、哪个是配置。拆成胶囊之后每条都能一眼定位；写成 `if (x) MetaChip(y)`
 * 的语句式，顺带绕开了"字符串拼接里的 if 会把后面几段全吞掉"那个老坑。
 *
 * [maxLines] 给 3 列网格用：1/3 宽的卡上这些胶囊会换成好几行、把卡片撑得高矮不齐，
 * 只排一行（放不下的按顺序丢掉，最重要的「新增/上次扫描」在最前面）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryMetaChips(lib: MediaLibraryEntity, recentAdded: Int, maxLines: Int = Int.MAX_VALUE) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = maxLines,
    ) {
        // 「新增」排最前并上色：这轮扫描到底捞到新片没有，是这几条里最该先看到的
        if (recentAdded > 0) MetaChip("新增 $recentAdded 部", tint = AppColors.GreenFg)
        if (lib.lastScanAt > 0) MetaChip("上次扫描 ${Format.ago(lib.lastScanAt)}")
        ratePlanText(lib)?.let { MetaChip(it) }
        if (lib.minVideoSizeMb > 0) MetaChip("过滤 <${lib.minVideoSizeMb}MB")
        val auto = autoScanText(lib)
        if (auto.isNotEmpty()) MetaChip(auto)
    }
}

/** 顶栏动作胶囊里的分隔线：三个图标各是一件工具（不是一组同质按钮），断开更好数 */
@Composable
private fun TopActionDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(18.dp)
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

/** 横排区块的标题行；actionText 非空时右侧给个文字按钮（「查看全部」进扫描记录） */
@Composable
private fun SectionHeaderRow(title: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/**
 * 「最近新增」的海报小卡：取图与海报墙同款（PosterImage，命中缓存零请求），
 * 下面两行片名 —— **恒两行**（多了截断、少了留白），整排卡片才齐。点了开详情浮层而不是直接播，
 * 与海报墙的手感一致。
 *
 * 右上角挂一枚来源库名：[libraryName] 是页面从扫描记录里带进来的（见 recentLibOf）——
 * 这一排是**跨库摊平**的，"这是哪个库新加的"必须看得见。
 */
@Composable
private fun PosterTile(
    card: MovieCard,
    libraryName: String?,
    width: Dp = 108.dp,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(width * 1.46f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // 兜底图标在下层：海报没加载出来/本来就没有时不剩空白
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
            PosterImage(
                posterPickCode = card.posterPickCode,
                backgroundPickCode = card.fanartPickCode,
                modifier = Modifier.fillMaxSize(),
            )
            if (!libraryName.isNullOrBlank()) {
                Text(
                    libraryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        // 库名可能很长（有的是"一层层目录拼出来的"那种），黑底胶囊最多占 2/3 宽
                        .widthIn(max = 72.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「继续观看」的卡：海报 + 底部进度条 + 还剩多久。点了直接续播
 * （判据与意图都和观影历史页一致，见 [com.open115.pad.ui.history.isFinished]）。
 */
@Composable
private fun ContinueTile(row: WatchHistoryRow, width: Dp = 108.dp, onClick: () -> Unit) {
    val fraction = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(width * 1.46f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
            PickCodeImage(row.posterPickCode, Modifier.fillMaxSize())
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            displayTitle(row),
            style = MaterialTheme.typography.bodySmall,
            // 与「最近新增」那一排对齐：标题恒两行，两排卡片的下沿才是齐的
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (row.durationMs > 0L) {
                "剩 " + Format.duration(((row.durationMs - row.positionMs) / 1000).coerceAtLeast(0L))
            } else {
                Format.ago(row.updatedAt)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 新建/编辑媒体库共用：库名 + 云盘路径（可多选、可继续追加）+ 扫描限速 + 体积过滤。
 * existing 非空为编辑模式：已选路径可逐条删除，再从目录树追加新路径。
 */
@Composable
private fun LibraryEditDialog(
    api: com.open115.pad.data.OpenApi,
    title: String,
    existing: MediaLibraryEntity?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        cids: List<String>,
        paths: List<String>,
        rateLimitMs: Long,
        autoScanOnStart: Boolean,
        /** 定时扫描方式（[ScanMode.Fast] / [ScanMode.Incremental]） */
        autoScanMode: ScanMode,
        minVideoSizeMb: Int,
        autoScanIntervalHours: Int,
        /** 阶梯限速（防 ban），见 [MediaLibraryEntity.rateTiered] */
        rateTiered: Boolean,
        /** 每 100 次列目录休息几分钟（0 = 不休息） */
        tierRestMinutes: Int,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var picked by remember {
        mutableStateOf(
            existing?.rootCids.orEmpty().zip(existing?.rootPaths.orEmpty())
                .map { (cid, path) -> com.open115.pad.data.FilterRules.BoundDir(cid = cid, name = path.substringAfterLast('/'), fullPath = path) },
        )
    }
    var rateText by remember {
        mutableStateOf(existing?.rateLimitMs?.takeIf { it > 0 }?.toString() ?: "")
    }
    var minSizeText by remember {
        mutableStateOf(existing?.minVideoSizeMb?.takeIf { it > 0 }?.toString() ?: "")
    }
    var autoScan by remember { mutableStateOf(existing?.autoScanOnStart ?: false) }
    // 回填上次选的方式；老库没有这一列 → 增量（与"定时扫描只有增量"那会儿一致）
    var autoMode by remember { mutableStateOf(existing?.autoScanModeOrDefault ?: ScanMode.Incremental) }
    var tiered by remember { mutableStateOf(existing?.rateTiered ?: false) }
    // 休息：开关 + 分钟数（3/4/5 三档，用户要求的就是 3~5 分钟）。关掉 = 存 0
    var restOn by remember { mutableStateOf((existing?.tierRestMinutes ?: 0) > 0) }
    var restMin by remember { mutableStateOf(existing?.tierRestMinutes?.takeIf { it > 0 } ?: 3) }
    var intervalText by remember {
        mutableStateOf(existing?.autoScanIntervalHours?.takeIf { it > 0 }?.toString() ?: "")
    }
    var picking by remember { mutableStateOf(false) }
    val rate = rateText.toLongOrNull()?.coerceAtLeast(0) ?: 0L
    val minSize = minSizeText.toIntOrNull()?.coerceIn(0, 100_000) ?: 0
    // 上限 30 天：再大就等于"关了"，不如直接留空（留空 = 不限间隔）
    val intervalHours = intervalText.toIntOrNull()?.coerceIn(1, 24 * 30) ?: 0

    FadeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // 竖着可滑：这一屏的内容会随开关变长（阶梯限速/休息/定时方式各自带说明文字），
            // 编辑多根目录的库时路径列表也可能很长 —— 不滑的话小屏上后半段会被弹窗直接裁掉
            // （保存/取消还在，可中间那几项看不见、也改不了）。
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("库名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picking = true }) {
                        Text(if (picked.isEmpty()) "选择云盘路径" else "添加路径")
                    }
                    Text(
                        when {
                            picked.isEmpty() -> "未选择"
                            picked.size == 1 -> picked[0].fullPath
                            else -> "已选 ${picked.size} 个目录"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (picked.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 已选路径逐行列出，编辑时可逐条删除
                picked.forEachIndexed { index, dir ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            dir.fullPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { picked = picked.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "移除路径", modifier = Modifier.height(18.dp).width(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("阶梯限速（防 ban）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "按这一轮扫到第几个目录自动加长间隔：≤10 个 500ms、11~20 个 1000ms、" +
                                "21~50 个 1500ms、51~100 个 2000ms、100 个以上 2500ms（越扫越慢）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(checked = tiered, onCheckedChange = { tiered = it })
                }
                // 定死值与阶梯是两套规则，同时生效只会互相打架 —— 开了阶梯就把这个输入框收起来
                if (!tiered) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it.filter { c -> c.isDigit() } },
                        label = { Text("扫描限速（毫秒，留空不限）") },
                        supportingText = { Text("相邻两次 115 API 请求的最小间隔，遇到频控可设为 500~1000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "阶梯限速已开启：上面那个定死值不再参与",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每 100 次列目录休息一次", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "连着扫几百个目录后停几分钟，最能让 115 那边看不出这是脚本；" +
                                "休息时进度卡上会写「休息中」，点停止最多多等 1 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(checked = restOn, onCheckedChange = { restOn = it })
                }
                if (restOn) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 4, 5).forEach { minutes ->
                            FilterChip(
                                selected = restMin == minutes,
                                onClick = { restMin = minutes },
                                label = { Text("$minutes 分钟") },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minSizeText,
                    onValueChange = { minSizeText = it.filter { c -> c.isDigit() } },
                    label = { Text("最小视频体积（MB，留空不限）") },
                    supportingText = {
                        Text(
                            if (minSize > 0) {
                                "小于 ${minSize}MB 的视频不入库（预告/花絮/样本）。改完要重扫一次才生效"
                            } else {
                                "按库单独设：正片库可设 100~200 挡掉预告，短片库留空"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启动时自动扫描", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "程序启动登录后按下面的方式扫这个库（顺序执行，不与其他库并发；" +
                                "流水线同步刚扫过的库会被跳过）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(checked = autoScan, onCheckedChange = { autoScan = it })
                }
                // 方式与间隔只在开关打开时才有意义（关掉了给它设这些是自相矛盾的输入）
                if (autoScan) {
                    Spacer(Modifier.height(8.dp))
                    Text("扫描方式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = autoMode == ScanMode.Fast,
                            onClick = { autoMode = ScanMode.Fast },
                            label = { Text("快速") },
                        )
                        FilterChip(
                            selected = autoMode == ScanMode.Incremental,
                            onClick = { autoMode = ScanMode.Incremental },
                            label = { Text("增量") },
                        )
                    }
                    Text(
                        if (autoMode == ScanMode.Fast) {
                            "没变的影片目录连列都不列，请求最少；目录内改名有盲区（每轮抽查一部分兜底）"
                        } else {
                            "逐个目录核对内容，最稳；代价是每个目录一次列目录请求"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("扫描间隔（小时，留空 = 每次启动都扫）") },
                        supportingText = {
                            Text(
                                if (intervalHours > 0) {
                                    "距上次扫描不足 ${intervalHours} 小时就跳过这个库（判断是纯本地的，不花请求）"
                                } else {
                                    "留空 = 每次启动都扫。库很大时可以设 6~24，减少列目录请求"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // 存完整路径（fullPath）：只存叶子名的话，不同父目录下的同名目录
                // 会在海报墙的前缀匹配里互相串数据
                onClick = {
                    if (picked.isNotEmpty()) {
                        onSave(
                            name.ifBlank { picked[0].name },
                            picked.map { it.cid },
                            picked.map { it.fullPath },
                            rate,
                            autoScan,
                            autoMode,
                            minSize,
                            if (autoScan) intervalHours else 0,
                            tiered,
                            if (restOn) restMin else 0,
                        )
                    }
                },
                enabled = picked.isNotEmpty(),
            ) { Text(if (existing == null) "创建" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking) {
        com.open115.pad.ui.filter.DirMultiPickerDialog(
            api = api,
            title = "选择媒体库根目录（可多选）",
            onDismiss = { picking = false },
            onConfirm = { dirs ->
                // 多次进入选择器是追加而不是覆盖；已选的跳过避免重复
                picked = (picked + dirs).distinctBy { it.cid }
                picking = false
            },
        )
    }
}

/**
 * 扫描进度卡片。
 *
 * 三层信息，各解决一个"看不出在干嘛"的问题：
 *  ① **进度条**：列目录阶段是**不确定态**（这一阶段没有分母 —— 目录数正是它要算的东西，
 *     而大库的列目录可能占掉整轮的大半时间）。早先这里恒显示 0/0，看着像卡死。
 *  ② 阶段 + 当前目录：路径只显示**最后两段**（全路径会被截断成开头那几段，
 *     而"现在扫到哪儿"恰恰在末尾）。
 *  ③ 计数：目录进度 / 已入库 / 已缓存海报。计数是**计数器不是快照**，
 *     大目录里逐条更新，不然一季几十集整段不动。
 */
@Composable
private fun ScanProgressCard(
    progress: MediaScanner.Progress,
    /** 队列里还有等着的（决定按钮是"跳过当前"还是"停止"） */
    hasQueued: Boolean,
    onSkip: () -> Unit,
) {
    // 边爬边入库之后，"列目录"与"索引"不再是先后两个阶段（列到一个目录就当场入库，见
    // MediaScanner.runScan）；只有单目录任务（流水线同步）还走"确定进度"的那条路
    // 休息中：每 1 秒刷一次倒计时 —— 扫描这时在睡觉、进度一动不动，
    // 界面上不说明的话就是"卡死了"（用户真机上的第一反应）
    var tick by remember(progress.restingUntil) { mutableStateOf(System.currentTimeMillis()) }
    if (progress.restingUntil > 0) {
        LaunchedEffect(progress.restingUntil) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val resting = progress.restingUntil > tick
    val listing = progress.phase == MediaScanner.Phase.Listing
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 多库排队之后"正在扫哪个库"成了必须显示的信息（早先只有一条进度，
                    // 谁在扫就是谁）；没有库名（直接调 runScan 的场合）就只显示阶段
                    listOfNotNull(
                        if (resting) {
                            "休息中（防频控）"
                        } else {
                            // 已经处理过目录就说明"爬"与"索引"在同时进行，还叫"正在列目录"
                            // 会让人以为索引还没开始
                            if (!listing) "正在索引"
                            else if (progress.doneDirs > 0) "正在扫描"
                            else "正在列目录"
                        },
                        progress.libraryName.takeIf { it.isNotBlank() }?.let { "「$it」" },
                    ).joinToString(""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    progress.currentDir.split('/').takeLast(2).joinToString("/"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSkip, enabled = !progress.stopping) {
                    Text(
                        when {
                            // 后面还有排队的：这个键是"跳过当前、接着跑下一个"，说清楚
                            progress.stopping -> if (hasQueued) "跳过中…" else "停止中…"
                            hasQueued -> "跳过当前"
                            else -> "停止"
                        },
                    )
                }
            }
            val barModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            if (listing) {
                LinearProgressIndicator(modifier = barModifier)
            } else {
                LinearProgressIndicator(
                    progress = {
                        if (progress.totalDirs > 0) {
                            progress.doneDirs.toFloat() / progress.totalDirs
                        } else {
                            0f
                        }
                    },
                    modifier = barModifier,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (resting) {
                        val left = (progress.restingUntil - tick).coerceAtLeast(0) / 1000
                        append("休息中：已扫 ${progress.doneDirs} 个目录，还剩 ${Format.duration(left)}")
                    } else if (listing) {
                        // 边爬边入库：分母是"已发现的目录数"（目录数正是爬取要算的东西，
                        // 不存在"总数"），所以进度条是不确定态 —— 至少让这几个数都在动
                        append("已发现 ${progress.discoveredDirs} 个目录")
                        if (progress.doneDirs > 0) {
                            append(" · 已处理 ${progress.doneDirs} 个")
                            append(" · 已入库 ${progress.moviesIndexed} 部")
                        }
                    } else {
                        append("目录 ${progress.doneDirs}/${progress.totalDirs}")
                        append(" · 已入库 ${progress.moviesIndexed} 部")
                    }
                    if (progress.postersFetched > 0) append(" · 已缓存海报 ${progress.postersFetched} 张")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 排队中的扫描任务卡片：一行一个库，**顺序执行**（队首是下一个要跑的）。
 *
 * 为什么要有这一块：能排队之后"点了扫描"必须看得见 —— 否则用户在扫描中点扫描，
 * 界面上毫无变化，没法判断是排上了还是没点上。
 *
 * 每行的 × 只撤这一个；「全部停止」是**整批不干了**（连正在跑的那一轮一起停，
 * 见 [MediaScanner.stopAll]）—— 只想跳过当前那个、让后面接着跑，用上面进度卡上的「跳过当前」。
 *
 * 行数封顶 [QUEUE_ROWS_SHOWN]：队列里塞了十几个库时，这块卡片会把下面的库列表挤没
 * （它在 LazyColumn 外面、不跟着滚），多的只报个数，整队清就走「全部停止」。
 */
@Composable
private fun ScanQueueCard(
    tasks: List<QueuedScan>,
    onCancel: (libraryId: Long) -> Unit,
    onStopAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "排队中 ${tasks.size} 个 · 依次扫描",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStopAll) { Text("全部停止") }
            }
            tasks.take(QUEUE_ROWS_SHOWN).forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${task.libraryName}（${task.mode.label}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onCancel(task.libraryId) }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "取消排队",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (tasks.size > QUEUE_ROWS_SHOWN) {
                Text(
                    "…还有 ${tasks.size - QUEUE_ROWS_SHOWN} 个（点「全部停止」可一次清掉）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 两个"停"长得像、作用不同，写一行说清分工（不然用户点错还会以为没生效）
            Text(
                "想跳过当前那一个、让后面接着跑：用上面进度卡上的「跳过当前」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 排队卡最多列几行，多的只报个数（见 [ScanQueueCard]） */
private const val QUEUE_ROWS_SHOWN = 4

/**
 * 媒体库里可以叠起来的浮层（栈顶那个才是当前显示的）。
 *
 * 详情页之上还能再叠"同演员/同标签的作品"页，所以这里不是单个变量而是一个栈。
 */
private sealed interface MediaOverlay {
    /**
     * 每个实例一个**创建后不变**的 id，AnimatedContent 的 contentKey 只认它。
     *
     * ☠ 不能用「栈深 + 类型」当 key：出栈那一帧栈深就变了，**还在转场里的旧页** key 跟着变，
     * AnimatedContent 把它当成"出现了新内容"就地重建——详情页重建成加载态（data==null，
     * 整屏黑底+转圈）盖住一切，这就是"退回作品页黑一下"的真身（录屏逐帧抓到的白点就是转圈）。
     */
    val id: Long

    /** 影片/系列详情（[list] 是它所在的列表，播完自动接下一部就是按它排的） */
    data class Detail(
        val card: MovieCard,
        val list: List<MovieCard>,
        val index: Int,
        override val id: Long = newOverlayId(),
        /** 共享元素 key：从推荐卡进来时是 "rec-<父片>-<子片>"，默认按墙上的海报 key */
        val sharedKey: String? = null,
    ) : MediaOverlay

    /** 同一个演员 / 同一个标签的全部作品（**跨所有媒体库**，见 dao.byActor/byTag） */
    data class Works(
        val kind: WorksKind,
        val name: String,
        override val id: Long = newOverlayId(),
    ) : MediaOverlay

    /**
     * 记录：**扫描 / 迁移 / 观影历史**三条流水合成的一个浮层（页头三个页签）。
     *
     * 三条都是"媒体库这条线自己的账" —— 扫了哪些库、搬过哪些片、看到哪儿了。
     * 原来扫描记录与观影历史各占顶栏一个图标，再加上迁移记录就是三个，收成一个入口更清爽；
     * 各页自己的小动作（清空 / 只看未看完）跟着页签摆在页头那一行，不再各占一个位置。
     */
    object Records : MediaOverlay {
        override val id: Long = 1L
    }

    /**
     * 标签球（演员 + 标签，画质/编码类已剔除）。
     *
     * 它在栈里的位置和别的浮层一样：球上点一个词会**再压一层** [Works]，返回逐层退。
     */
    object TagBall : MediaOverlay {
        override val id: Long = 3L
    }
}

/** 浮层实例 id 发号器：nanoTime 足够唯一（同一进程内不会重复） */
private fun newOverlayId(): Long = System.nanoTime()


// ---------------- 库卡片拖动排序 ----------------

/**
 * 拖动排序的宿主状态：**记 id 不记下标**。
 *
 * 为什么不按下标记：拖动期间列表可能被别的任务改写（扫描队列收尾、新建 / 删库），下标会漂。
 * 这里只记"拎的是谁、压在谁身上"，落点在下标层面现算（见 MediaLibraryScreen 的 dropLibrary）——
 * 中间任何一张卡的增删都不会把顺序拖错。
 *
 * 卡片边界由每张卡在 `onGloballyPositioned` 里注册到 [bounds]，坐标是 **root 坐标**：
 * 幻影挂在 Popup 里（窗口原点），两者同一套坐标系，不用再换算。
 */
private class LibraryDragHost {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var hoverId by mutableStateOf<Long?>(null)
        private set

    /** 指尖位置（root 坐标） */
    var finger by mutableStateOf(Offset.Zero)
        private set

    /** 拎起时手指在卡片内的偏移：幻影按它对齐，否则会跳到指尖的左上角 */
    var grab by mutableStateOf(Offset.Zero)
        private set

    /** 被拎那张卡的尺寸（px）：幻影按原尺寸悬浮，落到格子里才有"放回去"的连续感 */
    var cardSize by mutableStateOf(IntSize.Zero)
        private set

    val active: Boolean get() = draggingId != null

    private val bounds = mutableStateMapOf<Long, Rect>()

    fun register(id: Long, rect: Rect) {
        bounds[id] = rect
    }

    fun begin(id: Long, rootFinger: Offset, grabInCard: Offset, size: IntSize) {
        draggingId = id
        hoverId = id
        finger = rootFinger
        grab = grabInCard
        cardSize = size
    }

    fun dragTo(rootFinger: Offset) {
        finger = rootFinger
        nearest(rootFinger)?.let { hoverId = it }
    }

    /** 松手：[from] 拎起的那张、[hover] 落点。原地放回 / 没拿到坐标时返回 null（不白写一次库） */
    fun finish(): Pair<Long, Long>? {
        val from = draggingId
        val hover = hoverId
        reset()
        return if (from == null || hover == null || from == hover) null else from to hover
    }

    /** 手势被打断（被父节点抢走、进程切后台）时的收尾：不 reset 会把幻影永远留在屏幕上 */
    fun cancel() = reset()

    private fun reset() {
        draggingId = null
        hoverId = null
    }

    /**
     * 落点判定：**指尖落在谁的矩形里就是谁**（绝大多数时候这一句就定了）；落在卡片之间的缝里、
     * 或者落在列表外，再按"到矩形边的距离"取最近的那张。
     *
     * ★ 别用"离中心最近"：通栏主推位、以及用户设成 1×2 / 1×3 的宽卡横跨整行，它的中心在屏幕
     *   正中间，于是指尖在它**左上角**时算出来反而离下面那张更近 —— 想把它前面的格子当落点会全落偏
     *   （实测：把最后一张拖到左上角，结果插到了第二张的位置）。按边距离算就不受卡片宽窄影响。
     */
    private fun nearest(p: Offset): Long? {
        bounds.entries.firstOrNull { (_, r) -> r.contains(p) }?.let { return it.key }
        return bounds.entries.minByOrNull { (_, r) -> edgeDistanceSquared(r, p) }?.key
    }

    /** 点到矩形最近边的平方距离（点在矩形内时为 0） */
    private fun edgeDistanceSquared(r: Rect, p: Offset): Float {
        val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
        val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom)
        return dx * dx + dy * dy
    }
}

/**
 * 给库卡包一层"长按拎起来拖动"的手势。
 *
 * 手势分两段（与文件页那套拖动同源，见 FilesBrowser 的 QuickDirDragSource）：
 *  - **长按前只旁观、不消费**：手指一旦移动超过斜距就把手势让给列表滚动，抬手就是普通点击
 *    —— 所以点击进海报墙、上下滑动列表都不受影响；
 *  - **长按成立**之后才在 Initial 遍接管并消费（含 UP）：父节点先于卡片的 clickable 看到事件，
 *    松手才不会顺手把海报墙点开。
 *
 * ★ 卡片自己不做命中判断，落点由宿主统一算：手势挂在每张卡上，但状态是全局一份。
 */
@Composable
private fun LibraryDragBox(
    modifier: Modifier,
    host: LibraryDragHost,
    lib: MediaLibraryEntity,
    enabled: Boolean,
    onDrop: (fromId: Long, hoverId: Long) -> Unit,
    content: @Composable () -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptics = LocalHapticFeedback.current
    val currentDrop by rememberUpdatedState(onDrop)
    Box(
        modifier
            .onGloballyPositioned {
                coords = it
                host.register(lib.id, it.boundsInRoot())
            }
            .pointerInput(lib.id, enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 阶段一（只旁观）：横向先超斜距 = 立刻拎起（不必等长按，甩一下就起，与文件页那套拖动同源）；
                    // 纵向先超斜距 = 让位给列表滚动；两样都没发生、静置到长按时长 = 标准"长按拖动"。
                    // 横向在这里不冲突：这一页只有纵向滚动，没有横向可滚的东西
                    val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) return@withTimeoutOrNull false
                            moved += change.positionChange()
                            if (moved.getDistance() > slop) {
                                return@withTimeoutOrNull abs(moved.x) > abs(moved.y)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    } ?: true
                    if (!lifted) return@awaitEachGesture

                    val c = coords ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    host.begin(lib.id, c.localToRoot(down.position), down.position, c.size)
                    try {
                        // 阶段二：任意方向跟随，Initial 遍连 UP 一起消费
                        var at = down.position
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            at += change.positionChange()
                            change.consume()
                            host.dragTo(c.localToRoot(at))
                            if (!change.pressed) break
                        }
                        host.finish()?.let { (from, hover) ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentDrop(from, hover)
                        }
                    } finally {
                        // finish() 已经收过尾（draggingId 变 null），这里只兜"手势被打断"那一支
                        if (host.draggingId == lib.id) host.cancel()
                    }
                }
            },
    ) { content() }
}

/**
 * 拖动幻影：一张跟着手指的**简化**库卡（库名 + 部数）。
 *
 * 为什么不搬原卡：原卡要 dao / container 去取封面、算部数，等于把整条数据链路拖进浮层。
 * 幻影的职责只是"告诉手指还拎着哪个库、有多大一块"，名字 + 部数 + 原尺寸就够。
 *
 * ★ 用 `Popup` + 整窗 `Box`：Popup 的可绘制表面只有窗口框那么大，只铺一个小窗再靠
 *   offset 往外挪会被裁掉（踩过：拖得动、落点也高亮，就是看不见图标）。
 * ★ 位置直接读 [LibraryDragHost.finger]，**不做缓动** —— 跟手程度只取决于位置更新的频率，
 *   加缓动只会滞后（文件页那套要缓动是因为幻影要"指数趋近"手指，单张卡没必要）。
 */
@Composable
private fun LibraryDragGhost(
    host: LibraryDragHost,
    lib: MediaLibraryEntity,
    dao: com.open115.pad.data.media.MediaDao,
    refreshTick: Int,
    scanFinishedAt: Long,
    countCache: MutableMap<LibraryFaceKey, Int>,
) {
    val size = host.cardSize
    if (size.width <= 0 || size.height <= 0) return
    val count = LibraryCountState(dao, lib, refreshTick, scanFinishedAt, countCache)
    val density = LocalDensity.current
    val topLeft = host.finger - host.grab
    val w = with(density) { size.width.toDp() }
    val h = with(density) { size.height.toDp() }
    Popup(
        alignment = Alignment.TopStart,
        properties = PopupProperties(clippingEnabled = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size(w, h)
                    .graphicsLayer {
                        scaleX = 1.02f
                        scaleY = 1.02f
                    },
            ) {
                GlassCard(modifier = Modifier.fillMaxSize(), elevation = 22.dp) {
                    Row(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            lib.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (count != null) {
                            Spacer(Modifier.width(8.dp))
                            CountPill(count)
        
                        }
                    }
                }
            }
        }
    }
}
