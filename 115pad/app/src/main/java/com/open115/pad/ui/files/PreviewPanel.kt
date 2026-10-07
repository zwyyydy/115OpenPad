package com.open115.pad.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.open115.pad.data.FileItem
import com.open115.pad.data.PreviewBuilder
import com.open115.pad.data.VideoPreviewStore
import com.open115.pad.ui.theme.AppChip
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.components.VideoStrip
import com.open115.pad.util.Format

/** 从文件页哪个目录发起批量生成预览图 */
data class PreviewRequest(val cid: String, val path: String)

/**
 * 「生成本目录视频预览图」面板。
 *
 * 三态与去重面板一致（起始 / 进行中 / 结果 + 顶栏返回与「再来一轮」）。
 * 与去重面板最大的区别：这一步**只读**——不改云端任何东西，所以没有勾选与删除，
 * 结果页就是把生成好的缩略图铺出来给用户看一眼。
 *
 * 成本要写在脸上：每张约 376KB 流量（115 最小转码档的一个分片头），已经有的会跳过，
 * 所以重跑一轮只补缺的。**没转码的片子抓不到** —— 那种只会在"失败"里计数。
 */
@Composable
fun PreviewPanel(
    request: PreviewRequest,
    builder: PreviewBuilder,
    store: VideoPreviewStore,
    onDismiss: () -> Unit,
) {
    val progress by builder.progress.collectAsState()
    /** 缓存占用（每轮跑完/清空后重算一次，不用实时订阅） */
    var cacheBytes by remember { mutableStateOf(0L) }
    var recursive by remember { mutableStateOf(true) }

    // 占用只在"进面板"和"跑完"这两个时刻算（列一次目录）；跑的过程中用引擎累加的字节数，
    // 别每生成一张就 listFiles 一遍（那是在主线程上做 IO）
    LaunchedEffect(progress.finishedAt, progress.running) {
        if (!progress.running) cacheBytes = store.sizeBytes()
    }

    BackHandler { onDismiss() }

    // 实底：壁纸激活时半透明 surface 会透出底下的列表
    Surface(Modifier.fillMaxSize(), color = AppColors.Card) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "视频预览图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                if (!progress.running && progress.summary != null) {
                    TextButton(onClick = { builder.clearResult() }) { Text("再来一轮") }
                }
            }

            when {
                progress.running -> RunningBody(progress, onStop = { builder.stop() })

                progress.summary != null -> ResultBody(
                    progress = progress,
                    store = store,
                    cacheBytes = cacheBytes,
                    onClearCache = {
                        store.clear()
                        cacheBytes = store.sizeBytes()
                    },
                )

                else -> StartBody(
                    request = request,
                    recursive = recursive,
                    onRecursive = { recursive = it },
                    cacheBytes = cacheBytes,
                    onStart = { builder.start(request.cid, request.path, recursive) },
                    onClearCache = {
                        store.clear()
                        cacheBytes = store.sizeBytes()
                    },
                )
            }
        }
    }
}

@Composable
private fun StartBody(
    request: PreviewRequest,
    recursive: Boolean,
    onRecursive: (Boolean) -> Unit,
    cacheBytes: Long,
    onStart: () -> Unit,
    onClearCache: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "给「${request.path}」里的视频抓帧：一张封面 + 一条宽幅预览条",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "抓的是 115 最小转码档（HLS）里的关键帧，不下载整片。封面取片长的 30%（避开片头黑场）；" +
                "预览条按片长均分取样，5 分钟一片、最多 10 张（1 分钟的短片也有 2 张）。" +
                "两个都用同几条分片的头部，所以条上的帧几乎是白送的。\n" +
                "代价：每个取样点约 376KB 流量 + 1~2 秒，同一分片上的取样点只拉一次。图存在本机，" +
                "文件列表的视频行下面就显示这条预览条，以后翻列表零请求。\n\n" +
                "已经生成过、帧数也对得上的会跳过，所以重复跑只补缺的。云端还没转码的片子抓不到" +
                "（只计入失败），在播放器里长按截图键可以手工给某一条设封面。",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // onClick 必须显式传：AppChip 的尾随 lambda 绑的是 leading
            AppChip(label = "仅当前目录", selected = !recursive, onClick = { onRecursive(false) })
            Spacer(Modifier.width(8.dp))
            AppChip(label = "含所有子目录", selected = recursive, onClick = { onRecursive(true) })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (recursive) "子目录要一层层列，目录多时会慢（有防频控的限速），过程中可以停。"
            else "只处理这一层的视频。",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStart, enabled = request.cid.isNotBlank()) { Text("开始生成") }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已缓存 ${Format.size(cacheBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
            )
            if (cacheBytes > 0) {
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onClearCache) { Text("清空缓存") }
            }
        }
    }
}

@Composable
private fun RunningBody(progress: PreviewBuilder.Progress, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(36.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            "正在处理「${progress.rootPath}」",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            "已列 ${progress.scannedDirs} 个目录 · 视频 ${progress.seenVideos} 个\n" +
                "新生成 ${progress.generated}（${Format.size(progress.bytesWritten)}）· " +
                "跳过已有 ${progress.skipped} · 抓不到 ${progress.failed}",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onStop) { Text("停止") }
    }
}

@Composable
private fun ResultBody(
    progress: PreviewBuilder.Progress,
    store: VideoPreviewStore,
    cacheBytes: Long,
    onClearCache: () -> Unit,
) {
    val withPreview = remember(progress.finishedAt) { progress.videos.filter { it.pc?.let(store::has) == true } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                progress.summary ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本机预览图缓存 ${Format.size(cacheBytes)} · 这套缓存可随时清空（下次再看会重新抓）",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClearCache) { Text("清空") }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (withPreview.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "这一层没有可用的预览图",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(withPreview, key = { it.fid ?: it.fn }) { item -> PreviewCard(item, store) }
            }
        }
    }
}

/** 结果卡片：直接把缓存文件喂给 Coil（零请求），下边压一行文件名 */
@Composable
private fun PreviewCard(item: FileItem, store: VideoPreviewStore) {
    val pc = item.pc ?: return
    val file = remember(pc) { store.fileFor(pc) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey("vpreview|$pc|${file.lastModified()}")
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.fn,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 底下把预览条也铺出来：结果页一眼能看出"帧数对不对"（没有就是这条只有封面）
        VideoStrip(pc, tileWidth = 96.dp, tileHeight = 54.dp, topPadding = 4.dp)
    }
}
