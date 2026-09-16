package com.open115.pad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.open115.pad.data.FileItem
import com.open115.pad.data.OpenApi
import com.open115.pad.data.parseFilesResponse
import kotlinx.coroutines.launch

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 文件夹选择器：浏览 115 目录树并选中一个目标文件夹，用于移动/复制/云下载保存位置。
 *
 * 起点固定为根目录：面包屑只记录本次会话内点进去的路径，没有上级祖先链，
 * 若从"上次选过的目录"直接进入，用户会被困在该目录里无法返回上层
 * （表现为云下载"换不了保存目录/选择目录不生效"）。当前已保存的值由调用方自行展示。
 */
@Composable
fun FolderPickerDialog(
    api: OpenApi,
    title: String,
    onDismiss: () -> Unit,
    onPick: (cid: String, name: String) -> Unit,
) {
    var stack by remember { mutableStateOf(listOf("0" to "根目录")) }
    var dirs by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(cid: String) {
        scope.launch {
            loading = true
            error = null
            try {
                val page = parseFilesResponse(api.files(cid = cid, limit = 500, order = "file_name", asc = 1))
                dirs = page.items.filter { it.isDir }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(stack) { load(stack.last().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(stack.size) { i ->
                        Text(
                            stack[i].second + if (i < stack.size - 1) " ›" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == stack.size - 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                if (i < stack.size - 1) stack = stack.subList(0, i + 1)
                            },
                        )
                    }
                }
                Box(Modifier.height(360.dp).padding(vertical = 8.dp)) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                        }
                        dirs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有子文件夹")
                        }
                        else -> LazyColumn {
                            items(dirs, key = { it.fid ?: it.fn }) { dir ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { stack = stack + ((dir.fid ?: "0") to dir.fn) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        dir.fn,
                                        modifier = Modifier.padding(start = 12.dp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPick(stack.last().first, stack.last().second) }) {
                Text("选择此目录")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DownloadDialog(
    name: String,
    sizeText: String,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 2) },
        text = { Text(sizeText) },
        confirmButton = { Button(onClick = onDownload) { Text("下载") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopyLink) { Text("复制直链") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 搜索框（文件页顶部展开用） */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("搜索文件") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** 宽度参考常量，供侧栏使用 */
val SidePaneWidth = 280.dp
