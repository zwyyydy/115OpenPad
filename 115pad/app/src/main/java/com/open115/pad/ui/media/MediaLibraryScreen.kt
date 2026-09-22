package com.open115.pad.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
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

            // 全局扫描进度条
            if (scanProgress.running) {
                LinearProgressIndicator(
                    progress = {
                        if (scanProgress.totalDirs > 0) scanProgress.doneDirs.toFloat() / scanProgress.totalDirs else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Text(
                    "正在扫描 ${scanProgress.currentDir}（${scanProgress.doneDirs}/${scanProgress.totalDirs} 目录，已索引 ${scanProgress.moviesIndexed} 部）",
                    style = MaterialTheme.typography.bodySmall,
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
                                Text(
                                    lib.rootPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "建于 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(lib.createdAt)) + LibraryCount(dao, lib.rootPath),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            container.transferScope.launch {
                                                container.mediaScanner.runScan(lib.rootCid, lib.rootPath)
                                            }
                                        }
                                    },
                                    enabled = !scanProgress.running,
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "扫描")
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
        CreateLibraryDialog(
            api = api,
            onDismiss = { showCreate = false },
            onCreate = { name, cid, path ->
                scope.launch {
                    dao.insertLibrary(
                        MediaLibraryEntity(name = name, rootCid = cid, rootPath = path, createdAt = System.currentTimeMillis()),
                    )
                    showCreate = false
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除媒体库") },
            text = { Text("删除「${lib.name}」？只删除索引记录，不会动云盘里的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deleteLibrary(lib.id) }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 库内影片数：卡片上显示「N 部」，进库前就知道有没有内容 */
@Composable
private fun LibraryCount(dao: com.open115.pad.data.media.MediaDao, rootPath: String): String {
    val count by produceState(initialValue = -1, rootPath) {
        value = dao.movieCountIn(rootPath)
    }
    return when {
        count < 0 -> ""
        count == 0 -> " · 还没有影片"
        else -> " · $count 部"
    }
}

/** 新建媒体库：输入名字 → 选云盘路径（懒加载目录树选择器，复用 DirMultiPickerDialog） */
@Composable
private fun CreateLibraryDialog(
    api: com.open115.pad.data.OpenApi,
    onDismiss: () -> Unit,
    onCreate: (name: String, cid: String, path: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<com.open115.pad.data.FilterRules.BoundDir?>(null) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建媒体库") },
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
                        Text("选择云盘路径")
                    }
                    Text(
                        picked?.fullPath ?: "未选择",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (picked != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // 存完整路径（fullPath）：只存叶子名的话，不同父目录下的同名目录
                // 会在海报墙的前缀匹配里互相串数据
                onClick = {
                    picked?.let {
                        onCreate(name.ifBlank { it.name }, it.cid, it.fullPath)
                    }
                },
                enabled = picked != null,
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking) {
        com.open115.pad.ui.filter.DirMultiPickerDialog(
            api = api,
            title = "选择媒体库根目录",
            onDismiss = { picking = false },
            onConfirm = { dirs ->
                picked = dirs.firstOrNull()
                picking = false
            },
        )
    }
}
