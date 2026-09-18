package com.open115.pad.ui.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open115.pad.data.DownloadSubmitter
import com.open115.pad.data.DownloadPrefs
import com.open115.pad.data.OpenApi
import com.open115.pad.data.OfflineTask
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.parseOfflineQuota
import com.open115.pad.data.parseOfflineTasks
import com.open115.pad.ui.components.FolderPickerDialog
import com.open115.pad.util.Format
import com.open115.pad.util.Torrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

class OfflineViewModel(
    val api: OpenApi,
    /** 云下载保存位置持久化：设置一次，后续提交都落到这里 */
    private val downloadPrefs: DownloadPrefs? = null,
    /** 提交入口（与剪贴板/外部唤起共用同一套语义与错误提示） */
    private val submitter: DownloadSubmitter? = null,
) : ViewModel() {

    data class UiState(
        val tasks: List<OfflineTask> = emptyList(),
        val quotaTotal: Int? = null,
        val quotaSurplus: Int? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val page: Int = 1,
        val pageCount: Int = 1,
        val total: Long = 0,
        val filter: Int? = null, // null 全部；0 分配中 1 下载中 2 已完成 -1 失败
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        refresh()
        // 有进行中的任务时自动轮询；静默刷新按 hash 合并进度，不重置已加载页
        viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (_ui.value.tasks.any { it.status == 0 || it.status == 1 }) mergeRefresh()
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (silent && _ui.value.page > 1) {
            mergeRefresh()
            return
        }
        loadPage(1, replace = true)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.loadingMore || s.page >= s.pageCount) return
        loadPage(s.page + 1, replace = false)
    }

    fun setFilter(f: Int?) = _ui.update { it.copy(filter = f) }

    /** 只刷第一页并把已知任务按 info_hash 合并，保持已加载页不被重置 */
    private fun mergeRefresh() {
        viewModelScope.launch {
            try {
                val p = parseOfflineTasks(api.offlineTasks(page = 1))
                _ui.update { s ->
                    val byHash = p.tasks.associateBy { it.infoHash }
                    val merged = s.tasks.map { t -> byHash[t.infoHash] ?: t }
                    // 服务端有、本地没有的（例如刚从剪贴板/外部唤起提交的任务）
                    // 要补进列表——只做 map 替换会把新任务整个丢掉，跳过来也看不到。
                    val known = merged.mapTo(HashSet()) { it.infoHash }
                    val fresh = p.tasks.filter { it.infoHash !in known }
                    s.copy(tasks = fresh + merged, error = null)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadPage(page: Int, replace: Boolean) {
        viewModelScope.launch {
            _ui.update { if (replace) it.copy(loading = true, error = null) else it.copy(loadingMore = true) }
            try {
                val p = parseOfflineTasks(api.offlineTasks(page = page))
                val quota = if (replace) {
                    runCatching { parseOfflineQuota(api.offlineQuota()) }.getOrNull()
                } else null
                _ui.update {
                    it.copy(
                        tasks = if (replace) p.tasks else it.tasks + p.tasks,
                        page = page,
                        pageCount = p.pageCount ?: 1,
                        total = p.count?.toLong() ?: 0L,
                        quotaTotal = quota?.count ?: it.quotaTotal,
                        quotaSurplus = quota?.surplus ?: it.quotaSurplus,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }

    // ---- 保存位置持久化：设置一次，后续提交都落到这里 ----
    val saveLocation: kotlinx.coroutines.flow.StateFlow<DownloadPrefs.SaveLocation> =
        (downloadPrefs?.saveLocation ?: MutableStateFlow(DownloadPrefs.SaveLocation("0", "根目录")))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DownloadPrefs.SaveLocation("0", "根目录"),
            )

    fun rememberSaveLocation(cid: String, name: String) {
        viewModelScope.launch { downloadPrefs?.setSaveLocation(DownloadPrefs.SaveLocation(cid, name)) }
    }

    suspend fun addUrls(urls: String, wpPathId: String): String? {
        // 与剪贴板识别、外部唤起的提交共用同一套实现与错误提示
        val err = if (submitter != null) {
            submitter.submit(api, urls, wpPathId)
        } else {
            // 防御分支：未注入提交器时直接调接口（正常装配不会走到）
            try {
                val resp = api.offlineAddUrls(urls, wpPathId)
                if (resp.envOk()) null else resp.envMsg() ?: "添加任务失败"
            } catch (e: Exception) {
                "网络错误：${e.message}"
            }
        }
        if (err == null) refresh(silent = true)
        return err
    }

    suspend fun delete(infoHash: String, delSourceFile: Boolean): String? = try {
        val resp = api.offlineDelete(infoHash, if (delSourceFile) 1 else 0)
        if (resp.envOk()) {
            refresh(silent = true)
            null
        } else {
            resp.envMsg() ?: "删除任务失败"
        }
    } catch (e: Exception) {
        "网络错误：${e.message}"
    }
}

private fun statusLabel(status: Int): String = when (status) {
    -1 -> "下载失败"; 0 -> "分配中"; 1 -> "下载中"; 2 -> "已完成"; else -> "状态 $status"
}

private val filterOptions: List<Pair<String, Int?>> = listOf(
    "全部" to null,
    "下载中" to 1,
    "已完成" to 2,
    "失败" to -1,
    "分配中" to 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    vm: OfflineViewModel,
    expanded: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val ui by vm.ui.collectAsState()
    val saveLocation by vm.saveLocation.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<OfflineTask?>(null) }

    // 每次进入本页拉一次第一页：外部唤起/剪贴板刚提交的任务要能立刻看到，
    // 否则已存在的 ViewModel 只有在"有进行中任务"时才会轮询合并。
    LaunchedEffect(Unit) { vm.refresh(silent = true) }

    val shown = if (ui.filter == null) ui.tasks else ui.tasks.filter { it.status == ui.filter }

    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云下载") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("添加任务") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.quotaTotal != null) {
                // 配额微胶囊：横向进度指示（已用/总量）替代纯文字
                val total = ui.quotaTotal ?: 0
                val surplus = ui.quotaSurplus ?: 0
                val usedFrac = if (total > 0) ((total - surplus).toFloat() / total).coerceIn(0f, 1f) else 0f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.open115.pad.ui.theme.StatusBadge(
                        "配额 已用 ${total - surplus} / 共 $total",
                        com.open115.pad.ui.theme.AppColors.BlueBg,
                        com.open115.pad.ui.theme.AppColors.BlueFg,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(com.open115.pad.ui.theme.AppColors.GraySoft),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(usedFrac)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(com.open115.pad.ui.theme.AppColors.Accent),
                        )
                    }
                }
            }

            // 状态筛选：轻量分段胶囊
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                filterOptions.forEach { (label, status) ->
                    com.open115.pad.ui.theme.AppChip(
                        label = label,
                        selected = ui.filter == status,
                        onClick = { vm.setFilter(status) },
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    ui.loading && ui.tasks.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    ui.error != null && ui.tasks.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.refresh() }) { Text("重试") }
                    }

                    shown.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (ui.filter == null) "暂无云下载任务" else "该状态下暂无任务",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        // 宽屏自适应双列卡片（手机单列），底部加载指示独立于网格
                        Column(Modifier.fillMaxSize()) {
                            com.open115.pad.ui.theme.AdaptiveCardGrid(
                                items = shown,
                                key = { it.infoHash },
                                modifier = Modifier.weight(1f),
                                onLoadMore = { vm.loadMore() },
                            ) { task ->
                                TaskCard(
                                    task = task,
                                    onDelete = { deleteTarget = task },
                                )
                            }
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (ui.loadingMore) {
                                    CircularProgressIndicator(Modifier.height(24.dp))
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(
                                    "已加载 ${ui.tasks.size} / 共 ${ui.total} 项",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            api = vm.api,
            initial = saveLocation,
            onDismiss = { showAdd = false },
            // 选择目录即落盘：这里就写回持久化，不必等任务真的提交过
            onPickLocation = { cid, name -> vm.rememberSaveLocation(cid, name) },
            onConfirm = { urls, cid, name ->
                showAdd = false
                // 兜底再写一次：用户没动过"选择"时保存的仍是同一个值，重复写无副作用
                vm.rememberSaveLocation(cid, name)
                scope.launch { notify(vm.addUrls(urls, cid)) }
            },
        )
    }
    deleteTarget?.let { task ->
        var deleteSource by remember(task.infoHash) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除任务") },
            text = {
                Column {
                    Text("确定删除「${task.name ?: task.infoHash}」？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                        Text("同时删除已下载的文件", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val del = deleteSource
                    deleteTarget = null
                    scope.launch { notify(vm.delete(task.infoHash, del)) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TaskCard(task: OfflineTask, onDelete: () -> Unit) {
    val C = com.open115.pad.ui.theme.AppColors
    val title = task.name ?: task.url ?: task.infoHash
    // 类型识别：磁力链 / 压缩包 / 视频 / 普通（按名称后缀或 url 协议）
    val isMagnet = task.url?.startsWith("magnet:") == true || title.startsWith("magnet:")
    val isArchive = listOf("zip", "rar", "7z", "tar", "gz").any {
        title.substringAfterLast('.', "").lowercase() == it
    }
    val isVideo = listOf("mp4", "mkv", "avi", "mov", "wmv", "ts", "webm", "m4v", "rmvb").any {
        title.substringAfterLast('.', "").lowercase() == it
    }

    com.open115.pad.ui.theme.AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                isMagnet -> com.open115.pad.ui.theme.KindBadge(title, false, Modifier.size(36.dp))
                isArchive -> com.open115.pad.ui.theme.KindBadge(
                    if (title.contains('.')) title else "x.zip", false, Modifier.size(36.dp),
                )
                isVideo -> com.open115.pad.ui.theme.KindBadge(
                    if (title.contains('.')) title else "x.mp4", false, Modifier.size(36.dp),
                )
                else -> com.open115.pad.ui.theme.KindBadge(
                    if (title.contains('.')) title else "x.bin", false, Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = C.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除任务",
                    tint = C.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 状态徽章行
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (task.status) {
                1 -> {
                    com.open115.pad.ui.theme.StatusBadge("下载中", C.BlueBg, C.BlueFg)
                    if (task.size > 0) {
                        com.open115.pad.ui.theme.StatusBadge(
                            Format.size(task.size),
                            C.SlateBg, C.SlateFg,
                        )
                    }
                }
                2 -> com.open115.pad.ui.theme.StatusBadge("已完成", C.GreenBg, C.GreenFg)
                -1 -> com.open115.pad.ui.theme.StatusBadge("下载失败", C.RedBg, C.RedFg)
                0 -> com.open115.pad.ui.theme.StatusBadge("分配中", C.AmberBg, C.AmberFg)
                else -> com.open115.pad.ui.theme.StatusBadge(statusLabel(task.status), C.SlateBg, C.SlateFg)
            }
            if (task.addTime > 0) {
                com.open115.pad.ui.theme.StatusBadge(
                    Format.dateTime(task.addTime),
                    C.SlateBg, C.TextTertiary,
                )
            }
        }

        // 下载中/分配中：行内微型进度条 + 百分比
        if (task.status == 1 || task.status == 0) {
            val p = Format.percent(task.percentDone) / 100f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(C.GraySoft),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (task.status == 1) C.Accent else C.AmberFg),
                    )
                }
                Text(
                    "${Format.percent(task.percentDone)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = C.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun AddTaskDialog(
    api: OpenApi,
    initial: DownloadPrefs.SaveLocation,
    onDismiss: () -> Unit,
    onPickLocation: (cid: String, name: String) -> Unit,
    onConfirm: (urls: String, wpPathId: String, wpName: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf("") }
    var targetCid by remember { mutableStateOf(initial.cid) }
    var targetName by remember { mutableStateOf(initial.name) }
    var picking by remember { mutableStateOf(false) }
    var torrent by remember { mutableStateOf<Torrent.TorrentInfo?>(null) }
    var torrentBusy by remember { mutableStateOf(false) }
    var torrentError by remember { mutableStateOf<String?>(null) }

    // 选择 .torrent 文件 → 本地解析出 info_hash → 生成磁力链填入链接框
    val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            torrentBusy = true
            torrentError = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalArgumentException("无法读取所选文件")
                    }
                    val info = Torrent.parse(bytes)
                    torrent = info
                    urls = Torrent.buildMagnet(info)
                } catch (e: Exception) {
                    torrent = null
                    torrentError = e.message ?: "种子解析失败"
                } finally {
                    torrentBusy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加云下载任务") },
        text = {
            Column {
                OutlinedTextField(
                    value = urls,
                    onValueChange = { urls = it },
                    label = { Text("链接（每行一个）") },
                    placeholder = { Text("HTTP(S)、FTP、磁力链、电驴") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { torrentPicker.launch(arrayOf("*/*")) },
                        enabled = !torrentBusy,
                    ) {
                        Text(if (torrentBusy) "解析中…" else "选择种子文件")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "选 .torrent 自动转磁力链",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                torrent?.let { t ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已解析：${t.name ?: "未命名"} · ${Format.size(t.totalSize)} · ${t.fileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                torrentError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "保存位置：$targetName",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { picking = true }) { Text("选择") }
                }
                Text(
                    "选定即记住，之后手动添加、剪贴板识别、外部 App 唤起的云下载都默认存到这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(urls.trim(), targetCid, targetName) },
                enabled = urls.isNotBlank() && !torrentBusy,
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking) {
        FolderPickerDialog(
            api = api,
            title = "选择保存位置",
            onDismiss = { picking = false },
            // 选择器固定从根目录进入（面包屑没有祖先链，从上次位置直接进入会困死在该目录），
            // 当前已保存位置由上方"保存位置：xxx"文案展示，不动"选择"就沿用该值
            onPick = { cid, name ->
                targetCid = cid
                targetName = name
                // 立即持久化：用户只"指定目录"、不立刻提交任务时也要记住
                onPickLocation(cid, name)
                picking = false
            },
        )
    }
}
