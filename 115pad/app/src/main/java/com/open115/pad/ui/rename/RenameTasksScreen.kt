package com.open115.pad.ui.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.AppContainer
import com.open115.pad.data.RenameTask
import com.open115.pad.data.parseFilesResponse
import com.open115.pad.ui.components.FolderPickerDialog
import com.open115.pad.ui.files.BatchRenameRequest
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.AppCard
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.KindBadge
import com.open115.pad.ui.theme.StatusBadge
import com.open115.pad.util.Format
import kotlinx.coroutines.launch

/**
 * 重命名任务页。
 *
 * 批量改名是 N 次带限频的网络调用，几十个文件就要跑几十秒 —— 所以执行放在
 * 持久化队列里（[com.open115.pad.data.RenameQueueWorker]），这个页面只负责
 * **看进度**和**建新任务**，用户可以随时走开。
 *
 * 结构与传输中心一致（无 ViewModel、collectAsState、AdaptiveBody），因为它们是同一类页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameTasksScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    /** 选好目录后把配置面板交给根层级渲染（面板要浮在内容区之上） */
    onNewTask: (BatchRenameRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val queue = remember { container.renameQueue }
    val worker = remember { container.renameWorker }

    val tasks by queue.tasks.collectAsState()
    val activeId by worker.activeTaskId.collectAsState()

    var picking by remember { mutableStateOf(false) }
    var loadingDir by remember { mutableStateOf(false) }

    /** 排队位次：只有"该跑但还没轮到"的任务才有 */
    val runnable = remember(tasks) {
        tasks.filter { it.finishedAt == null && !it.paused && it.hasPending }.sortedBy { it.createdAt }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("重命名任务") },
            actions = {
                if (tasks.any { it.finishedAt != null }) {
                    TextButton(onClick = { scope.launch { queue.clearFinished() } }) {
                        Text("清空已完成")
                    }
                }
                TextButton(onClick = { picking = true }, enabled = !loadingDir) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建任务")
                }
            },
        )

        AdaptiveBody(Modifier.fillMaxSize()) {
            when {
                loadingDir -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                tasks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.DriveFileRenameOutline,
                            contentDescription = null,
                            tint = AppColors.TextTertiary,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("还没有重命名任务", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点右上角「新建任务」选云盘目录，或在文件页多选后点重命名",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { t ->
                        val pos = runnable.indexOfFirst { it.id == t.id }.takeIf { it >= 0 }?.plus(1)
                        TaskRow(
                            task = t,
                            isActive = activeId == t.id,
                            queuedPosition = if (t.id == activeId) null else pos,
                            onPause = { worker.pauseTask(t.id, scope) },
                            onResume = { worker.resumeTask(t.id, scope) },
                            onCancel = { worker.cancelTask(t.id, scope) },
                            onRetry = { worker.retryTask(t.id, scope) },
                            onRemove = { worker.removeTask(t.id, scope) },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        FolderPickerDialog(
            api = container.openApi,
            title = "选择要重命名的目录",
            onDismiss = { picking = false },
            onPick = { cid, name ->
                picking = false
                loadingDir = true
                scope.launch {
                    // 目录内全部条目（含子文件夹）一起进配置面板，面板里还能逐行去掉
                    val err = runCatching {
                        parseFilesResponse(
                            container.openApi.files(cid = cid, limit = 200, offset = 0, order = "file_name", asc = 1),
                        ).items.filter { it.fid != null }
                    }.getOrElse { e ->
                        loadingDir = false
                        snackbarHostState.showSnackbar("读取目录失败：${e.message}")
                        return@launch
                    }
                    loadingDir = false
                    if (err.isEmpty()) {
                        snackbarHostState.showSnackbar("该目录没有可重命名的条目")
                        return@launch
                    }
                    onNewTask(
                        BatchRenameRequest(items = err, allFiles = err, cid = cid, dirName = name),
                    )
                }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: RenameTask,
    isActive: Boolean,
    queuedPosition: Int?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(task.dirName, isDir = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.dirName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Format.dateTime(task.createdAt / 1000)} · ${task.total} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                    )
                }
                Spacer(Modifier.width(8.dp))

                val (label, colors) = statusVisual(task, isActive)
                StatusBadge(label, bg = colors.first, fg = colors.second)

                // 操作按钮随状态变：与传输中心的上传行同一套语义
                when {
                    task.finishedAt != null -> {
                        // 有失败项才给重试；重试会把失败项打回 PENDING 并重新打开任务
                        if (task.failed > 0) {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重试失败项", tint = AppColors.BlueFg)
                            }
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除记录", tint = AppColors.RedFg)
                        }
                    }
                    task.paused -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "继续", tint = AppColors.BlueFg)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消", tint = AppColors.RedFg)
                        }
                    }
                    isActive -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = AppColors.AmberFg)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消", tint = AppColors.RedFg)
                        }
                    }
                    else -> {
                        // 排队中：还没动过任何文件，取消即结束
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消", tint = AppColors.RedFg)
                        }
                    }
                }
            }

            // 进度：排队中显示位次，跑起来了显示条数 + 进度条
            when {
                isActive || task.paused -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${task.doneCount}/${task.total} · 成功 ${task.succeeded} 失败 ${task.failed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (task.total > 0) task.doneCount.toFloat() / task.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
                queuedPosition != null -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "排队中 · 第 $queuedPosition 位",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                    )
                }
            }

            // 失败明细必须露出来，否则用户不知道哪几个没改成
            val fails = task.items.filter { it.st == com.open115.pad.data.RenameItemStatus.FAILED }
            if (fails.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    fails.take(3).joinToString("；") { "${it.oldName}：${it.error ?: "失败"}" } +
                        if (fails.size > 3) " …还有 ${fails.size - 3} 个" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.RedFg,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            task.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.AmberFg)
            }
        }
    }
}

/** 状态徽章。文案与颜色跟传输中心保持一致，用户不用重新学一套（取 AppColors 需在组合期） */
@Composable
private fun statusVisual(task: RenameTask, isActive: Boolean): Pair<String, Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>> = when {
    task.finishedAt != null && task.cancelled -> "已取消" to (AppColors.SlateBg to AppColors.SlateFg)
    task.finishedAt != null && task.failed > 0 -> "部分失败" to (AppColors.RedBg to AppColors.RedFg)
    task.finishedAt != null -> "已完成" to (AppColors.GreenBg to AppColors.GreenFg)
    task.paused -> "已暂停" to (AppColors.AmberBg to AppColors.AmberFg)
    isActive -> "重命名中" to (AppColors.BlueBg to AppColors.BlueFg)
    else -> "排队中" to (AppColors.SlateBg to AppColors.SlateFg)
}
