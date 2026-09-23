package com.open115.pad.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieDeleteResult
import com.open115.pad.data.media.deleteMovie
import com.open115.pad.ui.theme.AdaptiveBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 海报墙：一个媒体库的海报网格。
 *
 * 海报/背景图只有 pick_code，加载时要经 ImageUrlResolver 走 downurl 换直链
 * （115 不给固定图床地址），解析结果有 LRU 缓存，翻页回来不会重复请求。
 */
@Composable
fun PosterWallScreen(
    library: MediaLibraryEntity,
    dao: MediaDao,
    /** 外部刷新信号（详情页删完会把它 +1）：墙在详情浮层下面没被销毁，只靠自己删时加的 tick 不够 */
    refreshKey: Int = 0,
    /** 本页删完通知外面 —— 外面再加 refreshKey，避免两处各存一份状态 */
    onChanged: () -> Unit = {},
    onBack: () -> Unit,
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as com.open115.pad.App115).container
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<MovieCard?>(null) }

    // 多根库：任一路径匹配即纳入（byLibraryPaths 支持 5 根，更多时逐根查询合并去重）
    val all by produceState(initialValue = emptyList(), library.rootPaths, refreshKey) {
        val paths = library.rootPaths
        value = if (paths.size <= 5) {
            val p = paths + List(5 - paths.size) { "" }
            dao.byLibraryPaths(p[0], p[1], p[2], p[3], p[4]).first()
        } else {
            paths.flatMap { dao.byLibraryPath(it).first() }.distinctBy { it.mediaKey }
        }
    }
    var query by remember { mutableStateOf("") }
    // 标题搜索是全表查询，结果再按本库的 mediaKey 收敛，避免搜出别的库的影片
    val hits by produceState<List<MovieCard>?>(initialValue = null, query, all) {
        value = if (query.isBlank()) {
            null
        } else {
            val keys = all.mapTo(HashSet()) { it.mediaKey }
            dao.searchByTitle(query, 100).filter { it.mediaKey in keys }
        }
    }
    val list = hits ?: all

    // 系统返回键 = 逐层退：有搜索词先清搜索，否则退回媒体库列表
    androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) { query = "" }
    androidx.activity.compose.BackHandler(enabled = query.isBlank()) { onBack() }

    // 不透明底：这两级"页"是浮在媒体库列表页之上的浮层，没有底色会把下层透出来
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        AdaptiveBody(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 顶部工作栏：返回键 + 标题弹性占位 + 搜索框收拢右上
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(library.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${library.rootPath} · ${list.size} 部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("在系列内搜索…") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        // 轻量搜索框：宽屏下收拢在右上，不横拉整行
                        modifier = Modifier
                            .widthIn(min = 180.dp, max = 320.dp)
                            .padding(end = 8.dp)
                            .height(52.dp),
                    )
                }

                if (list.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (query.isBlank()) "这个库还没有影片，回列表点扫描" else "没有匹配「$query」的影片",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // 全屏自适应网格：手机 2~3 列 / 平板 4~5 列 / 2K/4K 大屏 6~8 列，
                    // 列宽由系统按 minSize 均分铺满，横向拉伸只等比放大或增列，不留死白
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.mediaKey }) { card ->
                            PosterCard(
                                card = card,
                                onClick = { onOpenMovie(card, list, list.indexOf(card)) },
                                onLongClick = { deleteTarget = card },
                            )
                        }
                    }
                }
            }
        }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
      }
    }

    deleteTarget?.let { target ->
        MovieDeleteDialog(
            name = target.title,
            // 只有系列卡才点明"连同名下的 N 集"：卡片的 isEpisodeLike 在系列卡上是 false，
            // 而它的分集要靠查询才知道数量，这里先按"是不是分集"粗略区分
            episodeCount = 0,
            onDismiss = { deleteTarget = null },
            onDelete = { alsoCloud ->
                deleteTarget = null
                scope.launch {
                    val r = deleteMovie(
                        api = container.openApi,
                        dao = dao,
                        mediaCache = container.mediaCache,
                        imageUrlResolver = container.imageUrlResolver,
                        cacheDir = container.cacheDir,
                        mediaKey = target.mediaKey,
                        alsoCloud = alsoCloud,
                    )
                    when (r) {
                        MovieDeleteResult.Ok -> onChanged()
                        is MovieDeleteResult.CloudFailed -> snackbarHostState.showSnackbar(r.message)
                        MovieDeleteResult.NoCloudId -> snackbarHostState.showSnackbar(
                            "这条索引是升级前的旧数据，没存云盘文件 id；重扫一次这个库再删（云端文件一个没动）",
                        )
                    }
                }
            },
        )
    }
}

/**
 * 海报卡片（自适应规范）：
 * - 宽度继承网格单元格：fillMaxWidth()，严禁硬编码宽高
 * - 高度按 2:3 黄金比例由列宽自动推导：fillMaxWidth().aspectRatio(2f / 3f)
 * - 下置式信息区（标题加粗 + 年份·分类），替代海报上的黑蒙层
 * - 浮动元数据微标：右上 ★ 评分 / 左下画质（4K HDR、1080P…），wrapContentSize 自包覆
 * - 图片加载 Shimmer 骨架占位
 * - 长按弹删除菜单（[onLongClick]）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PosterCard(card: MovieCard, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 长按：用 combinedClickable 而不是叠加一个 clickable，否则两个手势会互相吃掉
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (card.posterPickCode.isNullOrBlank()) {
                // 无海报兜底：浅灰底 + 图标，不留空白
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PickCodeImage(
                    pickCode = card.posterPickCode,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 浮动元数据微标：右上磨砂半透明胶囊 ★ 评分（自包覆，不设固定容器）
            card.rating?.let { rating ->
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Text(
                        "★ ${"%.1f".format(rating)}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            // 左下画质微标：从主视频文件名启发式推导
            qualityBadgeOf(card.videoName)?.let { q ->
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                ) {
                    Text(
                        q,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        // 下置式信息区：海报图下方相对间距排布，不压黑蒙层
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp)) {
            Text(
                card.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                card.year?.let { append(it) }
                if (card.genre?.isNotBlank() == true) {
                    if (isNotEmpty()) append(" · ")
                    append(card.genre)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 画质微标：文件名启发式（4K/2160/HDR/1080/720…），推不出就返回 null 不显示 */
private fun qualityBadgeOf(videoName: String?): String? {
    if (videoName.isNullOrBlank()) return null
    val n = videoName.lowercase()
    val res = when {
        "2160" in n || "4k" in n || "uhd" in n -> "4K"
        "1080" in n -> "1080P"
        "720" in n -> "720P"
        else -> null
    } ?: return null
    val hdr = when {
        "dolby" in n && "vision" in n -> " Dolby Vision"
        "hdr10+" in n || "hdr10plus" in n -> " HDR10+"
        "hdr" in n -> " HDR"
        else -> ""
    }
    return res + hdr
}

/**
 * pick_code → 本地文件 / 直链 → Coil 加载。
 *
 * 命中本地落盘时**不解析直链**（见 [com.open115.pad.data.ImageUrlResolver.posterFor]）：
 * 海报墙全命中时零接口调用。解析失败/无图时留空（卡片底层已有图标兜底），绝不白屏崩溃。
 */
@Composable
fun PickCodeImage(
    pickCode: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as com.open115.pad.App115).container
    val model by produceState<Any?>(initialValue = null, pickCode) {
        value = container.imageUrlResolver.posterFor(pickCode, container.cacheDir)
    }
    if (model != null) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
            loading = { ShimmerPlaceholder(Modifier.fillMaxSize()) },
            error = {},
        )
    } else {
        Spacer(modifier)
    }
}

/** Shimmer 骨架占位：斜向高光扫过，海报/背景图加载期间显示 */
@Composable
fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                    start = Offset(x, 0f),
                    end = Offset(x + 400f, 400f),
                ),
            ),
        )
    }
}
