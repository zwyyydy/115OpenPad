package com.open115.pad.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.FilesPrefs
import com.open115.pad.data.JsonObject
import com.open115.pad.data.JsonPrimitive
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.FilterPrefs
import com.open115.pad.data.FilterRules
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.Uploader
import com.open115.pad.data.parseFilesResponse
import com.open115.pad.data.parseSearchResponse
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.toImageMediaItem
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.DownloadDialog
import com.open115.pad.ui.components.FolderPickerDialog
import com.open115.pad.ui.components.ImageGalleryDialog
import com.open115.pad.ui.components.SidePaneWidth
import com.open115.pad.ui.components.TextEntryDialog
import com.open115.pad.ui.components.isImageItem
import com.open115.pad.ui.settings.UserInfoCard
import com.open115.pad.util.Downloader
import com.open115.pad.util.Format
import com.open115.pad.util.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilesViewModel(
    val api: OpenApi,
    private val prefs: FilesPrefs? = null,
    /** 图片直链解析（三级降级链 + 防频控缓存），图片查看器与本处"复制直链/下载"共用 */
    val urlResolver: ImageUrlResolver? = null,
    /** 高级过滤：方案存储 + 右上角总开关（未注入则不过滤） */
    private val filterPrefs: FilterPrefs? = null,
) : ViewModel() {

    data class DirEntry(val cid: String, val name: String)

    data class UiState(
        val stack: List<DirEntry> = listOf(DirEntry("0", "全部文件")),
        val items: List<FileItem> = emptyList(),
        /** 高级过滤后的可见列表（未启用过滤时 == items）。原始 items 永远保留，供刷新/批量操作 */
        val display: List<FileItem> = emptyList(),
        /** 当前生效的过滤方案（含就近继承解析结果）；null = 该目录不执行过滤 */
        val activeFilter: FilterRules.FilterScheme? = null,
        val count: Long = 0,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val selection: Set<String> = emptySet(),
        val gridMode: Boolean = true,
        val order: String = "file_name",
        val asc: Int = 1,
        val typeFilter: Int? = null,
        val starOnly: Boolean = false,
        val searching: Boolean = false,
        val searchQuery: String = "",
    ) {
        val selectMode: Boolean get() = selection.isNotEmpty()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    // ---- 高级过滤状态 ----
    private val _schemes = MutableStateFlow(emptyList<FilterRules.FilterScheme>())

    /** 文件页右上角的过滤总开关（跨重启保留） */
    val filterOn: kotlinx.coroutines.flow.StateFlow<Boolean> =
        (filterPrefs?.toggle ?: kotlinx.coroutines.flow.MutableStateFlow(true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setFilterOn(v: Boolean) {
        viewModelScope.launch { filterPrefs?.setToggle(v) }
    }

    /** 是否存在已启用的方案：决定漏斗是"开关"还是"去创建"。避免开关关闭后漏斗变成死按钮 */
    val hasEnabledScheme: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _schemes.map { list -> list.any { it.enabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // 先恢复持久化的排序/视图/筛选状态，再加载列表
        viewModelScope.launch {
            prefs?.let { p ->
                _ui.update { s ->
                    s.copy(
                        order = p.order.first(),
                        asc = p.asc.first(),
                        gridMode = p.gridMode.first(),
                        typeFilter = p.typeFilter.first().takeIf { t -> t >= 0 },
                        starOnly = p.starOnly.first(),
                    )
                }
            }
            refresh()
        }

        // ---- 高级过滤：派生"可见列表" ----
        // items / 目录链 / 方案 / 总开关 任一变化都重算；display 与 activeFilter 写回 UiState 后
        // 若值未变，StateFlow 会去重，不会形成回环。过滤计算放 Default 线程，千级列表无感。
        // 未注入 FilterPrefs 或关掉开关时 display == items，UI 行为与从前完全一致。
        viewModelScope.launch { filterPrefs?.schemes?.collect { _schemes.value = it } }
        viewModelScope.launch {
            combine(_ui, filterOn, _schemes) { ui, on, schemes ->
                Triple(
                    ui.items,
                    on,
                    FilterRules.resolveScheme(
                        schemes,
                        ui.stack.map { FilterRules.DirRef(it.cid, it.name) },
                    ),
                )
            }
                .distinctUntilChanged()
                .collect { (items, on, scheme) ->
                    val shown = if (!on || scheme == null) items
                    else withContext(Dispatchers.Default) {
                        items.filter { FilterRules.evaluate(it, scheme.group) }
                    }
                    _ui.update {
                        it.copy(
                            display = shown,
                            activeFilter = if (on) scheme else null,
                        )
                    }
                }
        }
    }

    fun refresh() = load(0, more = false)

    fun loadMore() {
        val s = _ui.value
        if (!s.loading && !s.loadingMore && s.items.size < s.count) load(s.items.size, more = true)
    }

    private fun load(offset: Int, more: Boolean) {
        viewModelScope.launch {
            _ui.update { if (more) it.copy(loadingMore = true) else it.copy(loading = true, error = null) }
            try {
                val s = _ui.value
                if (s.searching) {
                    val page = parseSearchResponse(api.search(s.searchQuery, offset = offset, type = s.typeFilter))
                    val mapped = page.data.map { it.toItem() }
                    _ui.update {
                        it.copy(
                            items = if (more) it.items + mapped else mapped,
                            count = page.count,
                        )
                    }
                } else {
                    val cid = s.stack.last().cid
                    val page = parseFilesResponse(
                        api.files(
                            cid = cid,
                            offset = offset,
                            order = s.order,
                            asc = s.asc,
                            type = s.typeFilter,
                            star = if (s.starOnly) 1 else null,
                        )
                    )
                    _ui.update {
                        it.copy(
                            items = if (more) it.items + page.items else page.items,
                            count = page.count,
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }

    fun openDir(item: FileItem) {
        val fid = item.fid ?: return
        _ui.update { it.copy(stack = it.stack + DirEntry(fid, item.fn), selection = emptySet()) }
        refresh()
    }

    fun popDir() {
        if (_ui.value.stack.size > 1) {
            _ui.update { it.copy(stack = it.stack.dropLast(1), selection = emptySet()) }
            refresh()
        }
    }

    fun jumpTo(index: Int) {
        // 已在目标层级时直接返回，避免一次无谓的列表刷新
        if (index >= _ui.value.stack.lastIndex) return
        _ui.update { it.copy(stack = it.stack.subList(0, index + 1), selection = emptySet()) }
        refresh()
    }

    fun setSort(order: String, asc: Int) {
        _ui.update { it.copy(order = order, asc = asc) }
        persistState()
        refresh()
    }

    fun setType(type: Int?) {
        _ui.update { it.copy(typeFilter = type) }
        persistState()
        refresh()
    }

    fun toggleStarOnly() {
        _ui.update { it.copy(starOnly = !it.starOnly) }
        persistState()
        refresh()
    }

    fun setGrid(grid: Boolean) {
        _ui.update { it.copy(gridMode = grid) }
        persistState()
    }

    /** 排序/视图/筛选持久化 */
    private fun persistState() {
        val p = prefs ?: return
        val s = _ui.value
        viewModelScope.launch {
            p.setSort(s.order, s.asc)
            p.setGrid(s.gridMode)
            p.setTypeFilter(s.typeFilter)
            p.setStarOnly(s.starOnly)
        }
    }

    fun search(query: String) {
        _ui.update { it.copy(searching = query.isNotBlank(), searchQuery = query.trim()) }
        refresh()
    }

    fun exitSearch() {
        _ui.update { it.copy(searching = false, searchQuery = "") }
        refresh()
    }

    fun toggleSelect(id: String?) {
        if (id == null) return
        _ui.update {
            it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
        }
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    /** 上传目标目录（当前所在目录） */
    fun currentTargetCid(): String = currentCid()

    // ---------- 操作 ----------

    suspend fun addFolder(name: String): String? = runOp {
        val resp = api.addFolder(currentCid(), name)
        val ok = resp.envOk()
        if (ok) refresh()
        ok to (resp.envMsg() ?: "创建失败")
    }

    suspend fun rename(item: FileItem, newName: String): String? = runOp {
        val fid = item.fid ?: return@runOp false to "缺少文件ID"
        val resp = api.updateFile(fid, newName)
        val ok = resp.envOk()
        if (ok) refresh()
        ok to (resp.envMsg() ?: "重命名失败")
    }

    suspend fun toggleStarItem(item: FileItem): String? = runOp {
        val fid = item.fid ?: return@runOp false to "缺少文件ID"
        val resp = api.updateFile(fid, star = if (item.ism == 1) 0 else 1)
        val ok = resp.envOk()
        if (ok) refresh()
        ok to (resp.envMsg() ?: "操作失败")
    }

    suspend fun deleteSelected(): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        return runOp {
            val resp = api.deleteFiles(ids.joinToString(","), parentId = currentCid())
            val ok = resp.envOk()
            if (ok) {
                clearSelection()
                refresh()
            }
            ok to (resp.envMsg() ?: "删除失败")
        }
    }

    suspend fun moveSelected(toCid: String, toName: String): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        return runOp {
            val resp = api.moveFiles(ids.joinToString(","), toCid)
            val ok = resp.envOk()
            if (ok) {
                clearSelection()
                refresh()
            }
            ok to (if (ok) "已移动到「$toName」" else resp.envMsg() ?: "移动失败")
        }
    }

    suspend fun copySelected(toCid: String, toName: String): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        return runOp {
            val resp = api.copyFiles(pid = toCid, fileIds = ids.joinToString(","))
            val ok = resp.envOk()
            if (ok) {
                clearSelection()
                refresh()
            }
            ok to (if (ok) "已复制到「$toName」" else resp.envMsg() ?: "复制失败")
        }
    }

    /** 直链解析逻辑收敛到 ImageUrlResolver：与图片查看器共用同一份防频控缓存 */
    suspend fun getDownloadUrl(pickCode: String): Result<String> = runCatching {
        val resolver = urlResolver ?: error("下载地址解析器未初始化")
        resolver.downloadUrl(pickCode)
    }

    private fun currentCid(): String = _ui.value.stack.last().cid

    private inline fun runOp(block: () -> Pair<Boolean, String>): String? {
        return try {
            val (ok, msg) = block()
            if (ok) null else msg
        } catch (e: Exception) {
            "网络错误：${e.message}"
        }
    }
}

val filterTypes: List<Pair<String, Int?>> = listOf(
    "全部" to null,
    "视频" to 4,
    "图片" to 2,
    "音乐" to 3,
    "文档" to 1,
    "压缩包" to 5,
    "应用" to 6,
    "书籍" to 7,
)

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    expanded: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlayVideo: (item: FileItem, playlist: List<PlaylistEntry>, index: Int) -> Unit,
    onOpenGallery: (items: List<ImageMediaItem>, index: Int) -> Unit,
    onOpenFilterRules: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var starTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf<String?>(null) }
    var downloadTarget by remember { mutableStateOf<FileItem?>(null) }
    /** 当前视图里的图片序列（大图画廊用），保持用户看到的顺序 */
    val galleryItems = remember(ui.items) {
        ui.items.filter { isImageItem(it) }.map { it.toImageMediaItem() }
    }
    var searchMode by remember { mutableStateOf(false) }
    var sideCollapsed by rememberSaveable { mutableStateOf(false) }

    // ---- 返回键分级处理（后注册的优先级更高）----
    // ① 浏览子目录时：返回上一级；根目录"返无可返"时不拦截，交给系统退出应用
    androidx.activity.compose.BackHandler(enabled = ui.stack.size > 1) { vm.popDir() }
    // ② 多选模式下：先退出多选
    androidx.activity.compose.BackHandler(enabled = ui.selectMode) { vm.clearSelection() }
    // ③ 搜索栏展开时：先收起搜索
    androidx.activity.compose.BackHandler(enabled = searchMode) {
        searchMode = false
        vm.exitSearch()
    }

    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            notify("开始上传…")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                    if (bytes.size > 32 * 1024 * 1024) error("当前版本仅支持 32MB 内的小文件")
                    // SAF 不同来源的 uri 形态不同（数字 id / path），文件名以 DISPLAY_NAME 查询为准
                    var name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "upload.bin"
                    runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.let { c ->
                            if (c.moveToFirst()) {
                                val n = c.getString(0)
                                if (!n.isNullOrBlank()) name = n
                            }
                            c.close()
                        }
                    }
                    Uploader.uploadSmallLogged(
                        log = context.appContainer.transferLog,
                        api = vm.api,
                        fileName = name,
                        bytes = bytes,
                        targetCid = vm.currentTargetCid(),
                        // 传输中心要能回答"传到哪个目录了"，这里给面包屑全路径最直观
                        targetName = ui.stack.joinToString(" / ") { it.name },
                    )
                }
            }
            result.onSuccess {
                notify("上传成功：" + it.fileName + if (it.reused) "（秒传）" else "")
                vm.refresh()
            }.onFailure { notify("上传失败：${it.message}") }
        }
    }

    fun onItemActivate(item: FileItem) {
        if (ui.selectMode) {
            vm.toggleSelect(item.fid)
        } else if (item.isDir) {
            vm.openDir(item)
        } else if (item.isv == 1) {
            // 播放列表 = 当前视图里可播放的视频，保持用户看到的顺序（已应用排序与筛选），
            // 以 pick_code 作为身份标识（播放器就是按 pick_code 取流的）。
            // 注意：列表是分页加载的，这里只覆盖已加载部分——切集不会越界，仅范围有限。
            val entries = ui.items
                .filter { !it.isDir && it.isv == 1 }
                .mapNotNull { f -> f.pc?.let { pc -> PlaylistEntry(pc, f.fn, f.fid) } }
            val index = entries.indexOfFirst { it.pc == item.pc }.coerceAtLeast(0)
            onPlayVideo(item, entries, index)
        } else if (isImageItem(item)) {
            // 传"图片序列 + 索引"给大图画廊，才能左右翻页；同时带上归一化后的元数据
            // 把"图片序列 + 索引"上抛，画廊在应用根层级渲染（才能盖住侧栏）
            val media = item.toImageMediaItem()
            val idx = galleryItems.indexOfFirst { it.pickCode == media.pickCode }
            onOpenGallery(galleryItems, if (idx >= 0) idx else 0)
        } else {
            downloadTarget = item
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (expanded) {
            AnimatedVisibility(
                visible = !sideCollapsed,
                enter = expandHorizontally(),
                exit = shrinkHorizontally(),
            ) {
                Column(Modifier.width(SidePaneWidth).fillMaxSize()) {
                    UserInfoCard(Modifier.padding(16.dp))
                    HorizontalDivider()
                    FilesSidePane(vm, ui, Modifier.weight(1f))
                }
            }
            SidePaneHandle(
                collapsed = sideCollapsed,
                onToggle = { sideCollapsed = !sideCollapsed },
            )
            FilesBrowserPane(
                vm = vm,
                ui = ui,
                modifier = Modifier.weight(1f),
                searchMode = searchMode,
                onSearchToggle = {
                    searchMode = !searchMode
                    if (!searchMode) vm.exitSearch()
                },
                onActivate = ::onItemActivate,
                onMove = { moveMode = "move" },
                onCopy = { moveMode = "copy" },
                onDelete = { showDeleteConfirm = true },
                onRename = { renameTarget = it },
                onStar = { starTarget = it },
                onDownload = {
                    val files = ui.items.filter { it.fid in ui.selection && !it.isDir }
                    scope.launch {
                        var queued = 0
                        for (f in files) {
                            val pc = f.pc
                            if (pc.isNullOrBlank()) continue
                            vm.getDownloadUrl(pc).onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, f.fn) }
                                queued++
                            }
                        }
                        notify(if (queued > 0) "已加入 $queued 个下载任务" else "所选文件均无法下载")
                    }
                },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                onOpenFilterRules = onOpenFilterRules,
                onCreateFolder = { showCreate = true },
            )
        } else {
            FilesBrowserPane(
                vm = vm,
                ui = ui,
                modifier = Modifier.fillMaxSize(),
                searchMode = searchMode,
                onSearchToggle = {
                    searchMode = !searchMode
                    if (!searchMode) vm.exitSearch()
                },
                onActivate = ::onItemActivate,
                onMove = { moveMode = "move" },
                onCopy = { moveMode = "copy" },
                onDelete = { showDeleteConfirm = true },
                onRename = { renameTarget = it },
                onStar = { starTarget = it },
                onDownload = {
                    val files = ui.items.filter { it.fid in ui.selection && !it.isDir }
                    scope.launch {
                        var queued = 0
                        for (f in files) {
                            val pc = f.pc
                            if (pc.isNullOrBlank()) continue
                            vm.getDownloadUrl(pc).onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, f.fn) }
                                queued++
                            }
                        }
                        notify(if (queued > 0) "已加入 $queued 个下载任务" else "所选文件均无法下载")
                    }
                },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                onOpenFilterRules = onOpenFilterRules,
                onCreateFolder = { showCreate = true },
            )
        }
    }

    // ---------- 对话框 ----------

    if (showCreate) {
        TextEntryDialog("新建文件夹", "文件夹名称", onConfirm = {
            showCreate = false
            scope.launch { notify(vm.addFolder(it)) }
        }, onDismiss = { showCreate = false })
    }
    renameTarget?.let { target ->
        TextEntryDialog("重命名", "新名称", initial = target.fn, onConfirm = {
            renameTarget = null
            scope.launch { notify(vm.rename(target, it)) }
        }, onDismiss = { renameTarget = null })
    }
    starTarget?.let { target ->
        LaunchedEffect(target.fid) {
            notify(vm.toggleStarItem(target))
        }
        starTarget = null
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除 ${ui.selection.size} 项",
            text = "文件将被移入回收站，可在回收站中还原。",
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                scope.launch { notify(vm.deleteSelected()) }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    moveMode?.let { mode ->
        FolderPickerDialog(
            api = vm.api,
            title = if (mode == "move") "移动到…" else "复制到…",
            onDismiss = { moveMode = null },
            onPick = { cid, name ->
                moveMode = null
                scope.launch {
                    notify(if (mode == "move") vm.moveSelected(cid, name) else vm.copySelected(cid, name))
                }
            },
        )
    }
    downloadTarget?.let { item ->
        DownloadDialog(
            name = item.fn,
            sizeText = "大小：${Format.size(item.fs)}",
            onDismiss = { downloadTarget = null },
            onDownload = {
                downloadTarget = null
                scope.launch {
                    val pc = item.pc
                    if (pc.isNullOrBlank()) {
                        notify("该文件缺少提取码，无法下载")
                    } else {
                        vm.getDownloadUrl(pc)
                            .onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, item.fn) }
                                notify("已加入系统下载队列")
                            }
                            .onFailure { notify(it.message) }
                    }
                }
            },
            onCopyLink = {
                downloadTarget = null
                scope.launch {
                    val pc = item.pc
                    if (pc.isNullOrBlank()) notify("该文件缺少提取码")
                    else vm.getDownloadUrl(pc).onSuccess { copyToClipboard(context, it); notify("直链已复制") }
                        .onFailure { notify(it.message) }
                }
            },
        )
    }
}

/** 侧栏收起/展开把手：贴边的细条，点一下切换 */
@Composable
private fun SidePaneHandle(collapsed: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
            contentDescription = if (collapsed) "展开侧栏" else "收起侧栏",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 平板宽屏下的左侧栏：目录层级 + 分类筛选 */
@Composable
private fun FilesSidePane(vm: FilesViewModel, ui: FilesViewModel.UiState, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("当前位置", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        ui.stack.forEachIndexed { i, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.jumpTo(i) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = if (i == ui.stack.lastIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    entry.name,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == ui.stack.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("分类", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        filterTypes.forEach { (label, type) ->
            com.open115.pad.ui.theme.AppChip(
                label = label,
                selected = ui.typeFilter == type,
                onClick = { vm.setType(type) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        com.open115.pad.ui.theme.AppChip(
            label = "仅星标",
            selected = ui.starOnly,
            onClick = { vm.toggleStarOnly() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            leading = {
                Icon(
                    if (ui.starOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = if (ui.starOnly) com.open115.pad.ui.theme.AppColors.AccentDeep
                    else com.open115.pad.ui.theme.AppColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (ui.searching) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("搜索「${ui.searchQuery}」结果", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
