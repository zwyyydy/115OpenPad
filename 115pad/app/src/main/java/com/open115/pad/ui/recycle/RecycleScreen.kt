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
import com.open115.pad.data.JsonNull
import com.open115.pad.data.JsonObject
import com.open115.pad.data.JsonPrimitive
import com.open115.pad.data.OpenApi
import com.open115.pad.data.RecycleItem
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecycleViewModel(private val api: OpenApi) : ViewModel() {

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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val root = api.recycleList(limit = 200, offset = 0)
                if (!root.envOk()) error(root.envMsg() ?: "获取回收站列表失败")
                val data = root.envData() ?: JsonObject(emptyMap())
                val items = data.entries
                    .filter { it.key.toLongOrNull() != null }
                    .mapNotNull { (id, value) ->
                        val o = value as? JsonObject ?: return@mapNotNull null
                        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                        RecycleItem(
                            id = id,
                            name = s("file_name") ?: "",
                            isDir = s("type") == "2",
                            size = s("file_size")?.toLongOrNull() ?: 0,
                            dtime = s("dtime")?.toLongOrNull() ?: 0,
                            thumbUrl = s("thumb_url"),
                            cid = s("cid"),
                            parentName = s("parent_name"),
                            pickCode = s("pick_code"),
                            status = s("status")?.toIntOrNull() ?: 0,
                        )
                    }
                _ui.update { it.copy(items = items, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
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
                clearSelection()
                refresh()
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
                clearSelection()
                refresh()
            }
            ok to (if (ok) "已彻底删除 ${ids.size} 项" else resp.envMsg() ?: "删除失败")
        }
    }

    suspend fun clearAll(): String? = runOp {
        val resp = api.recycleDelete(null)
        val ok = resp.envOk()
        if (ok) {
            clearSelection()
            refresh()
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
