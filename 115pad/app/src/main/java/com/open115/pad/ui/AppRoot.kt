package com.open115.pad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open115.pad.AppContainer
import com.open115.pad.data.DownloadRequest
import com.open115.pad.data.DownloadSubmitter
import com.open115.pad.data.FileItem
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.LinkSource
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.Session
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.components.DownloadLinkSheet
import com.open115.pad.ui.components.ImageGalleryDialog
import com.open115.pad.ui.components.TextPreviewHost
import com.open115.pad.util.Downloader
import com.open115.pad.util.Format
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
    Dest("filter", "过滤规则", Icons.Outlined.Tune),
    Dest("offline", "云下载", Icons.Outlined.CloudDownload),
    Dest("transfer", "传输中心", Icons.Outlined.Download),
    Dest("recycle", "回收站", Icons.Outlined.RestoreFromTrash),
    Dest("settings", "设置", Icons.Outlined.Settings),
)

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
                MainScaffold(container, widthClass)
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, widthClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val expanded = widthClass != WindowWidthSizeClass.Compact

    fun navigate(dest: Dest) {
        navController.navigate(dest.route) {
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

    val onPlayVideo: (FileItem, List<PlaylistEntry>, Int) -> Unit = { item, playlist, index ->
        val pc = item.pc
        if (pc.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("该文件缺少提取码，无法播放") }
        } else {
            context.startActivity(PlayerActivity.intent(context, pc, item.fn, playlist, index))
        }
    }

    // ---------------- 大图画廊（应用根层级渲染，才能盖住侧栏） ----------------
    var imageGallery by remember { mutableStateOf<Pair<List<ImageMediaItem>, Int>?>(null) }
    /** 文本预览：与画廊一样在根层级全屏渲染，才能盖住侧栏 */
    var textPreview by remember { mutableStateOf<FileItem?>(null) }

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
            // snackbar 是挂起调用（挂住约 4 秒），单独起协程展示，
            // 不拖累后面的页面跳转与"返回原程序"弹窗
            scope.launch {
                snackbarHostState.showSnackbar(if (auto) "已自动提交云下载任务" else "任务已加入云端离线队列")
            }
            if (gotoOffline) destinations.firstOrNull { it.route == "offline" }?.let { navigate(it) }
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
            if (!expanded) {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
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
            if (expanded) {
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
            NavHost(
                navController = navController,
                startDestination = "files",
                modifier = Modifier.fillMaxSize(),
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
                        )
                    })
                    FilesScreen(
                        vm,
                        expanded,
                        snackbarHostState,
                        onPlayVideo,
                        onOpenGallery = { items, idx -> imageGallery = items to idx },
                        onPreviewText = { item -> textPreview = item },
                        onOpenFilterRules = {
                            destinations.firstOrNull { it.route == "filter" }?.let { navigate(it) }
                        },
                    )
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
                composable("transfer") {
                    com.open115.pad.ui.transfer.TransferScreen(snackbarHostState)
                }
                composable("settings") {
                    SettingsScreen(container)
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
