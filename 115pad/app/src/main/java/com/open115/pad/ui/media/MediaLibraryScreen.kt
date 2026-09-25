package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.WatchHistoryRow
import com.open115.pad.data.media.WorksSort
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.history.WatchHistoryScreen
import com.open115.pad.ui.history.displaySubtitle
import com.open115.pad.ui.history.displayTitle
import com.open115.pad.ui.history.isFinished
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.util.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    // 手机（窄屏）排版开关：这一页的顶栏与库卡片在 411dp 宽下会互相挤压
    // （搜索框 + 3 个图标总宽溢出、卡片右侧海报条把文字列压成一个字宽），
    // 都按这个标志切紧凑形态；平板（>=600dp）完全保持原样
    val compact = LocalConfiguration.current.screenWidthDp < 600

    val libraries by dao.libraries().collectAsState(initial = emptyList())
    val scanProgress by container.mediaScanner.progress.collectAsState()

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
        value = if (query.isBlank()) null else dao.searchAll(query, 200, sort.sql)
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

    /** 发起一个库的扫描（增量/全量共用，按钮收进了库卡片的「⋯」菜单） */
    fun startScan(lib: MediaLibraryEntity, incremental: Boolean) {
        scope.launch {
            container.transferScope.launch {
                lib.rootCids.zip(lib.rootPaths).forEach { (cid, path) ->
                    container.mediaScanner.runScan(
                        cid, path, incremental = incremental,
                        rateLimitMs = lib.rateLimitMs,
                        minVideoSizeMb = lib.minVideoSizeMb,
                        libraryId = lib.id,
                    )
                }
            }
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

    // 极光底：几团很淡的径向渐变，给毛玻璃卡片一点"透出来的东西"（纯绘制、零请求）
    Box(Modifier.fillMaxSize().auroraBackdrop()) {
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
                // 扫描记录：媒体库自己的流水（何时扫的哪个库、结果、新增影片带图）——
                // 不放文件页那份「操作记录」（那是文件操作的流水），也不占导航栏
                IconButton(onClick = { overlays.add(MediaOverlay.ScanLog) }) {
                    Icon(Icons.Outlined.Update, contentDescription = "扫描记录")
                }
                // 观影历史：入口放在媒体库页（它是"媒体库看到哪儿了"，不属于文件页 ——
                // 文件页的播放记录在操作记录里，两处不重复）
                IconButton(onClick = { overlays.add(MediaOverlay.History) }) {
                    Icon(Icons.Outlined.History, contentDescription = "观影历史")
                }
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建媒体库")
                }
            }

            // 全局扫描进度 + 停止。停止**在目录/簇边界生效**（不会打断正在进行的那个请求），
            // 所以按钮点下去会先变成"停止中…"，等当前这一小步跑完才真的停。
            if (scanProgress.running) {
                ScanProgressCard(
                    progress = scanProgress,
                    onStop = { container.mediaScanner.requestStop() },
                )
            } else if (scanProgress.stopped) {
                // 停止后说清两件事：进度没丢、怎么继续（续扫走增量，它按 scan_state 跳过已扫完的目录）
                Text(
                    "扫描已停止（已完成 ${scanProgress.doneDirs}/${scanProgress.totalDirs} 个目录，已索引 ${scanProgress.moviesIndexed} 部）" +
                        " · 用「增量扫描」可从断点继续",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                    items(libraries, key = { it.id }) { lib ->
                        LibraryCard(
                            lib = lib,
                            dao = dao,
                            container = container,
                            refreshTick = wallTick,
                            scanFinishedAt = scanProgress.finishedAt,
                            scanRunning = scanProgress.running,
                            compact = compact,
                            onOpen = { openedLib = lib },
                            onScan = { incremental -> startScan(lib, incremental) },
                            onEdit = { editTarget = lib },
                            onDelete = { deleteTarget = lib },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
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
            onSave = { name, cids, paths, rate, autoScan, minSize, interval ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.insertLibrary(
                        MediaLibraryEntity(
                            name = name, rootCid = cid, rootPath = path,
                            createdAt = System.currentTimeMillis(), rateLimitMs = rate,
                            autoScanOnStart = autoScan, minVideoSizeMb = minSize,
                            autoScanIntervalHours = interval,
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
            onSave = { name, cids, paths, rate, autoScan, minSize, interval ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.updateLibrary(lib.id, name, cid, path, rate, autoScan, minSize, interval)
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

    deleteTarget?.let { lib ->
        // 这个库正在被扫吗？是的话删除前必须先停 —— 否则扫描会在删除之后继续把条目写回来，
        // 留下的孤儿行既没有库能进、也不会再被任何一次扫描清理（那个路径已经没有库了）
        val scanningThis = scanProgress.running && scanProgress.rootCid in lib.rootCids
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除媒体库") },
            text = {
                Text(
                    "删除「${lib.name}」？只删除索引记录，不会动云盘里的文件。" +
                        if (scanningThis) "\n\n该库正在扫描，删除会先停止扫描（已扫完的目录不会白跑）。" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { deleteLibraryFully(container, dao, lib, waitForScan = scanningThis) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    }   // 极光底 Box 收尾
}

/**
 * 彻底删除一个媒体库：停扫描（如果正在扫它）→ 清索引 → 清该库对应的落盘缓存。
 *
 * 顺序不能反：
 * - 先停扫描再清索引 —— 扫描是协程，清完索引它还会继续 upsert，条目又回来了；
 *   所以要先 requestStop 并**等它真的收尾**（running 落回 false）再动数据库。
 *   等待是有界的：停止在目录/簇边界生效，上界约等于一次请求 + 一个限速间隔。
 * - 先收集 pick_code 再清索引 —— 索引一删就查不到该清哪些缓存文件了。
 */
private suspend fun deleteLibraryFully(
    container: com.open115.pad.AppContainer,
    dao: com.open115.pad.data.media.MediaDao,
    lib: MediaLibraryEntity,
    waitForScan: Boolean,
) {
    if (waitForScan) {
        container.mediaScanner.requestStop()
        // 兜底超时：万一收尾卡住（比如某个请求挂死），不能让删除永远不执行。
        // 超时后照常删 —— 最坏情况是留下几条孤儿行，比"删不掉"好。
        withTimeoutOrNull(15_000) { container.mediaScanner.progress.first { !it.running } }
    }

    val paths = lib.rootPaths
    val nfoCodes = mutableListOf<String>()
    val imageCodes = mutableListOf<String>()
    // 逐根处理而不是拼 5 参数 SQL：多根库本来就少见，循环更简单也不会踩 SQLite 变量上限
    paths.forEach { prefix ->
        dao.pickCodesInPath(prefix).forEach { p ->
            p.nfoPickCode?.let(nfoCodes::add)
            p.posterPickCode?.let(imageCodes::add)
            p.fanartPickCode?.let(imageCodes::add)
        }
        dao.deleteLibraryContent(prefix)
    }
    dao.deleteLibrary(lib.id)
    // 那条"扫描已停止（已完成 N/M 个目录）"提示指向的库已经没了，清掉它
    container.mediaScanner.clearStoppedNotice()

    // 清缓存。**按 pick_code 精确失效**，不能用粗粒度前缀：
    // nfo 的 key 是 `nfo|<pickCode>|<upt>`，清 "nfo|" 会把别的库的 nfo 一起清掉。
    container.mediaCache.invalidateAll(nfoCodes.distinct().map { "nfo|$it|" })
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
private fun autoScanText(lib: MediaLibraryEntity): String = when {
    !lib.autoScanOnStart -> ""
    lib.autoScanIntervalHours > 0 -> "自动扫描 ≤每 ${lib.autoScanIntervalHours}h"
    else -> "自动扫描（每次启动）"
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

/**
 * 库卡片（首页化的拼贴版）：左边文字（标题 + 部数徽标 + 路径 + 摘要），右边一条该库的海报。
 *
 * 海报**只取本地已缓存的**（[com.open115.pad.data.ImageUrlResolver.cachedPosterIfPresent]，
 * 纯文件判断、零请求）：这一页是入口页，一进来就要画几个库的图，不能背着
 * "未命中 = 一次直链解析 + 一次下载"的开销，更不该为铺卡片去打 115 接口。
 * 所以宁缺毋滥 —— 缓存里没有的库不画海报条，退回纯文字卡；海报会在扫描预取 /
 * 逛海报墙时自然落盘，下次进来就有了。
 *
 * 操作（增量/全量扫描、编辑、删除）收进右上角「⋯」菜单：原先三个图标按钮常驻，
 * 删除（破坏性）和扫描并列，平板上误触的代价太高。
 */
@Composable
private fun LibraryCard(
    lib: MediaLibraryEntity,
    dao: com.open115.pad.data.media.MediaDao,
    container: com.open115.pad.AppContainer,
    /** 内容变了要重查的信号（删片、扫描落了新海报都算），见 PosterStrip / libraryCountState */
    refreshTick: Int,
    scanFinishedAt: Long,
    scanRunning: Boolean,
    /** 手机窄屏：海报条从 3 张缩到 2 张并整体变小（见 PosterStrip 的说明） */
    compact: Boolean,
    onOpen: () -> Unit,
    onScan: (incremental: Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = LibraryCountState(dao, lib.rootPaths, scanFinishedAt)
    GlassCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lib.name, style = MaterialTheme.typography.titleMedium)
                    if (count != null) {
                        Spacer(Modifier.width(8.dp))
                        CountPill(count)
                    }
                }
                Spacer(Modifier.height(2.dp))
                // 多根库逐行列出；单根只有一行，视觉上和原来一致
                lib.rootPaths.forEach { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 元信息用 listOfNotNull + joinToString 拼：字符串拼接里的 if 优先级
                // 是踩过的坑（条件成立时后面几段全被吞掉），这种写法没有存身之处
                val meta = listOfNotNull(
                    if (lib.lastScanAt > 0) "上次扫描 ${Format.ago(lib.lastScanAt)}" else null,
                    if (lib.rateLimitMs > 0) "限速 ${lib.rateLimitMs}ms" else null,
                    if (lib.minVideoSizeMb > 0) "过滤 <${lib.minVideoSizeMb}MB" else null,
                    autoScanText(lib).ifEmpty { null },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            PosterStrip(lib, dao, container, refreshTick, scanFinishedAt, compact)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("增量扫描（只扫新增；上次停止后也用它继续）") },
                        enabled = !scanRunning,
                        onClick = {
                            menuOpen = false
                            onScan(true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("全量扫描（重新索引全部）") },
                        enabled = !scanRunning,
                        onClick = {
                            menuOpen = false
                            onScan(false)
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
 * 库卡片右侧的海报条：从该库随机采样一批候选，逐个查"在不在本地缓存"，凑满就收手。
 * 一张都没有就整条不画（卡片退回纯文字，别立几个空占位块）。
 *
 * [compact]（手机）下条子整体缩水：3 张 88dp → 2 张 64dp。
 * 旧布局的固定宽度（3 张 88dp + 间距 + 菜单键 ≈ 356dp）在 411dp 宽的屏上
 * 只剩 ~38dp 给左边文字列 —— 库名被挤成一字一行，必须让位。
 */
@Composable
private fun PosterStrip(
    lib: MediaLibraryEntity,
    dao: com.open115.pad.data.media.MediaDao,
    container: com.open115.pad.AppContainer,
    refreshTick: Int,
    scanFinishedAt: Long,
    compact: Boolean,
) {
    val want = if (compact) 2 else 3
    val tileW = if (compact) 64.dp else 88.dp
    val tileH = if (compact) 92.dp else 126.dp
    val models by produceState<List<Any?>>(
        initialValue = emptyList(), lib.rootPaths, refreshTick, scanFinishedAt,
    ) {
        val candidates = lib.rootPaths.flatMap { dao.posterSamplesInPath(it) }.distinct()
        val hits = mutableListOf<Any?>()
        for (code in candidates) {
            container.imageUrlResolver.cachedPosterIfPresent(code, container.cacheDir)?.let { hits.add(it) }
            if (hits.size >= want) break
        }
        value = hits
    }
    if (models.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        models.forEach { model ->
            Box(
                Modifier
                    .width(tileW)
                    .height(tileH)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
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

/** 「N 部」徽标：部数是这张卡上最该一眼看到的数字（原先埋在元信息行中间） */
@Composable
private fun CountPill(count: Int) {
    Text(
        if (count == 0) "还没有影片" else "$count 部",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
 * 下面一行片名。点了开详情浮层而不是直接播 —— 与海报墙的手感一致。
 */
@Composable
private fun PosterTile(card: MovieCard, onClick: () -> Unit) {
    Column(Modifier.width(108.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(158.dp)
                .clip(RoundedCornerShape(10.dp))
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
        }
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
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
                .clip(RoundedCornerShape(10.dp))
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
            maxLines = 1,
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
        minVideoSizeMb: Int,
        autoScanIntervalHours: Int,
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
            Column {
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
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it.filter { c -> c.isDigit() } },
                    label = { Text("扫描限速（毫秒，留空不限）") },
                    supportingText = { Text("相邻两次 115 API 请求的最小间隔，遇到频控可设为 500~1000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        Text("启动时自动增量扫描", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "程序启动登录后对这个库自动跑增量扫描（顺序执行，不与其他库并发）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoScan, onCheckedChange = { autoScan = it })
                }
                // 间隔只在开关打开时才有意义（关掉了给它设间隔是自相矛盾的输入）
                if (autoScan) {
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
                            minSize,
                            if (autoScan) intervalHours else 0,
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
    onStop: () -> Unit,
) {
    val listing = progress.phase == MediaScanner.Phase.Listing
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (listing) "正在列目录" else "正在索引",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
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
                TextButton(onClick = onStop, enabled = !progress.stopping) {
                    Text(if (progress.stopping) "停止中…" else "停止")
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
                    if (listing) {
                        append("已发现 ${progress.discoveredDirs} 个目录")
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

/**
 * 毛玻璃卡片：半透明白玻璃 + 上亮下暗的细描边 + 主色光晕。
 *
 * - 玻璃本体是**竖向渐变**（上 10% 白 → 下 3.5% 白）：均匀半透明看着像一块塑料，
 *   上亮下暗才有"一块玻璃立在那儿"的立体感
 * - 描边同样上亮下暗：顶边是环境光反射，底边几乎没有
 * - 光晕走 [androidx.compose.ui.draw.shadow] 的 spotColor/ambientColor（API 28+ 生效，
 *   更低版本退化成普通阴影，不会报错）
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                spotColor = accent.copy(alpha = 0.55f),
                ambientColor = accent.copy(alpha = 0.30f),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f)),
                ),
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) { content() }
}

/**
 * 极光底：三团很淡的径向渐变（主色 / 青 / 主色），给毛玻璃卡片一点"透出来的东西"。
 *
 * 这一页没有海报可铺（库列表页只有文字卡片），用色块造层次最省 —— 纯绘制、零请求、
 * 也不会像背景图那样喧宾夺主。位置刻意错开（左上 / 右侧 / 下方），避免看起来是三条带子。
 */
@Composable
private fun Modifier.auroraBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.04f),
                radius = size.width * 0.55f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 0.34f),
                radius = size.width * 0.50f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.42f, size.height * 1.02f),
                radius = size.width * 0.60f,
            ),
        )
    }
}
