package com.open115.pad.ui.media

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.data.media.WorksSort
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.WorksFilter
import com.open115.pad.data.media.facetsOf
import com.open115.pad.data.media.CardFacets
import com.open115.pad.data.media.cardFacetsOf
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieDeleteResult
import com.open115.pad.data.media.deleteMovie
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.components.dissolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 背景轮播节奏：20 秒换一张。再快晃眼，再慢看不出在换 */
private const val BACKDROP_ROTATE_MS = 20_000L

/**
 * 一次会话里最多轮几张背景。
 *
 * 每张没缓存过的 fanart 都要一次 downurl + 一次下载，而这是**无人值守**在换的：
 * 一个 200 部的库全轮一遍就是 200 次解析、几十 MB —— 用户只是把墙开着没动。
 * 所以每次进墙**随机取一段**来轮：既有"每次进来看到的不一样"，又把一次会话的额外请求
 * 钉在 20 次以内。第二轮回来时图已经在落盘缓存里，零请求。
 */
private const val BACKDROP_MAX = 20

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
    // 手机（窄屏）排版：顶栏一行放不下「标题列 + 3 个图标 + 搜索框」，把搜索框换到第二行；
    // 海报网格从 2 列调到 3 列。平板（>=600dp）保持原样
    val compact = LocalConfiguration.current.screenWidthDp < 600

    // 多根库：任一路径匹配即纳入（byLibraryPaths 支持 5 根，更多时逐根查询合并去重）
    // 排序方式（表头那个菜单选的）：与作品页共用同一个设置（存 MediaPrefs）
    val sort by container.mediaPrefs.worksSort.collectAsState(initial = WorksSort.DEFAULT)
    var sortMenu by remember { mutableStateOf(false) }
    val all by produceState(initialValue = emptyList(), library.rootPaths, refreshKey, sort) {
        val paths = library.rootPaths
        value = if (paths.size <= 5) {
            val p = paths + List(5 - paths.size) { "" }
            dao.byLibraryPaths(p[0], p[1], p[2], p[3], p[4], sort = sort.sql).first()
        } else {
            paths.flatMap { dao.byLibraryPath(it, sort = sort.sql).first() }
                .distinctBy { it.mediaKey }
                .let { list -> sortListInMemory(list, sort) }
        }
    }
    // 背景轮播用的图：本库顶层条目的 fanart（就是详情页那张背景图）
    val backdrops by produceState(initialValue = emptyList<String>(), library.rootPaths, refreshKey) {
        val paths = library.rootPaths
        value = if (paths.size <= 5) {
            val p = paths + List(5 - paths.size) { "" }
            dao.backdropsInPaths(p[0], p[1], p[2], p[3], p[4])
        } else {
            paths.flatMap { dao.backdropsInPath(it) }
        }
    }
    // 随机顺序 + 取一段（见 BACKDROP_MAX）；进一次墙洗一次牌
    val rotation = remember(backdrops) { backdrops.distinct().shuffled().take(BACKDROP_MAX) }
    var backdropIndex by remember(rotation) { mutableStateOf(0) }
    LaunchedEffect(rotation) {
        if (rotation.size <= 1) return@LaunchedEffect
        while (true) {
            delay(BACKDROP_ROTATE_MS)
            backdropIndex = (backdropIndex + 1) % rotation.size
        }
    }

    var query by remember { mutableStateOf("") }
    /**
     * 搜索结果 = **全局**：片名 / 演员 / 标签，跨所有媒体库（见 dao.searchAll）。
     *
     * 早先是"在本库内搜片名"（结果再按本库的 mediaKey 收敛）—— 演员和标签搜不到，
     * 别的库里的同演员作品也搜不到。现在搜出来的就是全库的，卡片、海报、点进去都一样。
     */
    val hits by produceState<List<MovieCard>?>(initialValue = null, query, all, sort) {
        value = if (query.isBlank()) null else dao.searchAll(query, 200, sort.sql)
    }
    val searching = !hits.isNullOrEmpty() || query.isNotBlank()
    // 筛选（年份/类型·标签/演员，多选）：选项从"这个库实际有的"现算，纯本地过滤
    var filter by remember { mutableStateOf(WorksFilter()) }
    var filterDialog by remember { mutableStateOf(false) }
    // 标签与演员都在关联表里，MovieCard 上没有 —— 一次查完整批建映射（不是每部片查一次）
    val extraOfKey by produceState<Map<String, CardFacets>>(emptyMap(), all, hits) {
        val keys = (all.map { it.mediaKey } + hits.orEmpty().map { it.mediaKey }).distinct()
        value = cardFacetsOf(dao, keys)
    }
    fun extraOf(card: MovieCard): CardFacets = extraOfKey[card.mediaKey] ?: CardFacets.Empty
    val facets = remember(all, extraOfKey) { facetsOf(all) { extraOf(it) } }

    val list = (hits ?: all).let { base ->
        if (filter.isEmpty) base else base.filter { filter.matches(it, extraOf(it)) }
    }

    // 系统返回键 = 逐层退：有搜索词先清搜索，否则退回媒体库列表
    androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) { query = "" }
    androidx.activity.compose.BackHandler(enabled = query.isBlank()) { onBack() }

    // 底色仍是**不透明**的：这两级"页"是浮在媒体库列表页之上的浮层，没有底色会把下层透出来
    // （轮播图没加载出来、或者这个库一张 fanart 都没有时，就只剩这层底色）
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ① 背景轮播：铺满整个窗口。
        // ☠ 必须在 AdaptiveBody **之外** —— 它的内层是 960dp 居中容器，背景放进去会被框成中间一条，
        //    左右各留一条死黑（详情页踩过同一个坑，见 MediaDetailScreen 的注释）
        Crossfade(
            targetState = rotation.getOrNull(backdropIndex),
            animationSpec = tween(1600),
            label = "wallBackdrop",
        ) { pickCode ->
            if (pickCode != null) {
                // 下沿溶进底色，避免图的下边缘在屏幕上切出一条硬边
                PickCodeImage(
                    pickCode = pickCode,
                    modifier = Modifier.fillMaxSize().dissolve(0.45f, 1f),
                )
            }
        }
        // ② 压暗：上面是深色卡片 + 浅色文字，亮剧照不压暗会把卡片边界和标题一起糊掉
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.62f),
                    0.45f to Color.Black.copy(alpha = 0.55f),
                    1.00f to Color.Black.copy(alpha = 0.80f),
                ),
            ),
        )
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
                        Text(
                            if (query.isBlank()) library.name else "全局搜索「$query」",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (query.isBlank()) {
                                "${library.rootPath} · ${list.size} 部 · ${sort.label}" +
                                    if (filter.isEmpty) "" else " · 已筛选 ${filter.selectedCount} 项"
                            } else {
                                // 明说跨库：搜出来的结果不限于当前这个库
                                "片名 / 演员 / 标签 · ${list.size} 部 · 全部媒体库"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // ☠ 必须限一行：窄屏下标题列只剩 ~200dp，这行副标题（二十来字）
                            // 不限行就会逐字换行撑成几十行，整个顶栏被顶到屏幕中部、
                            // 网格跟着掉下去（手机上实测到的排版事故）
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilterButton(filter) { filterDialog = true }
                    // 随机播放：从当前可见的卡片（已套搜索与筛选）里随机挑一部直接开播
                    IconButton(onClick = {
                        scope.launch { playRandomMovie(context, dao, list, snackbarHostState) }
                    }) {
                        Icon(Icons.Outlined.Shuffle, contentDescription = "随机播放")
                    }
                    // 排序菜单：与作品页同一个设置，选完立刻重排（sort 进了 produceState 的 key）
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Outlined.Sort, contentDescription = "排序方式")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            WorksSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        if (option == sort) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortMenu = false
                                        scope.launch { container.mediaPrefs.setWorksSort(option) }
                                    },
                                )
                            }
                        }
                    }
                    // 毛玻璃搜索框：背后是背景轮播那张图（模糊副本 + 半透明白边）。
                    // 窄屏放这一行的末尾会把图标挤出去，改到下面独立一行（见 compact 分支）
                    if (!compact) {
                        FrostedSearchField(
                            value = query,
                            onValueChange = { query = it },
                            backdrop = rotation.getOrNull(backdropIndex),
                            modifier = Modifier
                                .widthIn(min = 200.dp, max = 360.dp)
                                .padding(end = 8.dp),
                        )
                    }
                }
                // 手机：搜索框独占第二行（整行宽度，输入时也比一行里的半截胶囊好用）
                if (compact) {
                    FrostedSearchField(
                        value = query,
                        onValueChange = { query = it },
                        backdrop = rotation.getOrNull(backdropIndex),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }

                if (list.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (query.isBlank()) {
                                "这个库还没有影片，回列表点扫描"
                            } else {
                                "全部媒体库里都没有匹配「$query」的片名 / 演员 / 标签"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // 全屏自适应网格：手机 3 列（minSize 调小）/ 平板 4~5 列 / 2K/4K 大屏 6~8 列，
                    // 列宽由系统按 minSize 均分铺满，横向拉伸只等比放大或增列，不留死白
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (compact) 110.dp else 130.dp),
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

    if (filterDialog) {
        WorksFilterDialog(
            facets = facets,
            filter = filter,
            onChange = { filter = it },
            onDismiss = { filterDialog = false },
        )
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
            // 浅灰底 + 图标：海报没加载出来时垫在下面，不留空白。
            // 有海报的片图上来了就盖住它；没海报的片，如果详情页已经裁好兜底海报
            // （背景图右半边）也会盖住它 —— 所以这里不再按"有没有 pick_code"分流
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
            // cropIfMissing = false：墙上**只认已经裁好的兜底海报**，不在这里下载/现裁
            PosterImage(
                posterPickCode = card.posterPickCode,
                backgroundPickCode = backgroundSourceOf(card.fanartPickCode, card.extraFanartPickCodes),
                modifier = Modifier.fillMaxSize(),
            )
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

/**
 * 海报（竖图），**没有海报时用背景图裁一张兜底**。
 *
 * 兜底图 = 背景图的右半部分（16:9 取右半边 ≈ 0.89 的竖图）。裁切只在**详情页**里做
 * （那时背景图的字节正在取/已经在本地，见 [com.open115.pad.data.ImageUrlResolver.ensureCroppedPoster]），
 * 所以两种调用方式：
 *  - 详情页传 [cropIfMissing] = true：进页面顺手把兜底海报裁出来（**零额外请求**，
 *    复用背景图那一份字节）
 *  - 海报墙用默认的 false：**只查裁好的文件在不在**（纯文件判断，零请求），
 *    没裁过就还是占位图标 —— 墙上有几十张卡，每张都去下载/现裁一张图是不行的
 */
@Composable
fun PosterImage(
    posterPickCode: String?,
    backgroundPickCode: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cropIfMissing: Boolean = false,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as com.open115.pad.App115).container
    // 版本号进 key：详情页刚裁出一张兜底海报时，墙上那些**已经渲染过、判定为"没有"**的卡片
    // 才会重查一遍（否则从详情页返回时组合不会重跑，卡片一直停在占位图标）
    val derivedVersion by container.imageUrlResolver.derivedPosterVersion.collectAsState()
    val model by produceState<Any?>(null, posterPickCode, backgroundPickCode, cropIfMissing, derivedVersion) {
        val resolver = container.imageUrlResolver
        val poster = posterPickCode?.takeIf { it.isNotBlank() }
            ?.let { resolver.posterFor(it, container.cacheDir) }
        value = poster ?: if (cropIfMissing) {
            resolver.ensureCroppedPoster(backgroundPickCode, container.cacheDir)
        } else {
            resolver.croppedPosterIfCached(backgroundPickCode, container.cacheDir)
        }
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

/**
 * 毛玻璃搜索框：半透明胶囊 + 细白边 + **真正的高斯模糊底**。
 *
 * 模糊底的做法：把**背景图整屏再画一份**放进这个胶囊里，按自己相对屏幕的位置反向偏移，
 * 于是它显示出来的那块正好是"身后那张图"，糊掉之后就是毛玻璃。
 * （Compose 没有"模糊身后内容"的 backdrop 滤镜，`Modifier.blur` 模糊的是**自己**，
 *   所以只能把背景复制一份再裁。）
 *
 * 偏移用 `Modifier.offset { }` 的 lambda 版本：位置变化只在布局/绘制阶段生效，不触发重组。
 * API < 31 时 `Modifier.blur` 是空操作 → 退化成"半透明深色胶囊"，依然能用。
 *
 * 用 BasicTextField 而不是 OutlinedTextField：后者的容器/描边颜色是主题给的，
 * 要盖成玻璃得把一整套 colors 都改掉，反而更绕。
 */
@Composable
internal fun FrostedSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    /** 背后那张图（与墙上轮播用同一张，位置才对得上） */
    backdrop: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val screen = LocalConfiguration.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            // 玻璃边缘的一圈高光：没有它，深色背景上的胶囊边界会糊在一起
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .onGloballyPositioned { origin = it.positionInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        // ① 模糊的背景副本（整屏尺寸，反向偏移到与真背景对齐；超出胶囊的部分被裁掉）
        if (backdrop != null) {
            PickCodeImage(
                pickCode = backdrop,
                modifier = Modifier
                    .size(screen.screenWidthDp.dp, screen.screenHeightDp.dp)
                    .offset { IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()) }
                    .blur(28.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            )
        }
        // ② 压暗 + 提亮：模糊图比周围亮，压一层黑再垫一点白，跟整体色调对得上
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.36f)))
        Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.06f)))

        // ③ 内容
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "搜索片名 / 演员 / 标签…",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "清空搜索",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onValueChange("") },
                )
            }
        }
    }
}

/**
 * 多根库（>5 根）时逐根查询再合并，SQL 里的 ORDER BY 管不到合并后的整体顺序 ——
 * 在内存里按同一套规则重排一次。只在"根数超过 5"这种少见的库上走到。
 *
 * 首映/入库用 SQL 那份一致的规则：没有值的沉底（`nullsLast` 的效果靠比较函数自己给）。
 */
private fun sortListInMemory(list: List<MovieCard>, sort: WorksSort): List<MovieCard> = when (sort) {
    WorksSort.Rating -> list.sortedWith(compareBy(nullsLast(reverseOrder())) { it.rating })
    WorksSort.RatingAsc -> list.sortedWith(compareBy(nullsLast()) { it.rating })
    // 首映/入库没有对应字段可排：保持库内顺序（各根内部已按 SQL 排好，升序降序都一样降级）
    WorksSort.Premiered, WorksSort.PremieredAsc, WorksSort.DateAdded, WorksSort.DateAddedAsc -> list
    WorksSort.Title -> list.sortedBy { it.title }
    WorksSort.TitleDesc -> list.sortedByDescending { it.title }
    WorksSort.Random -> list.shuffled()
}
