package com.open115.pad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open115.pad.AppContainer
import com.open115.pad.data.DownloadRequest
import com.open115.pad.data.DownloadSubmitter
import com.open115.pad.data.FileItem
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.LinkSource
import com.open115.pad.data.OpType
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.Session
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.BatchRenamePanel
import com.open115.pad.ui.components.DownloadLinkSheet
import com.open115.pad.ui.components.ImageGalleryDialog
import com.open115.pad.ui.components.TextPreviewHost
import com.open115.pad.util.Downloader
import com.open115.pad.util.Format
import com.open115.pad.ui.files.BatchRenameRequest
import com.open115.pad.ui.files.FilesScreen
import com.open115.pad.ui.files.FilesViewModel
import com.open115.pad.ui.login.LoginScreen
import com.open115.pad.ui.offline.OfflineScreen
import com.open115.pad.ui.offline.OfflineViewModel
import com.open115.pad.ui.recycle.RecycleScreen
import com.open115.pad.ui.recycle.RecycleViewModel
import com.open115.pad.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("files", "文件", Icons.Outlined.Folder),
    Dest("media", "媒体库", Icons.Outlined.Movie),
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

@Composable
fun AppRoot(container: AppContainer, widthClass: WindowWidthSizeClass) {
    val loggedIn by container.session.loggedInFlow.collectAsState(initial = null)
    Surface(Modifier.fillMaxSize()) {
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

    fun navigate(dest: Dest) = navigateRoute(dest.route)

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

    val onPlayVideo: (FileItem, List<PlaylistEntry>, Int) -> Unit = { item, playlist, index ->
        val pc = item.pc
        if (pc.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("该文件缺少提取码，无法播放") }
        } else {
            // 记录点在 FilesScreen（只有那里拿得到"当时所在的目录"），这里只负责启动播放
            context.startActivity(PlayerActivity.intent(context, pc, item.fn, playlist, index))
        }
    }

    // ---------------- 大图画廊（应用根层级渲染，才能盖住侧栏） ----------------
    var imageGallery by remember { mutableStateOf<Pair<List<ImageMediaItem>, Int>?>(null) }
    /** 文本预览：与画廊一样在根层级全屏渲染，才能盖住侧栏 */
    var textPreview by remember { mutableStateOf<FileItem?>(null) }
    /** 批量重命名：面板渲染在内容区之内（左侧导航栏保持可见），状态由文件页触发 */
    var batchRename by remember { mutableStateOf<BatchRenameRequest?>(null) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 手机底部导航栏：媒体库页下滑收起（滑出+收合高度），回到其他页滑回来
            AnimatedVisibility(
                visible = !expanded && !immersiveMedia,
                enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 2 },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(300)) { it / 2 } + shrinkVertically(tween(300)),
            ) {
                NavigationBar {
                    val selected = phoneSelectedTab(currentRoute)
                    phoneTabs.forEach { dest ->
                        NavigationBarItem(
                            selected = selected == dest.route,
                            onClick = { navigate(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // 平板左侧导航栏：进媒体库向左滑出 + 淡出，同时宽度收合让内容区丝滑扩满
            AnimatedVisibility(
                visible = expanded && !immersiveMedia,
                enter = fadeIn(tween(250)) + slideInHorizontally(tween(300)) { -it / 2 } + expandHorizontally(tween(300)),
                exit = fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -it / 2 } + shrinkHorizontally(tween(300)),
            ) {
                NavigationRail {
                    destinations.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigate(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
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
                    composable("files") {
                        val vm: FilesViewModel = viewModel(initializer = {
                            FilesViewModel(
                                container.openApi,
                                container.filesPrefs,
                                container.imageUrlResolver,
                                container.filterPrefs,
                                container.pinnedPrefs,
                                container.dirCache,
                                container.opLog,
                            )
                        })
                        FilesScreen(
                            vm,
                            expanded,
                            snackbarHostState,
                            onPlayVideo,
                            onOpenGallery = { items, idx -> imageGallery = items to idx },
                            onPreviewText = { item -> textPreview = item },
                            onBatchRename = { batchRename = it },
                            onOpenFilterRules = {
                                destinations.firstOrNull { it.route == "filter" }?.let { navigate(it) }
                            },
                        )
                    }
                    // 媒体库页：淡入 + 轻微上浮缩放，配导航栏滑出，丝滑进全屏
                    composable(
                        "media",
                        enterTransition = {
                            fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 24 } +
                                scaleIn(initialScale = 0.96f, animationSpec = tween(350))
                        },
                        exitTransition = { fadeOut(tween(200)) },
                    ) {
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
                            OfflineViewModel(container.openApi, container.downloadPrefs)
                        })
                        OfflineScreen(vm, expanded, snackbarHostState)
                    }
                    composable("recycle") {
                        val vm: RecycleViewModel = viewModel(initializer = { RecycleViewModel(container.openApi) })
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
                            com.open115.pad.ui.more.MoreEntry(it.route, it.label, it.icon)
                        },
                        onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
                    )
                }
                }

                // 批量重命名面板：与 NavHost 同层，浮在内容区之上但**不盖住左侧导航栏** ——
                // 批量改名时经常要换目录挑文件，把导航藏掉反而挡路。
                // （图片画廊 / 文本预览仍是根层级全屏，那是沉浸式查看，语义不同）
                batchRename?.let { req ->
                    BatchRenamePanel(
                        request = req,
                        queue = container.renameQueue,
                        scope = scope,
                        onGotoTasks = {
                            batchRename = null
                            destinations.firstOrNull { it.route == "rename" }?.let { navigate(it) }
                        },
                        onDismiss = { batchRename = null },
                    )
                }
            }
        }
    }

    // 大图画廊：根层级全屏遮罩（Dialog 会被侧栏宽度内缩，盖不住左侧导航）
    imageGallery?.let { (items, idx) ->
        ImageGalleryDialog(
            items = items,
            initialIndex = idx,
            resolver = container.imageUrlResolver,
            onDismiss = { imageGallery = null },
        )
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
                onDismiss = { textPreview = null },
            )
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
        AlertDialog(
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
}

/** 外部联动处理结果：error 为 null 表示已成功提交 */
private data class ReturnAsk(val error: String?)

/** 从 Compose 的 context 一路解到宿主 Activity（moveTaskToBack 需要） */
private fun Context.findHostActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
