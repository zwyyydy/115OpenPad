package com.open115.pad.ui.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.open115.pad.data.FileItem
import com.open115.pad.data.FilterRules
import com.open115.pad.data.OpenApi
import com.open115.pad.data.parseFilesResponse
import kotlinx.coroutines.launch

private data class PickerDir(val cid: String, val name: String)

/**
 * 可视化目录选择器（规则"生效范围"绑定目录用）。
 *
 * 与"拒绝手输 ID/路径"对应：
 * - 懒加载：初始只拉根目录（cid=0）；点进某层才请求该层的子文件夹，
 *   避免一次性拉整棵网盘树触发频控/卡死
 * - 仅渲染文件夹（isDir / fc=0），实体文件一律隐藏
 * - 交互形态：点击逐级下钻 + 顶部面包屑回退
 * - 多选：勾选状态跨层级保留（可以先选 /影视/4K电影，再下钻选 /家庭/示例文件夹）
 * - fullPath 在**勾选那一刻**由当前面包屑快照生成并随选项带回
 *
 * 确认后回传 List<BoundDir>，由规则编辑器渲染成可删除的目录芯片。
 */
@Composable
fun DirMultiPickerDialog(
    api: OpenApi,
    title: String = "选择生效目录",
    onDismiss: () -> Unit,
    onConfirm: (List<FilterRules.BoundDir>) -> Unit,
) {
    // 根 → 当前 的下钻路径（面包屑数据源，也是 fullPath 的来源）
    var stack by remember { mutableStateOf(listOf(PickerDir("0", "根目录"))) }
    var dirs by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // cid -> BoundDir：跨层级保留的勾选集
    val selected = remember { mutableStateMapOf<String, FilterRules.BoundDir>() }
    val scope = rememberCoroutineScope()

    fun load(cid: String) {
        scope.launch {
            loading = true
            error = null
            try {
                // show_dir=1 拉全量再在客户端过滤文件夹：目录数远小于文件数，一次请求足够
                val page = parseFilesResponse(api.files(cid = cid, limit = 500, order = "file_name", asc = 1))
                dirs = page.items.filter { it.isDir }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(stack) { load(stack.last().cid) }

    /** 当前层的 fullPath 前缀：根目录不计入，形如 "/影视/4K电影" */
    val prefixPath = stack.drop(1).joinToString("/") { it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 面包屑：点任意一级回退（回退不清除已勾选的目录）
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(stack) { i, dir ->
                        Text(
                            dir.name + if (i < stack.lastIndex) " ›" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == stack.lastIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                if (i < stack.lastIndex) stack = stack.subList(0, i + 1)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(26.dp)) }

                    error != null -> Column {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { load(stack.last().cid) }) { Text("重试") }
                    }

                    dirs.isEmpty() -> Text(
                        "这个文件夹里没有子文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    ) {
                        itemsIndexed(dirs) { _, dir ->
                            val checked = dir.fid in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 点行体 = 进入下一级；勾选只认 Checkbox，两条交互互不干扰
                                        stack = stack + PickerDir(dir.fid ?: return@clickable, dir.fn)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            selected[dir.fid!!] = FilterRules.BoundDir(
                                                cid = dir.fid,
                                                name = dir.fn,
                                                // 勾选瞬间的面包屑快照即完整路径
                                                fullPath = "$prefixPath/${dir.fn}",
                                            )
                                        } else {
                                            selected.remove(dir.fid)
                                        }
                                    },
                                )
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    dir.fn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                // 右侧箭头提示"点行体是进入"，与勾选区分
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = "进入该文件夹",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "已选 ${selected.size} 个目录（跨层级累计）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.values.toList()) },
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (stack.size > 1) {
                    TextButton(onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = null, Modifier.size(18.dp))
                        Text("上一级")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
