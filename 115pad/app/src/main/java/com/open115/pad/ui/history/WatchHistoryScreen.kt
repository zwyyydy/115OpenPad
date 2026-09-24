package com.open115.pad.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.AppContainer
import com.open115.pad.data.media.WatchHistoryRow
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.media.PickCodeImage
import com.open115.pad.util.Format
import kotlinx.coroutines.launch

/**
 * 差值不到这么多毫秒就算"已经看完"：点击从头播、不显示百分比。
 *
 * 与播放器 [PlayerActivity] 里 `resumePos` 的判据同一个思路 —— 115 的观看记录
 * 经常把进度记成"刚好播完"，拿它续播会直接跳到片尾。这里留 15 秒余量。
 */
private const val NEAR_END_MS = 15_000L

/**
 * 观影历史：最近在**媒体库里**播过的片，点一下接着上次的位置继续看。
 *
 * 为什么只记媒体库的播放：文件页的播放记录在「操作记录」里（那边已经有一份），
 * 两处各记一份是重复。数据来源见 [com.open115.pad.data.media.WatchHistoryEntity]：
 * 115 没有"列出看过的片"的接口，所以这份是本机自己记的。
 * 库内条目的海报/标题在查询时 join `movies` 现取 —— 库里换了海报、改了名，这一页立刻跟着变。
 *
 * 交互：
 *  - 点一行 → 从记录的位置续播（已看完的从头播）
 *  - 长按 → 只删这一条（**只删历史，不动云端观看记录与本机续播点** ——
 *    那两个是"进度"，删了下次播会从头开始，不是用户点"从历史里删除"的本意）
 *  - 右上角清空全部
 *
 * @param onBack 非空 = 作为浮层嵌在媒体库页里，顶栏左上角给一个返回
 */
@Composable
fun WatchHistoryScreen(
    container: AppContainer,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 自己起一个 host：这个页现在只作为媒体库的浮层出现（见 MediaLibraryScreen 的 overlays），
    // 外面的 Scaffold 在浮层之上，不该借它的 host
    val snackbarHostState = remember { SnackbarHostState() }
    val dao = remember { container.mediaDatabase.mediaDao() }
    val rows by dao.watchHistoryRows().collectAsState(initial = emptyList())

    var onlyUnfinished by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WatchHistoryRow?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = remember(rows, onlyUnfinished) {
        if (onlyUnfinished) rows.filterNot { isFinished(it) } else rows
    }

    /** 续播位置：看完了就从头（0 会让播放器跳过"沿用观看记录"的分支，见 pendingStartMs 的注释） */
    fun play(row: WatchHistoryRow) {
        val start = if (isFinished(row)) 0L else row.positionMs
        val title = displayTitle(row)
        val sub = displaySubtitle(row)
        val label = if (sub.isBlank()) title else "$title · $sub"
        val intent = if (row.local) {
            // 只在历史行是"本机源"时才走这条路 —— 现在的写入链路只记媒体库（云端）播放，
            // 留这一支是为了万一有一条老数据/异常行时至少点得动
            PlayerActivity.localIntent(context, row.itemKey, label, start)
        } else {
            // 从历史里点开也算"在媒体库里看"：接着记，这条才会一直留在列表最上面
            PlayerActivity.intent(context, row.itemKey, label, startMs = start, recordHistory = true)
        }
        context.startActivity(intent)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scrim ->
    Column(Modifier.fillMaxSize().padding(scrim)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            } else {
                Icon(Icons.Outlined.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("观影历史", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (rows.isEmpty()) "在媒体库里播过的片会出现在这里"
                    else "${rows.size} 条记录 · 未看完 ${rows.count { !isFinished(it) }} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = onlyUnfinished,
                onClick = { onlyUnfinished = !onlyUnfinished },
                label = { Text("只看未看完") },
            )
            IconButton(
                onClick = { confirmClear = true },
                enabled = rows.isNotEmpty(),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "清空观影历史")
            }
        }

        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (rows.isEmpty()) {
                        "还没有观看记录。\n在媒体库里播一部片，这里就会出现它 —— 下次点一下直接接着看。"
                    } else {
                        "没有未看完的片（关掉上面的筛子看全部）"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.itemKey }) { row ->
                    HistoryRow(
                        row = row,
                        onClick = { play(row) },
                        onLongClick = { pendingDelete = row },
                    )
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从观影历史里删除") },
            text = {
                Text(
                    "「${displayTitle(row)}」将从这份列表里移除。\n" +
                        "只删这条记录，不动云端观看记录与本机续播点 —— 下次播还是从上次的位置继续。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = row.itemKey
                    pendingDelete = null
                    scope.launch {
                        dao.deleteWatchHistory(key)
                        snackbarHostState.showSnackbar("已从观影历史里删除")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空观影历史？") },
            text = { Text("${rows.size} 条记录会全部删除。播放进度不受影响，只是这份列表清空。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        dao.clearWatchHistory()
                        snackbarHostState.showSnackbar("观影历史已清空")
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    }
}

/**
 * 主标题：优先用**库里的**标题（分集时是所属系列的名字），库里没有才用播放时记的文件名。
 *
 * 两条兜底都是实测出来的：
 *  - `name` 可能为空 —— 从文件管理器/别的应用直接用 ACTION_VIEW 打开视频时，
 *    播放器那边没有文件名可传（见 PlayerActivity.sourceUriOrNull），历史行就只剩一个 uri
 *  - 本机条目退回 uri 的最后一段（就是文件名），至少认得出是哪部片
 */
private fun displayTitle(row: WatchHistoryRow): String =
    row.libraryTitle?.takeIf { it.isNotBlank() }
        ?: row.name.takeIf { it.isNotBlank() }
        ?: row.itemKey.substringAfterLast('/').ifBlank { row.itemKey }

/** 副标题：分集/分盘的集名（与主标题不同才有意义），否则空 */
private fun displaySubtitle(row: WatchHistoryRow): String {
    val own = row.ownTitle?.takeIf { it.isNotBlank() } ?: return ""
    return if (own == displayTitle(row)) "" else own
}

/** 位置到片尾不足 [NEAR_END_MS] 就算看完（没有时长信息时不算，宁可显示进度） */
private fun isFinished(row: WatchHistoryRow): Boolean =
    row.durationMs > 0L && row.positionMs >= row.durationMs - NEAR_END_MS

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    row: WatchHistoryRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val finished = isFinished(row)
    val fraction = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val remaining = if (row.durationMs > 0L) {
        ((row.durationMs - row.positionMs) / 1000).coerceAtLeast(0L)
    } else {
        0L
    }

    Row(
        Modifier
            .fillMaxWidth()
            // 长按：与海报墙同一套手势（combinedClickable，叠加两个 clickable 会互相吃手势）
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(84.dp)
                .height(118.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // 兜底图标在下层：海报没加载出来/本来就没有（本机文件、库外文件）时不会剩一块空白
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
            PickCodeImage(row.posterPickCode, Modifier.fillMaxSize())
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayTitle(row),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = displaySubtitle(row)
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    finished -> "已看完 · 点击从头再看"
                    row.durationMs > 0L && remaining > 0L ->
                        "已看 ${(fraction * 100).toInt()}% · 还剩 ${Format.duration(remaining)}"
                    row.positionMs > 0L -> "看到 " + Format.playTime(row.positionMs)
                    else -> "还没开始看"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (finished) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                Format.ago(row.updatedAt) + if (row.local) " · 本机文件" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = "继续观看",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
