package com.open115.pad.ui.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.BtTask
import com.open115.pad.ui.components.FadeAlertDialog
import com.open115.pad.util.Format

/**
 * 种子文件勾选面板：解析完种子后列出全部文件，用户自己决定下哪些。
 *
 * 默认勾选 = 服务端 `wanted` 标记的条目（一个都没标就全选）——这和官方客户端一致，
 * 也避免用户以为"没勾就是不下"结果一个文件都没选。
 */
@Composable
fun BtFileSelectDialog(
    parsed: BtTask.Parsed,
    busy: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
    confirmLabel: String = "开始下载",
) {
    // 换一个种子就换一份选择（key 用 infoHash，避免同一弹窗位置复用到上一条种子）
    val selected = remember(parsed.infoHash) {
        mutableStateListOf<Int>().apply { addAll(parsed.defaultSelection) }
    }
    val allSelected = parsed.files.isNotEmpty() && parsed.files.all { it.index in selected }
    val selectedSize = parsed.selectedSize(selected.toSet())

    FadeAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("选择下载文件") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    parsed.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "共 ${parsed.fileCount} 个文件 · ${Format.size(parsed.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${selected.size} 个 · ${Format.size(selectedSize)}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (allSelected) selected.clear()
                            else parsed.files.forEach { if (it.index !in selected) selected.add(it.index) }
                        },
                    ) { Text(if (allSelected) "全不选" else "全选") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(parsed.files, key = { it.index }) { f ->
                        val checked = f.index in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selected.remove(f.index) else selected.add(f.index)
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    if (on) {
                                        if (f.index !in selected) selected.add(f.index)
                                    } else {
                                        selected.remove(f.index)
                                    }
                                },
                            )
                            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                Text(
                                    f.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Format.size(f.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toSet()) },
                enabled = !busy && selected.isNotEmpty(),
            ) { Text(if (busy) "提交中…" else confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
