package com.open115.pad.ui.transfer

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.appContainer
import com.open115.pad.data.UploadRecord
import com.open115.pad.ui.theme.AppCard
import com.open115.pad.ui.theme.AppChip
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.KindBadge
import com.open115.pad.ui.theme.StatusBadge
import com.open115.pad.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 传输中心：本机下载（系统 DownloadManager 任务）+ 上传（文件页 / 播放器字幕上传）两份记录。
 *
 * 下载：不另建任务表，状态直接查系统；只额外记"什么时候开始下的"（DownloadManager 不提供）。
 * 上传：一次性 PUT 没有进度可查，只记开始/结束时间、大小、目标目录与成败。
 */
private data class DlTask(
    val id: Long,
    val name: String,
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val reason: Int,
    val mime: String?,
    val localUri: String?,
) {
    val active: Boolean
        get() = status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED

    /**
     * 已完成但文件已不在：用户在系统「文件」里把它删了/移走了，或同名文件被后来的下载覆盖。
     * 只对 file:// 落地路径做判断——content:// 交给系统的场景不猜，宁可当它还在
     * （真点「打开」时会给出明确提示），避免误报。
     */
    val fileGone: Boolean
        get() = status == DownloadManager.STATUS_SUCCESSFUL &&
            localUri?.startsWith("file://") == true &&
            !java.io.File(android.net.Uri.parse(localUri).path ?: "").exists()
}

/** 取当前 App 名下的下载任务（DownloadManager.query 只会返回本应用入队的任务），新的在前 */
private fun queryDownloads(context: Context): List<DlTask> {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        ?: return emptyList()
    val out = ArrayList<DlTask>()
    runCatching {
        dm.query(DownloadManager.Query())?.use { c ->
            while (c.moveToNext()) {
                fun col(name: String) = c.getColumnIndex(name)
                out += DlTask(
                    id = c.getLong(col(DownloadManager.COLUMN_ID)),
                    name = c.getString(col(DownloadManager.COLUMN_TITLE)) ?: "未命名",
                    status = c.getInt(col(DownloadManager.COLUMN_STATUS)),
                    downloaded = c.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    total = c.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    reason = c.getInt(col(DownloadManager.COLUMN_REASON)),
                    mime = c.getString(col(DownloadManager.COLUMN_MEDIA_TYPE)),
                    localUri = c.getString(col(DownloadManager.COLUMN_LOCAL_URI)),
                )
            }
        }
    }
    return out.sortedByDescending { it.id }
}

/** 状态徽章文案 + 颜色，失败时尽量把原因翻成人话 */
@Composable
private fun statusVisual(t: DlTask): Pair<String, Pair<Color, Color>> =
    when {
        t.fileGone -> "文件已删除" to (AppColors.GraySoft to AppColors.TextSecondary)
        t.status == DownloadManager.STATUS_SUCCESSFUL -> "已完成" to (AppColors.GreenBg to AppColors.GreenFg)
        t.status == DownloadManager.STATUS_RUNNING -> "下载中" to (AppColors.BlueBg to AppColors.BlueFg)
        t.status == DownloadManager.STATUS_PENDING -> "等待中" to (AppColors.AmberBg to AppColors.AmberFg)
        t.status == DownloadManager.STATUS_PAUSED -> "已暂停" to (AppColors.AmberBg to AppColors.AmberFg)
        else -> "失败" to (AppColors.RedBg to AppColors.RedFg)
    }

private fun failureText(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "空间不足"
    DownloadManager.ERROR_FILE_ERROR -> "写入失败"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
    DownloadManager.ERROR_CANNOT_RESUME -> "无法续传"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    -> "服务端拒绝（HTTP $reason）"
    else -> "错误码 $reason"
}

/**
 * 打开时用的 MIME：**优先按扩展名推断**。DownloadManager 上报的 media_type 常常是
 * application/octet-stream 这类泛型，直接用它会让系统把 apk 交给随机一个能收
 * octet-stream 的应用（实测投给了阿里云盘），而不是安装器。
 */
private fun mimeOf(name: String, fromColumn: String?): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    val guessed = if (ext.isNotEmpty()) {
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    } else null
    return guessed
        ?: fromColumn?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: "*/*"
}

/** 打开已下载的文件：用系统下载器给本应用的 content URI 交给外部应用查看 */
private fun openDownloaded(context: Context, id: Long, mime: String?): String? {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        ?: return "系统下载器不可用"
    val uri = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull() ?: return "文件不存在或已被移动"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "没有能打开该类型文件的应用"
    } catch (e: Exception) {
        "打开失败：${e.message}"
    }
}

/**
 * 删除任务：先按 URI 删已下载的文件，再摘掉任务记录。
 *
 * 注意两点（都是实测踩出来的）：
 * ① 视频/图片/音频这类进系统媒体库的文件，Android 10+ 应用未必删得掉，删完必须回头
 *    看文件是否真的没了，没删掉就如实告知，不能谎报"已删除"；
 * ② `dm.remove()` 的返回值不可靠（实测出现过返回 0 但记录已消失的情况），所以判定
 *    一律以文件/记录的实际状态为准，不看返回值。
 */
private fun removeDownload(context: Context, task: DlTask): String? {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        ?: return "系统下载器不可用"
    runCatching {
        dm.getUriForDownloadedFile(task.id)?.let { context.contentResolver.delete(it, null, null) }
    }
    runCatching { dm.remove(task.id) }
    val filePath = task.localUri?.takeIf { it.startsWith("file://") }
        ?.let { android.net.Uri.parse(it).path }
    val stillThere = filePath != null && java.io.File(filePath).exists()
    return if (stillThere) {
        "任务已移除；文件是媒体库文件，需在系统「文件」里删除（${task.name}）"
    } else {
        null
    }
}

/** 完成时间取落地文件的修改时间；文件已删则拿不到 */
private fun completedAt(t: DlTask): Long? =
    t.localUri?.startsWith("file://")?.let {
        val f = java.io.File(android.net.Uri.parse(t.localUri).path ?: "")
        f.lastModified().takeIf { ms -> f.exists() && ms > 0 }
    }

private fun speedText(bytesPerSec: Double): String = Format.size(bytesPerSec.toLong()) + "/s"

/** 剩余时间：给量级感即可，不追求秒级精确 */
private fun etaText(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}

private fun timeText(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { context.appContainer.transferLog }

    var tab by remember { mutableStateOf(0) } // 0 = 下载，1 = 上传

    // ---- 下载侧状态 ----
    var tasks by remember { mutableStateOf<List<DlTask>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DlTask?>(null) }

    val downloadRecords by log.downloads.collectAsState(initial = emptyList())
    val startedAtById = remember(downloadRecords) { downloadRecords.associate { it.id to it.startedAt } }

    // 速度：DownloadManager 只给累计字节，速度靠相邻两次采样差分；瞬时值抖动大，做指数平滑
    val samples = remember { mutableStateMapOf<Long, Pair<Long, Long>>() }
    val speeds = remember { mutableStateMapOf<Long, Double>() }

    // ---- 上传侧状态 ----
    val uploads by log.uploads.collectAsState(initial = emptyList())
    var pendingUploadDelete by remember { mutableStateOf<UploadRecord?>(null) }

    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    // 有进行中的任务时 1 秒一刷（速度/剩余时间要秒级刷新），全部停下后放慢到 2.5 秒
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val list = queryDownloads(context)
            for (t in list) {
                if (!t.active) {
                    samples.remove(t.id)
                    speeds.remove(t.id)
                    continue
                }
                val prev = samples[t.id]
                if (prev != null && now > prev.second) {
                    val instant = (t.downloaded - prev.first).coerceAtLeast(0) * 1000.0 / (now - prev.second)
                    val old = speeds[t.id]
                    speeds[t.id] = if (old == null || old <= 0) instant else old * 0.65 + instant * 0.35
                }
                samples[t.id] = t.downloaded to now
            }
            tasks = list
            loaded = true
            delay(if (list.any { it.active }) 1000 else 2500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("传输中心") },
            actions = {
                IconButton(onClick = {
                    tasks = queryDownloads(context)
                    notify("已刷新")
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            },
        )
        // 分段切换：下载 / 上传（数量直接标在标签上）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppChip("下载 ${tasks.size}", selected = tab == 0, onClick = { tab = 0 })
            AppChip("上传 ${uploads.size}", selected = tab == 1, onClick = { tab = 1 })
        }
        if (tab == 0) {
            // 明确告诉用户文件到底存哪儿：公共下载目录，系统「文件」App 里也能看到
            Text(
                "保存位置：Download/115OpenPad（系统「文件」App 中同样可见）",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            AdaptiveBody(Modifier.fillMaxSize()) {
                when {
                    tab == 0 -> DownloadList(
                        loaded = loaded,
                        tasks = tasks,
                        speeds = speeds,
                        startedAtById = startedAtById,
                        onOpen = { t -> notify(openDownloaded(context, t.id, mimeOf(t.name, t.mime))) },
                        onDelete = { t -> pendingDelete = t },
                    )

                    else -> UploadList(
                        uploads = uploads,
                        onDelete = { r -> pendingUploadDelete = r },
                    )
                }
            }
        }
    }

    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = { Text("将删除「${t.name}」的任务记录与已下载的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    notify(removeDownload(context, t) ?: "已删除「${t.name}」")
                    scope.launch { log.removeDownload(t.id) }
                    tasks = queryDownloads(context)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    pendingUploadDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingUploadDelete = null },
            title = { Text("移除上传记录") },
            // 只是历史记录，云盘上的文件不动——避免用户误以为会删云端文件
            text = { Text("仅移除这条记录，云盘上的「${r.name}」不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUploadDelete = null
                    scope.launch { log.removeUpload(r.id) }
                    notify("已移除记录")
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingUploadDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadList(
    loaded: Boolean,
    tasks: List<DlTask>,
    speeds: Map<Long, Double>,
    startedAtById: Map<Long, Long>,
    onOpen: (DlTask) -> Unit,
    onDelete: (DlTask) -> Unit,
) {
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        tasks.isEmpty() -> EmptyHint(
            icon = { Icon(Icons.Outlined.Download, null, tint = AppColors.TextTertiary, modifier = Modifier.size(44.dp)) },
            title = "还没有下载任务",
            hint = "在文件页选中文件后点「下载」，文件会保存到 Download/115OpenPad",
        )

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id }) { t ->
                DownloadRow(
                    task = t,
                    speed = speeds[t.id] ?: 0.0,
                    startedAt = startedAtById[t.id],
                    onOpen = { onOpen(t) },
                    onDelete = { onDelete(t) },
                )
            }
        }
    }
}

@Composable
private fun UploadList(uploads: List<UploadRecord>, onDelete: (UploadRecord) -> Unit) {
    if (uploads.isEmpty()) {
        EmptyHint(
            icon = { Icon(Icons.Outlined.CloudUpload, null, tint = AppColors.TextTertiary, modifier = Modifier.size(44.dp)) },
            title = "还没有上传记录",
            hint = "文件页的「上传文件」、播放器里的「上传到云盘」都会记在这里（时间 + 目标目录）",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 新的在前：id 是本地自增序号
        items(uploads.sortedByDescending { it.id }, key = { it.id }) { r ->
            UploadRow(record = r, onDelete = { onDelete(r) })
        }
    }
}

@Composable
private fun EmptyHint(
    icon: @Composable () -> Unit,
    title: String,
    hint: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(10.dp))
            Text(title, color = AppColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun UploadRow(record: UploadRecord, onDelete: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(record.name, isDir = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.name,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // 目标目录是这条记录的重点：云盘里同名文件多，事后靠它认路
                    "→ ${record.targetName ?: "云盘目录（${record.targetCid}）"} · ${Format.size(record.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val timing = when {
                    record.ok == null -> "开始 ${timeText(record.startedAt)}"
                    record.ok == true -> "上传于 ${timeText(record.finishedAt ?: record.startedAt)}"
                    else -> "上传于 ${timeText(record.finishedAt ?: record.startedAt)}"
                }
                Text(
                    if (record.ok == false && !record.error.isNullOrBlank()) "$timing · ${record.error}" else timing,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.ok == false) AppColors.RedFg else AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val (label, bg, fg) = when {
                record.ok == null -> Triple("上传中", AppColors.BlueBg, AppColors.BlueFg)
                record.ok == false -> Triple("失败", AppColors.RedBg, AppColors.RedFg)
                record.reused -> Triple("秒传", AppColors.AmberBg, AppColors.AmberFg)
                else -> Triple("已上传", AppColors.GreenBg, AppColors.GreenFg)
            }
            StatusBadge(label, bg = bg, fg = fg)
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除记录", tint = AppColors.RedFg)
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: DlTask,
    speed: Double,
    startedAt: Long?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(task.name, isDir = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.name,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                task.status == DownloadManager.STATUS_SUCCESSFUL ->
                                    Format.size(if (task.total > 0) task.total else task.downloaded)
                                task.total > 0 ->
                                    "${Format.size(task.downloaded)} / ${Format.size(task.total)}"
                                else -> Format.size(task.downloaded)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                        if (task.status == DownloadManager.STATUS_FAILED) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                failureText(task.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.RedFg,
                            )
                        }
                    }
                    // 第二行：进行中显示实时速度 + 预计剩余；已结束显示下载/完成时间
                    val timing = when {
                        task.active -> buildString {
                            startedAt?.let { append("开始 ${timeText(it)} · ") }
                            if (speed > 0) {
                                append(speedText(speed))
                                if (task.total > 0 && task.downloaded < task.total) {
                                    val eta = ((task.total - task.downloaded) / speed).toLong()
                                    append(" · 剩余约 ${etaText(eta.coerceAtLeast(1))}")
                                }
                            } else {
                                append("正在建立连接…")
                            }
                        }
                        task.fileGone -> startedAt?.let { "下载于 ${timeText(it)}" }
                        task.status == DownloadManager.STATUS_SUCCESSFUL ->
                            (completedAt(task) ?: startedAt)?.let { "完成于 ${timeText(it)}" }
                        else -> startedAt?.let { "开始于 ${timeText(it)}" }
                    }
                    if (!timing.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            timing,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val (label, colors) = statusVisual(task)
                StatusBadge(label, bg = colors.first, fg = colors.second)
                if (task.status == DownloadManager.STATUS_SUCCESSFUL && !task.fileGone) {
                    IconButton(onClick = onOpen) {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = "打开",
                            tint = AppColors.AccentDeep,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "删除",
                        tint = AppColors.RedFg,
                    )
                }
            }
            // 进行中的任务显示进度条：百分比未知（total 未上报）时退化成不确定进度
            if (task.active) {
                Spacer(Modifier.height(8.dp))
                if (task.total > 0) {
                    LinearProgressIndicator(
                        progress = { (task.downloaded.toFloat() / task.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}
