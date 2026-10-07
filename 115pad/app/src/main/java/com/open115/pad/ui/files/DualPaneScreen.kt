package com.open115.pad.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.data.QuickDir
import com.open115.pad.data.isTextFile
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.TextEntryDialog
import com.open115.pad.ui.components.isImageItem
import com.open115.pad.ui.components.videoExts
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.KindBadge
import com.open115.pad.util.Format
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 双文件面板（类 MT 管理器）：左右两栏**各自独立导航**，勾选后跨栏一键移动/复制。
 *
 * 与"移动 → 弹目录选择器逐层找"相比，双面板的目标目录就摆在旁边，导航过去点一下即可。
 * 两栏各挂一个 [FilesViewModel]（左 = 文件页本尊，右 = 第二实例），移动/复制直接走
 * 现成的 moveSelected / copySelected —— 限速、操作记录、缓存失效都是同一套。
 *
 * 点按分工（文件/文件夹同一套逻辑，圆圈与本体各管各的）：
 *  - 点尾部**圆圈**：文件/文件夹都直接进多选（勾上/取消），不用长按；
 *  - 点**文件夹本体**：进入该文件夹；
 *  - 点**文件本体**：视频/图片/文本 = 播放/看图/读文本预览（与单面板同一套体验，
 *    走 [onActivate]，操作记录记的是所在那一栏的目录）；其余类型 = 勾选
 *    （双栏里"勾选 → 跨栏搬移"仍是主路径）。
 */
@Composable
fun DualPaneContent(
    vmA: FilesViewModel,
    vmB: FilesViewModel,
    modifier: Modifier = Modifier,
    /** 退出双面板（顶栏切换键已不在视野内，面板里也要给出口） */
    onExit: () -> Unit,
    /** 与文件页同一只 snackbar：传入的是"成功提示或错误消息"，null 不提示 */
    notify: (String?) -> Unit,
    /** 点文件本体（视频/图片/文本）时的预览回调：参数是"所在栏的 VM + 条目" */
    onActivate: (FilesViewModel, FileItem) -> Unit = { _, _ -> },
    /** 用户触摸了哪一栏（点行、滚动、点头部都算）：true=左栏。返回键的分级处理跟着它走 */
    onPaneOperate: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val quickDirs by context.appContainer.quickDirs.entries.collectAsState(initial = emptyList())

    /** 跨栏搬移：from 里勾选的项 移动/复制 到 to 的当前目录，随后把目标栏强制重取 */
    fun cross(from: FilesViewModel, to: FilesViewModel, copy: Boolean) {
        scope.launch {
            val toName = to.ui.value.stack.last().name
            val msg = if (copy) from.copySelected(to.currentCid(), toName)
            else from.moveSelected(to.currentCid(), toName)
            notify(msg)
            // moveSelected 只重取了源栏；目标栏的缓存虽然已被作废，当前列表还得显式刷一次
            to.refresh()
        }
    }

    Column(modifier) {
        // ---- 模式条：退出键放右上（用户反馈：放底部会挡住文件行）----
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "双栏模式",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onExit,
                modifier = Modifier.height(30.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("退出双栏", style = MaterialTheme.typography.labelMedium)
            }
        }

        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                DualPaneColumn(vmA, quickDirs, Modifier.weight(1f), notify, onActivate) { onPaneOperate(true) }
                // 中缝分隔线：窄屏上两栏的分界要一眼可辨
                Box(Modifier.width(1.dp).fillMaxHeight().background(AppColors.CardBorder))
                DualPaneColumn(vmB, quickDirs, Modifier.weight(1f), notify, onActivate) { onPaneOperate(false) }
            }

            // 底部浮动操作条：任一栏有勾选就升起；两栏同时有勾选则两组按钮都给。
            // 删除 = 移入回收站（可还原），走确认框，防止一键误删
            val uiA by vmA.ui.collectAsState()
            val uiB by vmB.ui.collectAsState()
            var pendingDelete by remember { mutableStateOf<Boolean?>(null) }
            CrossActionBar(
                leftSel = uiA.selection.size,
                rightSel = uiB.selection.size,
                onCross = { fromLeft, copy -> cross(if (fromLeft) vmA else vmB, if (fromLeft) vmB else vmA, copy) },
                onDelete = { fromLeft -> pendingDelete = fromLeft },
                onClear = { vmA.clearSelection(); vmB.clearSelection() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            pendingDelete?.let { fromLeft ->
                val count = if (fromLeft) uiA.selection.size else uiB.selection.size
                ConfirmDialog(
                    title = "删除 $count 项",
                    text = "文件将被移入回收站，可在回收站中还原。",
                    confirmText = "删除",
                    onConfirm = {
                        pendingDelete = null
                        scope.launch { notify((if (fromLeft) vmA else vmB).deleteSelected()) }
                    },
                    onDismiss = { pendingDelete = null },
                )
            }
        }
    }
}

/** 占位已移除：直接用 androidx.compose.ui.platform.LocalContext */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualPaneColumn(
    vm: FilesViewModel,
    quickDirs: List<QuickDir>,
    modifier: Modifier = Modifier,
    notify: (String?) -> Unit,
    onActivate: (FilesViewModel, FileItem) -> Unit,
    /** 这一栏被触摸（点行/滚动/点头部）时回调：用来记"最近操作的是哪一栏" */
    onOperate: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var layerMenu by remember { mutableStateOf(false) }
    var quickMenu by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    // pointerInput(Unit) 的闭包不随重组重启，用 rememberUpdatedState 保证读到的是最新的回调
    val currentOperate by rememberUpdatedState(onOperate)

    Column(
        modifier.pointerInput(Unit) {
            // 只观察、不消费：这一栏里任何触摸（点行、滚列表、点头部按键）都算"在操作这一栏"，
            // 事件照常派发给子节点的行点击与滚动手势，互不影响
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                currentOperate()
            }
        },
    ) {
        // ---- 迷你头：返回上级 / 目录名（点击跳层）/ 快捷目录 / 新建夹 / 刷新 ----
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.popDir() }, enabled = ui.stack.size > 1) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回上级",
                    modifier = Modifier.size(18.dp),
                )
            }
            // 目录名 + 项数：点击弹出目录栈层级菜单（当前目录加粗在前），点层直达
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { layerMenu = true }) {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        ui.stack.last().name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!ui.loading) {
                        Text(
                            "${ui.display.size}/${ui.count} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
                DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                    ui.stack.asReversed().forEachIndexed { idx, entry ->
                        val index = ui.stack.lastIndex - idx
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (index == 0) "全部文件" else entry.name,
                                    fontWeight = if (index == ui.stack.lastIndex) FontWeight.Bold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                layerMenu = false
                                vm.jumpTo(index)
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { quickMenu = true }) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = "快捷目录", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = quickMenu, onDismissRequest = { quickMenu = false }) {
                    if (quickDirs.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有快捷目录\n（文件页侧栏可收藏）", style = MaterialTheme.typography.bodySmall) },
                            onClick = {},
                        )
                    } else {
                        quickDirs.forEach { d ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!d.path.isNullOrBlank()) {
                                            Text(
                                                d.path!!,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AppColors.TextTertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    quickMenu = false
                                    vm.openByCid(d.cid, d.name)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
            }
        }

        // ---- 列表：点圆圈=勾选（文件/文件夹一致）；点文件夹本体=进入；点文件本体=预览 ----
        // 滚动位置记忆与单面板同款（键 = vm 的 displayKey）：返回上级回到原处，不弹回顶部
        val listState = rememberLazyListState()
        val scrollMap = remember { mutableStateMapOf<String, Pair<String?, Int>>() }
        // 恢复声明在跟踪前：displayKey 变化的那一帧先恢复、跟踪晚一步才开始写
        LaunchedEffect(ui.displayKey) {
            val key = ui.displayKey ?: return@LaunchedEffect
            val saved = scrollMap[key] ?: return@LaunchedEffect
            val idx = ui.display.indexOfFirst { (it.fid ?: it.fn) == saved.first }
            if (idx >= 0) runCatching { listState.scrollToItem(idx, saved.second) }
        }
        LaunchedEffect(ui.displayKey) {
            val key = ui.displayKey ?: return@LaunchedEffect
            snapshotFlow {
                (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String) to
                    listState.firstVisibleItemScrollOffset
            }.collect { (k, off) -> scrollMap[key] = k to off }
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(ui.display, key = { it.fid ?: it.fn }) { item ->
                DualFileRow(
                    item = item,
                    selected = item.fid != null && item.fid in ui.selection,
                    highlightSeq = ui.highlight?.takeIf { it == item.fn }?.let { ui.highlightSeq },
                    onBodyClick = {
                        when {
                            item.isDir -> vm.openDir(item)
                            // 视频/图片/文本：点本体直接预览（与单面板同一套）；其余类型点本体 = 勾选
                            item.isv == 1 ||
                                item.fn.substringAfterLast('.', "").lowercase() in videoExts ||
                                isImageItem(item) ||
                                isTextFile(item.fn) -> onActivate(vm, item)
                            else -> vm.toggleSelect(item.fid)
                        }
                    },
                    onCircleClick = { vm.toggleSelect(item.fid) },
                    onLongClick = { vm.toggleSelect(item.fid) },
                )
            }
        }
    }

    if (showCreate) {
        TextEntryDialog("新建文件夹", "文件夹名称", onConfirm = {
            showCreate = false
            scope.launch { notify(vm.addFolder(it)) }
        }, onDismiss = { showCreate = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualFileRow(
    item: FileItem,
    selected: Boolean,
    /** 非空 = 这次要指认的就是这一条（返回上级闪刚出来的那层）：整行闪几下淡蓝底 */
    highlightSeq: Long?,
    onBodyClick: () -> Unit,
    /** 点尾部圆圈：文件/文件夹都直接进多选 */
    onCircleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val C = AppColors
    val flash = com.open115.pad.ui.components.rememberHighlightFlash(highlightSeq)
    Row(
        Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.lerp(Color.Transparent, AppColors.BlueBg, flash))
            .combinedClickable(onClick = onBodyClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(item.fn, item.isDir, Modifier.size(32.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                item.fn,
                style = MaterialTheme.typography.bodyMedium,
                color = C.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (item.isDir) "" else Format.size(item.fs),
                style = MaterialTheme.typography.labelSmall,
                color = C.TextTertiary,
                maxLines = 1,
            )
        }
        // 选中指示：描边小圆 → 主色实心勾。占位常驻，勾选前后行高不跳。
        // **圆圈是独立的点按目标**（外扩到 28dp 好按）：文件/文件夹点它都直接进多选
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onCircleClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) C.Accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (!selected) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(1.5.dp, C.TextTertiary, CircleShape)
                    )
                } else {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * 底部跨栏操作条（毛玻璃胶囊，样式对齐回收站的批量条）。
 * 只有左栏有勾选 → 「移/复/删 N →」；只有右栏 → 「← 移/复/删 N」；两边都有 → 两组都给。
 * 删除走确认框（移入回收站），退出双栏在顶部模式条（右上角），这里不再放。
 */
@Composable
private fun CrossActionBar(
    leftSel: Int,
    rightSel: Int,
    onCross: (fromLeft: Boolean, copy: Boolean) -> Unit,
    onDelete: (fromLeft: Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val C = AppColors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = leftSel + rightSel > 0,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier.padding(bottom = 12.dp).shadow(8.dp, RoundedCornerShape(50)),
                shape = RoundedCornerShape(50),
                color = Color(0xE6FFFFFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, C.CardBorder),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leftSel > 0) {
                        Text("已选 $leftSel 项", style = MaterialTheme.typography.labelLarge, color = C.TextPrimary)
                        CrossButton("移 ${leftSel} 项 →") { onCross(true, false) }
                        CrossButton("复 ${leftSel} 项 →", tonal = true) { onCross(true, true) }
                        CrossButton("删 ${leftSel} 项", danger = true) { onDelete(true) }
                    }
                    if (rightSel > 0) {
                        if (leftSel > 0) Spacer(Modifier.width(4.dp))
                        Text("已选 $rightSel 项", style = MaterialTheme.typography.labelLarge, color = C.TextPrimary)
                        CrossButton("← 移 ${rightSel} 项") { onCross(false, false) }
                        CrossButton("← 复 ${rightSel} 项", tonal = true) { onCross(false, true) }
                        CrossButton("删 ${rightSel} 项", danger = true) { onDelete(false) }
                    }
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "取消选择", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CrossButton(text: String, tonal: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val C = AppColors
    Button(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        colors = when {
            danger -> ButtonDefaults.buttonColors(containerColor = C.RedBg, contentColor = C.RedFg)
            tonal -> ButtonDefaults.buttonColors(containerColor = C.SlateBg, contentColor = C.TextPrimary)
            else -> ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = Color.White)
        },
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
