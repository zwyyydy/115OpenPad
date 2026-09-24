package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.WorksSort
import com.open115.pad.data.media.WorksFilter
import com.open115.pad.data.media.facetsOf
import com.open115.pad.data.media.CardFacets
import com.open115.pad.data.media.cardFacetsOf
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieDeleteResult
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.data.media.deleteMovie
import com.open115.pad.ui.components.dissolve
import com.open115.pad.ui.theme.AdaptiveBody
import kotlinx.coroutines.launch

/** 点演员还是点标签 —— 决定这个页面查哪张表 */
enum class WorksKind { Actor, Tag }

/**
 * 同一个演员 / 同一个标签的**全部作品**（**跨所有媒体库**）。
 *
 * 从详情页点演员或标签进来。列表口径与海报墙一致：只出顶层条目（影片 / 系列卡），
 * 分集归到系列卡里选集 —— 所以这里点一部剧出来的是那张系列卡，不是某一集。
 *
 * 这一页也允许再点进详情页、再从那里点别的演员/标签进来（浮层是叠起来的，
 * 见 MediaLibraryScreen 的浮层栈），所以 [onOpenMovie] 要能把当前列表整份交出去
 * 当播放列表（播完一部自动接下一部，在"同演员的作品"里连续看就是这个意思）。
 */
@Composable
fun MediaWorksScreen(
    kind: WorksKind,
    name: String,
    dao: MediaDao,
    /** 外部刷新信号（详情页删完会把它 +1）：这一页在详情浮层下面没被销毁，得靠它重查 */
    refreshKey: Int = 0,
    /** 这一页自己长按删掉一部之后通知外面（外面 +refreshKey，三处浮层一起刷新） */
    onChanged: () -> Unit = {},
    onBack: () -> Unit,
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as com.open115.pad.App115).container
    val snackbarHostState = remember { SnackbarHostState() }
    // 排序方式（表头那个菜单选的）：存设置里，跨页面/重启都记得
    val sort by container.mediaPrefs.worksSort.collectAsState(initial = WorksSort.DEFAULT)
    val cards by produceState<List<MovieCard>>(emptyList(), kind, name, refreshKey, sort) {
        value = when (kind) {
            WorksKind.Actor -> dao.byActor(name, sort.sql)
            WorksKind.Tag -> dao.byTag(name, sort.sql)
        }
    }
    var deleteTarget by remember { mutableStateOf<MovieCard?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    // 筛选（年份/类型·标签/演员，多选）：选项从"这一页实际有的"现算，纯本地过滤
    var filter by remember { mutableStateOf(WorksFilter()) }
    var filterDialog by remember { mutableStateOf(false) }
    // 标签与演员都在关联表里，MovieCard 上没有 —— 一次查完整批建映射（不是每部片查一次）
    val extraOfKey by produceState<Map<String, CardFacets>>(emptyMap(), cards) {
        value = cardFacetsOf(dao, cards.map { it.mediaKey })
    }
    fun extraOf(card: MovieCard): CardFacets = extraOfKey[card.mediaKey] ?: CardFacets.Empty
    val facets = remember(cards, extraOfKey) { facetsOf(cards) { extraOf(it) } }
    val visible = remember(cards, filter, extraOfKey) {
        if (filter.isEmpty) cards else cards.filter { filter.matches(it, extraOf(it)) }
    }

    androidx.activity.compose.BackHandler { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { padding ->
        // 底色不透明：这是浮在媒体库列表页之上的浮层，没有底色会把下层透出来
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 背景铺满整屏（同海报墙）：拿第一部作品的背景图垫底，页面不至于是纯色一片。
            // ☠ 必须在 AdaptiveBody **之外**，否则会被它的 960dp 居中容器框成中间一条。
            val backdrop = cards.firstOrNull()
                ?.let { backgroundSourceOf(it.fanartPickCode, it.extraFanartPickCodes) }
            PickCodeImage(
                pickCode = backdrop,
                modifier = Modifier.fillMaxSize().dissolve(0.45f, 1f),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.62f),
                        0.45f to Color.Black.copy(alpha = 0.55f),
                        1.00f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
            )

            AdaptiveBody(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                        Column(Modifier.padding(start = 4.dp).weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // "全部媒体库"是刻意的：点进来的结果不限于当前这个库
                                (if (kind == WorksKind.Actor) "演员" else "标签") +
                                    " · ${visible.size} 部作品 · 全部媒体库 · ${sort.label}" +
                                    if (filter.isEmpty) "" else " · 已筛选 ${filter.selectedCount} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        FilterButton(filter) { filterDialog = true }
                        // 排序菜单：选完立刻重查（sort 进了 produceState 的 key）
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(
                                    Icons.Outlined.Sort,
                                    contentDescription = "排序方式",
                                    tint = Color.White,
                                )
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
                    }

                    if (visible.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                if (filter.isEmpty) {
                                    "没有找到${if (kind == WorksKind.Actor) "这个演员" else "这个标签"}的其他作品"
                                } else {
                                    "当前筛选条件下没有作品（勾了 ${filter.selectedCount} 项）"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 130.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.mediaKey }) { card ->
                                PosterCard(
                                    card = card,
                                    onClick = { onOpenMovie(card, visible, visible.indexOf(card)) },
                                    onLongClick = { deleteTarget = card },
                                )
                            }
                        }
                    }
                }
            }
        }
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
