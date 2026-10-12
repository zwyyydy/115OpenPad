package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.SCAN_MODE_PIPELINE
import com.open115.pad.data.media.ScanLogEntity
import com.open115.pad.data.media.ScanReport
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.ui.components.GlassCard
import com.open115.pad.ui.components.MetaChip
import com.open115.pad.ui.components.auroraBackdrop
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.util.Format
import kotlinx.coroutines.launch
import com.open115.pad.ui.components.FadeAlertDialog

/**
 * 扫描记录：**什么时候、扫的哪个库、结果如何、新增了哪些片**。
 *
 * 放在媒体库这条线上（顶栏图标进、浮层显示），而不是文件页那份「操作记录」里：
 * 那边是文件操作的流水（复制/移动/上传…），而这里要带**海报图**展示新增影片 —— 那是媒体库的事。
 *
 * 数据是 `scan_log` 表；新增影片只存了键，海报与标题在这里 join `movies` 现取
 * （库里改了名换了海报，旧记录也跟着变），点一张就进详情页。
 *
 * 排版上与库列表页**共用同一套**（[AdaptiveBody] 收窄 + [GlassCard] + [MetaChip]）：
 * 这一页原来是通栏铺满整屏的（宽屏上库名在最左、时间在最右，中间空掉一大片），卡片也是
 * 另一套"35% 灰底方角块"—— 同一产品的两个页面看着像两个应用。
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

    // 系统返回键 = 出栈回媒体库页（与详情/作品页一致）。没有这一句时 BACK 会穿透到
    // 应用级导航、直接跳到「文件」页 —— 实测从「查看全部」进来一按返回就莫名其妙离开了媒体库。
    androidx.activity.compose.BackHandler { onBack() }

    // 所有记录里出现过的键 → 卡片，一次批量查完（几百条记录也就几百个键，见 dao.movieCardsByKeys 的分块）
    val cardsByKey by produceState<Map<String, MovieCard>>(emptyMap(), logs) {
        val keys = logs.flatMap { it.newKeyList }.distinct()
        value = runCatching { dao.movieCardsByKeys(keys).associateBy { it.mediaKey } }.getOrDefault(emptyMap())
    }

    Scaffold(
        // 背景交给下面的极光底（与库列表页/观影历史同一套）；不透明底色会把三层的关系遮断
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scrim ->
    Box(Modifier.fillMaxSize().auroraBackdrop()) {
    AdaptiveBody(modifier = Modifier.fillMaxSize()) {
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
    }
    }

    if (confirmClear) {
        FadeAlertDialog(
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
 * 一条记录：库名 + 结果徽标 + 相对时间，第二行是结果胶囊（[ScanResultChips]），
 * 下面接新增影片的海报横排。
 *
 * 卡片整体**不可点**（只有海报可点）—— 这里没有"点进一条记录"的详情页，给整张卡挂上
 * 点击只会让按下时缩放一下却什么都不发生。
 */
@Composable
private fun ScanLogCard(
    log: ScanLogEntity,
    cards: List<MovieCard>,
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
) {
    val report = remember(log) { log.toReport() }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        // 记录多的时候一屏好几十张卡，光晕会叠得很重：比库卡收敛一点
        elevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.libraryName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                // 结果徽标跟着库名（与库卡上「N 部」同一个位置）：这轮扫出什么，扫一眼就知道
                ResultBadge(report)
                Spacer(Modifier.weight(1f))
                Text(
                    Format.ago(log.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            ScanResultChips(report)

            if (cards.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("新增影片", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                // 海报横排：一次新增几十部时不至于把一屏撑爆（要横向滑）
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexedCards(cards) { index, card ->
                        NewMovieTile(card) { onOpenMovie(card, cards, index) }
                    }
                }
            } else if (report.aborted) {
                // "没有新增"已经由徽标说了，这里只补一句**为什么**没有 —— 中断和扫完了没新增
                // 是两回事，前者要再扫一次
                Spacer(Modifier.height(8.dp))
                MetaChip(
                    if (report.stopped) "这次没跑到索引阶段（可以再扫一次）" else "列目录中断，可以再扫一次",
                    tint = AppColors.AmberFg,
                )
            }
        }
    }
}

/** 结果徽标：停/中断 / 新增 N 部 / 无新增 —— 一条记录的结论，放库名旁边 */
@Composable
private fun ResultBadge(report: ScanReport) {
    when {
        report.aborted -> MetaChip(
            if (report.stopped) "已停止" else "未完成",
            tint = AppColors.AmberFg,
            bold = true,
        )
        report.newCount > 0 -> MetaChip("新增 ${report.newCount} 部", tint = AppColors.GreenFg, bold = true)
        else -> MetaChip("无新增")
    }
}

/**
 * 结果明细：一条事实一枚小胶囊（与库卡上的元信息同一套）。
 *
 * 早先是 `report.summary()` 拼出来的一整行灰字：「全量 · 新增 7 部 · 目录 7/7 · 索引 7 项 ·
 * 缓存海报 7 张 · 列目录 7 次 · 用时 00:10」—— 同灰度同字号，扫读要逐字读。
 * 拆开之后每一项都能一眼定位，`新增`已经由 [ResultBadge] 承担，这里不再重复。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanResultChips(report: ScanReport) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 模式胶囊：「流水线同步」是服务器刮削搬进来的（不是用户自己点的扫描），
        // 用主色点一下眼，扫记录时一眼能分出这批是"同步来的"
        if (report.mode.isNotBlank()) {
            MetaChip(
                report.mode,
                tint = if (report.mode == SCAN_MODE_PIPELINE) AppColors.Accent else null,
            )
        }
        if (report.stopped && !report.aborted) MetaChip("已停止", tint = AppColors.AmberFg)
        if (!report.aborted) {
            MetaChip("目录 ${report.doneDirs}/${report.totalDirs}")
            if (report.skippedDirs > 0) MetaChip("跳过 ${report.skippedDirs}")
            MetaChip("索引 ${report.indexed} 项")
            if (report.postersFetched > 0) MetaChip("缓存海报 ${report.postersFetched} 张")
        }
        if (report.listRequests > 0) MetaChip("列目录 ${report.listRequests} 次")
        MetaChip("用时 ${Format.duration(report.elapsedMs / 1000).ifEmpty { "0:00" }}")
        // 绝对时间：卡片右上角是"20 分钟前"，隔天回看时要有个准数
        MetaChip(report.finishedText())
    }
}

/** 只是给上面的 LazyRow 收一下签名（itemsIndexed + 稳定 key，滚动时不闪） */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCards(
    cards: List<MovieCard>,
    content: @Composable (Int, MovieCard) -> Unit,
) = items(cards.size, key = { cards[it].mediaKey }) { index -> content(index, cards[index]) }

/** 新增影片的一块：2:3 海报 + 两行标题；没有海报时画个占位图标（不留白块） */
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
                .clip(RoundedCornerShape(12.dp))
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
            // 与库列表的两条横排对齐：标题恒两行，整排卡片的下沿才是齐的
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
