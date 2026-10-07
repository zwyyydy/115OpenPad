package com.open115.pad.ui.files

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.exp
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open115.pad.appContainer
import com.open115.pad.data.DirCache
import com.open115.pad.data.FileItem
import com.open115.pad.data.FilesPrefs
import com.open115.pad.data.JsonObject
import com.open115.pad.data.JsonPrimitive
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.FilterPrefs
import com.open115.pad.data.FilterRules
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.OpEntry
import com.open115.pad.data.OpLog
import com.open115.pad.data.OpType
import com.open115.pad.data.PinnedFolder
import com.open115.pad.data.PinnedPrefs
import com.open115.pad.data.PathNode
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.RecycleItem
import com.open115.pad.data.Uploader
import com.open115.pad.data.resumeUploadRecord
import com.open115.pad.data.uploadFolder
import com.open115.pad.data.parseFilesResponse
import com.open115.pad.data.parseRecycleList
import com.open115.pad.data.parseSearchResponse
import com.open115.pad.data.envData
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.toImageMediaItem
import com.open115.pad.data.isTextFile
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.DownloadDialog
import com.open115.pad.ui.components.FolderPickerDialog
import com.open115.pad.ui.components.ImageGalleryDialog
import com.open115.pad.ui.components.SidePaneWidth
import com.open115.pad.ui.components.TextEntryDialog
import com.open115.pad.ui.components.isImageItem
import com.open115.pad.ui.settings.UserInfoCard
import com.open115.pad.util.Downloader
import com.open115.pad.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FilesViewModel(
    val api: OpenApi,
    private val prefs: FilesPrefs? = null,
    /** 图片直链解析（三级降级链 + 防频控缓存），图片查看器与本处"下载到本机"共用 */
    val urlResolver: ImageUrlResolver? = null,
    /** 高级过滤：方案存储 + 右上角总开关（未注入则不过滤） */
    private val filterPrefs: FilterPrefs? = null,
    /** 文件夹置顶（未注入则无置顶能力，列表行为与从前完全一致） */
    private val pinnedPrefs: PinnedPrefs? = null,
    /** 目录列表缓存（未注入则每次都真实请求，行为与从前一致） */
    private val cache: DirCache? = null,
    /** 操作记录（未注入则不记录，行为与从前一致） */
    private val opLog: OpLog? = null,
    /**
     * 改名成功后把**这个目录**在媒体库里的扫描状态作废（未注入则什么都不做）。
     *
     * 115 的目录 `upt` 不随改名变（实测），而媒体库快速扫描靠"父目录看到的 upt 没变"跳过目录 ——
     * 不通知的话，在文件页改完名，那一轮扫描会把这个目录当成"没变"跳过，库里一直挂旧文件名。
     * 走容器注入而不是这里直接拿 DAO：文件页不该认识媒体库那套表（同 urlResolver 的写法）。
     */
    private val invalidateMediaDir: suspend (cid: String) -> Unit = {},
    /**
     * 目录栈的存档位（未注入 = 原来的纯内存行为）。
     *
     * 为什么需要它：文件页"在哪一层"只存在这个 ViewModel 里，而播放器是**另一个 Activity** ——
     * 进播放器后 MainActivity 停在后台，一旦被系统回收（进程在后台被杀、任务随后按存档恢复，
     * 或 Activity 被单独回收），ViewModel 就跟着没了，stack 回到默认的根目录，看完退出时
     * 用户就被从原目录里"扔"出来。实测复现路径：在子目录里播视频 → 回桌面让进程被回收 →
     * 从任务栈回到播放器 → 退出，文件页落在根目录。SavedStateHandle 随 Activity 的
     * instance state 一起存档，重建时原样还回来；全新启动（本来就没有存档）仍然是根目录。
     */
    private val savedState: SavedStateHandle? = null,
    /**
     * 外部删除广播（回收站彻底删除/清空、云下载删任务且删源文件时发来，见 App115.dirRemovals）：
     * cid → 被移除条目名集合。收到后把这些目录的 [DirCache] 缓存作废（持久 —— 下次进入
     * 必然真实请求）；**正停在**受影响目录时把条目就地从 items 里拿掉（按名字认，同目录内
     * 名字唯一），不做整页重拉 —— 115 的删除异步生效，立即重拉很可能把"还没删完"的旧状态
     * 写回缓存，等于删了还在。空集合 = 目录内容整体变了，只作废缓存。未注入则忽略。
     */
    private val dirRemovals: Flow<Map<String, Set<String>>>? = null,
) : ViewModel() {

    /** 目录栈的一层：目录 cid + 显示名（面包屑与"全部文件"用得上） */
    @Serializable
    data class DirEntry(val cid: String, val name: String)

    data class UiState(
        val stack: List<DirEntry> = listOf(DirEntry(ROOT_CID, ROOT_NAME)),
        val items: List<FileItem> = emptyList(),
        /** 高级过滤后的可见列表（未启用过滤时 == items）。原始 items 永远保留，供刷新/批量操作 */
        val display: List<FileItem> = emptyList(),
        /** 当前生效的过滤方案（含就近继承解析结果）；null = 该目录不执行过滤 */
        val activeFilter: FilterRules.FilterScheme? = null,
        /** 已置顶文件夹的 fid（有序）；display 里的置顶条目按这个顺序排在最前 */
        val pinnedIds: List<String> = emptyList(),
        val count: Long = 0,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val selection: Set<String> = emptySet(),
        /** 视图模式：0=列表 1=小图标 2=大图标 */
        val viewMode: Int = 1,
        val order: String = "file_name",
        val asc: Int = 1,
        val typeFilter: Int? = null,
        val starOnly: Boolean = false,
        val searching: Boolean = false,
        val searchQuery: String = "",
        /**
         * 非空 = 当前这层目录是从**这条搜索**的结果里点进来的（值是那次的关键词）。
         * 界面据此显示"回到结果"条；返回键/返回箭头也是同一个动作（见 [popDir]）。
         */
        val searchBackQuery: String? = null,
        /**
         * 非空 = 从**侧栏操作记录**跳转过来时要指认的那一个文件（值是文件名）。
         *
         * 界面把它当"就是它"的标记：列表里同名那条铺淡蓝底（对齐 Windows 资源管理器选中态）、
         * 并把它滚进视野。文件名在同一个目录里唯一（115 不允许同名），所以按名字认不会认错；
         * 记录已被删除/改名或批量记录（"xxx 等 N 项"）时匹配不上，就是不高亮，不影响跳转本身。
         * 换目录 / 进搜索时清掉 —— 它是跟着"这一次跳转"来的一次性提示。
         */
        val highlight: String? = null,
        /**
         * 每次"带高亮的跳转"都 +1。值本身没有意义，只用来让**同一条记录再点一次**也被当成
         * 一次新请求（不然 highlight 没变，界面收不到通知，点了就像没反应、也不会重新滚回去）。
         */
        val highlightSeq: Long = 0,
        /**
         * highlight 的指认要不要"滚到它"：操作记录跳转 = true（滚过去 + 闪）；
         * 返回上级的闪烁 = false（只闪不滚 —— 回到哪里的位置交给滚动记忆恢复，
         * 两样都想动同一个列表只会互相打架）。
         */
        val scrollToHighlight: Boolean = true,
        /**
         * 当前 items 属于哪个"视角"：目录浏览 = 目录 cid，搜索 = "search:关键词"。
         * 由 load() 在写 items 的**同一次更新**里落下，派生 display 时原样带成 [displayKey]
         * —— 滚动位置的记忆与恢复都以它为键：数据真的换过来了才动手，避免"items 已换、
         * display 还没跟上"的窗口期里拿旧列表判锚点（返回上级回不到原位置的真因）。
         */
        val dataKey: String? = null,
        /** 派生列表（display）此刻属于哪个视角；与 display 在同一次更新里写，永远配套 */
        val displayKey: String? = null,
    ) {
        val selectMode: Boolean get() = selection.isNotEmpty()
    }

    /**
     * 从搜索结果点进某个目录时留下的"回去的路"：搜索前的目录链 + 进入的那一层
     * （[enteredPath]，用来判断用户还在不在这一支里）+ 当时的整份结果集。
     *
     * 为什么要有它：搜索状态在进目录时必须退出（load 靠 searching 区分"列目录"和"搜结果"），
     * 退出后返回键就只会沿目录链退到搜索前所在的那一层 —— 用户是从结果里跳进来的，
     * 想回去的是**那份结果**。把结果整份存下来，返回时原样端回去（零请求，连已翻的页都在），
     * 再按一次返回才真的退出搜索。
     */
    private data class SearchReturn(
        val stack: List<DirEntry>,
        val enteredPath: List<DirEntry>,
        val items: List<FileItem>,
        val count: Long,
        val query: String,
        val typeFilter: Int?,
    )

    private var searchReturn: SearchReturn? = null

    /** 高亮请求序号：每次点操作记录自增（用途见 [UiState.highlightSeq]） */
    private var highlightSeq = 0L

    /**
     * 这次列目录回来后要不要把目录栈补成真实祖先链，值是"要补的那个目录 cid"。
     * 由 [openByCid] 立起、[adoptRealPath] 消费（见那里的说明）。
     */
    private var pendingPathAdopt: String? = null

    /** 高亮到期清除的定时器：只留最后一次（后点的记录作废前一次的计时） */
    private var highlightClearJob: Job? = null

    /**
     * "可见列表"重算的输入指纹：其中任一项变化都必须重排。
     * 刻意不含 display 自身——重算结果写回 UiState 后指纹不变，
     * distinctUntilChanged 会吃掉这次回声，不会形成重算死循环。
     */
    private data class RenderKey(
        val items: List<FileItem>,
        val searching: Boolean,
        val filterOn: Boolean,
        val scheme: FilterRules.FilterScheme?,
        val pins: List<String>,
        /** 数据视角（见 UiState.dataKey）：items 换目录时它一起变，派生结果把 displayKey 带出去 */
        val dataKey: String?,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    // ---- 高级过滤状态 ----
    private val _schemes = MutableStateFlow(emptyList<FilterRules.FilterScheme>())

    /** 文件页右上角的过滤总开关（跨重启保留） */
    val filterOn: kotlinx.coroutines.flow.StateFlow<Boolean> =
        (filterPrefs?.toggle ?: kotlinx.coroutines.flow.MutableStateFlow(true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setFilterOn(v: Boolean) {
        viewModelScope.launch { filterPrefs?.setToggle(v) }
    }

    /** 是否存在已启用的方案：决定漏斗是"开关"还是"去创建"。避免开关关闭后漏斗变成死按钮 */
    val hasEnabledScheme: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _schemes.map { list -> list.any { it.enabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 条目最近浏览时间（key = "cid/文件名"）：只统计播放 / 看图 / 读文本三类操作。
     * 列表模式里给看过的条目补一行"最近浏览"。操作记录按条数裁剪，旧条目掉了
     * 只是时间行消失，无副作用。
     */
    val viewTimes: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> =
        (opLog?.entries ?: MutableStateFlow(emptyList()))
            .map { list ->
                list.filter {
                    it.type == OpType.VIDEO_PLAY.name ||
                        it.type == OpType.IMAGE_VIEW.name ||
                        it.type == OpType.TEXT_PREVIEW.name
                }.groupBy { "${it.cid}/${it.name}" }
                    .mapValues { (_, v) -> v.maxOf { it.at } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        // 先把上次的目录栈摆回原位（见 savedState）：必须**同步**做完，不能丢进协程 ——
        // 下面的置顶/过滤/加载都会读 stack，晚一步就会先按根目录请求一轮。
        restoreStack()

        // 目录栈一变就存档：退出播放器回来时（Activity 重建）靠它找回原地
        viewModelScope.launch {
            _ui.map { it.stack }.distinctUntilChanged().collect { saveStack(it) }
        }

        // 外部删除广播：缓存一律作废；正停在受影响目录就把被删条目就地拿掉。
        // 只动 items —— display 由下面的派生链按 items 重算，这里再改一遍会互相覆盖。
        dirRemovals?.let { flow ->
            viewModelScope.launch {
                flow.collect { changes ->
                    if (changes.isEmpty()) return@collect
                    changes.keys.forEach { cid -> cache?.invalidateDir(cid) }
                    val gone = changes[currentCid()]?.takeIf { it.isNotEmpty() } ?: return@collect
                    _ui.update { s ->
                        val removed = s.items.filter { it.fn in gone }
                        s.copy(
                            items = s.items.filter { it.fn !in gone },
                            count = (s.count - removed.size).coerceAtLeast(0),
                            selection = s.selection - removed.mapNotNullTo(HashSet()) { it.fid },
                        )
                    }
                }
            }
        }

        // 先恢复持久化的排序/视图/筛选状态，再加载列表
        viewModelScope.launch {
            prefs?.let { p ->
                _ui.update { s ->
                    s.copy(
                        order = p.order.first(),
                        asc = p.asc.first(),
                        viewMode = p.viewMode.first(),
                        typeFilter = p.typeFilter.first().takeIf { t -> t >= 0 },
                        starOnly = p.starOnly.first(),
                    )
                }
            }
            // ViewModel 重建时（进程还活着）缓存可能还是热的，能省掉一次请求
            loadCurrent()
        }

        // ---- 高级过滤 + 置顶：派生"可见列表" ----
        // items / 目录链 / 方案 / 总开关 / 置顶关系 任一变化都重算；display 与 activeFilter
        // 写回 UiState 后若值未变，StateFlow 会去重，不会形成回环。
        // 过滤计算放 Default 线程，千级列表无感。
        // 未注入 FilterPrefs / PinnedPrefs 时 display == items，UI 行为与从前完全一致。
        viewModelScope.launch { filterPrefs?.schemes?.collect { _schemes.value = it } }

        // 置顶关系（有序 fid）同步进 UiState：既给下面重排用，也给 UI 判断"选中项是否已置顶"
        viewModelScope.launch {
            pinnedPrefs?.pins?.collect { list ->
                _ui.update { it.copy(pinnedIds = list.map { p -> p.fid }) }
            }
        }

        viewModelScope.launch {
            combine(_ui, filterOn, _schemes) { ui, on, schemes ->
                RenderKey(
                    items = ui.items,
                    searching = ui.searching,
                    filterOn = on,
                    pins = ui.pinnedIds,
                    scheme = FilterRules.resolveScheme(
                        schemes,
                        ui.stack.map { FilterRules.DirRef(it.cid, it.name) },
                    ),
                    dataKey = ui.dataKey,
                )
            }
                .distinctUntilChanged()
                .collect { k ->
                    val filtered = if (!k.filterOn || k.scheme == null) k.items
                    else withContext(Dispatchers.Default) {
                        k.items.filter { FilterRules.evaluate(it, k.scheme.group) }
                    }
                    // 视频预览图：隐藏 `<视频名>预览图.jpg`、把它的缩略图借给视频条目（见 VideoPreview.kt）。
                    // **搜索态跳过** —— 搜索结果跨目录，同名预览图会配到别的目录的视频上。
                    val withPreviews = if (k.searching) filtered else applyVideoPreviews(filtered)
                    _ui.update {
                        it.copy(
                            display = applyPins(withPreviews, k.pins, k.searching),
                            activeFilter = if (k.filterOn) k.scheme else null,
                            displayKey = k.dataKey,
                        )
                    }
                }
        }
    }

    /**
     * 把已置顶的文件夹浮到列表最前，其余条目保持服务端返回的顺序。
     *
     * - 匹配用 fid 而不是名称：改名、移动都不会让置顶失效；
     *   反过来，置顶的文件夹被删掉后自然就从列表里消失，不需要额外清理。
     * - 只认文件夹（[FileItem.isDir]），呼应"指定文件夹置顶"的语义。
     * - 搜索结果不重排：那里是按相关度排的，把置顶项插到最前反而让人看不懂。
     * - 目录里没有置顶项时原样返回同一个 List 实例，避免列表做无谓的重组与重绘。
     */
    private fun applyPins(list: List<FileItem>, pins: List<String>, searching: Boolean): List<FileItem> {
        if (searching || pins.isEmpty() || list.isEmpty()) return list
        val rank = pins.withIndex().associate { (i, fid) -> fid to i }
        // 取置顶优先级；非文件夹、未置顶、无 fid 一律返回 MAX_VALUE（= 不参与置顶）
        fun rankOf(item: FileItem): Int {
            val fid = item.fid ?: return Int.MAX_VALUE
            if (!item.isDir) return Int.MAX_VALUE
            return rank[fid] ?: Int.MAX_VALUE
        }
        val (pinned, rest) = list.partition { rankOf(it) != Int.MAX_VALUE }
        if (pinned.isEmpty()) return list
        return pinned.sortedBy { rankOf(it) } + rest
    }

    /**
     * 强制刷新：**绕过缓存**重新拉第一页。
     * 点刷新按钮、以及所有写操作之后都走它——写完缓存本来就是脏的，重取即顺带更新。
     */
    fun refresh() = load(0, more = false, force = true)

    /**
     * 按当前条件加载第一页，**允许命中缓存**。
     * 切目录 / 返回上级 / 面包屑跳转 / 改排序 / 改筛选 / 进出搜索都走它。
     */
    private fun loadCurrent() = load(0, more = false, force = false)

    fun loadMore() {
        val s = _ui.value
        if (!s.loading && !s.loadingMore && s.items.size < s.count) load(s.items.size, more = true)
    }

    /** 当前条件下的缓存 key；搜索模式与"未注入缓存"都返回 null（即永不读写缓存） */
    private fun cacheKey(s: UiState): String? =
        cache?.key(s.stack.last().cid, s.order, s.asc, s.typeFilter, s.starOnly)

    private fun load(offset: Int, more: Boolean, force: Boolean = false) {
        viewModelScope.launch {
            // 只读一次快照：缓存 key 与请求参数都取自它，保证两者永远指向同一个目录/同一组条件。
            // （旧实现是在设置 loading 之后再读一次 _ui，中途切目录时两处会错位。）
            val s = _ui.value
            // 搜索模式的 key 随关键词变、结果按相关度排序、时效性也不同，一律不进缓存
            val key = if (s.searching) null else cacheKey(s)

            // 这次请求的"已有条目"基准：
            // - 分页：入口快照 s.items（offset 就是由它算出来的）
            // - 命中过期缓存：缓存里的那一份（**不是** s.items——s 是切目录之前的旧列表）
            // - 全新加载：空
            var servedFromCache = false
            var base: List<FileItem> = if (more) s.items else emptyList()

            // ---- ① 先吃缓存：命中就立刻出列表，用户不用等网络 ----
            if (key != null && !more && !force) {
                val hit = cache?.get(key)
                if (hit != null) {
                    base = hit.items
                    _ui.update {
                        it.copy(items = hit.items, count = hit.count, loading = false, error = null, dataKey = s.stack.last().cid)
                    }
                    // 命中缓存也要补路径：否则"60 秒内跳回同一个目录"只有两级，表现时好时坏
                    adoptRealPath(hit.path, fromCache = true)
                    // 还在新鲜期内：连后台请求都省掉——"来回进出一个目录"因此在 TTL 内零请求
                    if (cache?.isFresh(hit) == true) return@launch
                    servedFromCache = true
                }
            }

            // ---- ② 真实请求 ----
            // 已有缓存兜底时不再显示全屏转圈：列表就在屏幕上，再转圈是倒退。
            // 没有缓存才给 loading（首次进目录 / 改排序 / 搜索要让用户知道在加载）。
            _ui.update {
                when {
                    more -> it.copy(loadingMore = true)
                    servedFromCache -> it.copy(error = null)
                    else -> it.copy(loading = true, error = null)
                }
            }
            try {
                if (s.searching) {
                    val page = parseSearchResponse(api.search(s.searchQuery, offset = offset, type = s.typeFilter))
                    val mapped = page.data.map { it.toItem() }
                    _ui.update {
                        it.copy(
                            items = if (more) it.items + mapped else mapped,
                            count = page.count,
                            dataKey = "search:" + s.searchQuery,
                        )
                    }
                } else {
                    val page = parseFilesResponse(
                        api.files(
                            cid = s.stack.last().cid,
                            offset = offset,
                            order = s.order,
                            asc = s.asc,
                            type = s.typeFilter,
                            star = if (s.starOnly) 1 else null,
                        )
                    )
                    // 请求期间用户切走了：这份结果属于旧目录，直接丢掉。
                    // 否则会拿它覆盖当前目录的列表、并写进旧目录的缓存键里（切目录/连点排序都会踩）。
                    if (cacheKey(_ui.value) != key) return@launch

                    val next = when {
                        more -> base + page.items
                        // 过期缓存的后台刷新：**只换第一页、保留已加载的后续页**。
                        // 整表替换会把"已滚到第 400 条"的列表打回 200 条、滚动位置当场跳掉——
                        // 那正是缓存要修的问题，不能自己再造一遍。
                        servedFromCache && base.size > page.items.size ->
                            page.items + base.drop(page.items.size)
                        else -> page.items
                    }
                    _ui.update { it.copy(items = next, count = page.count, dataKey = s.stack.last().cid) }
                    // 写回缓存。分页必须把新页并到已有条目后面，否则下次进目录只剩第一页、
                    // 滚动位置也跟着对不上；缓存与视图共用同一份 next，保证两边永远一致。
                    if (key != null) {
                        cache?.put(
                            key,
                            DirCache.Entry(next, page.count, System.currentTimeMillis(), page.path),
                        )
                    }
                    // 按 cid 跳转的那一次：用响应里的 path[] 把面包屑补成真实层级
                    adoptRealPath(page.path)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }

    // ---- 下面这一组统一走 loadCurrent()（允许命中缓存）而不是 refresh()（强制重取）：
    // 切目录、改排序筛选都是"换一个视角看数据"，缓存 key 里带了参数，命不中自然会去请求。
    // 只有点刷新按钮和写操作之后才需要强制绕过缓存。

    /**
     * 记/清"回搜索"的路。**状态里的那条提示必须跟着一起动**：两者一旦不同步，
     * 界面上就会出现一条点了没反应的"回到结果"（或该有的提示不出现）。
     */
    private fun setSearchReturn(r: SearchReturn?) {
        searchReturn = r
        _ui.update { it.copy(searchBackQuery = r?.query) }
    }

    /** 当前浏览位置是否还在 [path] 这一支里（含 path 本身）；比 cid 不比名字，父目录改名不影响 */
    private fun onSearchPath(path: List<DirEntry>): Boolean {
        val s = _ui.value.stack
        if (s.size < path.size) return false
        return s.take(path.size).map { it.cid } == path.map { it.cid }
    }

    private fun dropSearchReturnIfOffPath() {
        val r = searchReturn ?: return
        if (!onSearchPath(r.enteredPath)) setSearchReturn(null)
    }

    fun openDir(item: FileItem) {
        val fid = item.fid ?: return
        // 手动进目录：栈是自己一级级压出来的，不需要（也不该）再用 path[] 覆盖
        pendingPathAdopt = null
        val s = _ui.value
        if (s.searching) {
            // 从搜索结果点进子目录：先记下"回去的路"（见 [SearchReturn]）。搜索状态本身仍要退出：
            // 否则 load() 里 s.searching 仍为 true，"进入"后加载的还是搜索结果而不是目录内容。
            setSearchReturn(
                SearchReturn(
                    stack = s.stack,
                    enteredPath = s.stack + DirEntry(fid, item.fn),
                    items = s.items,
                    count = s.count,
                    query = s.searchQuery,
                    typeFilter = s.typeFilter,
                ),
            )
        } else {
            dropSearchReturnIfOffPath()
        }
        _ui.update {
            it.copy(
                stack = it.stack + DirEntry(fid, item.fn),
                selection = emptySet(),
                searching = false,
                searchQuery = "",
                // 换了一层，之前"指认某个文件"的高亮不再属于这一屏
                highlight = null,
            )
        }
        loadCurrent()
    }

    /**
     * 返回上一级。**从搜索结果进来的那一层例外**：先回到搜索结果（见 [backToSearchResults]），
     * 再按一次返回才退出搜索、回到目录浏览。
     */
    fun popDir() {
        if (backToSearchResults()) return
        pendingPathAdopt = null
        val s = _ui.value
        if (s.stack.size > 1) {
            // 返回上级时顺手把"刚出来的那层"闪几下（操作记录跳转的同款特效）：
            // 只提示、不滚动 —— 滚回哪里交给滚动记忆恢复，见 FilesBrowserPane 的跟踪/恢复效果
            highlightSeq++
            scheduleHighlightClear(highlightSeq)
            _ui.update {
                it.copy(
                    stack = it.stack.dropLast(1),
                    selection = emptySet(),
                    highlight = s.stack.last().name,
                    highlightSeq = highlightSeq,
                    scrollToHighlight = false,
                )
            }
            // 绕出这一支之后，"回搜索"的路作废：用户已经在别的地方看东西了，
            // 再返回时冒出一份旧结果只会莫名其妙
            dropSearchReturnIfOffPath()
            loadCurrent()
        }
    }

    /**
     * 回到这次搜索的结果页（结果整份端回去，零请求、连已翻的页都在）。
     * 返回 false = 当前没有可回的结果页（不在"从搜索结果进来的那一层"）。
     */
    fun backToSearchResults(): Boolean {
        val r = searchReturn ?: return false
        // 只在"正站在从结果里点进来的那个目录"时成立；更深一层（结果 > A > B）先按普通返回退，
        // 退回到 A 再按才回结果 —— 与浏览器的原路返回一致
        if (_ui.value.stack.size != r.enteredPath.size) return false
        pendingPathAdopt = null
        setSearchReturn(null)
        _ui.update {
            it.copy(
                stack = r.stack,
                selection = emptySet(),
                searching = true,
                searchQuery = r.query,
                typeFilter = r.typeFilter,
                items = r.items,
                count = r.count,
                error = null,
                highlight = null,
                dataKey = "search:" + r.query,
            )
        }
        return true
    }

    fun jumpTo(index: Int) {
        // 已在目标层级时直接返回，避免一次无谓的列表刷新
        if (index >= _ui.value.stack.lastIndex) return
        pendingPathAdopt = null
        _ui.update {
            it.copy(stack = it.stack.subList(0, index + 1), selection = emptySet(), highlight = null)
        }
        dropSearchReturnIfOffPath()
        loadCurrent()
    }

    /**
     * 从操作记录跳转到任意目录：stack 重置为「全部文件 > 该目录」。
     * 拿不到完整中间路径（cid 无法反查），两级面包屑是最诚实的表达。
     *
     * [highlight] 非空 = 顺带指认这个文件（界面上会高亮它，见 [UiState.highlight]）。
     */
    fun openByCid(cid: String, name: String, highlight: String? = null) {
        // 带高亮（点操作记录）的跳转每次都算一次新请求：seq 自增，界面据此重新指认并滚过去；
        // 顺手排一个"闪完就收"的定时器 —— 提醒过了就不该继续占着底色
        if (highlight != null) {
            highlightSeq++
            scheduleHighlightClear(highlightSeq)
        } else if (cid == currentCid()) {
            // 没有指认目标、又已经站在这个目录里：与从前一致，什么都不做
            return
        }

        if (cid == currentCid()) {
            // 已经在这个目录里（从侧面点了一条本目录的记录）：不重新加载，只更新"就是它"的标记，
            // 界面据此高亮并滚过去。从前这里直接 return，点了等于没反应 —— 同一个目录点记录是常见动作
            _ui.update { it.copy(highlight = highlight, highlightSeq = highlightSeq, scrollToHighlight = true) }
            // 顺手把真实路径补上（这次不重新列目录，所以只能吃缓存里那份 path）
            pendingPathAdopt = cid
            cacheKey(_ui.value)?.let { k -> adoptRealPath(cache?.get(k)?.path.orEmpty()) }
            return
        }
        // 先按"两级面包屑"摆好（界面立刻有反应），等列目录的结果回来再用响应里的 path[] 补成真实链
        pendingPathAdopt = cid
        _ui.update {
            it.copy(
                stack = listOf(DirEntry(ROOT_CID, ROOT_NAME), DirEntry(cid, name)),
                selection = emptySet(),
                searching = false,
                searchQuery = "",
                highlight = highlight,
                highlightSeq = highlightSeq,
                scrollToHighlight = true,
            )
        }
        // 换了一条完全不同的路：即便 stack 深度凑巧和"从搜索进来的那层"一样，也不能再回那份结果
        setSearchReturn(null)
        loadCurrent()
    }

    /**
     * 把这次列目录响应里的祖先链补到目录栈上（见 [normalizePathChain]）。
     *
     * 只有**按 cid 跳转**才需要它（操作记录 / 快捷目录 / 云下载 / 离线任务，都走 [openByCid]）：
     * 那些入口手里只有一个 cid，反查不到祖先，从前只能硬写两级「全部文件 > 目标」——
     * 面包屑丢层级，而且按返回键会一步弹回根目录。
     *
     * 三道闸，缺一不可：① 只认"这次跳转要补的那个目录"（[pendingPathAdopt]）；
     * ② 用户已经切走了（栈末不是它）就不动；③ 补出来不比现在更长也不动。
     * **栈末一项始终还是同一个目录**，所以 cacheKey / 滚动记忆 / currentCid 全都不受影响。
     */
    private fun adoptRealPath(path: List<PathNode>, fromCache: Boolean = false) {
        val target = pendingPathAdopt ?: return
        // 这份响应没有祖先链（缓存里的老条目、搜索接口）：标记留着，等下一次带 path 的响应
        if (path.isEmpty()) return
        pendingPathAdopt = null
        val cur = _ui.value.stack.last()
        if (cur.cid != target) return
        val chain = normalizePathChain(path, cur.cid, cur.name)
        if (chain.size <= _ui.value.stack.size) return
        _ui.update { it.copy(stack = chain) }
        // 日志里带上来源：排查"为什么这次只有两级"时，一眼能看出是走了缓存的旧条目还是真没 path
        Log.i(TAG, "补全目录路径（${if (fromCache) "缓存" else "网络"}）：" + chain.joinToString(" / ") { it.name })
    }

    fun setSort(order: String, asc: Int) {
        _ui.update { it.copy(order = order, asc = asc) }
        persistState()
        loadCurrent()
    }

    fun setType(type: Int?) {
        _ui.update { it.copy(typeFilter = type) }
        persistState()
        loadCurrent()
    }

    fun toggleStarOnly() {
        _ui.update { it.copy(starOnly = !it.starOnly) }
        persistState()
        loadCurrent()
    }

    fun setViewMode(mode: Int) {
        _ui.update { it.copy(viewMode = mode.coerceIn(0, 2)) }
        persistState()
    }

    /** 排序/视图/筛选持久化 */
    private fun persistState() {
        val p = prefs ?: return
        val s = _ui.value
        viewModelScope.launch {
            p.setSort(s.order, s.asc)
            p.setViewMode(s.viewMode)
            p.setTypeFilter(s.typeFilter)
            p.setStarOnly(s.starOnly)
        }
    }

    fun search(query: String) {
        _ui.update {
            it.copy(
                searching = query.isNotBlank(),
                searchQuery = query.trim(),
                // 搜索结果是内容完全不同的另一份列表，之前"指认某个文件"的高亮留在上面只会认错行；
                // 把关键词清空（回到目录浏览）时保留它
                highlight = if (query.isNotBlank()) null else it.highlight,
            )
        }
        // 搜索不进缓存（key 为 null），这里用 loadCurrent 只是"不强制重取"，
        // 效果与刷新一致：真的会去请求
        loadCurrent()
    }

    fun exitSearch() {
        _ui.update { it.copy(searching = false, searchQuery = "") }
        // 这一次搜索到此为止：连带清掉"回结果"的路 —— 用户已经主动退出搜索了，
        // 之后某次返回再冒出一份旧结果只会莫名其妙
        setSearchReturn(null)
        // 退出搜索回到目录浏览：大概率能命中缓存，回来即刻就有内容
        loadCurrent()
    }

    fun toggleSelect(id: String?) {
        if (id == null) return
        _ui.update {
            it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
        }
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    /** 上传目标目录（当前所在目录） */
    fun currentTargetCid(): String = currentCid()

    // ---------- 操作 ----------

    suspend fun addFolder(name: String): String? = runOp {
        val resp = api.addFolder(currentCid(), name)
        val ok = resp.envOk()
        if (ok) refresh()
        ok to (resp.envMsg() ?: "创建失败")
    }

    suspend fun rename(item: FileItem, newName: String): String? = runOp {
        val fid = item.fid ?: return@runOp false to "缺少文件ID"
        val resp = api.updateFile(fid, newName)
        val ok = resp.envOk()
        if (ok) {
            // 改名**不顶 115 的目录 upt**（实测），而媒体库的快速扫描正是靠"父目录看到的 upt 没变"
            // 跳过目录的 —— 不在这里作废该目录的扫描状态，改完名那一轮扫描会当成"没变"跳过，
            // 库里一直挂旧文件名。增量/全量扫描不需要这个（它们每个目录都列），但代价只是一次 UPDATE。
            invalidateMediaDir(currentCid())
            refresh()
        }
        ok to (resp.envMsg() ?: "重命名失败")
    }

    suspend fun toggleStarItem(item: FileItem): String? = runOp {
        val fid = item.fid ?: return@runOp false to "缺少文件ID"
        val resp = api.updateFile(fid, star = if (item.ism == 1) 0 else 1)
        val ok = resp.envOk()
        if (ok) refresh()
        ok to (resp.envMsg() ?: "操作失败")
    }

    /**
     * 置顶 / 取消置顶（只对文件夹有效，选中的非文件夹会被忽略）。
     *
     * 规则：所选文件夹**全部已置顶**时整体取消置顶，否则把还没置顶的补上——
     * 混合选择时"补全"比"反向翻转"更符合直觉，不会把已经置顶的反而取消掉。
     *
     * 返回值直接就是"要展示给用户的文案"：本方法全是本机操作（DataStore，不碰网络），
     * 没有 [runOp] 里那条"网络错误"分支要走，成功与失败都直接给一句话反而更省事。
     */
    suspend fun togglePins(items: List<FileItem>): String {
        val prefs = pinnedPrefs ?: return "置顶功能未初始化"
        val folders = items.filter { it.isDir && it.fid != null }
        if (folders.isEmpty()) return "只有文件夹可以置顶"
        val pinnedNow = _ui.value.pinnedIds.toSet()
        val already = folders.count { it.fid in pinnedNow }
        return try {
            if (already == folders.size) {
                prefs.unpinAll(folders.mapNotNull { it.fid })
                if (folders.size == 1) "已取消置顶「${folders[0].fn}」"
                else "已取消 ${folders.size} 个文件夹的置顶"
            } else {
                val pid = currentCid()
                val now = System.currentTimeMillis()
                var added = 0
                folders.forEach { f ->
                    val fid = f.fid ?: return@forEach
                    if (fid in pinnedNow) return@forEach
                    prefs.pin(PinnedFolder(fid, f.fn, pid, now))
                    added++
                }
                if (folders.size == 1) "已置顶「${folders[0].fn}」，将在本目录内置顶显示"
                else "已置顶 $added 个文件夹"
            }
        } catch (e: Exception) {
            "操作失败：${e.message}"
        }
    }

    /** 选中项的展示名："a.jpg" 或 "a.jpg 等 N 项"；列表里找不到就退化为"N 项" */
    private fun selectionNames(ids: Collection<String>): String {
        val names = _ui.value.items.filter { it.fid in ids }.map { it.fn }
        if (names.isEmpty()) return "${ids.size} 项"
        return if (names.size == 1) names[0] else "${names[0]} 等 ${names.size} 项"
    }

    suspend fun deleteSelected(): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        val fromCid = currentCid()
        val fromName = _ui.value.stack.last().name
        return runOp {
            val resp = api.deleteFiles(ids.joinToString(","), parentId = fromCid)
            val ok = resp.envOk()
            if (ok) {
                // 置顶的文件夹被删掉后，置顶记录已无意义——顺手清理，避免记录无限累积
                pinnedPrefs?.unpinAll(ids)
                opLog?.log(OpType.DELETE, selectionNames(ids), "移入回收站", fromCid, fromName)
                clearSelection()
                refresh()
            }
            ok to (resp.envMsg() ?: "删除失败")
        }
    }

    /**
     * 操作记录里的「删除」一键恢复：去回收站把这条记录删掉的东西找回来（recycleRevert），
     * 成功后作废原目录的列表缓存（下次进入就是真实内容；正停在原目录时由调用方负责刷新）。
     *
     * 匹配规则：单条记录（名字就是文件名）按 原目录 cid + 文件名 精确认；批量记录
     * （"xxx 等 N 项"，其余名字没记）退而按 原目录 cid + 删除时间窗认（记录时刻前 2 分钟起算，
     * 回收站的 dtime 是秒）。找不到就明说，**绝不**把同目录里别的删除捞回来。
     *
     * 返回值 = 要展示给用户的文案。
     */
    suspend fun restoreFromOpLog(op: OpEntry): String {
        val cid = op.cid ?: return "这条记录没有目录信息，无法自动恢复"
        val batch = " 等 " in op.name && op.name.endsWith("项")
        val dtimeFrom = op.at / 1000 - 120
        val matches = mutableListOf<RecycleItem>()
        try {
            var offset = 0
            while (matches.isEmpty() && offset < 2000) {
                val root = api.recycleList(limit = 200, offset = offset)
                if (!root.envOk()) return root.envMsg() ?: "获取回收站列表失败"
                val items = parseRecycleList(root.envData() ?: JsonObject(emptyMap()))
                matches += items.filter {
                    it.cid == cid && (if (batch) it.dtime >= dtimeFrom else it.name == op.name)
                }
                if (items.size < 200) break
                offset += items.size
            }
        } catch (e: Exception) {
            return "网络错误：${e.message}"
        }
        if (matches.isEmpty()) {
            return if (batch) "回收站里没找到这批删除（可能已还原或已彻底删除）"
            else "回收站里没找到「${op.name}」（可能已还原或已彻底删除）"
        }
        return try {
            val resp = api.recycleRevert(matches.joinToString(",") { it.id })
            if (resp.envOk()) {
                cache?.invalidateDir(cid)
                "已恢复 ${matches.size} 项到原目录"
            } else {
                resp.envMsg() ?: "恢复失败"
            }
        } catch (e: Exception) {
            "网络错误：${e.message}"
        }
    }

    suspend fun moveSelected(toCid: String, toName: String): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        // 移动成功后条目从当前目录消失，"少了几项"很容易被忽略，所以明确告知去处
        return runOp(onSuccess = "已移动到「$toName」") {
            val resp = api.moveFiles(ids.joinToString(","), toCid)
            val ok = resp.envOk()
            if (ok) {
                // 跳转去目标目录：文件现在在那
                opLog?.log(OpType.MOVE, selectionNames(ids), "移动到「$toName」", toCid, toName)
                clearSelection()
                // 目标目录的缓存脏了（多出这几条），但它不在当前视图里，只能显式失效。
                // 先失效再 refresh：万一 toCid 就是当前目录，后面那次重取会把新数据重新写回去。
                cache?.invalidateDir(toCid)
                // 当前目录强制重取，顺带把它的缓存刷成最新
                refresh()
            }
            ok to (resp.envMsg() ?: "移动失败")
        }
    }

    suspend fun copySelected(toCid: String, toName: String): String? {
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return null
        val fromCid = currentCid()
        // 复制到别的目录后当前目录看不出任何变化，不给提示就等于"点了没反应"
        return runOp(onSuccess = "已复制到「$toName」") {
            val resp = api.copyFiles(pid = toCid, fileIds = ids.joinToString(","))
            val ok = resp.envOk()
            if (ok) {
                // 跳转去目标目录：副本在那
                opLog?.log(OpType.COPY, selectionNames(ids), "复制到「$toName」", toCid, toName)
                clearSelection()
                cache?.invalidateDir(toCid)
                // 复制不动源目录，所以只有"目标就是当前目录"（当场多出副本）时才需要重取；
                // 其余情况这一跳请求是白花的，缓存机制顺手把它省掉
                if (toCid == fromCid) refresh()
            }
            ok to (resp.envMsg() ?: "复制失败")
        }
    }

    /** 直链解析逻辑收敛到 ImageUrlResolver：与图片查看器共用同一份防频控缓存 */
    suspend fun getDownloadUrl(pickCode: String): Result<String> = runCatching {
        val resolver = urlResolver ?: error("下载地址解析器未初始化")
        resolver.downloadUrl(pickCode)
    }

    fun currentCid(): String = _ui.value.stack.last().cid

    /**
     * 批量重命名的目标：已选中项，**按列表显示顺序**返回。
     *
     * 顺序必须取自 [UiState.display] 而不是 selection —— 后者是无序的 `Set<String>`，
     * 拿它当顺序来源的话编号会和预览列表对不上（display 已经过筛选与置顶重排，
     * 正是用户看到的那个顺序）。fid 为空的项无法定位到云端文件，直接排除。
     */
    fun selectedInDisplayOrder(): List<FileItem> {
        val s = _ui.value
        return s.display.filter { it.fid != null && it.fid in s.selection }
    }

    /**
     * 全选 / 取消全选。
     *
     * 只作用于**当前显示**的条目（display 已过筛选与置顶重排，正是用户看到的那些）；
     * 搜索态下 display 就是结果集，所以搜索时全选的是搜索结果。
     * 已经全选时再点一次 = 取消全选，与回收站页的 toggleSelectAll 行为一致。
     */
    fun toggleSelectAll() {
        _ui.update { s ->
            val ids = s.display.mapNotNull { it.fid }.toSet()
            val allSelected = ids.isNotEmpty() && s.selection.containsAll(ids)
            s.copy(selection = if (allSelected) emptySet() else ids)
        }
    }

    /**
     * 执行一次接口操作，把结果翻译成"要不要提示用户、提示什么"（返回 null = 不打扰）。
     *
     * [onSuccess] 是成功时要给的文案，默认 null = **成功静默**。默认静默是有意的：
     * 重命名、星标、删除、新建这些操作完成后列表本身就有变化，再弹一句"操作成功"
     * 纯属噪音。只有**界面上看不出结果**的动作才需要说一声——移动和复制就是：
     * 东西从当前目录消失了，不明确告知的话，用户会以为按钮没点动。
     *
     * 旧实现无条件把成功结果丢成 null，于是 moveSelected / copySelected 里写好的
     * "已移动到「x」"成了死代码、成功后毫无反馈。这个参数就是为修它加的；
     * 其余调用点走默认值，行为与从前逐字一致。
     */
    private inline fun runOp(onSuccess: String? = null, block: () -> Pair<Boolean, String>): String? {
        return try {
            val (ok, msg) = block()
            if (ok) onSuccess else msg
        } catch (e: Exception) {
            "网络错误：${e.message}"
        }
    }

    // ---- 目录栈的存档/还原（退出播放器后回到原目录，见构造参数 savedState） ----

    /** 还原上次的目录栈；没有存档 / 存档就是根目录，都保持默认值不动 */
    private fun restoreStack() {
        val h = savedState ?: return
        val restored = decodeStack(h.get<String>(KEY_STACK)) ?: return
        // "0" 就是默认那一层（全部文件），存了等于没存 —— 顺便挡掉只剩一项的脏存档
        if (restored.last().cid == ROOT_CID) return
        _ui.value = _ui.value.copy(stack = restored)
    }

    private fun saveStack(stack: List<DirEntry>) {
        val h = savedState ?: return
        h[KEY_STACK] = encodeStack(stack)
    }

    /**
     * 高亮闪几下就收。界面那边只闪 1.8 秒左右（见 Items.kt 的 HIGHLIGHT_FLASH_*），
     * 这里稍晚一点把状态清掉：状态留着虽然看不出底色，但这一行被滚出屏幕再滚回来时
     * 组合会重建、动画会重放，等于又闪一遍 —— 用户要的是"提醒一下"，不是反复提醒。
     */
    private fun scheduleHighlightClear(seq: Long) {
        highlightClearJob?.cancel()
        highlightClearJob = viewModelScope.launch {
            delay(HIGHLIGHT_CLEAR_MS)
            // 这期间又点了别的记录（seq 变了）就不清，新的那次自己带着计时器
            if (_ui.value.highlightSeq == seq) _ui.update { it.copy(highlight = null) }
        }
    }

    companion object {
        /** 高亮状态清掉的时刻（略晚于界面的 3 下闪烁 ≈1.8s） */
        private const val HIGHLIGHT_CLEAR_MS = 2_200L

        /** 目录栈里"根"那一层：115 的根 cid 就是 "0" */
        internal const val ROOT_CID = "0"
        internal const val ROOT_NAME = "全部文件"

        private const val TAG = "Files"

        /**
         * 把云端列目录响应里的祖先链（`data.path`，见 [com.open115.pad.data.PathNode]）归一化成目录栈。
         *
         * 云端那条链**含不含根、含不含被请求的那个目录自身都不保证**，所以统一补齐，
         * 只保证两件事：首项是「全部文件」那一层、末项是目标目录。
         *
         * 脏数据也算进来：cid 为空的节点丢掉；连续重复的同一层去重（云端偶发把同一层给两遍）。
         * 目标是根的话直接返回只有一层 —— 否则会拼出「全部文件 > 全部文件」。
         */
        internal fun normalizePathChain(
            nodes: List<PathNode>,
            targetCid: String,
            targetName: String,
        ): List<DirEntry> {
            if (targetCid == ROOT_CID) return listOf(DirEntry(ROOT_CID, ROOT_NAME))
            val chain = nodes.mapNotNull { n ->
                n.cid?.takeIf { it.isNotBlank() }?.let { cid -> DirEntry(cid, n.name.ifBlank { cid }) }
            }
            val full = if (chain.lastOrNull()?.cid == targetCid) chain
            else chain + DirEntry(targetCid, targetName)
            val withRoot = if (full.first().cid == ROOT_CID) full
            else listOf(DirEntry(ROOT_CID, ROOT_NAME)) + full
            val out = mutableListOf<DirEntry>()
            for (e in withRoot) {
                if (out.lastOrNull()?.cid == e.cid) continue        // 连续重复层只留一个
                // 根那一层的名字以本应用为准：云端给的是「文件」，而手动导航时这一层显示「全部文件」——
                // 同一个位置两种叫法会让人以为跳转到了别的地方
                out += if (e.cid == ROOT_CID) DirEntry(ROOT_CID, ROOT_NAME) else e
            }
            return out
        }

        /** 目录栈在 SavedStateHandle 里的键 */
        private const val KEY_STACK = "files.dir_stack"

        /**
         * 目录栈存成 JSON 字符串。**刻意不用 ArrayList**：SavedStateHandle 会把值放进 Bundle，
         * 只有基本类型/Parcelable/Serializable 活得过那一趟，集合会被静默丢成 null。
         */
        internal fun encodeStack(stack: List<DirEntry>): String =
            runCatching { Json.encodeToString(ListSerializer(DirEntry.serializer()), stack) }
                .getOrDefault("")

        /** 解不出来（格式变过 / 存档损坏）一律当没有存档，行为退回到根目录，坏存档不会让页面打不开 */
        internal fun decodeStack(raw: String?): List<DirEntry>? = raw
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching { Json.decodeFromString(ListSerializer(DirEntry.serializer()), it) }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
    }
}

val filterTypes: List<Pair<String, Int?>> = listOf(
    "全部" to null,
    "视频" to 4,
    "图片" to 2,
    "音乐" to 3,
    "文档" to 1,
    "压缩包" to 5,
    "应用" to 6,
    "书籍" to 7,
)

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    expanded: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlayVideo: (item: FileItem, playlist: List<PlaylistEntry>, index: Int, opLogCid: String?, opLogPath: String?) -> Unit,
    onOpenGallery: (items: List<ImageMediaItem>, index: Int, sourcePath: String) -> Unit,
    onPreviewText: (item: FileItem) -> Unit,
    /** 多选时进批量重命名面板；入参里带上了整个目录的列表，面板可一键扩到全目录 */
    onBatchRename: (BatchRenameRequest) -> Unit,
    /** 查找重复文件：把"从当前层开始扫"的请求交给根层（面板与批量重命名同层渲染） */
    onFindDuplicates: (DupScanRequest) -> Unit = {},
    /** 批量生成视频预览图：与查重同层渲染面板，只读（不动云端） */
    onGeneratePreviews: (PreviewRequest) -> Unit = {},
    onOpenFilterRules: () -> Unit,
    /** 云下载页点任务跳过来的待打开目录 (cid, name)：进页即 openByCid，然后回调置空 */
    pendingJump: Pair<String, String>? = null,
    onJumpConsumed: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 跨页跳转（云下载 → 文件）：进页即打开目标目录，消费掉待处理槽位
    LaunchedEffect(pendingJump) {
        pendingJump?.let { (cid, name) ->
            vm.openByCid(cid, name)
            onJumpConsumed()
        }
    }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf<String?>(null) }
    var downloadTarget by remember { mutableStateOf<FileItem?>(null) }
    // 分类整理：任务挂在应用级 FileOrganizer 上（切页不停、前台服务跟着亮），
    // 这里只负责"弹确认框、发车、攒一句结束语"
    val organizer = context.appContainer.fileOrganizer
    val organizing by organizer.progress.collectAsState()
    val organizeExclusions by organizer.exclusions.collectAsState()
    var showOrganizeConfirm by remember { mutableStateOf(false) }
    // 例外勾选弹层：确认框里（发车前）和横幅上（执行中）共用同一份条目来源 = 当前目录列表
    var showOrganizeExclusions by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var sideCollapsed by rememberSaveable { mutableStateOf(true) }

    // ---- 双面板模式（类 MT 管理器）：内容区换成左右两栏，跨栏一键移动/复制 ----
    // 开关记忆两层：rememberSaveable 管旋转/重建，FilesPrefs 管跨启动恢复
    var dualMode by rememberSaveable { mutableStateOf(false) }
    val appContainer = context.appContainer
    LaunchedEffect(Unit) {
        dualMode = appContainer.filesPrefs.dualPane.first()
    }
    /** 视频行下面的宽幅预览条（设置 → 界面可关）：实时跟随，关掉立即收起 */
    val showVideoStrip by appContainer.filesPrefs.videoStrip.collectAsState(initial = true)
    // 右栏是第二个完整的 FilesViewModel 实例：移动/复制/操作记录/缓存全走同一套。
    // key 区分于主 vm；退出双栏不销毁（挂在导航条目上），再进来两栏还停在原目录
    val vmB: FilesViewModel = viewModel(key = "dualPaneB", initializer = {
        FilesViewModel(
            appContainer.openApi,
            appContainer.filesPrefs,
            appContainer.imageUrlResolver,
            appContainer.filterPrefs,
            appContainer.pinnedPrefs,
            appContainer.dirCache,
            appContainer.opLog,
            invalidateMediaDir = { cid ->
                appContainer.mediaDatabase.mediaDao().invalidateScanState(cid)
            },
            dirRemovals = appContainer.dirRemovals,
        )
    })
    // 右栏的 UI 状态：返回键分级处理在双栏时要拿它判断"活动栏"的层级深度/多选
    val uiB by vmB.ui.collectAsState()
    // 双栏下"最近操作的是哪一栏"：触摸过哪栏（点行/滚动都算），返回键就响应哪栏。
    // 进双栏默认左栏（用户是从主栏进来的），退出再进也回到左栏
    var dualActiveLeft by rememberSaveable { mutableStateOf(true) }
    fun toggleDualPane() {
        dualMode = !dualMode
        scope.launch { appContainer.filesPrefs.setDualPane(dualMode) }
        if (dualMode) {
            dualActiveLeft = true
        } else {
            // 退回单面板：勾选还挂着会带进普通视图的操作条，直接清掉
            vm.clearSelection()
            vmB.clearSelection()
        }
    }

    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    // 拖动多选 → 快捷目录：host 由文件行（手势源）与侧栏条目（落点）共享
    val quickDirDrag = remember { QuickDirDragHost() }
    fun onQuickDirDrop(cid: String, name: String) {
        // 目标就是当前目录：接口会"成功"但其实什么都没动，直接拦下
        if (cid == vm.currentCid()) {
            notify("文件已经在这个目录里了")
            return
        }
        scope.launch { notify(vm.moveSelected(cid, name)) }
    }

    // 重命名任务结束后刷新列表。
    // 刷新**不能**放在面板里：任务归持久化队列管，关面板、切页、杀进程都不停，
    // 用户很可能早就走开了。这里靠"最新的结束时间戳"变化触发，并且只在该任务
    // 正好属于当前目录时才刷 —— 别的目录改了不必白刷一遍。
    val renameTasks by context.appContainer.renameQueue.tasks.collectAsState()
    val lastRenameFinish = renameTasks.maxOfOrNull { it.finishedAt ?: 0L } ?: 0L
    LaunchedEffect(lastRenameFinish) {
        if (lastRenameFinish > 0L &&
            renameTasks.any { it.finishedAt == lastRenameFinish && it.cid == vm.currentCid() }
        ) {
            vm.refresh()
        }
    }

    // 查重删完之后刷新列表：文件被移进回收站了，列表里那几条不该还在。
    // 判据跟重命名那套一样 —— "最新的删除时刻 + 动没动过当前目录"，在别的目录删就不用白刷。
    val dedupe = context.appContainer.duplicateFinder
    val dedupeProgress by dedupe.progress.collectAsState()
    LaunchedEffect(dedupeProgress.lastDeletedAt) {
        if (dedupeProgress.lastDeletedAt > 0L && vm.currentCid() in dedupeProgress.lastDeletedCids) {
            vm.refresh()
        }
    }

    // 从搜索结果点进目录后再返回时，搜索状态是 VM 端回来的（searching 重新为 true）——
    // 这里把搜索栏一并展开，用户看到的就是刚才那份结果（关键词也还在）
    LaunchedEffect(ui.searching) {
        if (ui.searching) searchMode = true
    }

    // 分类整理结束后刷新列表（目录内容变了）。
    // **完成提示不在文件页发** —— AppRoot 统一管三类任务（扫描/重命名/整理）的收尾提示与通知，
    // 那边发一次就够，这里再发会双份。finishedAt 仍是唯一判据：0 = 没有过结果；换新值 = 有一条没处理过。
    // lastOrganizeFinishedAt 挡住"离开文件页再回来"时的重放（rememberSaveable 存档跨重组有效）。
    var lastOrganizeFinishedAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(organizing.finishedAt) {
        val fin = organizing.finishedAt
        if (fin == 0L || fin == lastOrganizeFinishedAt) return@LaunchedEffect
        lastOrganizeFinishedAt = fin
        if (organizing.rootCid == vm.currentCid()) vm.refresh()
    }

    // ---- 返回键分级处理（后注册的优先级更高）----
    // 双栏时作用于"最近操作的那一栏"（dualActiveLeft，由各栏的触摸观察回报），单栏就是主栏
    val backVm = if (dualMode && !dualActiveLeft) vmB else vm
    val backUi = if (dualMode && !dualActiveLeft) uiB else ui
    // ① 浏览子目录时：返回上一级；根目录"返无可返"时不拦截，交给系统退出应用
    androidx.activity.compose.BackHandler(enabled = backUi.stack.size > 1) { backVm.popDir() }
    // ② 多选模式下：先退出多选
    androidx.activity.compose.BackHandler(enabled = backUi.selectMode) { backVm.clearSelection() }
    // ③ 搜索栏展开时：先收起搜索（搜索栏只在单面板出现，不随双栏切栏）
    androidx.activity.compose.BackHandler(enabled = searchMode) {
        searchMode = false
        vm.exitSearch()
    }

    // ---- 断点续传：进程重启后恢复中断的大文件上传（每次进程只认领一次）----
    // 记录里落库了 SAF uri + pick_code + oss_upload_id，重启后按 ListParts 跳过
    // 已传分片继续；原文件不可访问（权限失效/被删）则把记录标记为失败。
    // 用户主动暂停的记录（paused=true）不会被 claim，保持暂停等手动继续。
    LaunchedEffect(Unit) {
        val log = context.appContainer.transferLog
        val pending = log.claimPendingUploads()
        if (pending.isEmpty()) return@LaunchedEffect
        notify("发现 ${pending.size} 个未完成上传，恢复中…")
        for (rec in pending) context.appContainer.transferScope.launch {
            runCatching { resumeUploadRecord(context, log, vm.api, rec) }
                .onSuccess { r ->
                    notify("续传完成：" + rec.name + if (r.reused) "（秒传）" else "")
                    vm.refresh()
                }
                .onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) {
                        notify("续传失败：" + rec.name + "（${e.message}）")
                    }
                }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // 挂应用级 transferScope：切页/换目录不中断上传；暂停与取消由传输中心显式操作
        if (uri != null) context.appContainer.transferScope.launch {
            notify("开始上传…")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // 持久化读权限：分片上传中断后重启进程仍能按 uri 打开原文件续传
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    // SAF 不同来源的 uri 形态不同（数字 id / path），文件名以 DISPLAY_NAME 查询为准
                    var name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "upload.bin"
                    runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.let { c ->
                            if (c.moveToFirst()) {
                                val n = c.getString(0)
                                if (!n.isNullOrBlank()) name = n
                            }
                            c.close()
                        }
                    }
                    val targetCid = vm.currentTargetCid()
                    // 传输中心要能回答"传到哪个目录了"，这里给面包屑全路径最直观
                    val targetPath = ui.stack.joinToString(" / ") { it.name }
                    val smallLimit = 32L * 1024 * 1024 // 超过 32MB 走分片上传（流式不占内存）
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法读取所选文件")
                    pfd.use {
                        val size = it.statSize
                        if (size <= smallLimit) {
                            // 小文件：整文件读内存直传（原链路不动）
                            val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
                                ?: error("无法读取所选文件")
                            Uploader.uploadSmallLogged(
                                log = context.appContainer.transferLog,
                                api = vm.api,
                                fileName = name,
                                bytes = bytes,
                                targetCid = targetCid,
                                targetName = targetPath,
                            )
                        } else {
                            // 大文件：流式分片上传（SHA1 流式算，不占内存，无大小上限）；
                            // uri 落库供进程重启后续传
                            Uploader.uploadLargeLogged(
                                log = context.appContainer.transferLog,
                                api = vm.api,
                                fileName = name,
                                pfd = it,
                                size = size,
                                targetCid = targetCid,
                                targetName = targetPath,
                                uri = uri.toString(),
                            )
                        }
                    }
                }
            }
            result.onSuccess {
                notify("上传成功：" + it.fileName + if (it.reused) "（秒传）" else "")
                context.appContainer.opLog.log(
                    OpType.UPLOAD,
                    it.fileName,
                    "上传到「" + ui.stack.joinToString(" / ") { s -> s.name } + "」" + if (it.reused) "（秒传）" else "",
                    vm.currentTargetCid(),
                    ui.stack.last().name,
                )
                vm.refresh()
            }.onFailure {
                // 暂停/取消走 CancellationException，不算失败
                if (it !is kotlinx.coroutines.CancellationException) notify("上传失败：${it.message}")
            }
        }
    }

    // ---- 文件夹上传（SAF 目录树）：递归建远端目录 + 逐文件走现有上传链路 ----
    // 每个文件是一条独立传输记录（并发排队/断点续传/暂停取消全部复用）；
    // 整个文件夹任务挂在应用级 transferScope，切页不中断。
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) context.appContainer.transferScope.launch {
            notify("文件夹上传已开始，可在传输中心查看进度")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // 持久化目录树读权限：子文档 uri 重启后仍可打开（断点续传依赖）
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    uploadFolder(
                        context = context,
                        log = context.appContainer.transferLog,
                        api = vm.api,
                        treeUri = uri,
                        targetCid = vm.currentTargetCid(),
                        targetPath = ui.stack.joinToString(" / ") { s -> s.name },
                    )
                }
            }
            result.onSuccess { s ->
                if (s.failed == 0 && s.dirsFailed == 0) {
                    notify("文件夹上传完成：${s.succeeded} 个文件" + if (s.reused > 0) "（含秒传 ${s.reused}）" else "")
                } else {
                    notify("文件夹上传完成：成功 ${s.succeeded}，失败 ${s.failed}" +
                        (if (s.dirsFailed > 0) "（含 ${s.dirsFailed} 个目录建失败）" else ""))
                }
                context.appContainer.opLog.log(
                    OpType.UPLOAD,
                    "文件夹（${s.succeeded}/${s.files} 个文件）",
                    "上传到「" + ui.stack.joinToString(" / ") { st -> st.name } + "」",
                    vm.currentTargetCid(),
                    ui.stack.last().name,
                )
                vm.refresh()
            }.onFailure {
                // 用户取消/应用退出走 CancellationException，不算失败
                if (it !is kotlinx.coroutines.CancellationException) notify("文件夹上传失败：${it.message}")
            }
        }
    }

    /**
     * 激活一个条目（点文件行/网格卡的本体）。双栏模式两栏各挂一个 [FilesViewModel]，
     * 右栏的条目也要能播放/看图/读文本，所以参数化成 [activateIn]；单面板等价于
     * activateIn(vm, …)。操作记录记的是"该条目所在的那一栏"的目录（vmX 的当前目录）。
     */
    fun activateIn(vmX: FilesViewModel, item: FileItem) {
        val uiX = vmX.ui.value
        if (uiX.selectMode) {
            vmX.toggleSelect(item.fid)
            return
        }
        if (item.isDir) {
            // 从搜索结果进入子目录时同步收起搜索栏（搜索状态已在 openDir 里退出）
            if (uiX.searching) searchMode = false
            vmX.openDir(item)
        } else if (item.isv == 1) {
            // 播放列表 = 当前视图里可播放的视频，保持用户看到的顺序（已应用排序与筛选），
            // 以 pick_code 作为身份标识（播放器就是按 pick_code 取流的）。
            // 注意：列表是分页加载的，这里只覆盖已加载部分——切集不会越界，仅范围有限。
            val entries = uiX.items
                .filter { !it.isDir && it.isv == 1 }
                .mapNotNull { f -> f.pc?.let { pc -> PlaylistEntry(pc, f.fn, f.fid) } }
            val index = entries.indexOfFirst { it.pc == item.pc }.coerceAtLeast(0)
            // 记录点在文件页而非 AppRoot：只有这里拿得到"当时所在的目录"，点击记录才能跳回来
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.VIDEO_PLAY,
                    item.fn,
                    if (entries.size > 1) "连播 ${entries.size} 项" else null,
                    vmX.currentTargetCid(),
                    uiX.stack.last().name,
                )
            }
            // cid/path 除了写操作记录，还要带给播放器：播放器内切集时补的记录
            // 用同一对值，文件页「最近浏览」的时间才会跟着切集走
            onPlayVideo(item, entries, index, vmX.currentTargetCid(), uiX.stack.last().name)
        } else if (isImageItem(item)) {
            // 传"图片序列 + 索引"给大图画廊，才能左右翻页；同时带上归一化后的元数据
            // 把"图片序列 + 索引"上抛，画廊在应用根层级渲染（才能盖住侧栏）
            val gallery = uiX.items.filter { isImageItem(it) }.map { it.toImageMediaItem() }
            val media = item.toImageMediaItem()
            val idx = gallery.indexOfFirst { it.pickCode == media.pickCode }
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.IMAGE_VIEW,
                    item.fn,
                    if (gallery.size > 1) "共 ${gallery.size} 张" else null,
                    vmX.currentTargetCid(),
                    uiX.stack.last().name,
                )
            }
            // 第三个参数 = 当前目录路径（面包屑写法）：画廊详情面板的"所在目录"用它
            onOpenGallery(gallery, if (idx >= 0) idx else 0, uiX.stack.joinToString(" / ") { it.name })
        } else if (isTextFile(item.fn)) {
            // 文本类（txt/py/md/js…）：直接进预览，内容与分页在应用根层级渲染
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.TEXT_PREVIEW,
                    item.fn,
                    cid = vmX.currentTargetCid(),
                    path = uiX.stack.last().name,
                )
            }
            onPreviewText(item)
        } else {
            downloadTarget = item
        }
    }

    fun onItemActivate(item: FileItem) {
        activateIn(vm, item)
    }

    Row(Modifier.fillMaxSize()) {
        if (expanded) {
            AnimatedVisibility(
                visible = !sideCollapsed,
                enter = expandHorizontally(),
                exit = shrinkHorizontally(),
            ) {
                Column(Modifier.width(SidePaneWidth).fillMaxSize()) {
                    UserInfoCard(Modifier.padding(16.dp))
                    HorizontalDivider()
                    FilesSidePane(vm, ui, Modifier.weight(1f), quickDirDrag)
                }
            }
            SidePaneHandle(
                collapsed = sideCollapsed,
                onToggle = { sideCollapsed = !sideCollapsed },
            )
            if (dualMode) {
                DualPaneContent(
                    vmA = vm,
                    vmB = vmB,
                    modifier = Modifier.weight(1f),
                    onExit = { toggleDualPane() },
                    notify = ::notify,
                    onActivate = { v, item -> activateIn(v, item) },
                    onPaneOperate = { dualActiveLeft = it },
                )
            } else FilesBrowserPane(
                vm = vm,
                ui = ui,
                modifier = Modifier.weight(1f),
                searchMode = searchMode,
                onSearchToggle = {
                    searchMode = !searchMode
                    if (!searchMode) vm.exitSearch()
                },
                onActivate = ::onItemActivate,
                onMove = { moveMode = "move" },
                onCopy = { moveMode = "copy" },
                onDelete = { showDeleteConfirm = true },
                onRename = { renameTarget = it },
                onBatchRename = {
                    val targets = vm.selectedInDisplayOrder()
                    if (targets.size >= 2) {
                        onBatchRename(
                            BatchRenameRequest(
                                items = targets,
                                // 整个目录的显示列表：面板里「全选本目录」要用
                                allFiles = ui.display,
                                cid = vm.currentCid(),
                                dirName = ui.stack.last().name,
                            ),
                        )
                    }
                },
                onSelectAll = { vm.toggleSelectAll() },
                // 直接在点击时启动：星标没有"目标要先记下来"的理由，走状态中转反而会自取消
                onStar = { scope.launch { notify(vm.toggleStarItem(it)) } },
                onPin = { scope.launch { notify(vm.togglePins(it)) } },
                onDownload = {
                    val files = ui.items.filter { it.fid in ui.selection && !it.isDir }
                    scope.launch {
                        var queued = 0
                        for (f in files) {
                            val pc = f.pc
                            if (pc.isNullOrBlank()) continue
                            vm.getDownloadUrl(pc).onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, f.fn) }
                                queued++
                            }
                        }
                        notify(if (queued > 0) "已加入 $queued 个下载任务" else "所选文件均无法下载")
                    }
                },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                onUploadFolder = { folderPicker.launch(null) },
                onOpenFilterRules = onOpenFilterRules,
                onCreateFolder = { showCreate = true },
                onOrganize = {
                    // 已有整理在跑就不重复发车：横幅就挂在顶栏下面，不会有"点了没反应"的困惑
                    if (organizing.running) notify("已有分类整理任务在进行")
                    else showOrganizeConfirm = true
                },
                organizing = organizing,
                onOrganizeExclusions = { showOrganizeExclusions = true },
                onStopOrganize = { organizer.stop() },
                // 查重从"当前这一层"开始扫；面板在根层渲染（与批量重命名同层）
                onFindDuplicates = {
                    onFindDuplicates(DupScanRequest(vm.currentCid(), ui.stack.last().name))
                },
                onGeneratePreviews = {
                    onGeneratePreviews(PreviewRequest(vm.currentCid(), ui.stack.last().name))
                },
                showVideoStrip = showVideoStrip,
                quickDirDrag = quickDirDrag,
                onQuickDirDrop = ::onQuickDirDrop,
                dualPaneOn = dualMode,
                onToggleDualPane = { toggleDualPane() },
            )
        } else if (dualMode) {
            DualPaneContent(
                vmA = vm,
                vmB = vmB,
                modifier = Modifier.fillMaxSize(),
                onExit = { toggleDualPane() },
                notify = ::notify,
                onActivate = { v, item -> activateIn(v, item) },
                onPaneOperate = { dualActiveLeft = it },
            )
        } else {
            FilesBrowserPane(
                vm = vm,
                ui = ui,
                modifier = Modifier.fillMaxSize(),
                searchMode = searchMode,
                onSearchToggle = {
                    searchMode = !searchMode
                    if (!searchMode) vm.exitSearch()
                },
                onActivate = ::onItemActivate,
                onMove = { moveMode = "move" },
                onCopy = { moveMode = "copy" },
                onDelete = { showDeleteConfirm = true },
                onRename = { renameTarget = it },
                onBatchRename = {
                    val targets = vm.selectedInDisplayOrder()
                    if (targets.size >= 2) {
                        onBatchRename(
                            BatchRenameRequest(
                                items = targets,
                                // 整个目录的显示列表：面板里「全选本目录」要用
                                allFiles = ui.display,
                                cid = vm.currentCid(),
                                dirName = ui.stack.last().name,
                            ),
                        )
                    }
                },
                onSelectAll = { vm.toggleSelectAll() },
                // 直接在点击时启动：星标没有"目标要先记下来"的理由，走状态中转反而会自取消
                onStar = { scope.launch { notify(vm.toggleStarItem(it)) } },
                onPin = { scope.launch { notify(vm.togglePins(it)) } },
                onDownload = {
                    val files = ui.items.filter { it.fid in ui.selection && !it.isDir }
                    scope.launch {
                        var queued = 0
                        for (f in files) {
                            val pc = f.pc
                            if (pc.isNullOrBlank()) continue
                            vm.getDownloadUrl(pc).onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, f.fn) }
                                queued++
                            }
                        }
                        notify(if (queued > 0) "已加入 $queued 个下载任务" else "所选文件均无法下载")
                    }
                },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                onUploadFolder = { folderPicker.launch(null) },
                onOpenFilterRules = onOpenFilterRules,
                onCreateFolder = { showCreate = true },
                onOrganize = {
                    // 已有整理在跑就不重复发车：横幅就挂在顶栏下面，不会有"点了没反应"的困惑
                    if (organizing.running) notify("已有分类整理任务在进行")
                    else showOrganizeConfirm = true
                },
                organizing = organizing,
                onOrganizeExclusions = { showOrganizeExclusions = true },
                onStopOrganize = { organizer.stop() },
                onFindDuplicates = {
                    onFindDuplicates(DupScanRequest(vm.currentCid(), ui.stack.last().name))
                },
                onGeneratePreviews = {
                    onGeneratePreviews(PreviewRequest(vm.currentCid(), ui.stack.last().name))
                },
                showVideoStrip = showVideoStrip,
                quickDirDrag = quickDirDrag,
                onQuickDirDrop = ::onQuickDirDrop,
                dualPaneOn = dualMode,
                onToggleDualPane = { toggleDualPane() },
            )
        }
    }

    // 拖动幻影：拾起→跟随→离场的动画编排都收在 QuickDirDragGhost 里，
    // 这里只在"拖动中或离场动画未播完"时把它挂上来
    if (quickDirDrag.active || quickDirDrag.exit != null) {
        QuickDirDragGhost(quickDirDrag)
    }

    // ---------- 对话框 ----------

    if (showCreate) {
        TextEntryDialog("新建文件夹", "文件夹名称", onConfirm = {
            showCreate = false
            scope.launch { notify(vm.addFolder(it)) }
        }, onDismiss = { showCreate = false })
    }
    if (showOrganizeConfirm) {
        OrganizeConfirmDialog(
            dirName = ui.stack.last().name,
            exclusionCount = organizeExclusions.size,
            onPickExclusions = { showOrganizeExclusions = true },
            onConfirm = {
                showOrganizeConfirm = false
                organizer.start(vm.currentCid(), ui.stack.joinToString(" / ") { it.name })
                notify("分类整理已开始")
            },
            onDismiss = { showOrganizeConfirm = false },
        )
    }
    if (showOrganizeExclusions) {
        OrganizeExclusionDialog(
            items = ui.items,
            selected = organizeExclusions,
            onConfirm = { picked ->
                showOrganizeExclusions = false
                organizer.setExclusions(picked)
            },
            onDismiss = { showOrganizeExclusions = false },
        )
    }
    renameTarget?.let { target ->
        TextEntryDialog("重命名", "新名称", initial = target.fn, onConfirm = {
            renameTarget = null
            scope.launch { notify(vm.rename(target, it)) }
        }, onDismiss = { renameTarget = null })
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除 ${ui.selection.size} 项",
            text = "文件将被移入回收站，可在回收站中还原。",
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                scope.launch { notify(vm.deleteSelected()) }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    moveMode?.let { mode ->
        FolderPickerDialog(
            api = vm.api,
            title = if (mode == "move") "移动到…" else "复制到…",
            onDismiss = { moveMode = null },
            onPick = { cid, name ->
                moveMode = null
                scope.launch {
                    notify(if (mode == "move") vm.moveSelected(cid, name) else vm.copySelected(cid, name))
                }
            },
        )
    }
    downloadTarget?.let { item ->
        DownloadDialog(
            name = item.fn,
            sizeText = "大小：${Format.size(item.fs)}",
            onDismiss = { downloadTarget = null },
            onDownload = {
                downloadTarget = null
                scope.launch {
                    val pc = item.pc
                    if (pc.isNullOrBlank()) {
                        notify("该文件缺少提取码，无法下载")
                    } else {
                        vm.getDownloadUrl(pc)
                            .onSuccess { url ->
                                runCatching { Downloader.enqueue(context, url, item.fn) }
                                context.appContainer.opLog.log(OpType.DOWNLOAD, item.fn, "下载到本机")
                                notify("已加入系统下载队列")
                            }
                            .onFailure { notify(it.message) }
                    }
                }
            },
        )
    }
}

/**
 * 「分类整理」确认框：说明规则 + 一行「例外」入口（发车前先勾掉不想动的文件/夹）。
 * 不用通用 [ConfirmDialog] 是因为要在这上面放一个可点的例外行。
 */
@Composable
private fun OrganizeConfirmDialog(
    dirName: String,
    /** 已在例外清单里的条目数（可能含其他目录选的，展示用） */
    exclusionCount: Int,
    onPickExclusions: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类整理当前目录") },
        text = {
            Column {
                Text(
                    "将递归扫描「$dirName」下的所有文件（含子文件夹里的），按类型移动到本目录下的" +
                        "「视频」「音频」「文本」「应用程序」「图片」「压缩包」六个文件夹，其余进「其他」。",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "· 只搬文件；搬空了的子文件夹会顺手删除（分类文件夹保留）\n" +
                        "· 目标文件夹已存在则直接复用\n" +
                        "· 同名文件 115 会自动改成「xxx（1）」，不会覆盖\n" +
                        "· 文件多时限速执行（防被 ban），可随时停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickExclusions)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "例外（不整理的文件/文件夹）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (exclusionCount > 0) "已选 $exclusionCount 项" else "去选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("开始整理") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 例外勾选弹层：列出**当前浏览目录**的条目（就是列表里已加载的那份，零额外请求），
 * 勾中的文件不搬、文件夹整个子树跳过。发车前（确认框进来）与执行中（横幅进来）共用；
 * 集合存放在 FileOrganizer，执行中确认后对还没搬到的文件立即生效。
 */
@Composable
private fun OrganizeExclusionDialog(
    items: List<FileItem>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择例外（不整理）") },
        text = {
            Column {
                if (items.isEmpty()) {
                    Text("当前目录没有条目")
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(items, key = { (it.fid ?: "") + "#" + it.fn }) { item ->
                            val fid = item.fid
                            val checked = fid != null && fid in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = fid != null) {
                                        picked = if (checked) picked - fid!! else picked + fid!!
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Icon(
                                    if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = if (item.isDir) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.fn + if (item.isDir) "（整个文件夹）" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "勾选的文件不移动；勾选的文件夹整个跳过（含里面的所有文件）。" +
                        "整理过程中也能打开本弹层随时增删，对还没搬到的文件立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 侧栏收起/展开把手：贴边的细条，点一下切换 */
@Composable
private fun SidePaneHandle(collapsed: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
            contentDescription = if (collapsed) "展开侧栏" else "收起侧栏",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 侧栏操作记录最多展示条数（存储层保留 100 条，这里只渲染最近的） */
private const val SIDE_PANE_OP_COUNT = 30

/**
 * 拖动幻影：Windows 式的缩略卡堆叠（最多 3 张 + 数量角标），动画编排全在这里——
 *
 * - 拾起：从被抓住那行的中心长出来（scale 0.6→1 回弹 + 淡入），不是凭空弹在指尖旁；
 * - 跟随：逐帧指数趋近手指（每帧必走一步），快扫也基本贴住指尖；中心悬在指尖上方
 *   一段免得被手指压住，悬停到目标上再弹大一档。**故意不用** Animatable+collectLatest
 *   按事件重启 spring：指针事件频率常高于帧率，动画帧被反复打断，拖快了幻影会大幅
 *   滞后、停下手指才追上来（"不跟手"的真因）；
 * - 离场：命中 → 飞向目标行中心收缩淡出（"收进去"），落空/取消 → 原地淡出；
 * - 丝滑的另一半在开销：位置/缩放/透明度全走 offset{} / graphicsLayer{} 的延迟读取，
 *   拖动全程只有这一层重布局重绘制，不惊动 FilesScreen 的重组。
 */
@Composable
private fun QuickDirDragGhost(host: QuickDirDragHost) {
    val half = with(LocalDensity.current) { 34.dp.toPx() } // 堆叠的视觉半径：52dp 卡 + 16dp 错位
    val lift = with(LocalDensity.current) { 52.dp.toPx() } // 幻影中心相对指尖的上浮距离
    val haptics = LocalHapticFeedback.current

    // 三个动画量全部手动逐帧驱动（两个 LaunchedEffect 循环），不经过 Animatable：
    // 位置必须每帧都动，跟手程度只由时间常数决定，与指针事件频率彻底解耦
    val posState = remember { mutableStateOf(Offset.Zero) }
    val scaleState = remember { mutableStateOf(0.6f) }
    val alphaState = remember { mutableStateOf(0f) }
    val hoverScale by animateFloatAsState(
        targetValue = if (host.hoverCid != null) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 550f),
        label = "dragGhostHover",
    )
    val borderColor by animateColorAsState(
        targetValue = if (host.hoverCid != null) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        animationSpec = tween(120),
        label = "dragGhostBorder",
    )

    // 拖动中：入场（淡入 + 回弹放大）+ 指数趋近手指。active 一翻 false 整个循环停手，
    // 位置交给离场循环接管
    LaunchedEffect(host.active) {
        if (!host.active) return@LaunchedEffect
        posState.value = host.beginAnchor
        scaleState.value = 0.6f
        alphaState.value = 0f
        var lastNanos = 0L
        var startNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (lastNanos == 0L) {
                lastNanos = now
                startNanos = now
                continue
            }
            val dtMs = ((now - lastNanos) / 1e6f).coerceIn(1f, 100f)
            lastNanos = now
            val sinceStartMs = (now - startNanos) / 1e6f
            alphaState.value = (sinceStartMs / 110f).coerceIn(0f, 1f)
            scaleState.value = 0.6f + 0.4f * EaseOutBack.transform((sinceStartMs / 220f).coerceIn(0f, 1f))
            // τ=10ms：一帧内补掉八成差距，肉眼上就是"钉在手指上"
            val target = Offset(host.position.x, host.position.y - lift)
            val p = posState.value
            val f = 1f - exp(-dtMs / 10f)
            posState.value = Offset(p.x + (target.x - p.x) * f, p.y + (target.y - p.y) * f)
        }
    }
    // 离场：命中 → 减速飞进目标行中心并收缩淡出；落空/取消 → 原地缩小淡散。
    // 播完才 clearExit，浮层（FilesScreen 的 if）随之退出组合
    LaunchedEffect(host.exit) {
        val exit = host.exit ?: return@LaunchedEffect
        val hit = exit.drop && exit.to != null
        val fromPos = posState.value
        val fromScale = scaleState.value
        val targetPos = if (hit) exit.to!! else fromPos
        val targetScale = if (hit) 0.35f else 0.85f
        val durationMs = if (hit) 180f else 140f
        val t0 = withFrameNanos { it }
        while (true) {
            var elapsedMs = 0f
            withFrameNanos { now -> elapsedMs = (now - t0) / 1e6f }
            val t = (elapsedMs / durationMs).coerceIn(0f, 1f)
            val e = FastOutSlowInEasing.transform(t)
            posState.value = Offset(
                fromPos.x + (targetPos.x - fromPos.x) * e,
                fromPos.y + (targetPos.y - fromPos.y) * e,
            )
            scaleState.value = fromScale + (targetScale - fromScale) * e
            alphaState.value = 1f - t
            if (t >= 1f) break
        }
        host.clearExit()
    }
    // 悬停进新目标时轻点一下：落不落得下，指尖有数
    LaunchedEffect(host.hoverCid) {
        if (host.hoverCid != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Popup(alignment = Alignment.TopStart, properties = PopupProperties(clippingEnabled = false)) {
        // 内容铺满整个窗口、幻影在其中定位：Popup 的可绘制表面只有窗口框那么大，
        // 之前"52dp 小窗 + 内容远远挪出去"会把幻影整个画在表面外裁没
        // （拖得动、落点高亮都正常，就是不见随指的图标）。铺满后手指在哪都能画，
        // offset{} 仍是布局期读取，每帧只重排幻影这一层。
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset {
                        IntOffset((posState.value.x - half).roundToInt(), (posState.value.y - half).roundToInt())
                    }
                    .graphicsLayer {
                        this.alpha = alphaState.value
                        scaleX = scaleState.value * hoverScale
                        scaleY = scaleState.value * hoverScale
                    },
            ) {
                val stack = host.items.take(3)
                // 从最底层画起：第一张选中项最后画、落在最上层（Windows 的堆叠手观感）
                stack.reversed().forEachIndexed { idx, item ->
                    val depth = stack.size - 1 - idx
                    Box(
                        Modifier
                            .offset(x = (depth * 8).dp, y = (depth * 8).dp)
                            .size(52.dp)
                            .shadow(6.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                            .alpha(if (depth == 0) 0.96f else 0.7f),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.open115.pad.ui.components.Thumb(item, Modifier.size(38.dp))
                    }
                }
                if (host.count > 1) {
                    Text(
                        "${host.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 8.dp, y = 8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 平板宽屏下的左侧栏：快捷目录 + 用户操作记录 */
@Composable
private fun FilesSidePane(
    vm: FilesViewModel,
    ui: FilesViewModel.UiState,
    modifier: Modifier = Modifier,
    /** 拖动多选的会话：条目在这里注册落点边界，悬停高亮也由它驱动 */
    quickDirDrag: QuickDirDragHost,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops by context.appContainer.opLog.entries.collectAsState(initial = emptyList())
    val quickDirs by context.appContainer.quickDirs.entries.collectAsState(initial = emptyList())
    /** 点了尾部 ×、等确认移除的快捷目录；非空 = 弹确认框 */
    var removeTarget by remember { mutableStateOf<com.open115.pad.data.QuickDir?>(null) }
    /** 点了删除记录、等确认"是否恢复"的那条操作记录 */
    var restoreTarget by remember { mutableStateOf<OpEntry?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        // ── 快捷目录：收藏常用目录，点击直达（跳转与操作记录同款 openByCid）──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("快捷目录", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Add,
                contentDescription = "收藏当前目录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        val cur = ui.stack.last()
                        scope.launch {
                            if (ui.stack.size <= 1) {
                                Toast.makeText(context, "先进入要收藏的目录，再点 + 收藏", Toast.LENGTH_SHORT).show()
                            } else {
                                val path = ui.stack.drop(1).joinToString("/") { it.name }
                                val ok = context.appContainer.quickDirs.add(cur.cid, cur.name, path)
                                Toast.makeText(
                                    context,
                                    if (ok) "已收藏「${cur.name}」" else "「${cur.name}」已在快捷目录里",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    .padding(2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        if (quickDirs.isEmpty()) {
            Text(
                "还没有快捷目录\n进入某个目录后点右上角 + 收藏，之后点击直达",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            quickDirs.forEach { d ->
                // 拖动落点：边界随布局变化重注册（滚动/展开收起都会触发），条目离场时注销
                DisposableEffect(d.cid) {
                    onDispose { quickDirDrag.unregisterTarget(d.cid) }
                }
                val hovered = quickDirDrag.hoverCid == d.cid
                // 高亮渐变而非瞬时开关：拖动扫过一排条目时是"划过"而不是"跳格"
                val hoverBg by animateColorAsState(
                    if (hovered) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    animationSpec = tween(150),
                    label = "quickDirHoverBg",
                )
                val hoverTint by animateColorAsState(
                    if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(150),
                    label = "quickDirHoverTint",
                )
                Row(
                    Modifier
                        .onGloballyPositioned { quickDirDrag.registerTarget(d.cid, d.name, it.boundsInRoot()) }
                        .fillMaxWidth()
                        .background(hoverBg, RoundedCornerShape(10.dp))
                        .clickable { vm.openByCid(d.cid, d.name) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = "快捷目录",
                        tint = hoverTint,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            d.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 收藏时的完整路径：同名目录光看名字认不出是哪一个
                        if (!d.path.isNullOrBlank()) {
                            Text(
                                d.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 悬停期间尾部 × 让位给"移入"图标：这一行此刻是落点不是删除键
                    Icon(
                        if (hovered) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.Close,
                        contentDescription = if (hovered) "松开移动到此目录" else "移除快捷目录",
                        tint = if (hovered) hoverTint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { removeTarget = d }
                            .padding(2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        // ── 操作记录（原有区块）──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("操作记录", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (ops.isNotEmpty()) {
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { scope.launch { context.appContainer.opLog.clear() } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val recent = ops.asReversed().take(SIDE_PANE_OP_COUNT)
        if (recent.isEmpty()) {
            Text(
                "暂无记录\n复制、移动、删除、上传下载、播放等操作会显示在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            recent.forEach { op ->
                val (icon, verb) = opTypeMeta(op.type)
                // 有目录信息的记录可点击跳转（下载到本机 / 云离线 / 旧数据没有，点击无响应）
                val target = op.cid?.let { c -> c to (op.path ?: op.name) }
                // 删除记录且知道原目录：点击 = 问要不要从回收站恢复（不再走跳转）
                val restorable = op.type == OpType.DELETE.name && op.cid != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = target != null) {
                            when {
                                restorable -> restoreTarget = op
                                // 跳过去之后把这条记录说的那个文件高亮出来（列表里铺淡蓝底并滚到它），
                                // 不然落在一个大目录里还得自己找；批量记录的名字是"xxx 等 N 项"，匹配不上就不高亮
                                else -> target?.let { vm.openByCid(it.first, it.second, highlight = op.name) }
                            }
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = verb,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            op.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(verb, op.detail, opRelTime(op.at)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (restorable) {
                        Text(
                            "恢复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (ui.searching) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("搜索「${ui.searchQuery}」结果", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    // 移除快捷目录要过一次确认：× 挨着点击跳转的行，误触代价不该是"书签没了"
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除快捷目录") },
            text = { Text("移除「${target.name}」？只删这条书签，云端目录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch { context.appContainer.quickDirs.remove(target.cid) }
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("取消") }
            },
        )
    }
    // 删除记录的"是否恢复"：确认 → 回收站里找回（还原到原目录）→ 跳过去并高亮。
    // 已经站在原目录时 openByCid 不会重新加载，改用 refresh 把恢复出来的条目刷出来
    restoreTarget?.let { op ->
        val primary = op.name.substringBefore(" 等")
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("是否恢复？") },
            text = {
                Text(
                    "把「$primary」从回收站恢复到原目录" +
                        (op.path?.let { "「$it」" } ?: "") + "，并跳转过去。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    scope.launch {
                        val msg = vm.restoreFromOpLog(op)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        val cid = op.cid ?: return@launch
                        if (msg.startsWith("已恢复")) {
                            if (vm.currentCid() == cid) vm.refresh()
                            else vm.openByCid(cid, op.path ?: op.name, highlight = primary)
                        }
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 操作类型 → (图标, 中文动词) */
private fun opTypeMeta(type: String): Pair<ImageVector, String> = when (type) {
    OpType.COPY.name -> Icons.Outlined.FileCopy to "复制"
    OpType.MOVE.name -> Icons.AutoMirrored.Outlined.DriveFileMove to "移动"
    OpType.DELETE.name -> Icons.Outlined.Delete to "删除"
    OpType.RENAME.name -> Icons.Outlined.DriveFileRenameOutline to "重命名"
    OpType.UPLOAD.name -> Icons.Outlined.Upload to "上传"
    OpType.DOWNLOAD.name -> Icons.Outlined.Download to "下载"
    OpType.OFFLINE.name -> Icons.Outlined.CloudDownload to "云离线"
    OpType.TEXT_PREVIEW.name -> Icons.Outlined.Description to "文本预览"
    OpType.IMAGE_VIEW.name -> Icons.Outlined.Image to "图片浏览"
    OpType.VIDEO_PLAY.name -> Icons.Outlined.PlayCircle to "视频播放"
    else -> Icons.Outlined.History to "操作"
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / M-d */
private fun opRelTime(at: Long): String {
    val diff = System.currentTimeMillis() - at
    val minute = 60_000L
    return when {
        diff < minute -> "刚刚"
        diff < 60 * minute -> "${diff / minute} 分钟前"
        diff < 24 * 60 * minute -> "${diff / (60 * minute)} 小时前"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
            ) "昨天"
            else SimpleDateFormat("M-d", Locale.getDefault()).format(Date(at))
        }
    }
}
