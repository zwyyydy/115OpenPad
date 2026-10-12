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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.collectAsState
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
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.media.backgroundSourceOf
import com.open115.pad.data.media.ActorCard
import com.open115.pad.data.media.EpisodeEntity
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.ActorAvatarSource
import com.open115.pad.data.media.EpisodeRow
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieRecommender
import com.open115.pad.data.media.RecommendationSeed
import com.open115.pad.data.media.loadRecommendations
import com.open115.pad.data.media.MovieEntity
import com.open115.pad.data.media.NfoMeta
import com.open115.pad.data.media.MovieDeleteResult
import com.open115.pad.data.media.deleteMovie
import com.open115.pad.data.media.episodeSortKey
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.FadeIn
import com.open115.pad.ui.components.PreviewZoomHost
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
    val seriesEpisodes: List<EpisodeRow>,
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
    /**
     * 点底部「推荐影片」里的一张：外面**再开一层详情**（浮层栈支持 Detail→Detail）。
     * [list] 传的是这一批推荐（不是进来的那个列表）—— 这样"播完自动接下一部"就在推荐里走。
     */
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit = { _, _, _ -> },
    /**
     * 刮削写回成功：把新键（可能已从文件前缀变成 `tmdb-<id>`）交给外层 ——
     * 详情页拿的是旧卡，键一变这条就得由外面重新解析（见 MediaLibraryScreen 的替换逻辑）。
     */
    onScraped: (newKey: String?) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val container = (context.applicationContext as com.open115.pad.App115).container
    var showDelete by remember { mutableStateOf(false) }
    var showScrape by remember { mutableStateOf(false) }

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
                seriesEpisodes = dao.episodeDetailsOfSeries(card.mediaKey),
                fanarts = updated.extraFanartList,
                nfo = updated.nfoJson?.let {
                    runCatching { nfoJson.decodeFromString<NfoMeta>(it) }.getOrNull()
                },
            )
        }
    }

    /**
     * 库里**没有剧照**的片：拿它播放时抓的本机预览帧顶替剧照（用户要求：媒体库没有预览图
     * （剧照）那么还是继续生成，以后充当剧照）。抓帧那侧在 PlayerActivity.ensurePreviewThumb：
     * 只有库里确实没剧照（只有单张 fanart.jpg 也算没剧照）的片才会被抓条，所以读出来的帧
     * 必然是这部片自己的画面，而且全在本机（零请求）。声明在 Scaffold 外面：内容区那排缩略图
     * 与全屏查看要读**同一份**。
     */
    val previewStore = remember(context) { container.videoPreviews }
    val previewVersion by previewStore.version.collectAsState()
    val stripHostPickCode = card.videoPickCode
        ?: data?.seriesEpisodes?.firstOrNull()?.videoPickCode
        ?: data?.episodes?.firstOrNull()?.videoPickCode
    val localFanarts = remember(data?.fanarts, stripHostPickCode, previewVersion) {
        if (!data?.fanarts.isNullOrEmpty() || stripHostPickCode == null) {
            emptyList()
        } else {
            previewStore.stripFiles(stripHostPickCode)
        }
    }

    /**
     * 「换一批」的次数：只用来派生推荐种子（见 [RecommendationSeed]）。
     *
     * 种子必须稳定，所以推荐**不能**每次重组都重算 —— `produceState` 的 key 只吃
     * "哪部片 + 第几批"，滚动/改窗口大小都不会让它重算。
     */
    var shuffleTick by remember { mutableStateOf(0) }
    val recommendations by produceState<List<MovieRecommender.Recommendation>>(
        initialValue = emptyList(),
        card.mediaKey,
        shuffleTick,
    ) {
        // 推荐是锦上添花：查失败（库正在被改/被删）就当没有，不该让详情页跟着报错
        value = runCatching {
            loadRecommendations(
                dao = dao,
                selfKey = card.mediaKey,
                count = RECOMMEND_COUNT,
                seed = RecommendationSeed.of(card.mediaKey, shuffleTick),
            )
        }.getOrDefault(emptyList())
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
    val epList: List<EpisodeRow> = remember(data) {
        val d = data ?: return@remember emptyList()
        if (d.seriesEpisodes.isNotEmpty()) {
            d.seriesEpisodes.sortedWith(
                // 有季/集号（刮过分集 nfo）就按号排；没有退回按文件名的集号
                compareBy(
                    { it.season ?: Int.MAX_VALUE },
                    { it.episode ?: Int.MAX_VALUE },
                    { episodeSortKey(it.videoName ?: it.title) },
                ),
            )
        } else {
            d.episodes.map {
                EpisodeRow(
                    mediaKey = it.mediaKey, title = it.episodeKey, plot = null, premiered = null,
                    season = it.season, episode = it.episode, videoPickCode = it.videoPickCode,
                    videoName = it.videoName,
                )
            }
        }
    }
    val epEntries = remember(epList) {
        epList.mapNotNull { r -> r.videoPickCode?.let { PlaylistEntry(it, r.title) } }
    }
    // 库内播放列表（播完一部自动接下一部）
    val movieEntries = remember(playlist) {
        playlist.mapNotNull { c -> c.videoPickCode?.let { pc -> PlaylistEntry(pc, c.title) } }
    }
    /**
     * 头像仓库补的演员头像：**名字 → 图片 URL**。
     *
     * 开关关着、或本地还没建索引时直接是空表（一次网络都不发）。开着时查的是本地那份紧凑索引
     * （一次扫描，见 [ActorAvatarSource.lookup]），**不联网**；图片本身由 Coil 加载并缓存。
     */
    val avatarSourceEnabled by container.mediaPrefs.avatarSourceEnabled.collectAsState(initial = false)
    val avatarBaseUrl by container.mediaPrefs.avatarSourceUrl.collectAsState(initial = "")
    val avatarFallbackUrls by produceState<Map<String, String>>(emptyMap(), card.mediaKey, data) {
        value = emptyMap()
        if (!avatarSourceEnabled) return@produceState
        val dir = context.filesDir
        if (!ActorAvatarSource.hasIndex(dir)) return@produceState
        val missing = data?.actors.orEmpty().filter { it.avatarPickCode.isNullOrBlank() }.map { it.name }
        if (missing.isEmpty()) return@produceState
        val base = avatarBaseUrl.ifBlank { ActorAvatarSource.DEFAULT_BASE }
        value = ActorAvatarSource.lookup(dir, missing)
            .mapValues { (_, path) -> ActorAvatarSource.imageUrl(base, path) }
    }

    fun play(pc: String, name: String, entries: List<PlaylistEntry>, at: Int) {
        // 预览图要上传到视频所在目录：先按卡片反查一次 dirCid（主片与分集都挂在同一张卡上，
        // 查 card.mediaKey 就够了），查不到就是 null —— 播放器里那个功能会提示不可用
        scope.launch {
            val cid = runCatching { dao.dirCidOf(card.mediaKey) }.getOrNull()
            context.startActivity(
                PlayerActivity.intent(
                    context, pc, name, entries,
                    at.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
                    // 媒体库的播放要进「观影历史」（文件页的播放不记，那边看操作记录）
                    recordHistory = true,
                    sourceCid = cid,
                ),
            )
        }
    }

    /** 播第 i 集：用 pickCode 在播放列表里反查下标 —— 有集缺 pickCode 时下标会错位，不能直接用 i */
    fun playEpisode(i: Int) {
        val row = epList.getOrNull(i) ?: return
        val pc = row.videoPickCode
        if (pc.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("这一集没有提取码，无法播放") }
            return
        }
        play(pc, row.title, epEntries, epEntries.indexOfFirst { it.pc == pc })
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
            // 数据到齐的那一帧从转圈整页淡入接上，别硬切（底色/背景此刻已经就位）
            FadeIn(Modifier.fillMaxSize(), durationMillis = 300) {
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
                        // 刮削：搜元数据 → 写回云端该片目录（nfo + 海报）。
                        // 没有视频文件名的行（剧集根、CD 合成行）连 nfo 名都生成不了，不给这个入口
                        if (!current.movie.videoName.isNullOrBlank()) {
                            IconButton(onClick = { showScrape = true }) {
                                Icon(
                                    Icons.Outlined.AutoFixHigh,
                                    contentDescription = "刮削元数据",
                                    tint = Color.White,
                                )
                            }
                        }
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
                            ActorAvatarRow(current.actors, roles, avatarFallbackUrls) { onOpenWorks(WorksKind.Actor, it) }
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

                    // 剧照（extrafanart）：横排缩略图，点开全屏翻页。
                    // 库里没有剧照的片（localFanarts 非空）用播放时抓的本机帧顶上，两种图源都点得开
                    val fanartCount = if (current.fanarts.isNotEmpty()) current.fanarts.size else localFanarts.size
                    if (fanartCount > 0) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "剧照（$fanartCount）",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current.fanarts.isNotEmpty()) {
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
                            } else {
                                itemsIndexed(localFanarts) { i, f ->
                                    Box(
                                        Modifier
                                            .width(196.dp)
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.08f))
                                            .clickable { viewingFanart = i },
                                    ) {
                                        AsyncImage(
                                            model = localFrameRequest(context, f),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
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
                        epList.forEachIndexed { i, row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.title,
                                        color = Color.White.copy(alpha = 0.92f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    episodeSubtitle(row)?.let { sub ->
                                        Spacer(Modifier.height(2.dp))
                                        Text(sub, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                                    }
                                    row.plot?.takeIf { it.isNotBlank() }?.let { p ->
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            p,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.62f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                                IconButton(onClick = { playEpisode(i) }) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = "播放", tint = Color.White)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        }
                    }

                    // 推荐影片：本地四路信号（同演员 / 同系列与标签 / 近期口味 / 随机扰动），
                    // 全程零网络。一条都没有（这部片没演员没标签、口味也空）时整段不显示 ——
                    // 宁可没有，也别出现一个空标题
                    if (recommendations.isNotEmpty()) {
                        val recCards = remember(recommendations) { recommendations.map { it.card } }
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "推荐影片",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            // 换一批 = 换种子重算一遍（本地查询，点一下就走）
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .clickable { shuffleTick++ }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "换一批",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(recommendations) { i, rec ->
                                RecommendTile(rec) { onOpenMovie(rec.card, recCards, i) }
                            }
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
            } // FadeIn
        }
    }

    // 刮削对话框：搜 TMDB → 选候选 → 写回云端（nfo + 海报）。
    // 写完由外层按新键重解析这条片（键会从文件前缀变成 tmdb-<id>，旧卡已经指不到行了）
    val scrapeMovie = data?.movie
    if (showScrape && scrapeMovie != null) {
        MediaScrapeDialog(
            plan = com.open115.pad.data.media.scrape.scrapePlanOfMovie(scrapeMovie),
            scraper = container.metadataScraper,
            aiClient = container.aiQueryClient,
            mediaPrefs = container.mediaPrefs,
            registry = container.scrapeSourceRegistry,
            onDismiss = { showScrape = false },
            onWritten = { newKey ->
                showScrape = false
                onScraped(newKey)
            },
        )
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
                        videoPreviews = container.videoPreviews,
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

    // 剧照全屏浏览（页面内全屏浮层叠在详情页之上，自己处理返回键；手势/顶栏/胶片条见 [FanartViewer]）
    val fanarts = data?.fanarts.orEmpty()
    val viewing = viewingFanart
    if (viewing != null && (fanarts.isNotEmpty() || localFanarts.isNotEmpty())) {
        PreviewZoomHost(onDismissed = { viewingFanart = null }) { requestClose ->
            FanartViewer(
                pickCodes = fanarts,
                localFiles = localFanarts,
                initial = viewing,
                resolver = container.imageUrlResolver,
                cacheDir = container.cacheDir,
                title = data?.movie?.title.orEmpty(),
                onDismiss = requestClose,
            )
        }
    }
}

/** 剧照预取每次批量解析多少条直链（一次 downurl 能带多个 pick_code；十几张一批就够） */
private const val FANART_PREFETCH_BATCH = 20

/** 推荐影片一次显示几部：与首页那两排横排的密度一致（再多一屏也看不完，还得横向滑） */
private const val RECOMMEND_COUNT = 12

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
    /**
     * 名字 → 头像仓库里的图片 URL（见 [ActorAvatarSource]）。**只在没有本地头像时用**，
     * 查不到就退回原来的首字母圆底 —— 绝不拿一张对不上的脸充数。
     */
    fallbackUrls: Map<String, String> = emptyMap(),
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
                    when {
                        !actor.avatarPickCode.isNullOrBlank() ->
                            PickCodeImage(actor.avatarPickCode, Modifier.fillMaxSize())

                        // 本地没有头像时，看头像仓库有没有对得上的：有就加载它（Coil 落盘缓存），
                        // 没有才回首字母 —— 网络失败/地址失效都会自动落回这里
                        fallbackUrls[actor.name] != null -> AsyncImage(
                            model = fallbackUrls[actor.name],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> Text(
                            actor.name.take(1),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
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

/**
 * 推荐卡：海报 + 两行标题 + 一行「为什么推它」。
 *
 * 结构与首页「最近新增」那排一致（108×158、12dp 圆角、标题恒两行），只多一行理由 ——
 * 那行是让人**信**这段推荐的关键：一屏陌生人脸，没有理由只会被当成随机塞的。
 * 理由取不到时（既没共同点也没高分）那一行不占位，卡片也不会高矮不齐（标题本来就恒两行）。
 */
@Composable
private fun RecommendTile(rec: MovieRecommender.Recommendation, onClick: () -> Unit) {
    Column(Modifier.width(108.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(158.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            // 兜底图标在下层：海报没加载出来/本来就没有时不剩一块空白
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
            PosterImage(
                posterPickCode = rec.card.posterPickCode,
                backgroundPickCode = rec.card.fanartPickCode,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            rec.card.title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        rec.reason?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分集行的副标题：季/集号 + 首播日期（缺哪个省哪个），全空返回 null 不占行 */
private fun episodeSubtitle(row: EpisodeRow): String? {
    val parts = buildList {
        when {
            row.season != null && row.episode != null -> add("第${row.season}季 · 第${row.episode}集")
            row.episode != null -> add("第${row.episode}集")
        }
        row.premiered?.takeIf { it.isNotBlank() }?.let { add(it.take(10)) }
    }
    return parts.joinToString("  ·  ").takeIf { it.isNotBlank() }
}
