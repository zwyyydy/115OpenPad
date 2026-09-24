package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.ScanLogEntity
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.util.Format
import kotlinx.coroutines.launch

/**
 * 扫描记录：**什么时候、扫的哪个库、结果如何、新增了哪些片**。
 *
 * 放在媒体库这条线上（顶栏图标进、浮层显示），而不是文件页那份「操作记录」里：
 * 那边是文件操作的流水（复制/移动/上传…），而这里要带**海报图**展示新增影片 —— 那是媒体库的事。
 *
 * 数据是 `scan_log` 表；新增影片只存了键，海报与标题在这里 join `movies` 现取
 * （库里改了名换了海报，旧记录也跟着变），点一张就进详情页。
 */
@Composable
fun MediaScanLogScreen(
    dao: MediaDao,
    /** 点新增影片的海报 → 开详情（[list] 是这条记录里新增的那一批，播完自动接下一部） */
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val logs by dao.scanLogs().collectAsState(initial = emptyList())
    var confirmClear by remember { mutableStateOf(false) }

    // 所有记录里出现过的键 → 卡片，一次批量查完（几百条记录也就几百个键，见 dao.movieCardsByKeys 的分块）
    val cardsByKey by produceState<Map<String, MovieCard>>(emptyMap(), logs) {
        val keys = logs.flatMap { it.newKeyList }.distinct()
        value = runCatching { dao.movieCardsByKeys(keys).associateBy { it.mediaKey } }.getOrDefault(emptyMap())
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scrim ->
        Column(Modifier.fillMaxSize().padding(scrim)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text("扫描记录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (logs.isEmpty()) "媒体库每扫一次都会记在这里（时间 / 哪个库 / 新增了哪些片）"
                        else "${logs.size} 次扫描 · 最近一次 ${Format.ago(logs.first().at)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmClear = true }, enabled = logs.isNotEmpty()) {
                    Icon(Icons.Outlined.Delete, contentDescription = "清空扫描记录")
                }
            }

            if (logs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "还没有扫描记录。\n在媒体库里点「扫描」跑一轮，这里会记下这次扫的是哪个库、新增了哪些片。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(logs, key = { it.id }) { log ->
                        ScanLogCard(
                            log = log,
                            cards = log.newKeyList.mapNotNull { cardsByKey[it] },
                            onOpenMovie = onOpenMovie,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空扫描记录？") },
            text = { Text("${logs.size} 条记录会全部删除。影片索引与云端文件都不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        dao.clearScanLogs()
                        snackbarHostState.showSnackbar("扫描记录已清空")
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/**
 * 一条记录：库名 + 完成时间（大字）+ 结果摘要（[com.open115.pad.data.media.ScanReport.summary]）
 * + 新增影片的海报横排。
 */
@Composable
private fun ScanLogCard(
    log: ScanLogEntity,
    cards: List<MovieCard>,
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
) {
    val report = remember(log) { log.toReport() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Update,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                log.libraryName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Format.ago(log.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            report.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            report.finishedText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (cards.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    report.aborted -> "这次没跑到索引阶段（可以再扫一次）"
                    else -> "本次没有新增影片"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                "新增影片（${cards.size}）",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            // 海报横排：一次新增几十部时不至于把一屏撑爆（要横向滑）
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexedCards(cards) { index, card ->
                    NewMovieTile(card) { onOpenMovie(card, cards, index) }
                }
            }
        }
    }
}

/** 只是给上面的 LazyRow 收一下签名（itemsIndexed + 稳定 key，滚动时不闪） */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCards(
    cards: List<MovieCard>,
    content: @Composable (Int, MovieCard) -> Unit,
) = items(cards.size, key = { cards[it].mediaKey }) { index -> content(index, cards[index]) }

/** 新增影片的一块：2:3 海报 + 一行标题；没有海报时画个占位图标（不留白块） */
@Composable
private fun NewMovieTile(card: MovieCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
            // 与海报墙同一套：没海报时用背景图裁好的兜底（只查本地已裁好的，零请求）。
            // 背景源和海报墙/详情页共用 backgroundSourceOf（fanart 优先，没有就用第一张剧照）
            PosterImage(
                posterPickCode = card.posterPickCode,
                backgroundPickCode = backgroundSourceOf(card.fanartPickCode, card.extraFanartPickCodes),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
