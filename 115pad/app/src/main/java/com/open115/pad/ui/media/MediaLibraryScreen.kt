package com.open115.pad.ui.media

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.media.MEDIA_NO_LIMIT
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.QueuedScan
import com.open115.pad.data.media.ScanMode
import com.open115.pad.data.media.WatchHistoryRow
import com.open115.pad.data.media.WorksSort
import com.open115.pad.data.media.autoScanModeOrDefault
import com.open115.pad.data.media.label
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.GlassCard
import com.open115.pad.ui.components.MetaChip
import com.open115.pad.ui.components.auroraBackdrop
import com.open115.pad.ui.components.dissolve
import com.open115.pad.ui.history.WatchHistoryScreen
import com.open115.pad.ui.history.displaySubtitle
import com.open115.pad.ui.history.displayTitle
import com.open115.pad.ui.history.isFinished
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 媒体库页（类 Yamby）：左侧独立入口。
 * 「新建媒体库」→ 输名字 + 从云盘目录树选路径 → 保存为库；
 * 每个库可手动扫描（带进度、可续跑）或删除。
 */
@Composable
fun MediaLibraryScreen(api: com.open115.pad.data.OpenApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.appContainer
    val dao = container.mediaDatabase.mediaDao()
    // 手机（窄屏）排版开关：搜索框 + 3 个图标在 411dp 宽下会互相挤压，按这个标志切紧凑形态
    // （搜索结果网格的列宽也跟着它）；平板（>=600dp）保持原样
    val compact = LocalConfiguration.current.screenWidthDp < 600
    // 库卡片是否铺两列：通栏单列在内容宽度（AdaptiveBody 收到 960dp）下，文字靠左、封面靠右，
    // 中间空出约 400dp —— 这就是"卡片又宽又空"的来源。两列之后卡片变窄、同屏还多看得见几个库。
    // 阈值 860dp：再窄下去每张卡不到 410dp，文字列装不下一行摘要
    val libColumns = if (LocalConfiguration.current.screenWidthDp >= 860) 2 else 1

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
    LaunchedEffect(libraries, pendingOrder) {
        val pending = pendingOrder ?: return@LaunchedEffect
        if (libraries.map { it.id } == pending) pendingOrder = null
    }
    val dragHost = remember { LibraryDragHost() }

    /**
     * 奇数个库时把**第一张**提出来做通栏（[heroLib]），剩下的（偶数个）两两成对。
     *
     * 为什么不是"落单那张通栏"：第一张当主推位、下面都是规整的两列，读起来是"一个主卡 + 一个网格"；
     * 而落单那张通栏会让最后一行孤零零横在下面。宽度不够两列（手机）时不做，保持单列。
     */
    val heroLib = if (libColumns > 1 && orderedLibs.size % 2 == 1) orderedLibs.first() else null
    val gridLibs = if (heroLib == null) orderedLibs else orderedLibs.drop(1)

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
    /**
     * 海报墙的刷新信号，**墙和详情页共用**。
     *
     * 详情页是盖在墙上面的浮层，墙并没有被销毁 —— 在详情页里删掉一条之后返回，
     * 墙还捧着删除前的列表（实测：DB 里已经 17 部，墙上仍显示 8 部）。
     * 所以删除成功要由这一层往下传一个"变了"的信号，两边都跟着它重查。
     */
    var wallTick by remember { mutableStateOf(0) }

    /**
     * 这一页的搜索：与海报墙**同一个查询**（片名/演员/标签，跨所有媒体库）。
     * 有搜索词时列表换成结果网格，点结果直接开详情浮层（走上面那个浮层栈）。
     */
    var query by remember { mutableStateOf("") }
    // 搜索结果跟着"作品排序"设置走（与海报墙/作品页同一个设置，不在这里再放一个菜单）
    val sort by container.mediaPrefs.worksSort.collectAsState(initial = WorksSort.DEFAULT)
    val hits by produceState<List<MovieCard>?>(initialValue = null, query, sort) {
        value = if (query.isBlank()) null else dao.searchAll(query, MEDIA_NO_LIMIT, sort.sql)
    }
    // 返回键：正在搜索就先清搜索（与海报墙一致）
    androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) { query = "" }

    // ---- 首页化的两个横排（只在非搜索态出现） ----

    /** 继续观看：观影历史里"开了头还没看完"的前 12 条（判据与观影历史页同一个 isFinished） */
    val historyRows by dao.watchHistoryRows(60).collectAsState(initial = emptyList())
    val continueRows = remember(historyRows) {
        historyRows.filter { !isFinished(it) && it.positionMs > 0 }.take(12)
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
    AdaptiveBody(modifier = Modifier.fillMaxSize()) {
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
                    // 扫描记录：媒体库自己的流水（何时扫的哪个库、结果、新增影片带图）——
                    // 不放文件页那份「操作记录」（那是文件操作的流水），也不占导航栏
                    IconButton(onClick = { overlays.add(MediaOverlay.ScanLog) }) {
                        Icon(Icons.Outlined.Update, contentDescription = "扫描记录")
                    }
                    TopActionDivider()
                    // 观影历史：入口放在媒体库页（它是"媒体库看到哪儿了"，不属于文件页 ——
                    // 文件页的播放记录在操作记录里，两处不重复）
                    IconButton(onClick = { overlays.add(MediaOverlay.History) }) {
                        Icon(Icons.Outlined.History, contentDescription = "观影历史")
                    }
                    TopActionDivider()
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建媒体库")
                    }
                }
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
                    "片名 / 演员 / 标签 · ${results.size} 部 · 全部媒体库",
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
                            "全部媒体库里都没有匹配「$query」的片名 / 演员 / 标签",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        // 窄屏用更小的列宽 → 手机上排 3 列（130dp 在 411dp 宽下只排得下 2 列，卡片偏大）
                        columns = GridCells.Adaptive(minSize = if (compact) 110.dp else 130.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(results, key = { it.mediaKey }) { card ->
                            PosterCard(
                                card = card,
                                onClick = {
                                    overlays.add(
                                        MediaOverlay.Detail(card, results, results.indexOf(card)),
                                    )
                                },
                            )
                        }
                    }
                }
            } else if (libraries.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("还没有媒体库", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("点右上角「新建」，选择云盘里的 Emby 资源目录", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    // 奇数个库时**最上面那张通栏**（用户要的"1×2"）：它铺 [HERO_COVERS] 张封面，
                    // 把多出来的宽度压住；其余的照旧两两成对。这样每一行都是满的，页面上不留洞。
                    // 通栏放在最上面而不是最后一张：视觉上"第一张是主推位"，往下都是规整的两列
                    if (heroLib != null) {
                        item(key = "hero-${heroLib.id}") {
                            LibraryCell(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                lib = heroLib,
                                covers = HERO_COVERS,
                                dao = dao,
                                container = container,
                                refreshTick = wallTick,
                                scanFinishedAt = scanProgress.finishedAt,
                                // 「扫描中 / 排队中」都是**按库**判的（早先只有一个全局 running，
                                // 一个库在扫就把所有库的扫描按钮一起置灰 —— 队列化之后没这个问题了）
                                scanning = scanProgress.running && scanProgress.libraryId == heroLib.id,
                                queued = pendingScans.any { it.libraryId == heroLib.id },
                                recentAdded = recentAdded[heroLib.id] ?: 0,
                                canDrag = orderedLibs.size > 1,
                                host = dragHost,
                                onOpen = { openedLib = heroLib },
                                onScan = { mode -> startScan(heroLib, mode) },
                                onEdit = { editTarget = heroLib },
                                onDelete = { deleteTarget = heroLib },
                                onDrop = { fromId, hoverId -> dropLibrary(fromId, hoverId) },
                            )
                        }
                    }

                    // 其余库：宽屏两列（窄屏单列，[libColumns] 定的）。
                    // 按行铺而不是用 LazyVerticalGrid：网格和下面两条横排（最近新增 / 继续观看）
                    // 没法在同一个滚动容器里混排，这里只要"每行 [libColumns] 张、等宽"就够了
                    items(gridLibs.chunked(libColumns), key = { row -> row.first().id }) { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { lib ->
                                LibraryCell(
                                    modifier = Modifier.weight(1f),
                                    lib = lib,
                                    covers = 1,
                                    dao = dao,
                                    container = container,
                                    refreshTick = wallTick,
                                    scanFinishedAt = scanProgress.finishedAt,
                                    scanning = scanProgress.running && scanProgress.libraryId == lib.id,
                                    queued = pendingScans.any { it.libraryId == lib.id },
                                    recentAdded = recentAdded[lib.id] ?: 0,
                                    canDrag = orderedLibs.size > 1,
                                    host = dragHost,
                                    onOpen = { openedLib = lib },
                                    onScan = { mode -> startScan(lib, mode) },
                                    onEdit = { editTarget = lib },
                                    onDelete = { deleteTarget = lib },
                                    onDrop = { fromId, hoverId -> dropLibrary(fromId, hoverId) },
                                )
                            }
                            // 单列时 [libColumns] = 1，这里天然是 0 次
                            repeat(libColumns - row.size) {
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
                                onAction = { overlays.add(MediaOverlay.ScanLog) },
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
                                        onClick = {
                                            overlays.add(
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
                                    ContinueTile(row = row, onClick = { playRow(row) })
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

    openedLib?.let { lib ->
        PosterWallScreen(
            library = lib,
            dao = dao,
            refreshKey = wallTick,
            onChanged = { wallTick++ },
            onBack = { openedLib = null },
            onOpenMovie = { card, list, index ->
                overlays.add(MediaOverlay.Detail(card, list, index))
            },
        )
    }

    // 浮层栈：只渲染栈顶。返回 = 出栈，进详情/进"同演员作品" = 入栈
    when (val top = overlays.lastOrNull()) {
        is MediaOverlay.Detail -> MediaDetailScreen(
            card = top.card,
            playlist = top.list,
            index = top.index,
            dao = dao,
            onDeleted = { wallTick++ },
            onOpenWorks = { kind, name -> overlays.add(MediaOverlay.Works(kind, name)) },
            // 点详情底部的推荐 → 再压一层详情；传的是那一批推荐，播完自动接下一部就在推荐里走
            onOpenMovie = { c, l, i -> overlays.add(MediaOverlay.Detail(c, l, i)) },
            onBack = { overlays.removeLastOrNull() },
        )

        is MediaOverlay.Works -> MediaWorksScreen(
            kind = top.kind,
            name = top.name,
            dao = dao,
            refreshKey = wallTick,
            onChanged = { wallTick++ },
            onBack = { overlays.removeLastOrNull() },
            onOpenMovie = { card, list, index ->
                overlays.add(MediaOverlay.Detail(card, list, index))
            },
        )

        is MediaOverlay.History -> WatchHistoryScreen(
            container = container,
            onBack = { overlays.removeLastOrNull() },
        )

        is MediaOverlay.ScanLog -> MediaScanLogScreen(
            dao = dao,
            onOpenMovie = { card, list, index -> overlays.add(MediaOverlay.Detail(card, list, index)) },
            onBack = { overlays.removeLastOrNull() },
        )

        null -> Unit
    }

    // 拖动幻影：只在拎起来时挂。挂在 Popup 里所以独立于列表，列表滚动也不影响它的坐标
    dragHost.draggingId?.let { id ->
        libraries.firstOrNull { it.id == id }?.let { lib ->
            LibraryDragGhost(
                host = dragHost,
                lib = lib,
                dao = dao,
                scanFinishedAt = scanProgress.finishedAt,
            )
        }
    }

    deleteTarget?.let { lib ->
        // 这个库正在被扫、或者排在队列里等着扫吗？两种都得先撤掉再删 —— 否则扫描会在删除之后
        // 继续把条目写回来，留下的孤儿行既没有库能进、也不会再被任何一次扫描清理（那个路径已经没有库了）。
        // 判据用 libraryId（每轮扫描都带着它）：早先只有 rootCid，库多根时对不齐。
        val scanningThis = scanProgress.running && scanProgress.libraryId == lib.id
        val queuedThis = pendingScans.any { it.libraryId == lib.id }
        AlertDialog(
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
    }   // 极光底 Box 收尾
}

/**
 * 彻底删除一个媒体库：撤掉它的扫描（正在跑的停掉、排队里的移出）→ 清索引 → 清该库对应的落盘缓存。
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
    // 逐根处理而不是拼 5 参数 SQL：多根库本来就少见，循环更简单也不会踩 SQLite 变量上限
    paths.forEach { prefix ->
        dao.pickCodesInPath(prefix).forEach { p ->
            p.nfoPickCode?.let(nfoCodes::add)
            p.posterPickCode?.let(imageCodes::add)
            p.fanartPickCode?.let(imageCodes::add)
            p.thumbPickCode?.let(imageCodes::add)
        }
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
    if (lib.tierRestMinutes > 0) "每100个休 ${lib.tierRestMinutes} 分" else null,
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
 */
@Composable
private fun LibraryCountState(
    dao: com.open115.pad.data.media.MediaDao,
    rootPaths: List<String>,
    refreshKey: Long,
): Int? {
    val count by produceState(initialValue = -1, rootPaths, refreshKey) {
        value = rootPaths.sumOf { dao.movieCountIn(it) }
    }
    return if (count < 0) null else count
}

/** 通栏那张铺几张封面：一张太单薄（卡片宽一倍，右边会空一大片），三张正好压住宽度 */
private const val HERO_COVERS = 3

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
    /** 内容变了要重查的信号（删片、扫描落了新海报都算）：由页面传，组件自己拿不到 */
    refreshTick: Int,
    scanFinishedAt: Long,
    scanning: Boolean,
    queued: Boolean,
    recentAdded: Int,
    /** 库少于两个时没得排序，直接不挂拖动手势 */
    canDrag: Boolean,
    host: LibraryDragHost,
    onOpen: () -> Unit,
    onScan: (ScanMode) -> Unit,
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
            refreshTick = refreshTick,
            scanFinishedAt = scanFinishedAt,
            scanning = scanning,
            queued = queued,
            recentAdded = recentAdded,
            covers = covers,
            // 拎在手上的那张淡下去、被压住的那张亮一圈：拖动时"我从哪儿拿、要放哪儿"看得见
            dimmed = host.draggingId == lib.id,
            highlighted = host.hoverId == lib.id && host.draggingId != lib.id,
            onOpen = onOpen,
            onScan = onScan,
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
    /** 内容变了要重查的信号（删片、扫描落了新海报都算），见 LibraryCover / LibraryCountState */
    refreshTick: Int,
    scanFinishedAt: Long,
    /** 正在扫这个库 */
    scanning: Boolean,
    /** 这个库排在队列里等着扫 */
    queued: Boolean,
    /** 最近一轮扫描新增了几部（0 = 这轮没捞到新片，卡上就不显示这枚胶囊） */
    recentAdded: Int,
    /** 封面位铺几张：普通卡 1 张；**通栏卡 3 张**（卡片变宽，只放一张右边会空一大片） */
    covers: Int = 1,
    /** 正被拖在手上（整块淡下去，主体交给跟着手指的幻影） */
    dimmed: Boolean = false,
    /** 拖动落点（亮一圈主色描边） */
    highlighted: Boolean = false,
    onOpen: () -> Unit,
    onScan: (ScanMode) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = LibraryCountState(dao, lib.rootPaths, scanFinishedAt)
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        dimmed = dimmed,
        highlighted = highlighted,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryCover(lib, dao, container, refreshTick, scanFinishedAt, count, want = covers)
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
                // "这个库指向哪儿"的注脚。最多铺 2 行，其余折成一句提示 —— 路径行数会顶高卡片，
                // 而卡片高度该由封面定（两列并排时不等高会很乱）。完整路径在「编辑」里
                val shownPaths = lib.rootPaths.take(2)
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
                if (lib.rootPaths.size > shownPaths.size) {
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
                LibraryMetaChips(lib, recentAdded)
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
    /** 铺几张：普通卡 1 张；**通栏那张 3 张**（见列表里"通栏卡"那段） */
    want: Int = 1,
) {
    val models by produceState<List<String>>(
        initialValue = emptyList(), lib.rootPaths, refreshTick, scanFinishedAt, want,
    ) {
        val candidates = lib.rootPaths.flatMap { dao.posterSamplesInPath(it) }.distinct()
        value = container.imageUrlResolver.cachedAmong(candidates, container.cacheDir, want)
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        models.forEach { model ->
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
 * 换行带来的高度由封面的固定高兜底（见 [LibraryCover]），所以换到两行也不会
 * 把两列卡片撑得高低不一。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryMetaChips(lib: MediaLibraryEntity, recentAdded: Int) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
private fun PosterTile(card: MovieCard, libraryName: String?, onClick: () -> Unit) {
    Column(Modifier.width(108.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(158.dp)
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
private fun ContinueTile(row: WatchHistoryRow, onClick: () -> Unit) {
    val fraction = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(Modifier.width(108.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(158.dp)
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
        /** 每 100 个目录休息几分钟（0 = 不休息） */
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

    AlertDialog(
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
                    Switch(checked = tiered, onCheckedChange = { tiered = it })
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
                        Text("每 100 个目录休息一次", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "连着扫几百个目录后停几分钟，最能让 115 那边看不出这是脚本；" +
                                "休息时进度卡上会写「休息中」，点停止最多多等 1 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = restOn, onCheckedChange = { restOn = it })
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
                    Switch(checked = autoScan, onCheckedChange = { autoScan = it })
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
    /** 影片/系列详情（[list] 是它所在的列表，播完自动接下一部就是按它排的） */
    data class Detail(val card: MovieCard, val list: List<MovieCard>, val index: Int) : MediaOverlay

    /** 同一个演员 / 同一个标签的全部作品（**跨所有媒体库**，见 dao.byActor/byTag） */
    data class Works(val kind: WorksKind, val name: String) : MediaOverlay

    /** 观影历史（媒体库这一库线自己的记录；文件页的播放不走这里，那边看操作记录） */
    object History : MediaOverlay

    /** 扫描记录（何时扫的哪个库、结果、新增了哪些片——带海报图） */
    object ScanLog : MediaOverlay
}


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
     * ★ 别用"离中心最近"：通栏那张（奇数个时的第一张）横跨两整列，它的中心在屏幕正中间，
     *   于是指尖在它**左上角**时算出来反而离下面那张更近 —— 想把它前面的格子当落点会全落偏
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
    scanFinishedAt: Long,
) {
    val size = host.cardSize
    if (size.width <= 0 || size.height <= 0) return
    val count = LibraryCountState(dao, lib.rootPaths, scanFinishedAt)
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
