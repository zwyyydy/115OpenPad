package com.open115.pad.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.open115.pad.ui.components.FadeDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Search
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.open115.pad.data.media.MediaPrefs
import com.open115.pad.data.media.NfoMeta
import com.open115.pad.data.media.scrape.MetadataScraper
import com.open115.pad.data.media.scrape.ScrapeCandidate
import com.open115.pad.data.media.scrape.ScrapeKind
import com.open115.pad.data.media.scrape.ScrapePlan
import com.open115.pad.data.media.scrape.ScrapedActor
import com.open115.pad.data.media.scrape.ScrapedMetadata

/** 「搜完自动刮第一条」的无操作等待时长（开了设置才生效） */
private const val AUTO_SCRAPE_DELAY_MS = 5_000L

/** 候选缩略图的内存缓存（列表滚动/重组时不重复下载；LRU 靠插入序裁剪） */
private object ScrapeThumbCache {
    private const val MAX = 60
    private val map = object : LinkedHashMap<String, ImageBitmap>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = map[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        map[url] = bitmap
    }
}

/** 远程缩略图（TMDB 图片，走独立第三方 client；下载失败画一块占位底） */
@Composable
fun ScrapeRemoteThumb(url: String?, scraper: MetadataScraper, modifier: Modifier) {
    if (url.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val image by produceState<ImageBitmap?>(ScrapeThumbCache.get(url), url) {
        if (value == null) {
            val bytes = scraper.fetchBytes(url)
            val bmp = bytes?.let { b ->
                runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                ScrapeThumbCache.put(url, bmp)
                value = bmp
            }
        }
    }
    val img = image
    if (img != null) {
        Image(
            bitmap = img,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 候选行：缩略图 + 标题（年份）+ 简介两行。详情页与批量复核共用一个样子 */
@Composable
private fun CandidateRow(
    cand: ScrapeCandidate,
    selected: Boolean,
    scraper: MetadataScraper,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ScrapeRemoteThumb(
                url = cand.posterUrl,
                scraper = scraper,
                modifier = Modifier.size(width = 44.dp, height = 66.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(cand.title)
                        cand.year?.let { append("（$it）") }
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                cand.originalTitle?.takeIf { it.isNotBlank() && it != cand.title }?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                cand.overview?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            cand.rating?.takeIf { it > 0 }?.let {
                Spacer(Modifier.width(8.dp))
                Text(String.format(java.util.Locale.US, "%.1f", it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 「刮削」对话框：搜索 → 选一条 → 确认写入（nfo + 海报传回云端）。
 *
 * 输入是 [ScrapePlan] —— 媒体库详情页与文件页都构造它，两个入口共用这一套界面与流程。
 * 覆盖说明只在**这个目录/这条片已经有 nfo** 时出现（那是"只补空"的例外，用户显式点进来才算确认）。
 */
@Composable
fun MediaScrapeDialog(
    plan: ScrapePlan,
    /** 现有 nfo 的解析结果（详情页传）：手动编辑用它预填表单；文件页拿不到时为 null */
    prefill: NfoMeta? = null,
    scraper: MetadataScraper,
    aiClient: com.open115.pad.data.media.scrape.AiQueryClient,
    mediaPrefs: com.open115.pad.data.media.MediaPrefs,
    registry: com.open115.pad.data.media.scrape.ScrapeSourceRegistry,
    onDismiss: () -> Unit,
    /** 写成功回调：新键（可能已经变成 tmdb-<id>，也可能为 null = 键没变），由外层决定要不要重解析卡片 */
    onWritten: (newKey: String?) -> Unit,
) {
    var query by remember { mutableStateOf(scraper.cleanQueryTitle(plan.title)) }
    var picked by remember { mutableStateOf<ScrapeCandidate?>(null) }
    // 用户是否手动接管过（点候选 / 点写入）：接管后「自动刮第一条」就不再抢着落笔
    var userInteracted by remember(plan.key) { mutableStateOf(false) }
    // 单片入口默认带剧照（批量那条在确认框里默认关）
    var withStills by remember { mutableStateOf(true) }
    // 补充模式（默认开）：只补现有 nfo 缺的字段与缺失的图片，不覆盖已有内容
    var supplement by remember { mutableStateOf(true) }
    // 手动编辑视图：把搜索/候选那套换成表单（只写 nfo，不动图片）。
    // 已有 nfo 但没有解析结果时不给入口：表单会是空的，保存等于把原有字段清掉。
    // 文件页拿不到 nfoJson，这种情况请从媒体库详情页进来编辑。
    var manualMode by remember { mutableStateOf(false) }
    val canManualEdit = prefill != null || plan.oldNfoFid.isNullOrBlank()
    // 剧集：默认带分集（每集真名/简介/首播）；电影不显示这个勾选
    var withEpisodes by remember { mutableStateOf(true) }
    var aiWorking by remember { mutableStateOf(false) }
    var aiNote by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 搜索跑在应用级 scope（scraper 的搜索会话）：慢源搜到一半也能关框走人，跑完照常弹完成提示；
    // 重新打开这部片的「刮削」会自动接上这一轮（searchState 的 key 对得上就用它存的结果）
    val searchState by scraper.searchState.collectAsState()
    val mine = searchState?.takeIf { it.key == plan.key }
    val searching = mine?.running == true
    val candidates = mine?.results ?: emptyList()
    val error = mine?.error
    val searchedOnce = mine != null && !searching
    // 「搜完 5 秒无操作自动刮第一条」开关（默认关）——计时器要用，先于计时器声明
    val autoFirst by mediaPrefs.scrapeAutoFirst.collectAsState(initial = false)

    // 对话框开着 = 结果就在眼前：这一轮搜索跑完不再弹窗打扰（AppRoot 的分发读这两处）
    DisposableEffect(Unit) {
        scraper.singleDialogOpen = true
        onDispose { scraper.singleDialogOpen = false }
    }
    // 每轮搜索出结果就把自动匹配的那条勾上（关框期间在后台搜完、再打开接上，也走这里）
    LaunchedEffect(mine?.finishedAt) {
        val s = mine ?: return@LaunchedEffect
        if (!s.running && s.error == null) picked = s.results.getOrNull(s.autoPicked ?: -1)
    }
    // 开了「自动刮第一条」：搜出结果后 5 秒没手动选，就选第一条直接写回。
    // 只在对话框开着时计时（关框 → 组合离开 → 自动取消，挂后台搜索不会自动落笔）；
    // userInteracted 进 key：用户一点候选/点写入，这个 effect 立即重启并因条件不满足而退出。
    LaunchedEffect(autoFirst, mine?.finishedAt, userInteracted, supplement) {
        if (!autoFirst || userInteracted) return@LaunchedEffect
        val s = mine ?: return@LaunchedEffect
        if (s.running || s.error != null || s.results.isEmpty()) return@LaunchedEffect
        delay(AUTO_SCRAPE_DELAY_MS)
        // 到点再确认一次：没被手动接管、也没有正在写的活儿
        if (userInteracted || scraper.writeState.value?.running == true) return@LaunchedEffect
        val cand = s.results.firstOrNull() ?: return@LaunchedEffect
        picked = cand
        userInteracted = true
        scraper.startWrite(plan = plan, candidate = cand, withStills = withStills, withEpisodes = withEpisodes, supplement = supplement)
    }

    // 源切换：这里选的源就是全局"当前源"（刮削/批量都用它），切了立即刷新注册表生效
    val selectedSourceId by mediaPrefs.selectedSourceId.collectAsState(initial = "tmdb")
    val availableSources = registry.sources()
    var sourceMenu by remember { mutableStateOf(false) }
    val sourceName = availableSources.firstOrNull { it.id == selectedSourceId }?.displayName
        ?: if (selectedSourceId == "tmdb") "TMDB" else selectedSourceId
    // 剧照张数（设置 → 实验室；0 = 不限制）：勾选文案跟着当前设置走
    val stillsMax by mediaPrefs.scrapeStillsMax.collectAsState(initial = MediaPrefs.DEFAULT_SCRAPE_STILLS_MAX)

    // AI 模板库（内置 + 自定义）与"刮削场景默认模板"
    val customTemplates by mediaPrefs.aiCustomTemplates.collectAsState(initial = emptyList())
    val defaultTpl by mediaPrefs.aiScrapeTemplate.collectAsState(
        initial = com.open115.pad.data.media.scrape.AiPromptTemplates.SCRAPE_QUERY,
    )
    val aiTemplates = com.open115.pad.data.media.scrape.AiPromptTemplates.all(customTemplates)
    var aiTpl by remember { mutableStateOf<String?>(null) }  // 本次临时选的模板（null = 用场景默认）
    val effTpl = aiTpl ?: defaultTpl
    val aiReady = aiClient.configured()

    // 「用 AI 优化」：拿原始文件名（比清洗过的标题信息更多）让模型洗成干净片名，回填检索词
    fun runAi() {
        val raw = plan.videoName.ifBlank { plan.title }
        if (raw.isBlank()) return
        val prompt = aiTemplates.firstOrNull { it.id == effTpl }?.prompt
            ?: com.open115.pad.data.media.scrape.AiPromptTemplates.builtinPrompt(effTpl)
        aiWorking = true
        aiNote = null
        scope.launch {
            val q = runCatching { aiClient.suggest(raw, prompt) }.getOrNull()
            if (!q.isNullOrBlank()) {
                query = q
                // 检索词换了，旧候选作废（有搜索在跑也一并停，免得新旧串台）
                scraper.clearSearch()
                picked = null
            } else {
                aiNote = "AI 没给出结果（检查设置 → 实验室 → AI 检索词，或看网络）"
            }
            aiWorking = false
        }
    }

    val writeState by scraper.writeState.collectAsState()
    val writing = writeState?.running == true && writeState?.key == plan.key
    val finished = writeState?.takeIf { it.key == plan.key && !it.running }

    // 写成功 → 通知外层重解析（只发一次：finishedAt 是这一轮的标记）
    LaunchedEffect(finished?.finishedAt) {
        val f = finished ?: return@LaunchedEffect
        if (f.error == null && f.wroteNfo) onWritten(f.newKey)
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        picked = null
        // 发起即返：搜索在 scraper 的应用级 scope 里跑，这个对话框关不关都不影响
        scraper.startSearch(plan, q)
    }

    // 关框：搜索/写回跑完的这轮顺手清掉（下次打开不带旧状态）；还在跑的留着转后台
    fun closeDialog() {
        if (mine != null && !searching) scraper.clearSearch()
        val w = scraper.writeState.value
        if (w?.key == plan.key && !w.running) scraper.clearWrite()
        onDismiss()
    }

    // 写回跑在应用级 scope 上（切页/关框都不中断），所以任何时候都能关；关了指纹红点/完成弹窗继续跟
    FadeDialog(onDismissRequest = { closeDialog() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            // 弹窗必须实底：壁纸模式下 surface 是半透明的，会把下面的文件行透进弹窗里
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (manualMode) {
                    ManualEditForm(
                        plan = plan,
                        prefill = prefill,
                        picked = picked,
                        scraper = scraper,
                        onBack = { manualMode = false },
                    )
                } else {
                val isTv = plan.kind == ScrapeKind.TV
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("刮削元数据${if (isTv) " · 剧集" else ""}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    // 源切换：点了直接换"当前源"，之后搜索/写回都用它
                    Box {
                        Row(
                            Modifier.clickable(enabled = availableSources.size > 1) { sourceMenu = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sourceName, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            if (availableSources.size > 1) {
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "切换源", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            availableSources.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(if (s.id == selectedSourceId) "✓ ${s.displayName}" else s.displayName) },
                                    onClick = {
                                        sourceMenu = false
                                        if (s.id != selectedSourceId) {
                                            // 换源 = 这一轮作废（跑着的也停：旧源的结果不该留着）
                                            scraper.clearSearch()
                                            picked = null
                                            scope.launch {
                                                mediaPrefs.setSelectedSourceId(s.id)
                                                registry.refresh()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { closeDialog() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Text(
                    if (isTv) "写回内容：tvshow.nfo（剧名/简介/评分/演员等）+ 海报 + 背景图，传到这部剧的云端目录;" +
                        "写完自动重扫该目录（分集暂不逐集写）。"
                    else "写回内容：nfo（标题/简介/评分/演员等）+ 海报 + 背景图，传到这部片的云端目录;" +
                        "写完自动重扫该目录。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = supplement,
                        enabled = !writing,
                        onCheckedChange = { supplement = it },
                    )
                    Text(
                        "补充模式：只补现有 nfo 缺的字段和没有的图片，不覆盖已有内容",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = withStills,
                        enabled = !writing,
                        onCheckedChange = { withStills = it },
                    )
                    Text(
                        "同时刮剧照（${if (stillsMax == MediaPrefs.SCRAPE_STILLS_UNLIMITED) "不限制张数，源里全部按赞数排序取回" else "按赞数取前 $stillsMax 张"}，存进这部片专属的 extrafanart 目录）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (isTv) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = withEpisodes,
                            enabled = !writing,
                            onCheckedChange = { withEpisodes = it },
                        )
                        Text(
                            "同时写分集（按 SxxExx 给每集补标题/简介/首播，一集一个 nfo）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (supplement) {
                    if (plan.overwriteNote != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这个目录已经有 nfo：补充模式会保留它现有的字段，只补缺的部分；已有的海报/背景/剧照也不会覆盖。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    plan.overwriteNote?.let { note ->
                        Spacer(Modifier.height(6.dp))
                        Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!plan.inLibrary) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "这个目录不在任何媒体库里：会写回云端，但不会自动重扫/入库。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("检索词", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (aiReady) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { runAi() },
                            enabled = !aiWorking && !searching && !writing,
                        ) {
                            if (aiWorking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("AI 优化")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { runSearch() }, enabled = !searching && !writing && query.isNotBlank()) {
                        if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("搜索")
                    }
                }
                if (aiReady) {
                    var tplMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { tplMenu = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("提示词模板", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            aiTemplates.firstOrNull { it.id == effTpl }?.name ?: "（未找到）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box {
                            DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                                aiTemplates.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(if (t.id == effTpl) "✓ ${t.name}" else t.name) },
                                        onClick = { aiTpl = t.id; tplMenu = false },
                                    )
                                }
                            }
                        }
                    }
                }
                aiNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(candidates, key = { it.id }) { cand ->
                            CandidateRow(
                                cand = cand,
                                selected = picked?.id == cand.id,
                                scraper = scraper,
                                onClick = { if (!writing) { picked = cand; userInteracted = true } },
                            )
                        }
                    }
                } else if (searchedOnce && !searching && error == null) {
                    // 只有"搜成功但零结果"才给这句；失败时上面那条错误就是唯一信息
                    Spacer(Modifier.height(8.dp))
                    Text("没搜到结果。可以改检索词再搜（比如只留片名、去掉副标题）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 写入进度 / 结果
                if (writing || finished != null) {
                    Spacer(Modifier.height(12.dp))
                    when {
                        writing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(writeState?.step ?: "写入中…", fontSize = 13.sp)
                        }
                        finished?.error != null -> Text(
                            "写入失败：${finished.error}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                        finished?.changed == false -> Text(
                            "没有需要补充的内容（现有 nfo / 图片都已齐全）。",
                            fontSize = 13.sp,
                        )
                        finished?.wroteNfo == true -> Column {
                            Text(
                                if (finished.addedFields > 0) "已补齐 nfo 缺的 ${finished.addedFields} 项，并排入重扫。"
                                else "已写入云端并排入重扫。",
                                fontSize = 13.sp,
                            )
                            val wrote = buildList {
                                if (finished.wrotePoster) add("海报")
                                if (finished.wroteFanart) add("背景图")
                                if (finished.wroteStills > 0) add("剧照 ${finished.wroteStills} 张")
                                if (finished.wroteEpisodes > 0) add("分集 ${finished.wroteEpisodes} 集")
                            }
                            Text(
                                if (wrote.isEmpty()) "没有可用的图片（只写了 nfo）。"
                                else "已上传 ${wrote.joinToString("、")}（重扫完成后详情页/海报墙自动更新）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    // 手动编辑：不依赖搜索/候选，自己填字段写回 nfo
                    if (!writing && !searching && canManualEdit) {
                        TextButton(onClick = { manualMode = true }) { Text("手动编辑") }
                        Spacer(Modifier.weight(1f))
                    }
                    // 写回中/搜索中都给"后台"挡：关掉框、任务照跑（红点跟进度，跑完弹提示），不清状态；
                    // 写回的那条是原有行为，搜索这条是新增（慢源不必守着对话框等）
                    if (writing) {
                        TextButton(onClick = { closeDialog() }) { Text("后台继续") }
                        Spacer(Modifier.width(8.dp))
                    } else if (searching) {
                        TextButton(onClick = { closeDialog() }) { Text("后台搜索") }
                        Spacer(Modifier.width(8.dp))
                    } else {
                        // 关掉时顺手清掉这一轮（搜索/写入）的已完成状态：下次打开不该看到上一轮的结果
                        TextButton(onClick = { closeDialog() }) {
                            Text(if (finished?.changed == false || finished?.wroteNfo == true) "完成" else "取消")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        enabled = picked != null && !searching && !writing,
                        onClick = {
                            val cand = picked ?: return@Button
                            userInteracted = true
                            // 候选已经选定，这一轮的搜索没用了：带旧错误行的一并清掉，别写在进度上面看着像失败
                            if (mine?.error != null) scraper.clearSearch()
                            scraper.startWrite(
                                plan = plan, candidate = cand,
                                withStills = withStills, withEpisodes = withEpisodes,
                                supplement = supplement,
                            )
                        },
                    ) { Text(if (supplement) "补充写入" else "覆盖写入") }
                }
                } // else（手动编辑 / 搜索视图）
            }
        }
    }
}

/**
 * 手动编辑的表单字段（顺序即界面顺序）。
 *
 * 只列**写回 nfo 时需要人改的字段**：votes/sorttitle/outline/tag 等要么不参与展示，
 * 要么由扫描器自己补；把它们放进表单只会让页面更长。海报/背景/剧照不在这里改。
 */
private enum class ManualField(val label: String, val multiline: Boolean = false, val hint: String = "") {
    TITLE("标题"),
    ORIGINAL_TITLE("原名"),
    YEAR("年份"),
    RATING("评分（0-10）"),
    RUNTIME("时长（分钟）"),
    PREMIERED("首映日期", hint = "yyyy-MM-dd"),
    CONTENT_RATING("分级"),
    TAGLINE("标语"),
    PLOT("简介", multiline = true),
    GENRES("类型", hint = "逗号分隔"),
    COUNTRIES("国家", hint = "逗号分隔"),
    LANGUAGES("语言", hint = "逗号分隔"),
    STUDIOS("制片公司", hint = "逗号分隔"),
    DIRECTORS("导演", hint = "逗号分隔"),
    WRITERS("编剧", hint = "逗号分隔"),
    ACTORS("演员", multiline = true, hint = "每行一个：名字|角色"),
    TMDB_ID("TMDB ID"),
    IMDB_ID("IMDb ID"),
    SET("合集"),
    TRAILER("预告片 URL"),
}

/** 表单初值：优先现有 nfo（详情页传进来的），否则退到计划标题/年份 + 已选候选 */
private fun manualInitialValues(plan: ScrapePlan, prefill: NfoMeta?, cand: ScrapeCandidate?): List<String> {
    fun ratingText(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.1f", it) }.orEmpty()
    if (prefill != null) return listOf(
        prefill.title ?: plan.title,
        prefill.originalTitle.orEmpty(),
        prefill.year?.toString() ?: plan.year?.toString().orEmpty(),
        ratingText(prefill.rating),
        prefill.runtimeMinutes?.toString().orEmpty(),
        prefill.premiered.orEmpty(),
        prefill.contentRating.orEmpty(),
        prefill.tagline.orEmpty(),
        prefill.plot.orEmpty(),
        prefill.genres.joinToString("，"),
        prefill.countries.joinToString("，"),
        prefill.languages.joinToString("，"),
        prefill.studios.joinToString("，"),
        prefill.directors.joinToString("，"),
        prefill.writers.joinToString("，"),
        prefill.actorPairs.joinToString("\n") { (n, r) -> if (r.isBlank()) n else "$n|$r" },
        prefill.uniqueTmdbid.orEmpty(),
        prefill.imdbId.orEmpty(),
        prefill.set.orEmpty(),
        prefill.trailer.orEmpty(),
    )
    return listOf(
        cand?.title?.takeIf { it.isNotBlank() } ?: plan.title,
        cand?.originalTitle.orEmpty(),
        (cand?.year ?: plan.year)?.toString().orEmpty(),
        ratingText(cand?.rating),
        "", "", "", "",
        cand?.overview.orEmpty(),
        "", "", "", "", "", "", "",
        "", "", "", "",
    )
}

private fun splitManualList(raw: String): List<String> =
    raw.split(',', '，', '/', '、', ';', '；', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun parseManualActors(raw: String): List<ScrapedActor> = raw.lines().mapNotNull { line ->
    val s = line.trim()
    if (s.isEmpty()) return@mapNotNull null
    val parts = s.split('|', '｜', limit = 2)
    val name = parts[0].trim()
    if (name.isEmpty()) null
    else ScrapedActor(name, parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
}

private fun buildManualMeta(plan: ScrapePlan, values: List<String>): ScrapedMetadata {
    fun at(f: ManualField) = values.getOrElse(f.ordinal) { "" }.trim()
    return ScrapedMetadata(
        sourceId = "manual",
        sourceKey = "manual",
        kind = plan.kind,
        title = at(ManualField.TITLE).ifBlank { plan.title },
        originalTitle = at(ManualField.ORIGINAL_TITLE).ifBlank { null },
        year = at(ManualField.YEAR).toIntOrNull(),
        rating = at(ManualField.RATING).toDoubleOrNull()?.coerceIn(0.0, 10.0),
        runtimeMinutes = at(ManualField.RUNTIME).toIntOrNull()?.takeIf { it > 0 },
        premiered = at(ManualField.PREMIERED).ifBlank { null },
        contentRating = at(ManualField.CONTENT_RATING).ifBlank { null },
        tagline = at(ManualField.TAGLINE).ifBlank { null },
        plot = at(ManualField.PLOT).ifBlank { null },
        genres = splitManualList(at(ManualField.GENRES)),
        countries = splitManualList(at(ManualField.COUNTRIES)),
        languages = splitManualList(at(ManualField.LANGUAGES)),
        studios = splitManualList(at(ManualField.STUDIOS)),
        directors = splitManualList(at(ManualField.DIRECTORS)),
        writers = splitManualList(at(ManualField.WRITERS)),
        actors = parseManualActors(at(ManualField.ACTORS)),
        tmdbId = at(ManualField.TMDB_ID).ifBlank { null },
        imdbId = at(ManualField.IMDB_ID).ifBlank { null },
        set = at(ManualField.SET).ifBlank { null },
        trailer = at(ManualField.TRAILER).ifBlank { null },
    )
}

/**
 * 手动编辑视图：表单预填现有 nfo（没有就从片名/候选起步），保存时只写 nfo。
 *
 * 与"刮削"的分工：这里不搜源、不选候选、不动图片；适合"源码里没有 / 刮到的标题是错的 /
 * 想把简介换成自己写的那段"这类场景。
 */
@Composable
private fun ManualEditForm(
    plan: ScrapePlan,
    prefill: NfoMeta?,
    picked: ScrapeCandidate?,
    scraper: MetadataScraper,
    onBack: () -> Unit,
) {
    val initial = remember(plan.key, prefill, picked?.id) { manualInitialValues(plan, prefill, picked) }
    val values = remember(initial) { androidx.compose.runtime.mutableStateListOf(*initial.toTypedArray()) }
    val writeState by scraper.writeState.collectAsState()
    val mine = writeState?.takeIf { it.key == plan.key }
    val writing = mine?.running == true
    val finished = mine?.takeIf { !it.running }

    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
        Text("手动编辑元数据", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "只更新 nfo（标题/简介/演员等），不动海报/背景/剧照；表单里没有的原 nfo 标签会保留。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        ManualField.values().forEach { f ->
            OutlinedTextField(
                value = values.getOrElse(f.ordinal) { "" },
                onValueChange = { if (f.ordinal < values.size) values[f.ordinal] = it },
                label = { Text(f.label, fontSize = 12.sp) },
                placeholder = if (f.hint.isNotBlank()) {
                    { Text(f.hint, fontSize = 12.sp) }
                } else null,
                singleLine = !f.multiline,
                minLines = if (f.multiline) 3 else 1,
                enabled = !writing,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
        }
        if (writing) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(mine?.step ?: "保存中…", fontSize = 13.sp)
            }
        }
        finished?.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("保存失败：$it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, enabled = !writing) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !writing,
                onClick = { scraper.startWriteManual(plan, buildManualMeta(plan, values.toList())) },
            ) { Text("保存到云端") }
        }
    }
}

/**
 * 批量复核面板：搜索进度 → 逐条勾选（可换候选）→ 写入进度 → 汇总。
 * 面板本身不持有任务：数据都在 [MetadataScraper.session] 里 —— 切页回来它还在，
 * 所以这里只负责"画出来 + 把用户动作转给 scraper"。
 */
@Composable
fun ScrapeBatchPanel(scraper: MetadataScraper, onClose: () -> Unit) {
    val session by scraper.session.collectAsState()
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    val s = session ?: return

    // 整屏覆盖层：壁纸模式下 surface 是半透明的，会整页透出下面的文件列表；
    // 非壁纸（含媒体库深色）仍用 surface，保持原有底色层次
    val panelColor =
        if (com.open115.pad.ui.theme.LocalWallpaperGlass.current.active) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        }
    Surface(color = panelColor, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "补全缺失元数据 · ${s.libraryName}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
            LinearProgressIndicator(
                progress = {
                    when {
                        s.searching -> if (s.items.isEmpty()) 0f else (s.searchDone.toFloat() / s.items.size).coerceIn(0f, 1f)
                        s.writing -> {
                            val total = s.items.count { it.chosen != null || it.written }
                            if (total == 0) 0f else (s.writeDone.toFloat() / total).coerceIn(0f, 1f)
                        }
                        else -> 1f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = when {
                    s.searching -> "正在搜索（${s.searchDone}/${s.items.size}）…"
                    s.writing -> "正在写入（${s.writeDone}/${s.items.count { it.chosen != null || it.written }}）…"
                    s.finished -> s.summary ?: "完成"
                    else -> "共 ${s.items.size} 部待补 · 勾选要写入的条目（可点条目换候选）"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // 改名方式在多选进入刮削前就已选定（文件页的设置弹框），这里只读显示。
            // 补充模式不改文件名、不动已有文件，优先展示这条口径。
            if (s.supplement) {
                Text(
                    "补充模式：只补缺失的字段 / 图片，不覆盖已有内容",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else if (s.renameToClean) {
                Text(
                    "写回时会把文件按规范名重命名",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(s.items, key = { _, it -> it.mediaKey }) { index, item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                            .clickable(enabled = !s.searching && !s.writing && !item.written) { pickerIndex = index },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.chosen != null || item.written,
                                enabled = item.candidates.isNotEmpty() && !s.searching && !s.writing && !item.written,
                                onCheckedChange = { on ->
                                    scraper.setChosen(index, if (on) (item.chosen ?: 0).coerceIn(0, (item.candidates.size - 1).coerceAtLeast(0)) else null)
                                },
                            )
                            val cand = item.candidates.getOrNull(item.chosen ?: -1)
                            if (cand != null) {
                                ScrapeRemoteThumb(
                                    url = cand.posterUrl,
                                    scraper = scraper,
                                    modifier = Modifier.size(width = 36.dp, height = 54.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                // 本地片名（要刮的那部）
                                Text(item.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // 匹配到的候选 / 状态
                                val line = when {
                                    item.error != null -> "失败：${item.error}"
                                    item.written -> item.note ?: "已写入"
                                    cand != null -> "→ " + cand.title + (cand.year?.let { "（$it）" } ?: "")
                                    item.note != null -> item.note!!
                                    else -> "没有搜到匹配（点条目可再选）"
                                }
                                Text(
                                    line,
                                    fontSize = 12.sp,
                                    color = when {
                                        item.error != null -> MaterialTheme.colorScheme.error
                                        cand != null -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            item.year?.let {
                                Text("($it)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 底栏
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                val pickedCount = s.items.count { it.chosen != null && !it.written }
                if (s.searching || s.writing) {
                    TextButton(onClick = { scraper.stopBatch() }) { Text("停止") }
                }
                if (s.finished) {
                    Button(onClick = onClose) { Text("完成") }
                } else {
                    Button(
                        enabled = pickedCount > 0 && !s.searching && !s.writing,
                        onClick = { scraper.writeSelected() },
                    ) { Text("写入选中（$pickedCount）") }
                }
            }
        }
    }

    // 换候选的小对话框
    pickerIndex?.let { idx ->
        val item = s.items.getOrNull(idx)
        if (item == null) {
            pickerIndex = null
        } else {
            FadeDialog(onDismissRequest = { pickerIndex = null }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Text("为《${item.title}》选择元数据", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        if (item.candidates.isEmpty()) {
                            Text("这条没有候选。可以关掉后用详情页的「刮削」手动搜。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                                items(item.candidates, key = { it.id }) { cand ->
                                    CandidateRow(
                                        cand = cand,
                                        selected = item.candidates.getOrNull(item.chosen ?: -1)?.id == cand.id,
                                        scraper = scraper,
                                        onClick = {
                                            val i = item.candidates.indexOfFirst { it.id == cand.id }
                                            if (i >= 0) scraper.setChosen(idx, i)
                                            pickerIndex = null
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                scraper.setChosen(idx, null)
                                pickerIndex = null
                            }) { Text("不写这条") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { pickerIndex = null }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}
