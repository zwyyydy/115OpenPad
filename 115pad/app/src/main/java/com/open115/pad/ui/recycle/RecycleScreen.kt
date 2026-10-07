package com.open115.pad.ui.recycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.data.JsonObject
import com.open115.pad.data.DirCache
import com.open115.pad.data.OpenApi
import com.open115.pad.data.RecycleItem
import com.open115.pad.data.VideoPreviewStore
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.parseRecycleList
import com.open115.pad.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecycleViewModel(
    private val api: OpenApi,
    /** 目录列表缓存：删除/还原后把原目录的缓存作废，文件页回到那里才不会显示旧内容 */
    private val dirCache: DirCache? = null,
    /** 目录内容变更广播（见 [com.open115.pad.App115.dirRemovals]），未注入则只作废缓存 */
    private val dirRemovals: MutableSharedFlow<Map<String, Set<String>>>? = null,
    /**
     * 视频预览图缓存（文件页缩略图 + 预览条那套）。
     *
     * **只在「彻底删除 / 清空」时清**（这里就是那种场合）：文件真没了，按 pick_code 存的预览
     * 再也没人引用。**「还原」与文件页的「移入回收站」都不清** —— 还能回来，回来要重新抓帧。
     */
    private val videoPreviews: VideoPreviewStore? = null,
) : ViewModel() {

    data class UiState(
        val items: List<RecycleItem> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val selection: Set<String> = emptySet(),
    ) {
        val selectMode: Boolean get() = selection.isNotEmpty()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    /**
     * 已请求彻底删除、但服务端可能还没删完的条目 id。115 的回收站删除是**异步生效**的：
     * 刚删完立即拉列表经常还能拉回来，等于"删了还在上面"。乐观移除之后，复核刷新
     * 里仍把这些 id 过滤掉，直到服务端返回的列表里真的没有它们为止。
     */
    private var pendingGoneIds: Set<String> = emptySet()

    /** 复核刷新的连续次数上限：服务端一直没删完就放弃过滤，以服务端状态为准 */
    private var confirmCount = 0

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val root = api.recycleList(limit = 200, offset = 0)
                if (!root.envOk()) error(root.envMsg() ?: "获取回收站列表失败")
                val items = parseRecycleList(root.envData() ?: JsonObject(emptyMap()))
                val gone = pendingGoneIds
                val visible = if (gone.isEmpty()) items else items.filter { it.id !in gone }
                _ui.update { it.copy(items = visible, error = null) }
                if (visible.size == items.size) {
                    // 服务端返回里已经没有待删条目 = 删除全部生效，解除过滤
                    pendingGoneIds = emptySet()
                    confirmCount = 0
                } else {
                    // 还有没删完的：再等一轮复核（有上限）
                    confirmCount++
                    if (confirmCount < 3) scheduleConfirmRefresh() else {
                        pendingGoneIds = emptySet()
                        confirmCount = 0
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    /** 延迟复核：删除/还原后等服务端生效了再拉一次，把列表校准成服务端的真实状态 */
    private fun scheduleConfirmRefresh() {
        viewModelScope.launch {
            delay(2_000)
            refresh()
        }
    }

    /**
     * 彻底删掉的那些条目，把它们的视频预览图缓存也清掉（封面 + 预览条）。
     *
     * 按 pick_code 认：回收站条目里带 `pick_code`，与预览缓存的键同为视频的 pick_code。
     * 拿不到 pick_code 的条目（不该有）跳过，剩下的交给上限淘汰。
     * **注意这里只覆盖"已经列出来的那些"**：列表只取第一页 200 条，更深的那批要等下次进页面
     * 拉出来、或由缓存上限淘汰 —— 清空回收站不额外翻页拉全（那几个 KB 不值得多打请求）。
     */
    private fun dropPreviews(ids: Collection<String>) {
        val store = videoPreviews ?: return
        val idSet = ids.toSet()
        val pcs = _ui.value.items.filter { it.id in idSet }.mapNotNull { it.pickCode?.takeIf(String::isNotBlank) }
        if (pcs.isEmpty()) return
        val n = store.deleteFor(pcs)
        if (n > 0) android.util.Log.i("VPreview", "回收站彻底删除：清掉 ${pcs.size} 个 pick_code 的预览图 $n 个文件")
    }

    /**
     * 乐观收尾：把已请求删除/还原的条目立刻从列表里拿掉（不等刷新 —— 115 异步生效，
     * 立即刷新会"删了还在"），同时按原目录作废缓存并广播给文件页（删除方向才有移除语义）。
     */
    private fun afterItemsGone(ids: Collection<String>, broadcast: Boolean) {
        val idSet = ids.toSet()
        val removed = _ui.value.items.filter { it.id in idSet }
        _ui.update { s ->
            s.copy(items = s.items.filter { it.id !in idSet }, selection = s.selection - idSet)
        }
        val byCid = removed.mapNotNull { item -> item.cid?.let { it to item.name } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> names.toSet() }
        if (byCid.isNotEmpty()) {
            dirCache?.let { c -> byCid.keys.forEach(c::invalidateDir) }
            if (broadcast) dirRemovals?.tryEmit(byCid)
        }
    }

    fun toggleSelect(id: String) = _ui.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    /** 全选 / 取消全选（供底部批量操作栏使用） */
    fun toggleSelectAll() = _ui.update {
        if (it.selection.size == it.items.size) it.copy(selection = emptySet())
        else it.copy(selection = it.items.map { item -> item.id }.toSet())
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    suspend fun restoreSelected(): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        return runOp {
            val resp = api.recycleRevert(ids.joinToString(","))
            val ok = resp.envOk()
            if (ok) {
                // 还原是把文件放回原目录：只作废缓存让文件页下次进入真实请求，
                // 不发移除广播（那是删除的语义）
                afterItemsGone(ids, broadcast = false)
                scheduleConfirmRefresh()
            }
            ok to (if (ok) "已还原 ${ids.size} 项" else resp.envMsg() ?: "还原失败")
        }
    }

    suspend fun deleteSelected(): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        return runOp {
            val resp = api.recycleDelete(ids.joinToString(","))
            val ok = resp.envOk()
            if (ok) {
                pendingGoneIds = pendingGoneIds + ids
                dropPreviews(ids)
                afterItemsGone(ids, broadcast = true)
                scheduleConfirmRefresh()
            }
            ok to (if (ok) "已彻底删除 ${ids.size} 项" else resp.envMsg() ?: "删除失败")
        }
    }

    suspend fun clearAll(): String? = runOp {
        val resp = api.recycleDelete(null)
        val ok = resp.envOk()
        if (ok) {
            pendingGoneIds = pendingGoneIds + _ui.value.items.map { it.id }
            dropPreviews(_ui.value.items.map { it.id })
            afterItemsGone(_ui.value.items.map { it.id }, broadcast = true)
            scheduleConfirmRefresh()
        }
        ok to (if (ok) "回收站已清空" else resp.envMsg() ?: "清空失败")
    }

    private inline fun runOp(block: () -> Pair<Boolean, String>): String? {
        return try {
            val (ok, msg) = block()
            if (ok) null else msg
        } catch (e: Exception) {
            "网络错误：${e.message}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecycleScreen(
    vm: RecycleViewModel,
    expanded: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val ui by vm.ui.collectAsState()
    var showClearAll by remember { mutableStateOf(false) }

    // 外层 Box：让底部浮动操作栏（BottomCenter 对齐）悬浮在列表上方
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("回收站") },
            actions = {
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                TextButton(onClick = { showClearAll = true }, enabled = ui.items.isNotEmpty()) {
                    Text("清空")
                }
            },
        )
        RecycleContent(
            vm, snackbarHostState, Modifier.weight(1f), embedded = false,
            showClearAll = showClearAll, onClearRequest = { showClearAll = true },
            onDismissClearAll = { showClearAll = false },
        )
    }
    }
}

/**
 * 手机模式「传输中心」的内嵌形态：没有自己的顶栏，计数/刷新/清空收成
 * 列表上方的一行紧凑工具行（其余与独立页完全一致）。
 */
@Composable
fun RecycleEmbedded(
    vm: RecycleViewModel,
    snackbarHostState: SnackbarHostState,
) {
    var showClearAll by remember { mutableStateOf(false) }
    RecycleContent(
        vm, snackbarHostState, Modifier.fillMaxSize(), embedded = true,
        showClearAll = showClearAll, onClearRequest = { showClearAll = true },
        onDismissClearAll = { showClearAll = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecycleContent(
    vm: RecycleViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    embedded: Boolean,
    showClearAll: Boolean,
    onClearRequest: () -> Unit,
    onDismissClearAll: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        if (embedded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${ui.items.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.open115.pad.ui.theme.AppColors.TextTertiary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                TextButton(onClick = onClearRequest, enabled = ui.items.isNotEmpty()) {
                    Text("清空")
                }
            }
        }
        Box(Modifier.weight(1f)) {
            // 宽屏防拉伸：回收站列表收进 960dp 居中容器
            com.open115.pad.ui.theme.AdaptiveBody(Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                ui.error != null && ui.items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("重试") }
                }

                ui.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { Text("回收站是空的", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.items, key = { it.id }) { item ->
                        val C = com.open115.pad.ui.theme.AppColors
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { vm.toggleSelect(item.id) },
                                    onLongClick = { vm.toggleSelect(item.id) },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.thumbUrl != null) {
                                SubcomposeAsyncImage(
                                    model = item.thumbUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    loading = {
                                        com.open115.pad.ui.theme.KindBadge(
                                            item.name, item.isDir, Modifier.fillMaxSize(),
                                        )
                                    },
                                    error = {
                                        com.open115.pad.ui.theme.KindBadge(
                                            item.name, item.isDir, Modifier.fillMaxSize(),
                                        )
                                    },
                                )
                            } else {
                                // 文件类型彩色视觉：淡色圆角底 + 深色图标
                                com.open115.pad.ui.theme.KindBadge(item.name, item.isDir, Modifier.size(40.dp))
                            }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = C.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 原位置弱化为浅灰胶囊 + 时间淡灰
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 3.dp),
                                ) {
                                    if (!item.parentName.isNullOrBlank()) {
                                        com.open115.pad.ui.theme.StatusBadge(
                                            item.parentName!!,
                                            C.SlateBg, C.TextTertiary,
                                        )
                                    }
                                    if (item.dtime > 0) {
                                        Text(
                                            Format.dateTime(item.dtime),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = C.TextTertiary,
                                            maxLines = 1,
                                        )
                                    }
                                    if (item.status == -1) {
                                        com.open115.pad.ui.theme.StatusBadge("还原中", C.AmberBg, C.AmberFg)
                                    }
                                }
                            }
                            // 自定义圆角 Checkbox（品牌蓝底白勾），热区 32dp
                            com.open115.pad.ui.theme.AppCheckbox(
                                checked = item.id in ui.selection,
                                onCheckedChange = { vm.toggleSelect(item.id) },
                            )
                        }
                    }
                }
            }
            }
        }
    }

        // 底部浮动批量操作栏（毛玻璃胶囊，居中悬浮）：勾选 ≥1 项时升起
        androidx.compose.animation.AnimatedVisibility(
            visible = ui.selectMode,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val C = com.open115.pad.ui.theme.AppColors
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .shadow(8.dp, RoundedCornerShape(50)),
                    shape = RoundedCornerShape(50),
                    color = Color(0xE6FFFFFF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, C.CardBorder),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "已选 ${ui.selection.size} 项",
                            style = MaterialTheme.typography.labelLarge,
                            color = C.TextPrimary,
                        )
                        TextButton(
                            onClick = { vm.toggleSelectAll() },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = C.TextSecondary,
                            ),
                        ) {
                            Text(
                                if (ui.selection.size == ui.items.size) "取消全选" else "全选",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Button(
                            onClick = { scope.launch { notify(vm.restoreSelected()) } },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = C.Accent,
                                contentColor = androidx.compose.ui.graphics.Color.White,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.RestoreFromTrash,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("批量还原", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = C.RedBg,
                                contentColor = C.RedFg,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("彻底删除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = onDismissClearAll,
            title = { Text("清空回收站") },
            text = { Text("回收站内所有文件将被彻底删除且无法恢复，确定继续？") },
            confirmButton = {
                Button(onClick = {
                    onDismissClearAll()
                    scope.launch { notify(vm.clearAll()) }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = onDismissClearAll) { Text("取消") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("彻底删除 ${ui.selection.size} 项") },
            text = { Text("删除后无法恢复，确定继续？") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    scope.launch { notify(vm.deleteSelected()) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
        )
    }
}
