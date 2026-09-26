package com.open115.pad.ui.files

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
import androidx.lifecycle.viewModelScope
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
import com.open115.pad.data.OpLog
import com.open115.pad.data.OpType
import com.open115.pad.data.PinnedFolder
import com.open115.pad.data.PinnedPrefs
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.Uploader
import com.open115.pad.data.resumeUploadRecord
import com.open115.pad.data.uploadFolder
import com.open115.pad.data.parseFilesResponse
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
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {

    data class DirEntry(val cid: String, val name: String)

    data class UiState(
        val stack: List<DirEntry> = listOf(DirEntry("0", "全部文件")),
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
    ) {
        val selectMode: Boolean get() = selection.isNotEmpty()
    }

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
                )
            }
                .distinctUntilChanged()
                .collect { k ->
                    val filtered = if (!k.filterOn || k.scheme == null) k.items
                    else withContext(Dispatchers.Default) {
                        k.items.filter { FilterRules.evaluate(it, k.scheme.group) }
                    }
                    _ui.update {
                        it.copy(
                            display = applyPins(filtered, k.pins, k.searching),
                            activeFilter = if (k.filterOn) k.scheme else null,
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
                        it.copy(items = hit.items, count = hit.count, loading = false, error = null)
                    }
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
                    _ui.update { it.copy(items = next, count = page.count) }
                    // 写回缓存。分页必须把新页并到已有条目后面，否则下次进目录只剩第一页、
                    // 滚动位置也跟着对不上；缓存与视图共用同一份 next，保证两边永远一致。
                    if (key != null) {
                        cache?.put(key, DirCache.Entry(next, page.count, System.currentTimeMillis()))
                    }
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

    fun openDir(item: FileItem) {
        val fid = item.fid ?: return
        // 从搜索结果点进子目录要退出搜索：否则 load() 里 s.searching 仍为 true，
        // "进入"后加载的还是搜索结果而不是子目录内容。
        _ui.update {
            it.copy(stack = it.stack + DirEntry(fid, item.fn), selection = emptySet(), searching = false, searchQuery = "")
        }
        loadCurrent()
    }

    fun popDir() {
        if (_ui.value.stack.size > 1) {
            _ui.update { it.copy(stack = it.stack.dropLast(1), selection = emptySet()) }
            loadCurrent()
        }
    }

    fun jumpTo(index: Int) {
        // 已在目标层级时直接返回，避免一次无谓的列表刷新
        if (index >= _ui.value.stack.lastIndex) return
        _ui.update { it.copy(stack = it.stack.subList(0, index + 1), selection = emptySet()) }
        loadCurrent()
    }

    /**
     * 从操作记录跳转到任意目录：stack 重置为「全部文件 > 该目录」。
     * 拿不到完整中间路径（cid 无法反查），两级面包屑是最诚实的表达。
     */
    fun openByCid(cid: String, name: String) {
        if (cid == currentCid()) return
        _ui.update {
            it.copy(
                stack = listOf(DirEntry("0", "全部文件"), DirEntry(cid, name)),
                selection = emptySet(),
                searching = false,
                searchQuery = "",
            )
        }
        loadCurrent()
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
        _ui.update { it.copy(searching = query.isNotBlank(), searchQuery = query.trim()) }
        // 搜索不进缓存（key 为 null），这里用 loadCurrent 只是"不强制重取"，
        // 效果与刷新一致：真的会去请求
        loadCurrent()
    }

    fun exitSearch() {
        _ui.update { it.copy(searching = false, searchQuery = "") }
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
        if (ok) refresh()
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
    onPlayVideo: (item: FileItem, playlist: List<PlaylistEntry>, index: Int) -> Unit,
    onOpenGallery: (items: List<ImageMediaItem>, index: Int) -> Unit,
    onPreviewText: (item: FileItem) -> Unit,
    /** 多选时进批量重命名面板；入参里带上了整个目录的列表，面板可一键扩到全目录 */
    onBatchRename: (BatchRenameRequest) -> Unit,
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
    /** 当前视图里的图片序列（大图画廊用），保持用户看到的顺序 */
    val galleryItems = remember(ui.items) {
        ui.items.filter { isImageItem(it) }.map { it.toImageMediaItem() }
    }
    var searchMode by remember { mutableStateOf(false) }
    var sideCollapsed by rememberSaveable { mutableStateOf(true) }

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

    // ---- 返回键分级处理（后注册的优先级更高）----
    // ① 浏览子目录时：返回上一级；根目录"返无可返"时不拦截，交给系统退出应用
    androidx.activity.compose.BackHandler(enabled = ui.stack.size > 1) { vm.popDir() }
    // ② 多选模式下：先退出多选
    androidx.activity.compose.BackHandler(enabled = ui.selectMode) { vm.clearSelection() }
    // ③ 搜索栏展开时：先收起搜索
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

    fun onItemActivate(item: FileItem) {
        if (ui.selectMode) {
            vm.toggleSelect(item.fid)
        } else if (item.isDir) {
            // 从搜索结果进入子目录时同步收起搜索栏（搜索状态已在 openDir 里退出）
            if (ui.searching) searchMode = false
            vm.openDir(item)
        } else if (item.isv == 1) {
            // 播放列表 = 当前视图里可播放的视频，保持用户看到的顺序（已应用排序与筛选），
            // 以 pick_code 作为身份标识（播放器就是按 pick_code 取流的）。
            // 注意：列表是分页加载的，这里只覆盖已加载部分——切集不会越界，仅范围有限。
            val entries = ui.items
                .filter { !it.isDir && it.isv == 1 }
                .mapNotNull { f -> f.pc?.let { pc -> PlaylistEntry(pc, f.fn, f.fid) } }
            val index = entries.indexOfFirst { it.pc == item.pc }.coerceAtLeast(0)
            // 记录点在文件页而非 AppRoot：只有这里拿得到"当时所在的目录"，点击记录才能跳回来
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.VIDEO_PLAY,
                    item.fn,
                    if (entries.size > 1) "连播 ${entries.size} 项" else null,
                    vm.currentTargetCid(),
                    ui.stack.last().name,
                )
            }
            onPlayVideo(item, entries, index)
        } else if (isImageItem(item)) {
            // 传"图片序列 + 索引"给大图画廊，才能左右翻页；同时带上归一化后的元数据
            // 把"图片序列 + 索引"上抛，画廊在应用根层级渲染（才能盖住侧栏）
            val media = item.toImageMediaItem()
            val idx = galleryItems.indexOfFirst { it.pickCode == media.pickCode }
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.IMAGE_VIEW,
                    item.fn,
                    if (galleryItems.size > 1) "共 ${galleryItems.size} 张" else null,
                    vm.currentTargetCid(),
                    ui.stack.last().name,
                )
            }
            onOpenGallery(galleryItems, if (idx >= 0) idx else 0)
        } else if (isTextFile(item.fn)) {
            // 文本类（txt/py/md/js…）：直接进预览，内容与分页在应用根层级渲染
            scope.launch {
                context.appContainer.opLog.log(
                    OpType.TEXT_PREVIEW,
                    item.fn,
                    cid = vm.currentTargetCid(),
                    path = ui.stack.last().name,
                )
            }
            onPreviewText(item)
        } else {
            downloadTarget = item
        }
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
            FilesBrowserPane(
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
                quickDirDrag = quickDirDrag,
                onQuickDirDrop = ::onQuickDirDrop,
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
                quickDirDrag = quickDirDrag,
                onQuickDirDrop = ::onQuickDirDrop,
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = target != null) {
                            target?.let { vm.openByCid(it.first, it.second) }
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
                    Column(Modifier.padding(start = 8.dp)) {
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
