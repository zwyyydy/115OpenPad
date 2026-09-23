package com.open115.pad.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MovieCard
import com.open115.pad.ui.theme.AdaptiveBody
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
    var detail by remember { mutableStateOf<Triple<MovieCard, List<MovieCard>, Int>?>(null) }

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
                Text("媒体库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建媒体库")
                }
            }

            // 全局扫描进度 + 停止。停止**在目录/簇边界生效**（不会打断正在进行的那个请求），
            // 所以按钮点下去会先变成"停止中…"，等当前这一小步跑完才真的停。
            if (scanProgress.running) {
                LinearProgressIndicator(
                    progress = {
                        if (scanProgress.totalDirs > 0) scanProgress.doneDirs.toFloat() / scanProgress.totalDirs else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // totalDirs 为 0 时还在列目录阶段 —— 大库这一阶段本身可能占掉整轮的大半时间，
                        // 显示成"0/0 目录"会让人以为卡住了
                        if (scanProgress.totalDirs > 0) {
                            "正在扫描 ${scanProgress.currentDir}（${scanProgress.doneDirs}/${scanProgress.totalDirs} 目录，已索引 ${scanProgress.moviesIndexed} 部）"
                        } else {
                            "正在列目录 ${scanProgress.currentDir}…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                    TextButton(
                        onClick = { container.mediaScanner.requestStop() },
                        enabled = !scanProgress.stopping,
                    ) {
                        Text(if (scanProgress.stopping) "停止中…" else "停止")
                    }
                }
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

            if (libraries.isEmpty()) {
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
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openedLib = lib }
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
                                        if (lib.rateLimitMs > 0) " · 限速 ${lib.rateLimitMs}ms" else "",
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
                                                                cid, path, incremental = true, rateLimitMs = lib.rateLimitMs,
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
                                                                cid, path, incremental = false, rateLimitMs = lib.rateLimitMs,
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
            onSave = { name, cids, paths, rate, autoScan ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.insertLibrary(
                        MediaLibraryEntity(
                            name = name, rootCid = cid, rootPath = path,
                            createdAt = System.currentTimeMillis(), rateLimitMs = rate,
                            autoScanOnStart = autoScan,
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
            onSave = { name, cids, paths, rate, autoScan ->
                scope.launch {
                    val (cid, path) = MediaLibraryEntity.joinRoots(cids, paths)
                    dao.updateLibrary(lib.id, name, cid, path, rate, autoScan)
                    editTarget = null
                }
            },
        )
    }

    openedLib?.let { lib ->
        PosterWallScreen(
            library = lib,
            dao = dao,
            onBack = { openedLib = null },
            onOpenMovie = { card, list, index -> detail = Triple(card, list, index) },
        )
    }

    detail?.let { (card, list, index) ->
        MediaDetailScreen(
            card = card,
            playlist = list,
            index = index,
            dao = dao,
            onBack = { detail = null },
        )
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
 * 新建/编辑媒体库共用：库名 + 云盘路径（可多选、可继续追加）+ 扫描限速。
 * existing 非空为编辑模式：已选路径可逐条删除，再从目录树追加新路径。
 */
@Composable
private fun LibraryEditDialog(
    api: com.open115.pad.data.OpenApi,
    title: String,
    existing: MediaLibraryEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, cids: List<String>, paths: List<String>, rateLimitMs: Long, autoScanOnStart: Boolean) -> Unit,
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
    var autoScan by remember { mutableStateOf(existing?.autoScanOnStart ?: false) }
    var picking by remember { mutableStateOf(false) }
    val rate = rateText.toLongOrNull()?.coerceAtLeast(0) ?: 0L

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
