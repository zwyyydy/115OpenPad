package com.open115.pad.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.FileItem
import com.open115.pad.ui.components.FileGridCard
import com.open115.pad.ui.components.FileListRow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState

private val sortOptions = listOf(
    "file_name" to "按名称",
    "file_size" to "按大小",
    "user_utime" to "按修改时间",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesBrowserPane(
    vm: FilesViewModel,
    ui: FilesViewModel.UiState,
    modifier: Modifier = Modifier,
    searchMode: Boolean,
    onSearchToggle: () -> Unit,
    onActivate: (FileItem) -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRename: (FileItem) -> Unit,
    /** 选中 ≥2 项时的批量重命名入口 */
    onBatchRename: () -> Unit,
    /** 全选 / 取消全选当前显示的条目 */
    onSelectAll: () -> Unit,
    onStar: (FileItem) -> Unit,
    /** 置顶 / 取消置顶：传入当前选择集里可置顶的文件夹（可能为空） */
    onPin: (List<FileItem>) -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onUploadFolder: () -> Unit,
    onCreateFolder: () -> Unit,
    onOpenFilterRules: () -> Unit,
    /** 拖动到快捷目录的会话；null（窄屏没有侧栏）时拖动整体不启用 */
    quickDirDrag: QuickDirDragHost? = null,
    /** 拖到快捷目录条目上松手：把当前多选移过去 */
    onQuickDirDrop: (cid: String, name: String) -> Unit = { _, _ -> },
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    // 置顶条目的 fid 集合。搜索模式下不标（那时不重排，标了反而误导）
    val pinnedIds: Set<String> = if (ui.searching) emptySet() else ui.pinnedIds.toSet()

    Column(modifier) {
        if (searchMode) {
            SearchBarInline(
                initial = ui.searchQuery,
                onSearch = { vm.search(it) },
                onClose = onSearchToggle,
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.stack.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ui.count > 0 && !ui.loading) {
                            // 过滤开启时带上方案名，让用户知道当前生效的是哪条规则
                            val suffix = ui.activeFilter?.let { " · ${it.name}" } ?: ""
                            Text(
                                "${ui.display.size}/${ui.count} 项$suffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (ui.stack.size > 1) {
                        IconButton(onClick = { vm.popDir() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                        }
                    }
                },
                actions = {
                    // 入口 B：高级过滤总开关（漏斗）。
                    // 有生效方案 → 点击开/关过滤（开启高亮主色）；
                    // 没有生效方案 → 点击跳转规则配置中心（而不是做成点不动的死按钮）。
                    val filterOn by vm.filterOn.collectAsState()
                    val hasScheme by vm.hasEnabledScheme.collectAsState()
                    IconButton(
                        onClick = {
                            if (hasScheme) vm.setFilterOn(!filterOn) else onOpenFilterRules()
                        },
                    ) {
                        Icon(
                            Icons.Outlined.FilterAlt,
                            contentDescription = if (hasScheme) "高级过滤开关" else "高级过滤：去创建规则",
                            tint = when {
                                // 过滤生效中：主色高亮（开关开 + 当前目录解析出了方案）
                                hasScheme && filterOn && ui.activeFilter != null ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onSearchToggle) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Outlined.FilterList, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            sortOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = {
                                        if (ui.order == key) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        vm.setSort(key, ui.asc)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (ui.asc == 1) "改为降序" else "改为升序") },
                                onClick = {
                                    vm.setSort(ui.order, if (ui.asc == 1) 0 else 1)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = { vm.setGrid(!ui.gridMode) }) {
                        Icon(
                            if (ui.gridMode) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            contentDescription = "切换视图",
                        )
                    }
                    IconButton(onClick = onCreateFolder) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                    // 上传：文件 / 文件夹（SAF 目录树）二选一
                    var uploadMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { uploadMenuOpen = true }) {
                            Icon(Icons.Outlined.Upload, contentDescription = "上传")
                        }
                        DropdownMenu(expanded = uploadMenuOpen, onDismissRequest = { uploadMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("上传文件") },
                                leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                                onClick = {
                                    uploadMenuOpen = false
                                    onUpload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("上传文件夹") },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                                onClick = {
                                    uploadMenuOpen = false
                                    onUploadFolder()
                                },
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )

            // 分页加载进度条：下滑自动加载更多页时，可视化"已加载 / 总数"。
            // 全部加载完（items.size == count）后自动消失；
            // 看的是原始加载量（ui.items），不受高级过滤影响。
            if (ui.count > 0 && ui.items.size < ui.count) {
                LinearProgressIndicator(
                    progress = {
                        (ui.items.size.toFloat() / ui.count.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }

            // 面包屑：像 Windows 资源管理器一样，点任意上级目录即可直接跳转。
            // 末段是当前目录（主色、不可点）；其余为可点的中间目录（带涟漪反馈）。
            val pathState = rememberLazyListState()
            LaunchedEffect(ui.stack.size, ui.stack.lastOrNull()?.cid) {
                if (ui.stack.isNotEmpty()) pathState.animateScrollToItem(ui.stack.lastIndex)
            }
            LazyRow(
                state = pathState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(ui.stack) { i, entry ->
                    val isCurrent = i == ui.stack.lastIndex
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) com.open115.pad.ui.theme.AppColors.AccentDeep
                        else com.open115.pad.ui.theme.AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(enabled = !isCurrent) { vm.jumpTo(i) }
                            .padding(vertical = 6.dp)
                            .let { m ->
                                if (i < ui.stack.lastIndex) m.padding(end = 4.dp) else m
                            }
                            .let { m ->
                                if (i > 0) m.padding(start = 4.dp) else m
                            },
                    )
                    if (i < ui.stack.lastIndex) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(0.dp),
                        )
                    }
                }
            }

            // 分类筛选：轻量分段胶囊（无粗边框）
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filterTypes.size) { i ->
                    val (label, type) = filterTypes[i]
                    com.open115.pad.ui.theme.AppChip(
                        label = label,
                        selected = ui.typeFilter == type,
                        onClick = { vm.setType(type) },
                    )
                }
                item {
                    com.open115.pad.ui.theme.AppChip(
                        label = "星标",
                        selected = ui.starOnly,
                        onClick = { vm.toggleStarOnly() },
                        leading = {
                            Icon(
                                if (ui.starOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = null,
                                tint = if (ui.starOnly) com.open115.pad.ui.theme.AppColors.AccentDeep
                                else com.open115.pad.ui.theme.AppColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }

        // ---- 列表/网格的滚动状态（必须提升到下面的 when 之外）----
        // ① 建在 when 分支里的话，一旦切到"加载中/空目录/错误"分支状态就被销毁，再回到列表
        //    会从顶部开始（返回上级、刷新、切视图、退出播放/看图回来都可能踩到）；
        // ② 提升后它始终在组合中，rememberSaveable 的存档才会被登记，配置变更/重创建后可恢复；
        // ③ 另外按目录 cid 分别记住滚动位置：从子目录返回上级、或在目录间跳转时回到原处，
        //    而不是继承上一个目录的偏移——子目录内容短时偏移会被夹到 0，返回上级就成了"回弹到顶部"。
        val listState = rememberLazyListState()
        val gridState = rememberLazyGridState()
        val listScroll = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
        val gridScroll = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
        val curCid = ui.stack.last().cid
        var lastCid by remember { mutableStateOf(curCid) }
        var pendingRestore by remember { mutableStateOf<String?>(null) }

        // 目录切换：先把上一个目录的位置存下来，并标记待恢复的目标目录
        LaunchedEffect(curCid) {
            if (curCid == lastCid) return@LaunchedEffect
            listScroll[lastCid] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            gridScroll[lastCid] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            lastCid = curCid
            pendingRestore = curCid
        }

        // 恢复必须等**新目录的数据到位之后**：LazyColumn 是按 item key 记忆位置的，
        // 数据还是上一级目录时恢复会被随后的整表替换重置回顶部（实测踩过）。
        LaunchedEffect(curCid, ui.loading, ui.items) {
            if (ui.loading || pendingRestore != curCid) return@LaunchedEffect
            pendingRestore = null
            // 只恢复当前显示模式那一个（另一个没在组合里，别去碰它）；没有记录就从顶部开始
            runCatching {
                if (ui.gridMode) {
                    val (i, o) = gridScroll[curCid] ?: (0 to 0)
                    gridState.scrollToItem(i, o)
                } else {
                    val (i, o) = listScroll[curCid] ?: (0 to 0)
                    listState.scrollToItem(i, o)
                }
            }
        }

        // 宽屏防拉伸：列表/网格收进 960dp 居中容器，手机端自然占满
        Box(Modifier.weight(1f)) {
            com.open115.pad.ui.theme.AdaptiveBody(Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                ui.error != null && ui.items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("重试") }
                }

                ui.display.isEmpty() && ui.items.isNotEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 目录里有东西但被方案筛光了：明确告诉用户，而不是误报"空文件夹"
                    Text(
                        "没有符合「${ui.activeFilter?.name ?: ""}」的文件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ui.display.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (ui.searching) "没有找到相关文件" else "这个文件夹是空的",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ui.gridMode -> {
                    LaunchedEffect(gridState, ui.display.size, ui.searchQuery) {
                        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collect { last ->
                                if (last != null && last >= ui.display.size - 8) vm.loadMore()
                            }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ui.display, key = { (it.fid ?: "") + "#" + it.fn }) { item ->
                            QuickDirDragSource(
                                enabled = ui.selectMode,
                                host = quickDirDrag,
                                onDrop = onQuickDirDrop,
                            ) {
                                FileGridCard(
                                    item = item,
                                    pinned = item.fid in pinnedIds,
                                    selectMode = ui.selectMode,
                                    selected = item.fid in ui.selection,
                                    onClick = { onActivate(item) },
                                    onLongClick = if (ui.selectMode) null else ({ vm.toggleSelect(item.fid) }),
                                )
                            }
                        }
                    }
                }

                else -> {
                    LaunchedEffect(listState, ui.display.size, ui.searchQuery) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collect { last ->
                                if (last != null && last >= ui.display.size - 8) vm.loadMore()
                            }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        listItems(ui.display, key = { (it.fid ?: "") + "#" + it.fn }) { item ->
                            QuickDirDragSource(
                                enabled = ui.selectMode,
                                host = quickDirDrag,
                                onDrop = onQuickDirDrop,
                            ) {
                                FileListRow(
                                    item = item,
                                    pinned = item.fid in pinnedIds,
                                    selectMode = ui.selectMode,
                                    selected = item.fid in ui.selection,
                                    onClick = { onActivate(item) },
                                    // 多选态长按让位给"拖动"（见 QuickDirDragSource 注释），增删选择走勾选框/点按
                                    onLongClick = if (ui.selectMode) null else ({ vm.toggleSelect(item.fid) }),
                                )
                            }
                        }
                        if (ui.loadingMore) {
                            item {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        // 多选操作栏
        AnimatedVisibility(visible = ui.selectMode) {
            val filesSelected = ui.display.count { it.fid in ui.selection && !it.isDir }
            val single = ui.selection.singleOrNull()?.let { id -> ui.items.firstOrNull { it.fid == id } }
            BottomAppBar {
                IconButton(onClick = { vm.clearSelection() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                }
                Text(
                    "已选 ${ui.selection.size} 项",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 4.dp),
                )
                // 全选/取消全选：批量改名、移动、删除这些常见操作往往要整个目录一起处理，
                // 一个个点太费事。只作用于**当前显示**的条目（与筛选/搜索/置顶保持一致）。
                val displayedIds = ui.display.mapNotNull { it.fid }
                val allSelected = displayedIds.isNotEmpty() && displayedIds.all { it in ui.selection }
                IconButton(onClick = onSelectAll) {
                    Icon(
                        if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                        contentDescription = if (allSelected) "取消全选" else "全选",
                    )
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = onDownload, enabled = filesSelected > 0) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "下载")
                }
                IconButton(onClick = onMove, enabled = !ui.searching) {
                    Icon(Icons.Outlined.DriveFileMove, contentDescription = "移动")
                }
                IconButton(onClick = onCopy, enabled = !ui.searching) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                }
                // 重命名：单选走原来的对话框（快、老习惯不变），多选才进批量面板
                IconButton(
                    onClick = { if (single != null) onRename(single) else onBatchRename() },
                    enabled = ui.selection.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "重命名")
                }
                IconButton(onClick = { single?.let(onStar) }, enabled = single != null) {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "星标")
                }
                // 置顶：只对文件夹有意义，所以选择集里至少有一个文件夹时按钮才可用
                //（夹在里面的文件会被忽略）。这些文件夹若已全部置顶，按钮转为实心态。
                val pinTargets = ui.items.filter { it.isDir && it.fid in ui.selection }
                val allPinned = pinTargets.isNotEmpty() && pinTargets.all { it.fid in ui.pinnedIds }
                IconButton(onClick = { onPin(pinTargets) }, enabled = pinTargets.isNotEmpty()) {
                    Icon(
                        if (allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (allPinned) "取消置顶" else "置顶",
                        tint = if (allPinned) com.open115.pad.ui.theme.AppColors.AccentDeep
                        else androidx.compose.material3.LocalContentColor.current,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun SearchBarInline(initial: String, onSearch: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("搜索文件名…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { onSearch(text) }) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索")
            }
        },
        leadingIcon = {
            androidx.compose.material3.IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 「长按拖动多选 → 松手移到快捷目录」的会话状态。
 *
 * 手势侧（文件行，见 [QuickDirDragSource]）写入手指位置；侧栏的快捷目录条目把
 * 自己的窗口边界注册进来做命中判定；拖动徽标浮层读 [position] 画跟随。
 *
 * 只在应用窗口内自绘判定，不走系统拖放协议 —— 高亮、落点、命中的口径全部自己说了算，
 * 也省掉 ClipData 的来回打包。
 */
class QuickDirDragHost {
    /** 拖动进行中（浮层与侧栏高亮都看它） */
    var active by mutableStateOf(false)
        private set

    /** 手指在窗口内的位置（root 坐标） */
    var position by mutableStateOf(Offset.Zero)
        private set

    /** 当前悬停的快捷目录 cid；null = 不在任何条目上 */
    var hoverCid by mutableStateOf<String?>(null)
        private set

    // cid -> (目录名, 条目边界)。条目布局一变就重注册（onGloballyPositioned）
    private val targets = mutableStateMapOf<String, Pair<String, Rect>>()

    fun registerTarget(cid: String, name: String, bounds: Rect) {
        targets[cid] = name to bounds
    }

    fun unregisterTarget(cid: String) {
        targets.remove(cid)
    }

    fun begin(at: Offset) {
        active = true
        position = at
        hoverCid = null
    }

    fun dragTo(at: Offset) {
        position = at
        hoverCid = targetAt(at)?.first
    }

    /** 结束拖动：落在快捷目录上返回 (cid, 目录名) 并复位；没命中返回 null */
    fun finish(): Pair<String, String>? {
        val hit = targetAt(position)
        reset()
        return hit
    }

    fun cancel() = reset()

    private fun targetAt(p: Offset): Pair<String, String>? =
        targets.entries.firstOrNull { it.value.second.contains(p) }?.let { it.key to it.value.first }

    private fun reset() {
        active = false
        hoverCid = null
    }
}

/**
 * 给文件行 / 网格卡包一层拖动手势。仅多选态（[enabled] = true）启用：
 * **横向为主的滑动**进入拖动会话（纵向让给列表滚动），松手落在快捷目录条目上
 * 就把当前多选移过去。非多选态原样放行事件，长按选择行为不变。
 *
 * ★ 多选态下调用方必须把行的 onLongClick 传 null：combinedClickable 的长按
 *   触发后会 consumeUntilUp 把后续事件全部消费，拖动手势会立刻收到
 *   "事件已被消费"而取消。
 */
@Composable
fun QuickDirDragSource(
    enabled: Boolean,
    host: QuickDirDragHost?,
    onDrop: (cid: String, name: String) -> Unit,
    content: @Composable () -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    Box(
        Modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(enabled, host) {
                if (!enabled || host == null) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var total = Offset.Zero
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        total += change.positionChange()
                        if (!dragging) {
                            // 还没定方向：累计位移超过触摸斜距后，横主纵从 → 开始拖动；纵向 → 放弃（列表滚动）
                            if (total.getDistance() > slop) {
                                if (kotlin.math.abs(total.x) > kotlin.math.abs(total.y)) {
                                    dragging = true
                                    coords?.let { host.begin(it.localToRoot(down.position + total)) }
                                } else {
                                    cancelled = true
                                    break
                                }
                            }
                            if (!dragging) continue
                        }
                        change.consume()
                        coords?.let { host.dragTo(it.localToRoot(down.position + total)) }
                    }
                    if (dragging) {
                        val hit = host.finish()
                        if (hit != null) currentOnDrop(hit.first, hit.second)
                    } else if (cancelled) {
                        host.cancel()
                    }
                }
            },
    ) { content() }
}
