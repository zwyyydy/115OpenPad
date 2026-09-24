package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.data.media.ActorCard
import com.open115.pad.data.media.EpisodeEntity
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieEntity
import com.open115.pad.data.media.NfoMeta
import com.open115.pad.data.media.MovieDeleteResult
import com.open115.pad.data.media.deleteMovie
import com.open115.pad.data.media.episodeSortKey
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.dissolve
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.rememberTone
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private data class MediaDetailData(
    val movie: MovieEntity,
    val actors: List<ActorCard>,
    val tags: List<String>,
    val episodes: List<EpisodeEntity>,
    /**
     * 系列卡名下的分集（按集号排好）。非系列为空。
     *
     * 剧集包扫出来是"系列根一条 + 每季 N 条分集"，海报墙只显示系列卡，
     * 分集就在这里列出来选集 —— 所以详情页有两种分集来源：
     * 系列卡用 [seriesEpisodes]，番号式/多视频影片用它自己的 [episodes]。
     */
    val seriesEpisodes: List<MovieCard>,
    /**
     * 剧照（`extrafanart/fanart1.jpg…`）的 pick_code，已按自然序排好。
     * 只在扫描时记了 pick_code，**字节是进这个页面才开始缓存的**（见下面的 LaunchedEffect）。
     */
    val fanarts: List<String> = emptyList(),
    /**
     * nfo 的**完整解析结果**（`movies.nfoJson`）：时长/国家/语言/编剧/合集/技术参数/角色名…
     * 这些长尾字段不参与查询，所以整份存 JSON（见 MovieEntity.nfoJson），这里解出来展示。
     * 老库升级上来时是 null（只显示原有字段），重扫一次补上。
     */
    val nfo: NfoMeta? = null,
)

/**
 * 影片详情：fanart 铺底 + 海报/标题/评分/简介/演员/标签/剧集 + 直达播放。
 *
 * 播放列表取海报墙传来的整个库的卡片：播完一部自动接下一部（ PlayerActivity 自己管队列），
 * 剧集类资源则把当前影片的分集作为播放列表。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDetailScreen(
    card: MovieCard,
    playlist: List<MovieCard>,
    index: Int,
    dao: MediaDao,
    /** 删成功时通知外面刷新海报墙（墙在详情浮层下面没被销毁，不会自己发现数据变了） */
    onDeleted: () -> Unit = {},
    /**
     * 点演员 / 点标签：外面开一页"这个演员（标签）的全部作品"。
     * **跨所有媒体库** —— 演员本来就不属于某一个库。
     */
    onOpenWorks: (WorksKind, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val container = (context.applicationContext as com.open115.pad.App115).container
    var showDelete by remember { mutableStateOf(false) }

    val data by produceState<MediaDetailData?>(initialValue = null, card.mediaKey) {
        val movie = dao.movie(card.mediaKey)
        if (movie != null && com.open115.pad.data.media.MediaScanner.needsNfoRefetch(movie)) {
            // 扫描期 nfo 拉取失败的影片、以及**还没存完整解析结果的老数据**（nfoJson 为空）：
            // 进详情页按需重拉一次（自愈），然后再查库。判据与 refetchNfo 共用一份。
            com.open115.pad.data.media.MediaScanner.refetchNfo(
                container.openApi, container.okHttpClient, dao, container.mediaCache, card.mediaKey,
            )
        }
        val updated = dao.movie(card.mediaKey)
        value = if (updated == null) {
            null
        } else {
            MediaDetailData(
                movie = updated,
                actors = dao.actorCardsOf(card.mediaKey),
                tags = dao.tagsOf(card.mediaKey),
                episodes = dao.episodesOf(card.mediaKey),
                seriesEpisodes = dao.episodesOfSeries(card.mediaKey),
                fanarts = updated.extraFanartList,
                nfo = updated.nfoJson?.let {
                    runCatching { nfoJson.decodeFromString<NfoMeta>(it) }.getOrNull()
                },
            )
        }
    }

    /**
     * 剧照**进详情页就开始缓存**（用户要求的时机：扫描期不碰，点开才拉）。
     *
     * 两步走，顺序有意义：
     *  ① [ImageUrlResolver.prefetch] 一次 downurl 换回全部直链（pick_code 支持逗号分隔多个）——
     *     15 张图从 15 次 downurl 降到 1 次。downurl 是 115 最容易触发频控的接口，
     *     而媒体库扫描本身也在打它。
     *  ② 再逐张落盘（[ImageUrlResolver.posterFor]）。串行不并发：这是后台补图，
     *     不该跟播放/滚动抢带宽。
     *
     * 命中本地字节的图在第 ① 步之后就是**零请求**（posterFor 先查本地再解析直链）。
     * 页面退出时协程随 composition 取消，没拉完的下次进来接着拉。
     */
    LaunchedEffect(data?.fanarts) {
        val list = data?.fanarts ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val resolver = container.imageUrlResolver
        list.chunked(FANART_PREFETCH_BATCH).forEach { batch ->
            runCatching { resolver.prefetch(batch) }
            batch.forEach { runCatching { resolver.posterFor(it, container.cacheDir) } }
        }
    }

    /** 剧照全屏查看的当前页；null = 没在看 */
    var viewingFanart by remember { mutableStateOf<Int?>(null) }

    // 分集列表（显示与播放共用一份，**索引必须对齐**）：
    //  - 系列卡 → 它名下的分集行。按集号**数值**排：按标题字符串排会让 S01E2 排到 S01E10 后面
    //  - 番号式 / 多视频影片 → 它自己的 episodes 表行
    //
    // ★ 集号取自**文件名**而不是标题：刮削过的包标题是剧集名（示例剧集三那包是「示例剧集标题二」），
    //   里面没有 S01E07，拿它排序等于按中文标题乱排。文件名一定带集号（示例剧集三 - S01E07 - 第7集.mp4）。
    val epList: List<Pair<String, String?>> = remember(data) {
        val d = data ?: return@remember emptyList()
        if (d.seriesEpisodes.isNotEmpty()) {
            d.seriesEpisodes
                .sortedWith(
                    compareBy(
                        { episodeSortKey(it.videoName ?: it.title) },
                        { it.videoName ?: it.title },
                    ),
                )
                .map { it.title to it.videoPickCode }
        } else {
            d.episodes.map { it.episodeKey to it.videoPickCode }
        }
    }
    val epEntries = remember(epList) {
        epList.mapNotNull { (label, pc) -> pc?.let { PlaylistEntry(it, label) } }
    }
    // 库内播放列表（播完一部自动接下一部）
    val movieEntries = remember(playlist) {
        playlist.mapNotNull { c -> c.videoPickCode?.let { pc -> PlaylistEntry(pc, c.title) } }
    }

    fun play(pc: String, name: String, entries: List<PlaylistEntry>, at: Int) {
        context.startActivity(
            PlayerActivity.intent(
                context, pc, name, entries,
                at.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
                // 媒体库的播放要进「观影历史」（文件页的播放不记，那边看操作记录）
                recordHistory = true,
            ),
        )
    }

    /** 播第 i 集：用 pickCode 在播放列表里反查下标 —— 有集缺 pickCode 时下标会错位，不能直接用 i */
    fun playEpisode(i: Int) {
        val (label, pc) = epList.getOrNull(i) ?: return
        if (pc.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("这一集没有提取码，无法播放") }
            return
        }
        play(pc, label, epEntries, epEntries.indexOfFirst { it.pc == pc })
    }

    // 系统返回键 = 顶栏返回键：退回海报墙
    androidx.activity.compose.BackHandler { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 不透明黑底：详情页是浮在海报墙之上的浮层，fanart 没加载出来时不能透出下层
        containerColor = Color.Black,
    ) { padding ->
        // 沉浸式：**背景铺满整个窗口，内容收在居中窄栏里**
        //
        // ☠ 背景层必须在 AdaptiveBody **之外**。早先整块（含 fanart）都包在 AdaptiveBody 里，
        //    而它的内层是 `widthIn(max = 960.dp)` 的居中容器 —— 背景因此被框成"中间一条"，
        //    左右各留一条死黑、宽度也只有容器那么宽，看起来就是"背景只有半屏"。
        //
        // 底色从**海报**取色（取不到回落纯黑）：每部片因此有自己的颜色，
        // "示例影片"和"示例剧集"进详情页底色不一样。
        val posterModel by produceState<Any?>(initialValue = null, data?.movie?.posterPickCode) {
            value = container.imageUrlResolver.posterFor(data?.movie?.posterPickCode, container.cacheDir)
        }
        val tone = rememberTone(posterModel, fallback = Color.Black)
        // tone 本身已经 clamp 在 v ≤ 0.68，再压到约三成亮度才压得住白字
        val base = lerp(tone, Color.Black, 0.72f)

        Box(Modifier.fillMaxSize().background(base)) {
            // ① fanart 铺满；下沿用 dissolve 把**图自己的 alpha** 推到 0、溶进底色
            //    （不是"在图上盖一层渐变" —— 那会留一块比周围略深的矩形，见 Dissolve.kt）
            //    没有 fanart.jpg 的片用**第一张剧照**兜底：刮了 extrafanart 的目录往往没有单张背景图，
            //    空着就是整屏纯色，有图总比没有强。
            //
            // ★ 这一张同时也是**没有海报时那张兜底海报的来源**（裁它的右半部分，见下面的海报框）：
            //   所以先把它算出来存着，两处用同一个 pick_code，不会各取各的导致对不上。
            //   取法与海报墙共用 backgroundSourceOf（fanart 优先，没有就用第一张剧照）。
            val backgroundPick = backgroundSourceOf(
                data?.movie?.fanartPickCode,
                data?.movie?.extraFanartPickCodes,
            )
            PickCodeImage(
                pickCode = backgroundPick,
                modifier = Modifier.fillMaxSize().dissolve(0.45f, 1.0f),
            )
            // ② 水平渐变：文字都在左半区，左侧压暗保证可读，右侧透出剧照主体
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0.00f to base.copy(alpha = 0.88f),
                        0.45f to base.copy(alpha = 0.28f),
                        1.00f to Color.Transparent,
                    ),
                ),
            )

            val current = data
            if (current == null) {
                // 还没加载完或已被删除：转圈，删除了就一直转——由用户点返回
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                return@Scaffold
            }

            // 内容层：长文本在平板上不该横跨整屏，收进 960dp 居中栏；整页可滚
            AdaptiveBody(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            current.movie.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Color.White)
                        }
                    }

                    Row(Modifier.padding(top = 8.dp)) {
                        // 海报：竖版 2:3，宽度按可用宽度的比例约束（大屏不甩成半屏宽，窄屏不挤成一指宽），
                        // 高度由 2:3 自动推导，无硬编码宽高。
                        // 没有 poster 的片用背景图裁一张（cropIfMissing = true：进这个页面才裁，
                        // 复用背景图那份字节，零额外请求；裁好后海报墙下次也用它）
                        BoxWithConstraints(Modifier.fillMaxWidth(0.32f).widthIn(min = 110.dp, max = 180.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            ) {
                                PosterImage(
                                    posterPickCode = current.movie.posterPickCode,
                                    backgroundPickCode = backgroundPick,
                                    modifier = Modifier.fillMaxSize(),
                                    cropIfMissing = true,
                                )
                            }
                        }
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(
                                current.movie.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                current.movie.year?.let {
                                    Text("$it", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(10.dp))
                                }
                                current.movie.rating?.let {
                                    Icon(
                                        Icons.Outlined.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFC107),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text("%.1f".format(it), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            current.movie.genre?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                            }
                            // nfo 里的关键元信息（时长/首播/国家/语言/分级/合集），一行放不下就省略
                            val facts = listOfNotNull(
                                current.nfo?.runtimeText,
                                current.nfo?.premiered,
                                current.nfo?.countries?.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                                current.nfo?.languages?.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                                current.nfo?.contentRating,
                                current.nfo?.set?.takeIf { it.isNotBlank() }?.let { "合集：$it" },
                            )
                            if (facts.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    facts.joinToString(" · "),
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 标语（tagline）：单独一行、斜一点，它是"宣传语"不是元数据
                            current.nfo?.tagline?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    it,
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            val mainPc = current.movie.videoPickCode
                            // 系列卡自己没有视频文件，播放按钮落到第一集
                            val playFirstEpisode = mainPc.isNullOrBlank() && epList.isNotEmpty()
                            Button(
                                onClick = {
                                    when {
                                        playFirstEpisode -> playEpisode(0)
                                        mainPc.isNullOrBlank() ->
                                            scope.launch { snackbarHostState.showSnackbar("没有找到可播放的视频文件") }
                                        else -> play(mainPc, current.movie.videoName ?: current.movie.title, movieEntries, index)
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (playFirstEpisode) "播放第 1 集" else "播放")
                            }
                        }
                    }

                    current.movie.plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text("简介", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                    }

                    // nfo 里的长尾字段（时长/首播/国家/导演/编剧/合集/技术参数…）：有值的才列
                    current.nfo?.let { NfoFactsSection(it) }

                    if (current.actors.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("演员", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        // 刮到过头像（`.actors/` 目录，同名演员在别的影片里刮到过也算）就铺头像卡；
                        // 一个都没有时保持原来的药丸列表 —— 一排首字母圆底并不比药丸好读。
                        // 两种形态都**可点**：点开这个演员在**全部媒体库**里的作品
                        // nfo 里的角色名（`<actor><role>`）：名字对得上就显示"饰 XXX"
                        val roles = remember(current.nfo) { current.nfo?.actorPairs?.toMap().orEmpty() }
                        if (current.actors.any { !it.avatarPickCode.isNullOrBlank() }) {
                            ActorAvatarRow(current.actors, roles) { onOpenWorks(WorksKind.Actor, it) }
                        } else {
                            LabelFlow(
                                current.actors.map { a ->
                                    roles[a.name]?.takeIf { it.isNotBlank() }?.let { "${a.name} · $it" } ?: a.name
                                },
                            ) { label ->
                                // 药丸文案带了角色名，点出去查的是**演员名**（取 · 之前那段）
                                onOpenWorks(WorksKind.Actor, label.substringBefore(" · "))
                            }
                        }
                    }

                    // 剧照（extrafanart）：横排缩略图，点开全屏翻页
                    if (current.fanarts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "剧照（${current.fanarts.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(current.fanarts) { i, pc ->
                                Box(
                                    Modifier
                                        .width(196.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable { viewingFanart = i },
                                ) {
                                    PickCodeImage(pc, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }

                    if (current.tags.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("标签", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        LabelFlow(current.tags) { onOpenWorks(WorksKind.Tag, it) }
                    }

                    // 分集：系列卡列它名下的分集；番号式/多视频影片列它自己的 episodes
                    if (epList.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("分集（${epList.size}）", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        epList.forEachIndexed { i, (label, _) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { playEpisode(i) }) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = "播放", tint = Color.White)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        current.movie.dirPath ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }

    if (showDelete) {
        MovieDeleteDialog(
            name = data?.movie?.title ?: card.title,
            // 只有系列卡才点明"连同名下的 N 集"（seriesEpisodes 只对系列有值）
            episodeCount = data?.seriesEpisodes?.size ?: 0,
            onDismiss = { showDelete = false },
            onDelete = { alsoCloud ->
                showDelete = false
                scope.launch {
                    val r = deleteMovie(
                        api = container.openApi,
                        dao = dao,
                        mediaCache = container.mediaCache,
                        imageUrlResolver = container.imageUrlResolver,
                        cacheDir = container.cacheDir,
                        mediaKey = card.mediaKey,
                        alsoCloud = alsoCloud,
                    )
                    when (r) {
                        MovieDeleteResult.Ok -> {
                            onDeleted()
                            onBack()
                        }
                        is MovieDeleteResult.CloudFailed -> snackbarHostState.showSnackbar(r.message)
                        MovieDeleteResult.NoCloudId -> snackbarHostState.showSnackbar(
                            "这条索引是升级前的旧数据，没存云盘文件 id；重扫一次这个库再删（云端文件一个没动）",
                        )
                    }
                }
            },
        )
    }

    // 剧照全屏查看（Dialog 叠在最上层，自己处理返回键）
    val fanarts = data?.fanarts.orEmpty()
    val viewing = viewingFanart
    if (viewing != null && fanarts.isNotEmpty()) {
        FanartViewer(
            pickCodes = fanarts,
            initial = viewing,
            onDismiss = { viewingFanart = null },
        )
    }
}

/** 剧照预取每次批量解析多少条直链（一次 downurl 能带多个 pick_code；十几张一批就够） */
private const val FANART_PREFETCH_BATCH = 20

/**
 * 演员头像卡：圆形头像 + 名字。
 *
 * 头像是**按需加载**的（[PickCodeImage] → posterFor）：进入详情页时只有可见的那几张会去
 * 解析直链、下载落盘，滚到谁才拉谁。没有头像的（同名演员也没刮到）退回首字母圆底 ——
 * 不占位空白，也不假装有图。
 */
@Composable
private fun ActorAvatarRow(
    actors: List<ActorCard>,
    /** 演员 → 角色名（来自 nfo 的 `<actor><role>`），没有就不显示第二行 */
    roles: Map<String, String> = emptyMap(),
    onClick: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(actors, key = { it.name }) { actor ->
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick(actor.name) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (actor.avatarPickCode.isNullOrBlank()) {
                        Text(
                            actor.name.take(1),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        PickCodeImage(actor.avatarPickCode, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    actor.name,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                roles[actor.name]?.takeIf { it.isNotBlank() }?.let { role ->
                    Text(
                        role,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 剧照全屏查看：左右翻页、点任意处或返回键退出。
 *
 * 用 Dialog 而不是页面内浮层：海报墙那边本来就叠着详情页浮层，再叠一层要自己管返回栈与层级。
 * 图片 `Fit` 而不是 `Crop` —— 全屏看剧照时把画面裁掉一块是不能接受的。
 */
@Composable
private fun FanartViewer(pickCodes: List<String>, initial: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(
        initialPage = initial.coerceIn(0, (pickCodes.size - 1).coerceAtLeast(0)),
    ) { pickCodes.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                // 每页自己也吃掉点击：pager 只消费拖拽，点击会冒泡到外层那个 dismiss
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                ) {
                    // getOrNull：数据若在看图期间刷新（列表变短），page 可能越界，
                    // 直接下标会崩 —— 翻页看剧照崩掉是最没必要的一种崩
                    pickCodes.getOrNull(page)?.let { pc ->
                        PickCodeImage(
                            pickCode = pc,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 56.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Text(
                    "${pager.currentPage + 1} / ${pickCodes.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 演员/标签小药丸：自动换行；[onClick] 点开"这个演员/标签的全部作品"（跨所有媒体库） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelFlow(labels: List<String>, onClick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.clickable { onClick(label) },
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * nfo 长尾字段的解码器（容错：库里存的可能是老版本解析器写的 JSON，多出来的键忽略）。
 * 只是展示用，解不出来就当没有 —— 不该让详情页崩。
 */
private val nfoJson = Json { ignoreUnknownKeys = true }

/**
 * 影片信息：nfo 里那些**不参与查询、只在详情页看**的字段（时长/首播/国家/导演/编剧/合集/
 * 技术参数/外部编号…）。一条都没有就整节不出现 —— 老库升级上来时 nfoJson 是空的，
 * 表现跟以前完全一样（重扫一次就补上）。
 */
@Composable
private fun NfoFactsSection(nfo: NfoMeta) {
    val rows = buildList {
        nfo.runtimeText?.let { add("时长" to it) }
        nfo.premiered?.takeIf { it.isNotBlank() }?.let { add("首播" to it) }
        nfo.countries.takeIf { it.isNotEmpty() }?.let { add("国家/地区" to it.joinToString(" / ")) }
        nfo.languages.takeIf { it.isNotEmpty() }?.let { add("语言" to it.joinToString(" / ")) }
        nfo.contentRating?.takeIf { it.isNotBlank() }?.let { add("分级" to it) }
        nfo.directors.takeIf { it.isNotEmpty() }?.let { add("导演" to it.joinToString(" / ")) }
        nfo.writers.takeIf { it.isNotEmpty() }?.let { add("编剧" to it.joinToString(" / ")) }
        nfo.studios.takeIf { it.isNotEmpty() }?.let { add("片商" to it.joinToString(" / ")) }
        nfo.network?.takeIf { it.isNotBlank() }?.let { add("电视台" to it) }
        nfo.set?.takeIf { it.isNotBlank() }?.let { add("合集" to it) }
        nfo.status?.takeIf { it.isNotBlank() }?.let { add("状态" to it) }
        nfo.source?.takeIf { it.isNotBlank() }?.let { add("来源" to it) }
        // 评分：主评分（带人数）/ 媒体评分 / 榜单
        nfo.rating?.let { r ->
            val votes = nfo.votes?.let { v -> "（${if (v >= 10000) "%.1f 万".format(v / 10000.0) else "$v"} 人）" } ?: ""
            add("评分" to "%.1f%s".format(r, votes))
        }
        nfo.criticRating?.let { add("媒体评分" to "%.1f".format(it)) }
        nfo.top250?.let { add("榜单" to "TOP $it") }
        // 技术参数（nfo 的 <fileinfo><streamdetails>）
        listOfNotNull(nfo.resolutionText, nfo.videoCodec, nfo.videoAspect).takeIf { it.isNotEmpty() }
            ?.let { add("画面" to it.joinToString(" · ")) }
        listOfNotNull(
            nfo.audioCodec,
            nfo.audioChannels?.let { "$it 声道" },
            nfo.audioLanguages.takeIf { it.isNotEmpty() }?.joinToString("/"),
        ).takeIf { it.isNotEmpty() }?.let { add("音轨" to it.joinToString(" · ")) }
        nfo.subtitleLanguages.takeIf { it.isNotEmpty() }?.let { add("字幕" to it.joinToString(" / ")) }
        nfo.videoDurationSeconds?.takeIf { it > 0 }?.let { add("片长" to "${it / 60} 分 ${it % 60} 秒") }
        // 外部编号（刮削器写的 id，排查"为什么没刮到"时有用）
        listOfNotNull(
            nfo.uniqueTmdbid?.let { "TMDB $it" },
            nfo.imdbId,
            nfo.tvdbId?.let { "TVDB $it" },
        ).takeIf { it.isNotEmpty() }?.let { add("编号" to it.joinToString(" · ")) }
        nfo.dateAdded?.takeIf { it.isNotBlank() }?.let { add("入库" to it.take(10)) }
    }
    if (rows.isEmpty()) return

    Spacer(Modifier.height(16.dp))
    Text("影片信息", style = MaterialTheme.typography.titleSmall, color = Color.White)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { (label, value) ->
            Row {
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(66.dp),
                )
                Text(
                    value,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
