package com.open115.pad.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.media.PadSync
import com.open115.pad.data.media.PadSyncReport
import com.open115.pad.data.media.SignalDirStatus
import com.open115.pad.data.media.SignalFileChoice
import com.open115.pad.data.media.SignalInspection
import com.open115.pad.data.media.signalFileLabel
import com.open115.pad.ui.components.MetaChip
import com.open115.pad.ui.theme.AppColors
import kotlinx.coroutines.launch
import com.open115.pad.ui.components.FadeAlertDialog

/**
 * 「手动同步文件」对话框：**挑一份信号文件 → 跟本地库比对 → 按需排同步**。
 *
 * 存在意义是自动同步失败时的兜底（115 限流 / 网络抖动）—— 服务器那份信号文件还在，
 * 自动轮询没吃到，用户就手动指一份。比对是拿信号里的目录去查**本地索引**：
 * 库里已经有条目的标「已同步」，没有的标「未同步」，于是"到底同没同过"一眼可见，
 * 不必去翻媒体库看哪部片在不在。
 *
 * ★ 比对判据只回答"库里有没有这个目录的条目"，不回答"新不新"：重刮过的老片库里本来就有，
 *   也会显示已同步 —— 那种情况用「全部重新同步」（它不看这个判据）。
 *
 * 三个阶段各自独立成 UI（挑文件 / 比对 / 同步中），互相之间用 [stage] 切换。
 */
@Composable
fun ManualSignalSyncDialog(
    padSync: PadSync,
    onDismiss: () -> Unit,
    onDone: (PadSyncReport) -> Unit,
) {
    val scope = rememberCoroutineScope()

    /** 阶段：挑文件 → 比对 → （点同步）同步中 */
    var files by remember { mutableStateOf<List<SignalFileChoice>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var inspection by remember { mutableStateOf<SignalInspection?>(null) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    var readingName by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }

    // 进对话框就把信号目录列一遍（1 次请求）
    LaunchedEffect(Unit) {
        val result = padSync.listSignalFiles()
        files = result.files
        listError = result.error
    }

    fun open(name: String) {
        readingName = name
        inspectError = null
        scope.launch {
            val res = padSync.inspectSignal(name)
            readingName = null
            if (res == null) {
                inspectError = "读不到这份信号文件（限流/网络），稍后再试"
            } else {
                inspection = res
            }
        }
    }

    fun apply(insp: SignalInspection, onlyPending: Boolean) {
        if (applying) return
        applying = true
        scope.launch {
            val paths = if (onlyPending) insp.pending.map { it.raw } else null
            val report = padSync.applySignal(insp.name, paths)
            applying = false
            onDone(report)
        }
    }

    val current = inspection
    FadeAlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    IconButton(onClick = { if (!applying) inspection = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
                Text(if (current == null) "手动同步信号文件" else "比对结果")
            }
        },
        text = {
            Box(Modifier.heightIn(min = 200.dp, max = 420.dp)) {
                when {
                    applying -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在排同步…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    readingName != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取信号…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    current != null -> InspectionView(current)

                    else -> FilePickView(
                        files = files,
                        error = listError ?: inspectError,
                        onOpen = ::open,
                    )
                }
            }
        },
        confirmButton = {
            val insp = current
            if (insp != null && !applying) {
                TextButton(
                    onClick = { apply(insp, onlyPending = true) },
                    enabled = insp.pending.isNotEmpty(),
                ) {
                    Text(if (insp.pending.isEmpty()) "都已同步" else "同步未同步的 ${insp.pending.size} 个")
                }
            } else if (!applying && readingName == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            val insp = current
            if (insp != null && !applying) {
                TextButton(onClick = { apply(insp, onlyPending = false) }) { Text("全部重新同步") }
            }
        },
    )
}

/** 挑文件：信号目录里的 .json，按时间倒序（文件名就是服务器写的时间戳） */
@Composable
private fun FilePickView(
    files: List<SignalFileChoice>?,
    error: String?,
    onOpen: (String) -> Unit,
) {
    when {
        files == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        error != null && files.isEmpty() -> CenteredHint(error)

        files.isEmpty() -> CenteredHint(
            "信号目录里没有 .json 信号文件。" +
                "服务器跑完一轮刮削会往信号目录写一份，写完再回来。",
        )

        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "选一份信号文件（新 → 旧）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.name }) { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(file.name) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                signalFileLabel(file.name),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (signalFileLabel(file.name) != file.name) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 比对结果：每个目录一行「已同步 N 部 / 未同步 / 不在库内」，顶上一句结论 */
@Composable
private fun InspectionView(insp: SignalInspection) {
    Column(Modifier.fillMaxSize()) {
        Text(
            signalFileLabel(insp.name),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetaChip("目录 ${insp.dirs.size}")
            if (insp.syncedCount > 0) MetaChip("已同步 ${insp.syncedCount}", tint = AppColors.GreenFg)
            if (insp.pending.size > 0) MetaChip("未同步 ${insp.pending.size}", tint = AppColors.AmberFg)
            if (insp.outsideCount > 0) MetaChip("库外 ${insp.outsideCount}")
        }
        Spacer(Modifier.height(8.dp))
        if (insp.allSynced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = AppColors.GreenFg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "这些目录在库里都已经有条目了，看起来已经同步过。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.GreenFg,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "重刮过的老片库里本来就有条目（这条判据只说「有没有」、不说「新不新」）——" +
                    "想强制搬一次就用「全部重新同步」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "未同步的目录在库里还没有条目，点下面的「同步未同步的」把它们排进扫描队列。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            insp.dirs.forEach { dir -> DirStatusRow(dir) }
        }
    }
}

@Composable
private fun DirStatusRow(dir: SignalDirStatus) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dir.path.trimEnd('/').substringAfterLast('/').ifBlank { dir.path },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                !dir.inLibrary -> MetaChip("不在库内")
                dir.synced -> MetaChip("已同步 ${dir.existing} 部", tint = AppColors.GreenFg)
                else -> MetaChip("未同步", tint = AppColors.AmberFg)
            }
        }
        Text(
            dir.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
