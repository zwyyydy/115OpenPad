package com.open115.pad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open115.pad.AppContainer
import com.open115.pad.mainUiVisible
import com.open115.pad.data.DownloadRequest
import com.open115.pad.data.DownloadSubmitter
import com.open115.pad.data.FileItem
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.LinkSource
import com.open115.pad.data.OpType
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.Session
import com.open115.pad.data.TaskDoneNotifier
import com.open115.pad.data.media.MediaScanner
import com.open115.pad.data.media.ScanMode
import com.open115.pad.data.media.label
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.BatchRenamePanel
import com.open115.pad.ui.components.DownloadLinkSheet
import com.open115.pad.ui.components.FadePresence
import com.open115.pad.ui.components.ImageGalleryDialog
import com.open115.pad.ui.components.PreviewZoomHost
import com.open115.pad.ui.components.TextPreviewHost
import com.open115.pad.ui.components.observeLastTapPoint
import com.open115.pad.util.Downloader
import com.open115.pad.util.Format
import com.open115.pad.ui.files.BatchRenameRequest
import com.open115.pad.ui.files.DupScanRequest
import com.open115.pad.ui.files.PreviewPanel
import com.open115.pad.ui.files.PreviewRequest
import com.open115.pad.ui.files.DuplicatePanel
import com.open115.pad.ui.files.FilesScreen
import com.open115.pad.ui.files.FilesViewModel
import com.open115.pad.ui.login.LoginScreen
import com.open115.pad.ui.offline.OfflineScreen
import com.open115.pad.ui.offline.OfflineViewModel
import com.open115.pad.ui.recycle.RecycleScreen
import com.open115.pad.ui.recycle.RecycleViewModel
import com.open115.pad.ui.settings.SettingsScreen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.open115.pad.ui.components.FadeAlertDialog

private data class Dest(val route: String, val label: String, val icon: ImageVector)

/** 媒体库的路由名。它单独沉在平板左栏底部（见 AppRoot 里导航栏那段），所以路由名抽出来共用 */
private const val MEDIA_ROUTE = "media"

/**
 * 媒体库"忙"判据的防抖窗口。
 *
 * 扫描队列里上一条出队、下一条还没起跑之间会闪一下假空闲（`running=false`、`pending` 也空了）——
 * 不防抖的话导航栏红点会抖、完成提示还会在换任务的空档里提前弹。1.2 秒同 App115 里前台服务的
 * 判据（见 watchTasksForKeepAlive），短任务之间的空档掀不起浪。
 */
private const val SCAN_BUSY_DEBOUNCE_MS = 1200L

/**
 * 「这个结果是刚出的吗」的判据窗口（重命名 / 整理 / 查重 / 预览图的收尾提示用）。
 *
 * 这几类任务的结束状态是**持久**的（finishedAt 留在任务/进度里），而暂停、取消、停止
 * 也会把"忙"翻成"闲"、走同一条边沿 —— 靠时间窗把它们分开：刚结束的才值得报一句。
 */
private const val FINISH_WINDOW_MS = 15_000L

private val destinations = listOf(
    Dest("files", "文件", Icons.Outlined.Folder),
    Dest(MEDIA_ROUTE, "媒体库", Icons.Outlined.Movie),
    Dest("filter", "过滤规则", Icons.Outlined.Tune),
    Dest("offline", "云下载", Icons.Outlined.CloudDownload),
    Dest("transfer", "传输中心", Icons.Outlined.Download),
    // 改名是 N 次带限频的网络调用，几十秒起步，所以单独一页看进度、也在这里建任务
    Dest("rename", "重命名", Icons.Outlined.DriveFileRenameOutline),
    Dest("recycle", "回收站", Icons.Outlined.RestoreFromTrash),
    Dest("settings", "设置", Icons.Outlined.Settings),
)

/**
 * 手机底部导航栏只放 5 个高频入口：8 个全平铺会把窄屏挤满。
 * 「云下载 / 回收站」收进传输中心（手机模式下是传输中心的内嵌分页），
 * 「过滤规则 / 重命名」收进「更多」，以后手机模式新增的低频功能也进「更多」。
 * 平板左侧导航栏保持 [destinations] 全量不变。
 */
private val phoneTabs = listOf(
    Dest("files", "文件", Icons.Outlined.Folder),
    Dest("media", "媒体库", Icons.Outlined.Movie),
    Dest("transfer", "传输中心", Icons.Outlined.Download),
    Dest("more", "更多", Icons.Outlined.MoreHoriz),
    Dest("settings", "设置", Icons.Outlined.Settings),
)

/** 手机模式下当前路由应高亮的底部 tab（子页面归属到收纳它的 tab） */
private fun phoneSelectedTab(route: String?): String? = when {
    route == null -> null
    route == "filter" || route == "rename" -> "more"       // 「更多」里的子页
    route == "offline" || route == "recycle" -> "transfer" // 传输中心里的子页（含宽窄切换瞬间落在旧路由上）
    // 注意：currentBackStackEntryAsState 给的是路由模式（"transfer?tab={tab}"），
    // 不是导航时的实际字符串，所以传输中心要用前缀匹配
    route.startsWith("transfer") -> "transfer"
    else -> route
}

/** 「更多」收纳页的入口清单（按序展示）。以后手机模式新增低频功能，把 route 追加到这里即可 */
private val moreRoutes = listOf("filter", "rename")

/**
 * 把一个"忙不忙"的流做成**（可防抖的）** Compose 状态，并在"忙 → 闲"的边沿调一次 [onFinished]。
 *
 * 防抖理由同 [SCAN_BUSY_DEBOUNCE_MS]：队列换任务的空档会闪假空闲，不防抖红点会抖、
 * 完成提示还会在空档里提前弹。**不排队**的任务（查重 / 预览图）传 [debounceMs] = 0：
 * 它们没有假空闲可等，而边沿要即时生效（完成弹窗得按"这一刻面板开没开"决定弹不弹）。
 * [onFinished] 经 rememberUpdatedState 取最新一份（闭包冻结坑）。
 */
@OptIn(FlowPreview::class)
@Composable
private fun rememberBusyEdge(
    busy: Flow<Boolean>,
    debounceMs: Long = SCAN_BUSY_DEBOUNCE_MS,
    onFinished: suspend () -> Unit,
): State<Boolean> {
    val state = remember { mutableStateOf(false) }
    val callback = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        var was = false
        val source = if (debounceMs > 0) busy.debounce(debounceMs) else busy
        source.distinctUntilChanged().collect { now ->
            state.value = now
            if (was && !now) callback.value()
            was = now
        }
    }
    return state
}

/**
 * 导航入口图标上的"任务在跑"小红点（扫描 → 媒体库、重命名 → 重命名/更多、整理/查重/预览图 → 文件）。
 *
 * 挂在导航栏上而不是各页面里 —— 任务跑着时用户往往已经退到别的页，这个点是"那边还有活在干"
 * 的唯一指示：跑完红点消失，同时弹一条完成提示（见 announceDone 与几个 xxxNotice）。
 */
@Composable
private fun BusyDestIcon(icon: ImageVector, label: String, busy: Boolean) {
    if (busy) {
        BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = label) }
    } else {
        Icon(icon, contentDescription = label)
    }
}

/**
 * 一条"任务跑完了"的提示内容。
 *
 * 与重命名 / 整理仍走 Snackbar 不同，扫描 / 查找重复 / 视频预览图 这三类**耗时以分钟计**、
 * 又多半在用户已经翻到别的页面时才跑完，所以用**弹窗**报（Snackbar 一闪就过去了）；
 * 主界面不可见时改发系统通知（见 announceDone）。
 */
private data class TaskDoneNotice(
    val title: String,
    val text: String,
    /** 「查看结果」的落点；null = 没有可看的结果，只给一个「知道了」（如扫描） */
    val view: PanelTarget? = null,
)

/** 「查看结果」要打开的面板：面板还留着这一轮的结果，所以只要把它的请求重新挂回去 */
private sealed interface PanelTarget {
    data class Dup(val request: DupScanRequest) : PanelTarget
    data class Preview(val request: PreviewRequest) : PanelTarget

    /**
     * 刮削的"结果"就是媒体库页里的复核面板（它直接读引擎的会话，不需要请求对象）——
     * 点「查看结果」回到媒体库页即可。
     */
    data object MediaScrape : PanelTarget
}

/**
 * 媒体库扫描跑完：库名 + 队列累计入库数。
 *
 * 用**队列累计**（[MediaScanner.batchIndexed]）而不是 Progress.moviesIndexed：后者只是最后一个
 * 目录的数字（流水线同步一次排 6 个目录，拿它报会少报成"新增 1 部"）。
 * 「停止 / 取消」不算完成，不报 —— 那两种的文案在媒体库自己的进度卡上。
 */
private fun scanNotice(container: AppContainer): TaskDoneNotice? {
    val p = container.mediaScanner.progress.value
    if (p.stopped) return null
    val indexed = container.mediaScanner.batchIndexed.value
    val body = if (indexed > 0) "本次新增 $indexed 部" else "没有新增影片"
    val name = p.libraryName.trim()
    return TaskDoneNotice(
        title = "媒体库扫描完成",
        text = if (name.isEmpty()) body else "「$name」$body",
    )
}

/**
 * 查找重复 / 同片多版本：两个引擎共用「查找重复」面板，谁刚跑完就报谁（取 finishedAt 更新的那个）。
 *
 * 与扫描不同，这里**「停止 / 出错」也报** —— 这两个引擎特意做了"停下来也把已经扫到的结果给你"，
 * summary 里那句（"已停止 · 发现 2 组重复" / "出错了（…）· 已扫到的仍在下面"）本身就是用户要的结果。
 * 面板开着时不报（结果就在眼前），判据在调用点。
 */
private fun dupNotice(container: AppContainer): TaskDoneNotice? {
    val now = System.currentTimeMillis()
    val dp = container.duplicateFinder.progress.value
    val vp = container.versionFinder.progress.value
    // 时间窗把"刚跑完"与"上一轮的旧结果"分开（[FINISH_WINDOW_MS]）
    fun freshText(finishedAt: Long, summary: String?): String? =
        summary?.takeIf { finishedAt > 0 && now - finishedAt <= FINISH_WINDOW_MS }
    val dupText = freshText(dp.finishedAt, dp.summary)
    val verText = freshText(vp.finishedAt, vp.summary)
    return when {
        dupText != null && (verText == null || dp.finishedAt >= vp.finishedAt) -> TaskDoneNotice(
            title = "查找重复文件",
            text = dupText,
            view = PanelTarget.Dup(DupScanRequest(dp.rootCid, dp.rootPath)),
        )
        verText != null -> TaskDoneNotice(
            title = "同片多版本预筛",
            text = verText,
            view = PanelTarget.Dup(DupScanRequest(vp.rootCid, vp.rootPath, version = true)),
        )
        else -> null
    }
}

/** 视频预览图跑完：summary 自带"已生成 / 已停止 / 出错了"的措辞，原样报 */
private fun previewNotice(container: AppContainer): TaskDoneNotice? {
    val p = container.previewBuilder.progress.value
    val text = p.summary ?: return null
    if (p.finishedAt <= 0 || System.currentTimeMillis() - p.finishedAt > FINISH_WINDOW_MS) return null
    return TaskDoneNotice(
        title = "视频预览图",
        text = text,
        view = PanelTarget.Preview(PreviewRequest(p.rootCid, p.rootPath)),
    )
}

/**
 * 元数据刮削跑完（单条写入 / 批量的搜索与写入各算一轮）：summary 自带措辞，原样报。
 *
 * 「查看结果」只在**批量会话还在**时给 —— 那种结果在媒体库页的复核面板里；
 * 单条写入（详情页点的）没有独立结果页，就不给这个按钮。
 */
private fun scrapNotice(container: AppContainer): TaskDoneNotice? {
    val p = container.metadataScraper.progress.value
    val text = p.summary ?: return null
    if (p.finishedAt <= 0 || System.currentTimeMillis() - p.finishedAt > FINISH_WINDOW_MS) return null
    val hasSession = container.metadataScraper.session.value != null
    return TaskDoneNotice(
        title = "元数据刮削",
        text = text,
        view = if (hasSession) PanelTarget.MediaScrape else null,
    )
}

/**
 * 重命名完成：读队列里"刚刚完成"的任务（[FINISH_WINDOW_MS] 内、且不是被暂停/取消的）。
 *
 * 为什么按 finishedAt 时间筛而不是看 activeTaskId 的变化：暂停/取消也会清空它、触发同一条
 * 边沿 —— 那些不是"完成"，不该报；真完成的任务 finishedAt 是刚写的，时间窗把两者分开。
 */
private suspend fun notifyRenameFinished(context: Context, container: AppContainer, snackbar: SnackbarHostState) {
    val task = container.renameQueue.tasks.value
        .filter { it.finishedAt != null && !it.cancelled && !it.paused }
        .maxByOrNull { it.finishedAt!! }
        ?: return
    if (System.currentTimeMillis() - task.finishedAt!! > FINISH_WINDOW_MS) return
    val text = buildString {
        append("批量重命名完成：成功 ${task.succeeded} 个")
        if (task.failed > 0) append("，失败 ${task.failed} 个")
    }
    finishNotice(context, snackbar, text)
}

/** 整理结束：summary 自带措辞（完成 / 已停止 / 中断 / 失败），原样报 —— 与它以前在文件页里的口径一致 */
private suspend fun notifyOrganizeFinished(context: Context, container: AppContainer, snackbar: SnackbarHostState) {
    val p = container.fileOrganizer.progress.value
    if (p.finishedAt == 0L || System.currentTimeMillis() - p.finishedAt > FINISH_WINDOW_MS) return
    finishNotice(context, snackbar, p.summary ?: "文件整理完成")
}

/**
 * 重命名 / 整理的收尾提示走哪条路看主界面在不在前台：看得见 → Snackbar；看不见 → 系统通知。
 * 不做"两边都发"：通知栏和屏幕同时各响一次是双份打扰。
 * （扫描 / 查重 / 预览图不用这条 —— 那三类走弹窗，见 [TaskDoneNotice]。）
 */
private suspend fun finishNotice(context: Context, snackbar: SnackbarHostState, text: String) {
    if (context.mainUiVisible) snackbar.showSnackbar(text)
    else TaskDoneNotifier.notifyDone(context, text)
}

/**
 * 「上次没跑完的扫描」弹窗的正文。
 *
 * 用 listOfNotNull + joinToString 拼（字符串拼接里的 if 会吞掉后面几段，是踩过的坑）。
 * 断在哪个目录也写出来：多根库能据此看出是哪个根，长目录名能看出是扫到哪一部了。
 * 异常原因可能很长（OkHttp 的文案），截一下 —— 这句只是给用户一个线索，完整原因在日志里。
 */
private fun resumeScanText(p: MediaScanner.ResumeProposal): String {
    val r = p.resume
    return listOfNotNull(
        // 单目录任务（流水线同步排的）不该说成"某某扫描"—— 它是"这 N 个目录要重扫"，
        // 而且它的进度计数是"一次调用一个目录"，说成 N/M 个目录会误导
        if (p.task.noRecurse) {
            "流水线同步排的 ${p.task.rootPaths.size} 个目录上次没扫完。"
        } else {
            "「${p.task.libraryName}」上次的${p.task.mode.label}扫描没跑完。"
        },
        if (p.task.noRecurse || r.doneDirs <= 0) {
            null
        } else if (r.totalDirs > 0) {
            "已完成 ${r.doneDirs}/${r.totalDirs} 个目录（已发现），已入库 ${r.moviesIndexed} 部。"
        } else {
            "已完成 ${r.doneDirs} 个目录，已入库 ${r.moviesIndexed} 部。"
        },
        r.currentDir.takeIf { it.isNotBlank() }?.let { "断在：$it" },
        if (r.reason.isBlank()) {
            "中断原因：程序上次在扫描中被关闭。"
        } else {
            "中断原因：${r.reason.take(120)}"
        },
        // 整库全量续扫没有意义（它的语义是"重新索引一切"），acceptResume 会按增量跳过已完成的目录 ——
        // 先跟用户说一声，免得他看到队列卡片上写的是"增量"以为选错了。
        // 单目录任务不受影响（它本来就只扫几个目录、固定全量）
        if (p.task.mode == ScanMode.Full && !p.task.noRecurse) {
            "继续会按增量扫描，已完成的目录会自动跳过。"
        } else {
            null
        },
        // 三个答复里"跳过本次"是新的、也最容易被当成"不再询问"：说清它只跳过这一次
        "「跳过本次」这次先不扫，下次启动还会再问一遍。",
    ).joinToString("\n")
}

@Composable
fun AppRoot(container: AppContainer, widthClass: WindowWidthSizeClass) {
    val loggedIn by container.session.loggedInFlow.collectAsState(initial = null)
    // 根 Surface 用 background 而非默认 surface：平时两者都被 Scaffold 盖住看不出差别，
    // 壁纸激活时 background 是半透明 token（Open115Theme 里改的），壁纸才能从这里透出来
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (loggedIn) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            false -> LoginScreen(container)
            else -> {
                // 主动探活：登录态下启动先验证一次授权有效性（最轻量的已认证接口）。
                // 若令牌链已死（终态授权码），Session/拦截器会自动登出并给出原因，
                // loggedInFlow 变 false 后这里自动切回登录页。
                // 探活结果顺便预热用户信息缓存，UserInfoCard 直接命中、不重复请求。
                LaunchedEffect(Unit) {
                    runCatching {
                        com.open115.pad.data.parseUserInfo(container.openApi.userInfo())
                    }.onSuccess { com.open115.pad.ui.settings.UserInfoCache.put(it) }
                }
                // 启动首页（设置 →「启动首页」）必须**在 NavHost 首次组合前**读出来：
                // 读出来才建 MainScaffold，否则会先按默认落页、再跳，肉眼就是"闪一下文件页"。
                // DataStore 首次读取是毫秒级，这段空窗复用上面登录态检查的同一个转圈。
                val startPagePref by container.appPrefs.startPage.collectAsState(initial = null)
                val startRoute = startPagePref?.route
                if (startRoute == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    MainScaffold(container, widthClass, initialPage = startRoute)
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MainScaffold(container: AppContainer, widthClass: WindowWidthSizeClass, initialPage: String) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val expanded = widthClass != WindowWidthSizeClass.Compact
    // 媒体库页隐藏导航栏（rail/bottomBar 都藏），全屏更有沉浸感；
    // 媒体库页自己的海报墙/详情浮层盖在它上面，返回也逐层退，不依赖导航栏
    val immersiveMedia = currentRoute == "media"

    // ---------------- 长任务跑完的收尾提示（扫描 / 查找重复 / 视频预览图）----------------
    /** 完成弹窗的槽位：三类任务共用，后到的覆盖先到的（同时跑完两个的场景极少，够用） */
    var taskDone by remember { mutableStateOf<TaskDoneNotice?>(null) }
    /** 查找重复文件面板的请求：从文件页发起，面板与批量重命名同层渲染 */
    var dupRequest by remember { mutableStateOf<DupScanRequest?>(null) }
    /** 批量生成视频预览图面板的请求：与查重面板同层渲染 */
    var previewRequest by remember { mutableStateOf<PreviewRequest?>(null) }

    /**
     * 收尾提示的分发：主界面看得见 → 弹窗（比 Snackbar 显眼，长任务跑完时用户往往已经翻到别的页）；
     * 看不见（切走 / 锁屏 / 在播放器里）→ 系统通知。不做"两边都发"：双份打扰。
     */
    fun announceDone(notice: TaskDoneNotice) {
        if (context.mainUiVisible) taskDone = notice
        else TaskDoneNotifier.notifyDone(context, "${notice.title}：${notice.text}")
    }

    // 后台任务的"忙"状态（导航栏红点）+ 跑完的提示。
    // 扫描要防抖（理由见 SCAN_BUSY_DEBOUNCE_MS：队列换任务的空档会被当成"跑完了"）；
    // 查重 / 预览图不排队、一轮跑完就散场，边沿即时可用（debounceMs = 0）——
    // 它们的完成弹窗要按"这一刻面板开没开"决定弹不弹，防抖会把那个判据推迟 1.2 秒。
    val scanBusy by rememberBusyEdge(
        busy = combine(container.mediaScanner.progress, container.mediaScanner.pending) { p, queue ->
            p.running || queue.isNotEmpty()
        },
    ) { scanNotice(container)?.let { announceDone(it) } }
    val renameBusy by rememberBusyEdge(
        busy = container.renameWorker.activeTaskId.map { it != null },
    ) { notifyRenameFinished(context, container, snackbarHostState) }
    val organizeBusy by rememberBusyEdge(
        busy = container.fileOrganizer.progress.map { it.running },
    ) { notifyOrganizeFinished(context, container, snackbarHostState) }
    val dupBusy by rememberBusyEdge(
        busy = combine(container.duplicateFinder.progress, container.versionFinder.progress) { a, b ->
            a.running || b.running
        },
        debounceMs = 0L,
    ) {
        // 面板开着 = 结果就在眼前，不再弹窗打扰；但"人已经切走"时面板看不见，照旧发通知
        if (dupRequest == null || !context.mainUiVisible) dupNotice(container)?.let { announceDone(it) }
    }
    val previewBusy by rememberBusyEdge(
        busy = container.previewBuilder.progress.map { it.running },
        debounceMs = 0L,
    ) {
        if (previewRequest == null || !context.mainUiVisible) previewNotice(container)?.let { announceDone(it) }
    }
    val scrapBusy by rememberBusyEdge(
        busy = container.metadataScraper.progress.map { it.running },
        debounceMs = 0L,
    ) {
        // 批量面板开着（在媒体库页复核候选）或单片刮削对话框开着：搜索/写入的结果就在眼前，
        // 不弹窗打扰；切走了（对话框关了、任务挂后台）才报
        val panelVisible = context.mainUiVisible &&
            (container.metadataScraper.session.value != null || container.metadataScraper.singleDialogOpen)
        if (!panelVisible) scrapNotice(container)?.let { announceDone(it) }
    }

    /** 某个导航入口上要不要挂"任务在跑"的红点（route 现算：手机底栏把重命名收在「更多」里） */
    fun destBusy(route: String): Boolean = when (route) {
        // 整理 / 查重 / 预览图都是从文件页发起的长任务：谁在跑都点亮「文件」这个入口
        "files" -> organizeBusy || dupBusy || previewBusy
        "rename", "more" -> renameBusy
        // 刮削红点也挂媒体库入口（批量从媒体库发起；详情页单条写入同样在这条线里）
        MEDIA_ROUTE -> scanBusy || scrapBusy
        else -> false
    }

    fun navigateRoute(route: String) {
        navController.navigate(route) {
            // 关键：弹回起始目的地并保存各标签自己的状态。
            // 之前 popUpTo(dest.route) 会把目标自身压栈/恢复整个旧栈，导致
            // 切换几个标签后点击失效、必须按返回键才能出来。
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // ---------------- 媒体库入场：暗色水波 ----------------
    // 点「媒体库」**不立刻切路由**：暗色圆从按键中心扩散铺满全屏，盖严的那一瞬间才
    // navigate —— 媒体库首帧的重活（DB 查询 + 海报墙组装 + 图片解码）整体藏在纯暗
    // 背后，随后暗色淡出、溶进媒体库自己的深色底里。原先"淡入+上浮"过渡期间跟页面
    // 组装抢主线程造成的卡顿生硬，就这么绕开了。
    var mediaWave by remember { mutableStateOf<Offset?>(null) }
    /** 这一波是**退场**（暗面收缩回按键）还是入场（从按键扩散铺满）—— 同一套画法，方向相反 */
    var waveBack by remember { mutableStateOf(false) }
    /**
     * 退场的**起手铺满**：点返回的那一刻同步置位，画面上立刻是一层满屏暗色。
     *
     * 为什么不靠"把半径 snap 到 maxR"：那是协程里的动作，跑到它的时机和这一帧的绘制谁先谁后
     * 是竞态 —— 真机上约一半概率会多露几帧媒体库画面（用户实测报的"停留几帧才切"）。
     * 铺满这一下看不出来（波色就是媒体库底色），所以用最笨也最稳的办法：同步盖住，
     * 等收缩动画起来时（effect 里）再交回给圆。
     */
    var waveCovered by remember { mutableStateOf(false) }
    val waveRadius = remember { Animatable(0f) }
    val waveAlpha = remember { Animatable(1f) }
    var waveAreaSize by remember { mutableStateOf(IntSize.Zero) }

    /** 各导航按键的中心（root 坐标）：水波从被点的那个按键扩散出来 */
    val navCenters = remember { mutableMapOf<String, Offset>() }

    fun navigate(dest: Dest) {
        // 波面进行中不响应切页：路由在暗面底下跳变会穿帮
        if (mediaWave != null) return
        val center = navCenters[dest.route]
        if (dest.route == MEDIA_ROUTE && center != null) {
            waveBack = false
            mediaWave = center
        } else {
            navigateRoute(dest.route)
        }
    }

    /**
     * 离开媒体库：入场的**反向** —— 暗面从满屏收缩回「媒体库」那个按键，一来一回。
     *
     * 顺序是**先切路由、再收缩**：路由一换，下面的页面就在暗面背后组装就位，
     * 缩到最后露出来的是一个已经画完的页面（而不是"边淡入边被露出来"）。
     * 起手那一下（暗面盖满全屏）看不见 —— 媒体库页本身就是这个颜色。
     */
    fun leaveMediaWave() {
        if (mediaWave != null) return
        val center = navCenters[MEDIA_ROUTE]
            ?: Offset(waveAreaSize.width / 2f, waveAreaSize.height / 2f)
        // 同步盖满（见 waveCovered 的注释）：这一帧起画面就是暗面，不再依赖协程调度次序
        waveCovered = true
        waveBack = true
        mediaWave = center
    }

    // 返回键离开媒体库也走反向水波 —— 但**不能挂在这里**：NavHost 自己的返回回调注册得更晚、
    // 优先级更高，会把返回直接吃掉（真机实测：挂这里时波根本不出现）。它挂在 media 路由的内容里，
    // 见下面 NavHost 的 composable("media")。

    LaunchedEffect(mediaWave, waveBack) {
        val center = mediaWave ?: return@LaunchedEffect
        val w = waveAreaSize.width.coerceAtLeast(1).toFloat()
        val h = waveAreaSize.height.coerceAtLeast(1).toFloat()
        val maxR = hypot(maxOf(center.x, w - center.x), maxOf(center.y, h - center.y))
        if (waveBack) {
            // 起手（waveCovered）已经在按下返回的那一瞬间同步盖住了，这里**等两帧**再切路由：
            // 第一帧等暗面真的画上屏，第二帧才动手切 —— 切路由（返回文件页：整页重组 + 列表取数）
            // 是这一帧里最重的一件事，跟"画暗面"挤在同一帧时会把暗面推后，
            // 屏幕上就还是媒体库画面（用户实测："返回后媒体库画面还停留几帧才切到文件"）。
            // 这两帧看着是静止的，但暗面颜色就是媒体库底色，看不出来。
            waveRadius.snapTo(maxR)
            waveAlpha.snapTo(1f)
            withFrameNanos { }
            withFrameNanos { }
            navController.popBackStack()
            // 路由换完再把画面交回给圆：收缩从"铺满"开始
            waveCovered = false
            waveRadius.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            // 尾巴上淡一下：收成小圆点时不至于突兀
            waveAlpha.animateTo(0f, tween(180))
            waveBack = false
            mediaWave = null
        } else {
            waveRadius.snapTo(0f)
            waveAlpha.snapTo(1f)
            // 等一帧：波面 Box 的实际尺寸（onSizeChanged）先落下来
            withFrameNanos { }
            // 扩散：暗色铺满全屏
            waveRadius.animateTo(maxR, tween(420, easing = FastOutSlowInEasing))
            // 盖严的瞬间才切路由（导航栏的滑出也发生在纯暗背后，不可见）
            navigateRoute(MEDIA_ROUTE)
            // 淡出：波色就是媒体库深色方案的 background，淡出即溶进页面底色
            waveAlpha.animateTo(0f, tween(320))
            mediaWave = null
        }
    }

    /**
     * 完成弹窗点「查看结果」：面板的"家"在文件页，先切过去再把面板挂回来 ——
     * 结果还在引擎的进度对象里，不用重跑。
     *
     * 媒体库要走**反向水波**退出（它没有导航栏、只能走那条路），其它页走普通切页；
     * 面板渲染在内容区之上、水波又画在全部内容之上，所以切页那一下不会穿帮。
     */
    fun gotoPanel(open: () -> Unit) {
        when (currentRoute) {
            "files" -> open()
            MEDIA_ROUTE -> {
                leaveMediaWave()
                open()
            }
            else -> {
                navigateRoute("files")
                open()
            }
        }
    }

    /**
     * 启动首页：导航图起点**恒为文件页**，登录后只往偏好页跳一次。
     *
     * 为什么不直接把偏好页设成 startDestination：媒体库页是"盖在文件页之上"的沉浸页
     * （收起导航栏、返回键回文件页 —— 用户熟悉的就是这个语义）。把它当图起点，BACK 会直接
     * 退出应用，人在媒体库页又没有导航栏可点，等于进得去出不来。
     *
     * [landed] 是给第一次绘制遮一下的：跳转发生在首帧之后，不遮的话会看到一帧文件页
     * （"闪一下"）。跳完/不需要跳（本来就是文件页）才显示内容。
     */
    var landed by remember { mutableStateOf(initialPage == "files") }
    LaunchedEffect(Unit) {
        if (initialPage != "files") {
            // 不用 navigate(dest)：它是给"点标签"用的（会 popUpTo 起点 + 存/恢复状态），
            // 这里就是普通的压栈，语义与手动点一下「媒体库」完全一致
            navController.navigate(initialPage) { launchSingleTop = true }
        }
        landed = true
    }

    val onPlayVideo: (FileItem, List<PlaylistEntry>, Int, String?, String?) -> Unit =
        { item, playlist, index, opLogCid, opLogPath ->
            val pc = item.pc
            if (pc.isNullOrBlank()) {
                scope.launch { snackbarHostState.showSnackbar("该文件缺少提取码，无法播放") }
            } else {
                // 记录点在 FilesScreen（只有那里拿得到"当时所在的目录"），这里只负责启动播放；
                // cid/path 原样进播放器 —— 播放器内切集时补的操作记录要用同一对值
                context.startActivity(
                    PlayerActivity.intent(
                        context, pc, item.fn, playlist, index,
                        opLogCid = opLogCid, opLogPath = opLogPath,
                        // 预览图上传目标：当前目录就是视频所在目录（opLogCid 同时充当来源目录）
                        sourceCid = opLogCid,
                    )
                )
            }
        }

    // ---------------- 大图画廊（应用根层级渲染，才能盖住侧栏） ----------------
    var imageGallery by remember { mutableStateOf<Triple<List<ImageMediaItem>, Int, String>?>(null) }
    /** 文本预览：与画廊一样在根层级全屏渲染，才能盖住侧栏 */
    var textPreview by remember { mutableStateOf<FileItem?>(null) }
    /** 批量重命名：面板渲染在内容区之内（左侧导航栏保持可见），状态由文件页触发 */
    var batchRename by remember { mutableStateOf<BatchRenameRequest?>(null) }
    /** 云下载页点任务跳转：待打开的目录 (cid, name)，文件页进页时消费（openByCid） */
    var pendingCloudJump by remember { mutableStateOf<Pair<String, String>?>(null) }
    val jumpToCloudDir: (String, String) -> Unit = { cid, name ->
        pendingCloudJump = cid to name
        navigateRoute("files")
    }

    // ---------------- 上次没跑完的扫描：问一次要不要接着扫 ----------------
    // 扫描器在爬取途中会把"扫到哪儿了"落盘（见 MediaScanner.ScanResume）：进程被杀、
    // 撞频控、网络断开都会留下这份现场，重启登录后在这里问一句 —— 答"继续"就从断点接着扫
    // （已扫完的目录靠 scan_state 跳过），答"忽略"这次中断就作罢（同一个中断只问一次）。
    // 挂在根层级而不是媒体库页里：启动首页可能是文件页，用户不一定马上进媒体库。
    //
    // **先探活再问**（用户要求）：中断的原因多半就是接口被 ban / 网络不通，那时弹窗是白弹 ——
    // 用户点「继续扫描」只会立刻再失败一次。所以扫描器先把问题以 checking = true 挂起来
    // （这时什么都不显示），探到接口通才翻成 false 放出来（见 MediaScanner.raiseResumeProposal）
    val resumeProposal by container.mediaScanner.resumeProposal.collectAsState()
    resumeProposal?.takeIf { !it.checking }?.let { proposal ->
        FadeAlertDialog(
            onDismissRequest = { container.mediaScanner.dismissResume() },
            title = { Text("上次扫描未完成") },
            text = { Text(resumeScanText(proposal)) },
            confirmButton = {
                TextButton(onClick = { container.mediaScanner.acceptResume() }) { Text("继续扫描") }
            },
            // 两个"不扫"的答复并排：区别在**现场留不留** ——
            // 跳过本次留（下次启动再问，见 MediaScanner.deferResume），
            // 不再询问不留（这次中断就此作罢，见 MediaScanner.dismissResume）
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { container.mediaScanner.deferResume() }) { Text("跳过本次") }
                    TextButton(onClick = { container.mediaScanner.dismissResume() }) { Text("不再询问") }
                }
            },
        )
    }

    // ---------------- 剪贴板识别 / 外部 App 唤起的下载链接 ----------------

    val autoSubmitClipboard by container.downloadPrefs.autoSubmitClipboardDownload
        .collectAsState(initial = false)
    val pendingDownload by container.downloadLinks.pending.collectAsState()
    // 需要二次确认的请求（仅剪贴板来源 + 未开静默模式），留在本地状态里等用户点按
    var sheetRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    var submittingDownload by remember { mutableStateOf(false) }
    // 外部程序联动云离线：处理结束后询问是否返回原程序（成功、失败都问）
    var returnAsk by remember { mutableStateOf<ReturnAsk?>(null) }

    /**
     * 提交下载并给出反馈。
     *
     * 注意这里的协程必须挂在 scope（随组合存活）而不是 LaunchedEffect(pendingDownload) 里：
     * 第一行 consume() 会把 pending 置空，进而改变那个 LaunchedEffect 的 key 并**取消它自己的协程**，
     * 后面的提示与跳转就全丢了。早期实现正是踩了这个坑——提交成功但界面毫无反馈。
     */
    suspend fun submitDownload(req: DownloadRequest, auto: Boolean, gotoOffline: Boolean) {
        container.downloadLinks.consume() // 立刻消费，保证同一请求不会被处理两次
        submittingDownload = true
        val err = container.downloadSubmitter.submit(container.openApi, req.url)
        submittingDownload = false
        if (err == null) {
            // 记录云离线操作（URL 只留前 40 字符做摘要，磁力/直链都够辨认）
            scope.launch {
                container.opLog.log(
                    OpType.OFFLINE,
                    if (req.url.length > 40) req.url.take(40) + "…" else req.url,
                    if (req.source == LinkSource.EXTERNAL) "来自外部应用" else "来自剪贴板",
                )
            }
            // snackbar 是挂起调用（挂住约 4 秒），单独起协程展示，
            // 不拖累后面的页面跳转与"返回原程序"弹窗
            scope.launch {
                snackbarHostState.showSnackbar(if (auto) "已自动提交云下载任务" else "任务已加入云端离线队列")
            }
            if (gotoOffline) {
                // 手机模式下云下载页收进了传输中心（内嵌分页），带 tab 参数直达云下载分页；
                // 平板仍是独立路由
                if (expanded) destinations.firstOrNull { it.route == "offline" }?.let { navigate(it) }
                else navigateRoute("transfer?tab=offline")
            }
        } else {
            snackbarHostState.showSnackbar("提交失败：$err")
        }
        // 外部 App 唤起的：**不论提交成败**都问一次要不要回去。
        // 用户是从别的应用跳过来的，留在这里多半不是本意；失败时更需要一个出口。
        // 早先只在成功时问，于是磁力/直链因"任务已存在"等被服务端拒绝时毫无反馈，
        // 看起来就像"应用已在运行时被联动却什么都不弹"。
        if (req.source == LinkSource.EXTERNAL) returnAsk = ReturnAsk(err)
    }

    LaunchedEffect(pendingDownload) {
        val req = pendingDownload ?: return@LaunchedEffect
        when {
            // 外部 App 唤起（Deep Link / 磁力 / 系统分享）：默认直接提交，不再二次确认
            req.source == LinkSource.EXTERNAL ->
                scope.launch { submitDownload(req, auto = true, gotoOffline = true) }

            // 剪贴板 + 静默模式：直接提交，只给一条轻提示
            autoSubmitClipboard ->
                scope.launch { submitDownload(req, auto = true, gotoOffline = false) }

            // 剪贴板 + 默认模式：转交底部抽屉二次确认。
            // 请求已存进 sheetRequest，这里可以放心消费掉待处理槽位。
            else -> {
                sheetRequest = req
                container.downloadLinks.consume()
            }
        }
    }

    Scaffold(
        // 暗色水波画在 Scaffold 全部内容（含导航栏/底栏）之上：drawWithContent 先画
        // 原内容再补一个圆，波面状态变化只触发重绘、不触发重组；尺寸顺手量给波循环
        modifier = Modifier
            .fillMaxSize()
            // 预览浮层（图片画廊/文本预览）进场时从点击位置放大 —— 在根部以 Initial pass
            // 只观察不消费地记下每次按压点，对子组件手势零影响
            .observeLastTapPoint()
            .onSizeChanged { waveAreaSize = it }
            .drawWithContent {
                drawContent()
                val center = mediaWave ?: return@drawWithContent
                // 退场起手那一帧：整屏盖住（同步置位，不等协程 —— 见 waveCovered 的注释）
                if (waveCovered) {
                    drawRect(MediaWaveDark)
                    return@drawWithContent
                }
                drawCircle(
                    color = MediaWaveDark.copy(alpha = waveAlpha.value),
                    radius = waveRadius.value,
                    center = center,
                )
            },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 手机底部导航栏：媒体库页收起。进媒体库时波面已盖严全屏，收起必须**瞬时**
            // （ExitTransition.None）——任何滑出/淡出都会在暗色淡出时从波面下"闪"出来；
            // 回到其他页的滑入动画保留
            AnimatedVisibility(
                visible = !expanded && !immersiveMedia,
                // 同左栏：反向水波那一下瞬时（理由见左栏那段），平时保留滑入
                enter = if (waveBack) EnterTransition.None
                else fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 2 },
                exit = ExitTransition.None,
            ) {
                NavigationBar {
                    val selected = phoneSelectedTab(currentRoute)
                    phoneTabs.forEach { dest ->
                        NavigationBarItem(
                            selected = selected == dest.route,
                            onClick = { navigate(dest) },
                            modifier = Modifier.onGloballyPositioned {
                                navCenters[dest.route] = it.boundsInRoot().center
                            },
                            icon = { BusyDestIcon(dest.icon, dest.label, destBusy(dest.route)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // 平板左侧导航栏：同底栏——进媒体库时收起必须瞬时。原来"滑出+收合宽度"的
            // 退场会让内容区在暗色淡出窗口里被挤着扩宽，波面一透明就看见导航栏闪现、
            // 页面变形；从媒体库回其他页的滑入+展开保留 —— ★ 除了"反向水波"那一下
            // （waveBack）：那时也必须瞬时。展开宽度是**改布局**的动画，每帧都要把内容区
            // 重新排版一遍，和收缩水波抢主线程，暗面会被推后几帧才画出来
            // （真机反馈："返回后媒体库画面还停留几帧才切到文件"）。暗面盖着，瞬时进出都看不见。
            AnimatedVisibility(
                visible = expanded && !immersiveMedia,
                enter = if (waveBack) EnterTransition.None
                else fadeIn(tween(250)) + slideInHorizontally(tween(300)) { -it / 2 } + expandHorizontally(tween(300)),
                exit = ExitTransition.None,
            ) {
                NavigationRail {
                    // 常规入口（媒体库不在这里，见下面）
                    destinations.filter { it.route != MEDIA_ROUTE }.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigate(dest) },
                            modifier = Modifier.onGloballyPositioned {
                                navCenters[dest.route] = it.boundsInRoot().center
                            },
                            icon = { BusyDestIcon(dest.icon, dest.label, destBusy(dest.route)) },
                            label = { Text(dest.label) },
                        )
                    }
                    // ★ 媒体库**沉到栏底**、和上面那组用一条分隔线隔开：
                    //   它不是"又一个页面"，而是一整层沉浸式界面（进去会把导航栏整个收掉、
                    //   出来走暗色水波），位置上也不该跟切页的入口混成一串。
                    //   加一层浅底色是第二重区分：一眼看出这不是同类的入口。
                    Spacer(Modifier.weight(1f))
                    // ★ 宽度必须写死：导航栏的列是**松约束**（宽度由子项决定），用 fillMaxWidth()
                    //   会让这条线按"可用最大宽度"去量，等于撑满整屏、把内容区挤成 0 宽。
                    Box(
                        Modifier
                            .width(48.dp)
                            .padding(vertical = 10.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    destinations.firstOrNull { it.route == MEDIA_ROUTE }?.let { media ->
                        NavigationRailItem(
                            selected = currentRoute == media.route,
                            onClick = { navigate(media) },
                            modifier = Modifier
                                // 底部留出边距：贴到屏幕最下沿时这个圆角色的下半部分会被切掉
                                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .onGloballyPositioned {
                                    navCenters[media.route] = it.boundsInRoot().center
                                },
                            icon = { BusyDestIcon(media.icon, media.label, scanBusy) },
                            label = { Text(media.label) },
                        )
                    }
                }
            }
            // NavHost 与批量重命名面板同层，面板只盖内容区、不盖导航栏
            Box(Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    // 起点恒为文件页；启动首页由上面那次 navigate 跳过去（返回语义见那里的注释）
                    startDestination = "files",
                    // 首帧遮一下：跳转还没发生前别把文件页露出来（见 landed 的注释）
                    modifier = Modifier.fillMaxSize().alpha(if (landed) 1f else 0f),
                ) {
                    composable(
                        "files",
                        // 从媒体库"收缩"回来时是贴着暗面现身的：这时的入场必须是 None ——
                        // 默认那 700ms 淡入会让收缩过程中露出来的画面是半透明的，跟入场水波不对称
                        enterTransition = { if (waveBack) EnterTransition.None else fadeIn(tween(700)) },
                    ) {
                        val vm: FilesViewModel = viewModel(initializer = {
                            FilesViewModel(
                                container.openApi,
                                container.filesPrefs,
                                container.imageUrlResolver,
                                container.filterPrefs,
                                container.pinnedPrefs,
                                container.dirCache,
                                container.opLog,
                                // 文件页改名的目录要作废媒体库的扫描状态（理由见 FilesViewModel 的构造参数）
                                invalidateMediaDir = { cid ->
                                    container.mediaDatabase.mediaDao().invalidateScanState(cid)
                                },
                                // 目录栈存档：进播放器后 MainActivity 被系统回收、回来重建时
                                // 靠它回到原来那一层，而不是被打回根目录（见该构造参数的说明）
                                savedState = createSavedStateHandle(),
                                // 回收站/云下载删除的目录内容广播：文件页同步移除对应条目
                                dirRemovals = container.dirRemovals,
                            )
                        })
                        FilesScreen(
                            vm,
                            expanded,
                            snackbarHostState,
                            onPlayVideo,
                            onOpenGallery = { items, idx, path -> imageGallery = Triple(items, idx, path) },
                            onPreviewText = { item -> textPreview = item },
                            onBatchRename = { batchRename = it },
                            onFindDuplicates = { dupRequest = it },
                            onGeneratePreviews = { previewRequest = it },
                            onOpenFilterRules = {
                                destinations.firstOrNull { it.route == "filter" }?.let { navigate(it) }
                            },
                            pendingJump = pendingCloudJump,
                            onJumpConsumed = { pendingCloudJump = null },
                        )
                    }
                    // 媒体库页：进场出场都交给「暗色水波」编排（见 MainScaffold 的 mediaWave）——
                    // 路由在波面盖严全屏的瞬间才切，这里直接就位即可。
                    // **出场也必须瞬时**：默认的 200ms 淡出会让收缩过程中露出来的区域里
                    // 还叠着半透明的媒体库页（用户实测："退出后媒体库画面还会停留几帧"）。
                    composable(
                        "media",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                    ) {
                        // 返回键：走**反向水波**离开媒体库（一来一回，见 leaveMediaWave）。
                        // 这个 BackHandler 必须挂在这一层：挂到外层的 Scaffold 上不生效 ——
                        // NavHost 自己的返回回调注册得更晚、优先级更高，返回会被它直接吃掉。
                        // 放在页面内容**之前**：页面内部的返回（清搜索词 / 退海报墙 / 退浮层）
                        // 都在它后面注册、优先消费，所以这里只在"真要从媒体库退出"时才触发。
                        BackHandler(enabled = mediaWave == null) { leaveMediaWave() }
                        // 媒体库整条线走深色：列表 / 海报墙 / 详情页三屏连成一体，
                        // 详情页是 fanart 铺满 + 取色底色，前两屏跟着暗才不割裂。
                        // 在这里包一次即可 —— 三个页面都只用 MaterialTheme.colorScheme.*
                        com.open115.pad.ui.theme.Open115DarkTheme {
                            // 还要**显式铺一层深色底**：页面底色来自外层 Scaffold 的 containerColor，
                            // 那个在深色方案之外，拿到的还是浅色（卡片会变暗、底却还是白的）
                            androidx.compose.material3.Surface(
                                color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                com.open115.pad.ui.media.MediaLibraryScreen(container.openApi)
                            }
                        }
                    }
                    composable("filter") {
                        com.open115.pad.ui.filter.FilterRulesScreen(container)
                    }
                    composable("offline") {
                        val vm: OfflineViewModel = viewModel(initializer = {
                            OfflineViewModel(
                                container.openApi,
                                container.downloadPrefs,
                                container.dirCache,
                                container.dirRemovals,
                                // 提交走 DownloadSubmitter（剪贴板/外部唤起同一套错误文案）
                                container.downloadSubmitter,
                                // 磁力搜索（MCP）：配置在实验室
                                container.playerPrefs,
                                container.okHttpClient,
                            )
                        })
                        OfflineScreen(vm, expanded, snackbarHostState, onJump = jumpToCloudDir)
                    }
                    composable("recycle") {
                        val vm: RecycleViewModel = viewModel(initializer = {
                            RecycleViewModel(container.openApi, container.dirCache, container.dirRemovals, container.videoPreviews)
                        })
                        RecycleScreen(vm, expanded, snackbarHostState)
                    }
                    composable(
                        // 手机模式下「云下载/回收站」是传输中心的内嵌分页，外部唤起提交后
                        // 用 transfer?tab=offline 直达云下载分页；不带参数=普通进页
                        route = "transfer?tab={tab}",
                        arguments = listOf(navArgument("tab") {
                            type = NavType.StringType
                            defaultValue = ""
                        }),
                    ) { entry ->
                        com.open115.pad.ui.transfer.TransferScreen(
                            snackbarHostState,
                            showCloudTabs = !expanded,
                            requestedTab = entry.arguments?.getString("tab").orEmpty(),
                            onCloudJump = jumpToCloudDir,
                        )
                    }
                composable("rename") {
                    com.open115.pad.ui.rename.RenameTasksScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        // 选好目录后把配置面板交给根层级渲染（面板要浮在内容区之上）
                        onNewTask = { batchRename = it },
                    )
                }
                composable("settings") {
                    SettingsScreen(container)
                }
                // 手机模式的「更多」收纳页：低频功能入口（过滤规则/重命名，以后新增也进这里）。
                // 从这里点进去是普通压栈（不 popUpTo），返回键回到「更多」而不是文件页
                composable("more") {
                    com.open115.pad.ui.more.MoreScreen(
                        entries = destinations.filter { it.route in moreRoutes }.map {
                            com.open115.pad.ui.more.MoreEntry(
                                it.route, it.label, it.icon,
                                busy = destBusy(it.route),
                            )
                        },
                        onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
                    )
                }
                }

                // 批量重命名面板：与 NavHost 同层，浮在内容区之上但**不盖住左侧导航栏** ——
                // 批量改名时经常要换目录挑文件，把导航藏掉反而挡路。
                // （图片画廊 / 文本预览仍是根层级全屏，那是沉浸式查看，语义不同）
                FadePresence(batchRename) { req ->
                    val aiCustom = container.mediaPrefs.aiCustomTemplates.collectAsState(initial = emptyList()).value
                    val aiRenameTpl = container.mediaPrefs.aiRenameTemplate.collectAsState(
                        initial = com.open115.pad.data.media.scrape.AiPromptTemplates.RENAME_CLEAN,
                    ).value
                    BatchRenamePanel(
                        request = req,
                        queue = container.renameQueue,
                        scope = scope,
                        aiClient = container.aiQueryClient,
                        aiTemplates = com.open115.pad.data.media.scrape.AiPromptTemplates.all(aiCustom),
                        aiDefaultTpl = aiRenameTpl,
                        onGotoTasks = {
                            batchRename = null
                            destinations.firstOrNull { it.route == "rename" }?.let { navigate(it) }
                        },
                        onDismiss = { batchRename = null },
                    )
                }

                // 查找重复文件面板：同一层（盖内容区、不盖导航栏）。判重是只读的，删除走 115 回收站。
                FadePresence(dupRequest) { req ->
                    DuplicatePanel(
                        request = req,
                        finder = container.duplicateFinder,
                        versionFinder = container.versionFinder,
                        api = container.openApi,
                        frames = container.videoFrames,
                        opLog = container.opLog,
                        // 候选条目试播：单条起播（不带连播队列），目录信息照记操作记录
                        onPlay = { item, dirCid, dirPath ->
                            val pc = item.pc
                            if (pc.isNullOrBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("该文件缺少提取码，无法播放") }
                            } else {
                                scope.launch {
                                    container.opLog.log(OpType.VIDEO_PLAY, item.fn, "重复文件面板试播", dirCid, dirPath)
                                }
                                context.startActivity(
                                    PlayerActivity.intent(
                                        context, pc, item.fn,
                                        listOf(PlaylistEntry(pc, item.fn, item.fid)), 0,
                                        opLogCid = dirCid, opLogPath = dirPath, sourceCid = dirCid,
                                    )
                                )
                            }
                        },
                        onDismiss = { dupRequest = null },
                        onMessage = { msg -> msg?.let { scope.launch { snackbarHostState.showSnackbar(it) } } },
                    )
                }

                // 批量生成视频预览图面板：同一层，只读（抓帧落本机缓存，不动云端）
                FadePresence(previewRequest) { req ->
                    PreviewPanel(
                        request = req,
                        builder = container.previewBuilder,
                        store = container.videoPreviews,
                        onDismiss = { previewRequest = null },
                    )
                }
            }
        }
    }

    // 大图画廊：根层级全屏遮罩（Dialog 会被侧栏宽度内缩，盖不住左侧导航）
    imageGallery?.let { (items, idx, path) ->
        PreviewZoomHost(onDismissed = { imageGallery = null }) { requestClose ->
            ImageGalleryDialog(
                items = items,
                initialIndex = idx,
                resolver = container.imageUrlResolver,
                onDismiss = requestClose,
                sourcePath = path,
            )
        }
    }

    // 文本预览：同一套根层级全屏遮罩；缺提取码的文件直接提示并关闭
    textPreview?.let { item ->
        val pc = item.pc
        if (pc.isNullOrBlank()) {
            LaunchedEffect(item.fid) {
                snackbarHostState.showSnackbar("该文件缺少提取码，无法预览")
                textPreview = null
            }
        } else {
            PreviewZoomHost(onDismissed = { textPreview = null }) { requestClose ->
                TextPreviewHost(
                    name = item.fn,
                    pickCode = pc,
                    sizeText = Format.size(item.fs),
                    resolver = container.imageUrlResolver,
                    client = container.okHttpClient,
                    onDownload = { url, name ->
                        runCatching { Downloader.enqueue(context, url, name) }
                        scope.launch { snackbarHostState.showSnackbar("已加入系统下载队列") }
                    },
                    onDismiss = requestClose,
                )
            }
        }
    }

    // 剪贴板链接的二次确认抽屉（外部唤起与静默模式都不经过这里）
    sheetRequest?.let { req ->
        DownloadLinkSheet(
            request = req,
            submitting = submittingDownload,
            onSubmit = {
                scope.launch {
                    submitDownload(req, auto = false, gotoOffline = true)
                    sheetRequest = null
                }
            },
            onDismiss = {
                // 用户忽略：待处理槽位在转交抽屉时就已消费，这里只收起抽屉。
                // 内容指纹已落盘，同一段剪贴板不会再次打扰。
                sheetRequest = null
            },
        )
    }

    // 外部程序联动云离线：处理结束后询问是否返回原程序。
    // "返回" = 把本应用任务整体退到后台，系统自然回到唤起方。
    // 刻意不用 finish()：主界面若被销毁，任务栈就空了，用户再切回来等于冷启动
    // （正在播放的视频、浏览到的目录全丢），这正是要避免的"返回后把应用杀掉"。
    returnAsk?.let { ask ->
        FadeAlertDialog(
            onDismissRequest = { returnAsk = null },
            title = { Text(if (ask.error == null) "已提交云下载" else "云下载未提交") },
            text = {
                Text(
                    if (ask.error == null) "任务已加入离线队列。是否返回原来的应用？"
                    else "${ask.error}。是否返回原来的应用？",
                )
            },
            confirmButton = {
                Button(onClick = {
                    returnAsk = null
                    context.findHostActivity()?.moveTaskToBack(true)
                }) { Text("返回原程序") }
            },
            dismissButton = {
                TextButton(onClick = { returnAsk = null }) { Text("留在本应用") }
            },
        )
    }

    // 长任务（扫描 / 查找重复 / 视频预览图）跑完的弹窗。
    // 「查看结果」把面板挂回来：结果还在引擎的进度对象里，不用重跑（查重面板还会落在
    // 它跑的那个模式上，见 DupScanRequest.version）。
    taskDone?.let { done ->
        FadeAlertDialog(
            onDismissRequest = { taskDone = null },
            title = { Text(done.title) },
            text = { Text(done.text) },
            confirmButton = {
                done.view?.let { target ->
                    TextButton(onClick = {
                        taskDone = null
                        when (target) {
                            is PanelTarget.Dup -> gotoPanel { dupRequest = target.request }
                            is PanelTarget.Preview -> gotoPanel { previewRequest = target.request }
                            // 刮削的结果在媒体库页的复核面板里：直接切过去
                            // （不走 gotoPanel —— 那个是"面板挂在文件页"的语义）
                            PanelTarget.MediaScrape -> navigateRoute(MEDIA_ROUTE)
                        }
                    }) { Text("查看结果") }
                }
            },
            dismissButton = {
                TextButton(onClick = { taskDone = null }) { Text("知道了") }
            },
        )
    }
}

/** 外部联动处理结果：error 为 null 表示已成功提交 */
private data class ReturnAsk(val error: String?)

/**
 * 暗色水波的波面颜色 = Open115DarkTheme 的 background（Theme.kt 的 DarkColors）。
 * 波色与媒体库页面底色相同，淡出阶段就溶进页面里，看不出切换接缝。
 */
private val MediaWaveDark = Color(0xFF1C1C23)

/** 从 Compose 的 context 一路解到宿主 Activity（moveTaskToBack 需要） */
private fun Context.findHostActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
