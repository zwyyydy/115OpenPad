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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.WorksSort
import com.open115.pad.ui.history.WatchHistoryScreen
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.util.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val libraries by dao.libraries().collectAsState(initial = emptyList())
    val scanProgress by container.mediaScanner.progress.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    var editTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }
    // 扫描菜单的展开状态（每个库卡片一个）
    var scanMenuFor by remember { mutableStateOf<MediaLibraryEntity?>(null) }
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
                Text(
                    if (query.isBlank()) "媒体库" else "全局搜索「$query」",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 毛玻璃搜索框（这一页没有背景图可透，退化成半透明胶囊）
                FrostedSearchField(
                    value = query,
                    onValueChange = { query = it },
                    backdrop = null,
                    modifier = Modifier.widthIn(min = 200.dp, max = 340.dp).padding(end = 4.dp),
                )
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
                        columns = GridCells.Adaptive(minSize = 130.dp),
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
                LazyColumn(Modifier.weight(1f)) {
                    items(libraries, key = { it.id }) { lib ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            onClick = { openedLib = lib },
                        ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(lib.name, style = MaterialTheme.typography.titleMedium)
                                // 多根库逐行列出；单根只有一行，视觉上和原来一致
                                lib.rootPaths.forEach { path ->
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "建于 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(lib.createdAt)) +
                                        // 扫描结束后要重算，否则"还没有影片"会一直挂着
                                        LibraryCount(dao, lib.rootPaths, scanProgress.finishedAt) +
                                        // ☠ 每个 if 都必须带括号：`a + if (c) x else "" + if (d) y else ""`
                                        // 会解析成 `a + if (c) x else ("" + if (d) y else "")` ——
                                        // 条件成立时后面几段全被吞掉（早先「限速」一有值，「过滤」就再也显示不出来）
                                        (if (lib.rateLimitMs > 0) " · 限速 ${lib.rateLimitMs}ms" else "") +
                                        (if (lib.minVideoSizeMb > 0) " · 过滤 <${lib.minVideoSizeMb}MB" else "") +
                                        autoScanText(lib) +
                                        (if (lib.lastScanAt > 0) " · 上次扫描 ${Format.ago(lib.lastScanAt)}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                                // 扫描菜单：增量（按 upt 跳过未变化目录）/ 全量（不跳过）
                                Box {
                                    IconButton(
                                        onClick = { scanMenuFor = lib },
                                        enabled = !scanProgress.running,
                                    ) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = "扫描")
                                    }
                                    DropdownMenu(
                                        expanded = scanMenuFor == lib,
                                        onDismissRequest = { scanMenuFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("增量扫描（只扫新增；上次停止后也用它继续）") },
                                            onClick = {
                                                scanMenuFor = null
                                                scope.launch {
                                                    container.transferScope.launch {
                                                        lib.rootCids.zip(lib.rootPaths).forEach { (cid, path) ->
                                                            container.mediaScanner.runScan(
                                                                cid, path, incremental = true,
                                                                rateLimitMs = lib.rateLimitMs,
                                                                minVideoSizeMb = lib.minVideoSizeMb,
                                                                libraryId = lib.id,
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("全量扫描（重新索引全部）") },
                                            onClick = {
                                                scanMenuFor = null
                                                scope.launch {
                                                    container.transferScope.launch {
                                                        lib.rootCids.zip(lib.rootPaths).forEach { (cid, path) ->
                                                            container.mediaScanner.runScan(
                                                                cid, path, incremental = false,
                                                                rateLimitMs = lib.rateLimitMs,
                                                                minVideoSizeMb = lib.minVideoSizeMb,
                                                                libraryId = lib.id,
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                IconButton(onClick = { editTarget = lib }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                                }
                                IconButton(onClick = { deleteTarget = lib }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
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
 * 抽出来是因为它在字符串拼接里，而那种地方最容易踩优先级坑（见调用点的注释）。
 */
private fun autoScanText(lib: MediaLibraryEntity): String = when {
    !lib.autoScanOnStart -> ""
    lib.autoScanIntervalHours > 0 -> " · 自动扫描 ≤每 ${lib.autoScanIntervalHours}h"
    else -> " · 自动扫描（每次启动）"
}

/**
 * 库内影片数：卡片上显示「N 部」，进库前就知道有没有内容（多根库取各根之和）。
 *
 * [refreshKey] 必须传一个"扫描结束后会变"的值（这里传 scanProgress.finishedAt）：
 * produceState 只在 key 变化时重算，而 rootPaths 在扫描前后是不变的 ——
 * 只以它为 key 的话，刚扫完的库会一直显示扫描前的数字（"还没有影片"）。
 */
@Composable
private fun LibraryCount(
    dao: com.open115.pad.data.media.MediaDao,
    rootPaths: List<String>,
    refreshKey: Long,
): String {
    val count by produceState(initialValue = -1, rootPaths, refreshKey) {
        value = rootPaths.sumOf { dao.movieCountIn(it) }
    }
    return when {
        count < 0 -> ""
        count == 0 -> " · 还没有影片"
        else -> " · $count 部"
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
