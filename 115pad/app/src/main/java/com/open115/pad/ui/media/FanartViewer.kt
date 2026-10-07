package com.open115.pad.ui.media

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.open115.pad.appContainer
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.ui.components.ZoomState
import com.open115.pad.ui.components.ZoomableBox
import com.open115.pad.ui.theme.WallpaperCropFlow
import com.open115.pad.ui.theme.wallpaperBasePath
import com.open115.pad.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 剧照（`extrafanart/`）全屏浏览：左右翻页 + 双指缩放 + 下拉关闭 + 底部胶片条。
 *
 * 与文件页那套图片画廊（[com.open115.pad.ui.components.ImageGalleryDialog]）**共用同一套手势层**
 * （[ZoomableBox] / [ZoomState]），所以两处的缩放、双击、下拉关闭、边缘交棒给翻页的手感完全一致。
 * 差别只在"剧照没有文件名/大小/时间戳"这些列表元数据 —— 这里的图源只有 pick_code。
 *
 * 几个刻意的选择：
 *  - **单击屏幕 = 显隐顶栏与胶片条**（不是退出）。退出走左上角 ✕、下拉、系统返回键三条路，
 *    与画廊一致；"点哪都退"那种做法在放大的时候很容易误触退出去。
 *  - **页面内全屏浮层，不是 Dialog**：Dialog 是独立窗口，铺不进状态栏底下（顶部会漏一条缝，
 *    露出底下的详情页）。浮层跟着宿主 Activity 铺满整个窗口，顶栏与胶片条各自加
 *    statusBars/navigationBars padding 让位。图片画廊那边也是同样的结论（Dialog 盖不住侧栏）。
 *  - 返回键在这里拦（本浮层比详情页的 BackHandler 后组合，优先级更高）：
 *    信息面板开着先收面板，再按才是退出浏览。
 *  - 图片 `Fit` 而不是 `Crop`：全屏看剧照时把画面裁掉一块是不能接受的。
 *
 * 图源：详情页进页面时已经把整份剧照预取到本地（见 MediaDetailScreen 的 LaunchedEffect），
 * 所以这里绝大多数情况是**读本地文件**，翻页零等待；没轮到的那几张由 [ImageUrlResolver.posterFor]
 * 自己走"命中本地 → 解析直链 → 落盘"的链路补上（与海报墙/详情页同一套缓存，键是 pick_code）。
 *
 * 另一种图源是**本机预览帧**（[localFiles]）：库里没有剧照的片拿它播放时抓的画面顶替剧照
 * （见 MediaDetailScreen 的兜底）。那批帧本来就躺在 `vstrip/` 里，所以零请求、也没有 pick_code。
 */
@Composable
fun FanartViewer(
    pickCodes: List<String>,
    /**
     * 本机预览帧（`vstrip/vs_*.jpg`）：只有"**库里没有剧照**的片"才给（见 MediaDetailScreen 的兜底）。
     * 与 [pickCodes] **互斥**：给了 pick_code 就按云端剧照走，否则这批本机帧就是这次的剧照。
     */
    localFiles: List<File> = emptyList(),
    initial: Int,
    resolver: ImageUrlResolver,
    cacheDir: File,
    /** 顶栏第一行的片名，让"这是哪部片的剧照"不用退出去看 */
    title: String,
    onDismiss: () -> Unit,
) {
    if (pickCodes.isEmpty() && localFiles.isEmpty()) return
    /** 这次看的是本机预览帧：没有 pick_code 可解析，也不该去请求云端 */
    val local = pickCodes.isEmpty()
    val total = if (local) localFiles.size else pickCodes.size
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, total - 1)) {
        total
    }
    /** 顶栏 + 胶片条的显隐（单击屏幕切换） */
    var chromeVisible by remember { mutableStateOf(true) }
    /** 下拉关闭进度：整块画面跟着渐隐（见 [ZoomableBox.onDismissProgress]） */
    var dismissProgress by remember { mutableStateOf(0f) }
    var showDetails by remember { mutableStateOf(false) }
    /** 非空 = 壁纸裁切浮层开着（复用设置页那套横竖各裁一次） */
    var wallpaperCropUri by remember { mutableStateOf<String?>(null) }

    // 返回键在这里拦：信息面板开着先收面板，再按才是退出浏览
    BackHandler {
        if (showDetails) showDetails = false else onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.75f)),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            FanartPage(
                pickCode = pickCodes.getOrNull(page),
                localFile = localFiles.getOrNull(page),
                resolver = resolver,
                cacheDir = cacheDir,
                // 只有当前页去解析直链/下载：Pager 会预组合相邻页，让它们也抢一次请求的话
                // 一次滑动就是三张图同时打 downurl（115 最容易频控的接口）
                active = page == pager.targetPage,
                onTap = { chromeVisible = !chromeVisible },
                onDismissRequest = onDismiss,
                onDismissProgress = { dismissProgress = it },
            )
        }

        // 顶栏：关闭 + 片名/页码 + 信息。不铺半透明黑带（那条带子压在画面上很脏），
        // 可读性由文字阴影 + 图标双层描边保证。
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIconButton(Icons.Outlined.Close, "退出剧照", onDismiss)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = CHROME_TEXT_SHADOW),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "剧照 ${pager.currentPage + 1} / $total",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall.copy(shadow = CHROME_TEXT_SHADOW),
                    )
                }
                ChromeIconButton(Icons.Outlined.Info, "剧照信息") { showDetails = !showDetails }
            }
        }

        // 胶片条：一眼看全有多少张、现在是第几张，点一下直接跳过去。
        // 详情面板开着时让位（两者都贴底，叠一起会互相盖）
        AnimatedVisibility(
            visible = chromeVisible && !showDetails,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FanartStrip(
                pickCodes = pickCodes,
                localFiles = localFiles,
                current = pager.currentPage,
                onSelect = { i -> scope.launch { pager.scrollToPage(i) } },
            )
        }

        AnimatedVisibility(
            visible = showDetails,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val pc = pickCodes.getOrNull(pager.currentPage)
            val lf = localFiles.getOrNull(pager.currentPage)
            if (pc != null || lf != null) {
                FanartDetailPanel(
                    pickCode = pc,
                    localFile = lf,
                    index = pager.currentPage,
                    total = total,
                    resolver = resolver,
                    cacheDir = cacheDir,
                    onSetWallpaper = { uri ->
                        showDetails = false
                        wallpaperCropUri = uri
                    },
                    onClose = { showDetails = false },
                )
            }
        }
    }

    wallpaperCropUri?.let { uri ->
        WallpaperCropFlow(
            uri = uri,
            onFinished = {
                wallpaperCropUri = null
                scope.launch {
                    context.appContainer.appPrefs.setWallpaper(wallpaperBasePath(context))
                    Toast.makeText(
                        context,
                        "已设为应用壁纸（遮罩与模糊在设置 → 界面显示里调）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onCancelled = { wallpaperCropUri = null },
        )
    }
}

/**
 * 单张剧照：加载 model 后交给 [ZoomableBox] 承载缩放/平移/下拉关闭。
 *
 * 尺寸（intrinsic）必须等解码成功才拿得到 —— [ZoomState] 用它算"适配屏幕"的比例，
 * 也就决定了双击"看原图"的目标倍数与放大后的可平移范围。拿不到就退化成只能平移不能缩放（不崩）。
 *
 * 图源二选一：云端剧照给 [pickCode]（沿用"命中本地 → 解析直链 → 落盘"那条链路）；
 * 本机预览帧给 [localFile]（库里没有剧照的片，见 [FanartViewer]）。本机那份**同步**就位，
 * 不需要转圈，也不该因为 pick_code 为空就永远显示加载中。
 */
@Composable
private fun FanartPage(
    pickCode: String?,
    localFile: File?,
    resolver: ImageUrlResolver,
    cacheDir: File,
    active: Boolean,
    onTap: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    val context = LocalContext.current
    val zoomState = remember(pickCode, localFile) { ZoomState() }
    var model by remember(pickCode, localFile) {
        mutableStateOf<Any?>(localFile?.let { localFrameRequest(context, it) })
    }
    var painted by remember(pickCode, localFile) { mutableStateOf(false) }

    // 只有成为当前页才解析：见调用点 active 的注释
    LaunchedEffect(pickCode, localFile, active) {
        if (active && localFile == null && pickCode != null && model == null) {
            model = resolver.posterFor(pickCode, cacheDir)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ZoomableBox(
            state = zoomState,
            modifier = Modifier.fillMaxSize(),
            onTap = onTap,
            onDismissRequest = onDismissRequest,
            onDismissProgress = onDismissProgress,
        ) {
            model?.let { m ->
                AsyncImage(
                    model = m,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // 解码完成前保持透明：突然"跳"出一张图比淡入更扎眼
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (painted) 1f else 0f },
                    onSuccess = { s ->
                        val i = s.painter.intrinsicSize
                        zoomState.onIntrinsic(i.width.toInt(), i.height.toInt())
                        painted = true
                    },
                )
            }
        }

        // 还在解析/下载中（图源没到位才转，本地命中是瞬时的，转圈一闪而过也不扰人）
        if (active && model == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(30.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}

/** 胶片条单张的宽度（16:9）。与高度一起恒定，选中态只改描边与透明度，滑动时行不会抖 */
private val STRIP_ITEM_W = 62.dp

/**
 * 本机预览帧的 Coil 请求（详情页的兜底剧照与这个查看器共用）。
 *
 * ★ `memoryCacheKey` 必须掺文件 mtime：预览帧按 pick_code 哈希命名，**重新生成时路径不变**，
 * 只按路径缓存会一直显示旧画面（文件页缩略图踩过同一个坑，见 Items.kt 的 localPreviewModel）。
 */
internal fun localFrameRequest(context: android.content.Context, file: File): ImageRequest =
    ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey("vstrip|${file.name}|${file.lastModified()}")
        .build()

/**
 * 底部胶片条：横排缩略图，当前张高亮，点一下跳过去。
 *
 * 选中张自动滚进视野靠 [rememberLazyListState] + `animateScrollToItem(index - 2)`：
 * 减去 2 是让当前张大致落在**中间**而不是贴着左边缘（翻到第 18 张时贴边会看不到后面还有什么）。
 */
@Composable
private fun FanartStrip(
    pickCodes: List<String>,
    localFiles: List<File>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    // 图源二选一：有 pick_code 就按云端（缩略图由 PickCodeImage 走同一份缓存），
    // 否则是本机预览帧当剧照——那本来就在本机，直接当文件加载
    val thumbs: List<Any> = if (pickCodes.isNotEmpty()) pickCodes else localFiles
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    Box(
        Modifier
            .fillMaxWidth()
            // 很轻的一道自下而上的压暗：缩略图本身是不透明的，但底下的画面若很亮，
            // 相邻两张之间的缝与未选中的 0.6 透明度会看不清边界
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))),
            )
            .padding(top = 24.dp)
            .navigationBarsPadding(),
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(thumbs) { i, src ->
                val selected = i == current
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .width(STRIP_ITEM_W)
                        .aspectRatio(16f / 9f)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.28f)
                            },
                            shape = shape,
                        )
                        .graphicsLayer { alpha = if (selected) 1f else 0.6f }
                        .clickable { onSelect(i) },
                ) {
                    when (src) {
                        is String -> PickCodeImage(src, Modifier.fillMaxSize())
                        is File -> AsyncImage(
                            model = localFrameRequest(context, src),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 剧照信息面板：剧照没有文件名/时间戳这些列表元数据（扫描期只记了 pick_code），
 * 所以这里给的是**解码后才知道的两项**（像素尺寸、本地缓存字节）加上这张图的 pick_code，
 * 外加「设为应用壁纸」—— 与图片画廊的详情面板同一种用法。
 *
 * 本机预览帧（[localFile] 非空，库里没有剧照的片拿它顶剧照）没有 pick_code，来源那行改成说明它是什么。
 */
@Composable
private fun FanartDetailPanel(
    pickCode: String?,
    localFile: File?,
    index: Int,
    total: Int,
    resolver: ImageUrlResolver,
    cacheDir: File,
    onSetWallpaper: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var model by remember(pickCode, localFile) {
        mutableStateOf<Any?>(localFile?.let { localFrameRequest(context, it) })
    }
    var dims by remember(pickCode, localFile) { mutableStateOf<Pair<Int, Int>?>(null) }
    var bytes by remember(pickCode, localFile) { mutableStateOf<Long?>(localFile?.length()) }

    LaunchedEffect(pickCode, localFile) {
        if (localFile == null && pickCode != null) model = resolver.posterFor(pickCode, cacheDir)
    }
    // 尺寸与字节数都从**手上的这一份**读：命中的本地文件直接 stat + 解码，
    // 没命中（缓存关掉/落盘失败）就解直链，反正刚才全屏看的就是它，Coil 缓存直接命中
    LaunchedEffect(model, localFile) {
        val m = model ?: return@LaunchedEffect
        val (s, b) = withContext(Dispatchers.IO) {
            val size = if (localFile != null) {
                // 本机预览帧读**文件头**就够了：长边只有 320，走 Coil 的 size(2048) 会把尺寸放大成假的
                runCatching {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(localFile.absolutePath, o)
                    if (o.outWidth > 0) o.outWidth to o.outHeight else null
                }.getOrNull()
            } else {
                runCatching {
                    val r = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(m).allowHardware(false).size(2048).build(),
                    )
                    (r.drawable as? BitmapDrawable)?.bitmap?.let { it.width to it.height }
                }.getOrNull()
            }
            val len = (m as? String)?.let { p ->
                runCatching { File(p).takeIf { it.isFile }?.length() }.getOrNull()
            } ?: localFile?.takeIf { it.isFile }?.length()
            size to len
        }
        dims = s
        bytes = b
    }
    /** 设为壁纸要用一个"能打开的来源"：云端那份解析下来是本地路径字符串，本机帧直接就是文件 */
    val wallpaperPath = localFile?.absolutePath ?: (model as? String)

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "剧照 ${index + 1} / $total",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(10.dp))
            InfoRow(
                "像素尺寸",
                dims?.let { "${it.first} × ${it.second}" } ?: if (model == null) "读取中…" else "未知",
            )
            InfoRow("本地缓存", bytes?.let { Format.size(it) } ?: "—")
            if (pickCode != null) {
                InfoRow("提取码", pickCode)
            } else {
                // 本机预览帧：说清它不是什么神秘图 —— 这部片库里没剧照，这是播放时抓的画面顶上的
                InfoRow("来源", "本机预览帧（库里没有剧照）")
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) {
                    Text("关闭", color = Color.White.copy(alpha = 0.85f))
                }
                Button(
                    onClick = { wallpaperPath?.let(onSetWallpaper) },
                    enabled = wallpaperPath != null,
                ) {
                    Text("设为应用壁纸")
                }
            }
        }
    }
}

/** 信息面板的一行：左标签 + 右值 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 顶栏图标：画两层（底层深色偏移半像素、上层纯白）模拟描边。
 * 去掉半透明黑带之后，白色图标压在亮剧照上就靠它读得出来。
 */
@Composable
private fun ChromeIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.offset(0.5.dp, 0.5.dp),
            )
            Icon(icon, contentDescription = label, tint = Color.White)
        }
    }
}

/** 顶栏文字的字形阴影（与图片画廊同一套做法，见 ImageGalleryDialog.CHROME_TEXT_SHADOW） */
private val CHROME_TEXT_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(1f, 1f),
    blurRadius = 3f,
)
