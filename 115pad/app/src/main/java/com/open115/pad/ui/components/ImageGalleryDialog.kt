package com.open115.pad.ui.components

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.ImageUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片大图画廊：左右翻页 + 渐进式加载 + 双向预加载 + 超大图分块解码。
 *
 * 加载策略（杜绝白屏/黑屏等待）：
 *   第一层：列表已缓存的 thumb 缩略图，进入即显示
 *   第二层：原图直链解析（uo → downurl → thumb 三级链）完成后淡入替换
 *
 * 预加载：浏览第 N 张时，后台解析并预热 N-1 / N+1 的直链与位图缓存。
 *
 * 缓存：两层都按 pick_code 挂 Coil 的磁盘缓存键（见 [diskKeyOf]）—— 关掉画廊再进来、
 * 甚至冷启动重进都能命中本地字节。跨会话省不掉的是直链解析那一步（签名 20 分钟 TTL，
 * 见 ImageUrlResolver），但省掉的是几 MB 的整张重下。
 *
 * 鸟瞰位置图：放大后右下角浮出半透明小图 + 当前视口框（见 [MiniMap]）。
 * 长图/巨图放大到 5x 时最容易"我在图的哪一块"迷失，这个框就是为它做的。
 */
/**
 * 注意：不要用 Dialog 承载。平板（expanded）布局下 Dialog 窗口会被侧栏宽度内缩，
 * 盖不住左侧导航。这个组件设计成普通全屏 Composable，由调用方在应用根层级渲染。
 */
@Composable
fun ImageGalleryDialog(
    items: List<ImageMediaItem>,
    initialIndex: Int,
    resolver: ImageUrlResolver,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    // 浏览图片时，系统返回键 = 退出浏览（优先级高于文件页的目录返回）
    androidx.activity.compose.BackHandler { onDismiss() }
    val context = LocalContext.current
    val startIndex = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var chromeVisible by remember { mutableStateOf(true) }
    var dismissProgress by remember { mutableStateOf(0f) }

    // 进浏览即隐藏状态栏 + 导航栏，退出时恢复。
    // 用 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 而不是"永久隐藏"：
    // 用户仍可从边缘划出系统栏看时间/电量，划出后会自动收起，不会把画面顶掉。
    // ⚠️ onDispose 必须无条件 show —— 若在这里判断条件，退出时会漏掉恢复，
    //    整个 App 的状态栏就再也不回来了（和 VR 那个 DisposableEffect 的坑同源）。
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            (context as? Activity)?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.75f)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { items[it].fileId ?: "img_$it" },
        ) { page ->
            GalleryPage(
                item = items[page],
                resolver = resolver,
                active = page == pagerState.targetPage,
                onTap = { chromeVisible = !chromeVisible },
                onDismissRequest = onDismiss,
                onDismissProgress = { dismissProgress = it },
            )
        }

        // 顶部栏：关闭 + 文件名 + 页码（单击屏幕显隐）。
        // 不再铺整条半透明黑带——那条带子会压在画面上，观感很脏。
        // 可读性改由文字/图标的描边阴影保证：文字用 TextStyle.shadow（跟字形走），
        // 图标画两层（底层黑、上层白）模拟描边。
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
                IconButton(onClick = onDismiss) {
                    Box {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.55f),
                            modifier = Modifier.offset(0.5.dp, 0.5.dp),
                        )
                        Icon(Icons.Outlined.Close, "关闭", tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        items.getOrNull(pagerState.currentPage)?.fileName ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = CHROME_TEXT_SHADOW,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            shadow = CHROME_TEXT_SHADOW,
                        ),
                    )
                }
            }
        }
    }

    // 双向预加载：翻到第 N 张时解析并预热 N-1 / N+1 的直链与位图缓存
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            val neighbours = listOfNotNull(items.getOrNull(page - 1), items.getOrNull(page + 1))
            // 列表里没带原图直链(uo)的项才需要 downurl 解析；
            // pick_code 支持逗号分隔多个（文档 §25），一次请求解析完相邻两张
            resolver.prefetch(
                neighbours.filter { it.originUrl.isNullOrBlank() }.mapNotNull { it.pickCode },
            )
            for (n in neighbours) {
                // 超大图不在这里预热：它根本不走 Coil（HugeImage 自己拉字节落盘做分块解码），
                // 给它 enqueue 等于把几十 MB 原图白下一遍到 Coil 缓存里，随后还会被重下一份
                if (n.isHugeBySize) continue
                val url = resolver.resolveOrigin(n) ?: continue
                // 键要和正式加载时一致，否则预热下来的字节正式加载时命中不到
                context.imageLoader.enqueue(originRequest(context, n, url))
            }
        }
    }
}

/**
 * 磁盘缓存的**稳定键**：与直链签名无关，用 pick_code（没有就退 fid）。
 *
 * 为什么必须换键：Coil 默认拿 URL 当磁盘缓存键，而 115 的直链每次解析都会换签名
 * （`uo` 每次列目录变、downurl 每次请求变）—— 于是关掉画廊再进来、或者杀掉进程重进，
 * 键就变了，Coil 必然 miss，几 MB 的原图整张重新下一遍。换成 pick_code 之后签名怎么变
 * 都命中同一份缓存，与超大图的 huge_img、媒体库的 media_img 是同一套"稳定 key"思路。
 *
 * [kind] 必须区分缩略图与原图：两者是**不同的字节**（缩略图是方形裁剪的小图），
 * 共用一个键会让原图位置渲染出缩略图，而且永远刷不掉（缓存一直命中）。
 */
private fun diskKeyOf(item: ImageMediaItem, kind: String): String? =
    (item.pickCode ?: item.fileId)?.let { "$it|$kind" }

/**
 * 原图请求。**只覆盖 diskCacheKey，不碰 memoryCacheKey**：
 *
 * 内存缓存故意沿用 Coil 默认键（里面含请求尺寸）—— 全屏原图与右下角鸟瞰图是两个尺寸，
 * 共用一个内存键会让先解码的那张小图污染另一处（鸟瞰图会把全屏画面顶成糊的）。
 * 而内存缓存只在进程内有效、且进程内 URL 本来就不变，不覆盖也照样命中；
 * 需要跨会话复用的只有磁盘那一层。
 */
private fun originRequest(context: Context, item: ImageMediaItem, url: String): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url)
    diskKeyOf(item, "origin")?.let { builder.diskCacheKey(it) }
    return builder.build()
}

/**
 * 大图首帧的缩略图请求。
 * `preview` 可能是 thumb、也可能（该文件没有 thumb 时）退回原图，键要跟着走 ——
 * 否则会把整张原图当成缩略图存进 "|thumb" 键下，正式加载原图时又下一份。
 */
private fun previewRequest(context: Context, item: ImageMediaItem): ImageRequest? {
    val url = item.preview ?: return null
    val kind = if (!item.thumbnailUrl.isNullOrBlank()) "thumb" else "origin"
    val builder = ImageRequest.Builder(context).data(url)
    diskKeyOf(item, kind)?.let { builder.diskCacheKey(it) }
    return builder.build()
}

@Composable
private fun GalleryPage(
    item: ImageMediaItem,
    resolver: ImageUrlResolver,
    active: Boolean,
    onTap: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    val context = LocalContext.current
    val zoomState = remember(item) { ZoomState() }
    var originUrl by remember(item) { mutableStateOf<String?>(null) }
    var originReady by remember(item) { mutableStateOf(false) }
    /** 超大图落盘后的本地文件：鸟瞰图直接复用它，不再走一次网络 */
    var cachedFile by remember(item) { mutableStateOf<File?>(null) }

    // 只有成为当前页才解析，避免 Pager 预组合相邻页时抢跑打满频控
    LaunchedEffect(item, active) {
        if (active && originUrl == null) originUrl = resolver.resolveOrigin(item)
    }

    // 两个请求都挂上稳定磁盘键（见 [diskKeyOf]）：关掉画廊再进来 / 冷启动重进，
    // 直链签名变了也命中同一份磁盘缓存。鸟瞰图与原图共用同一个 model，
    // 顺带省掉鸟瞰图那次独立的网络请求。
    val previewModel = remember(item) { previewRequest(context, item) }
    val originModel = remember(item, originUrl) {
        originUrl?.takeIf { !item.isHugeBySize }?.let { originRequest(context, item, it) }
    }

    Box(Modifier.fillMaxSize()) {
        ZoomableBox(
            state = zoomState,
            modifier = Modifier.fillMaxSize(),
            onTap = onTap,
            onDismissRequest = onDismissRequest,
            onDismissProgress = onDismissProgress,
        ) {
            // 第一层：缩略图。列表页已经加载过它，大图首帧必然有内容
            AsyncImage(
                model = previewModel,
                contentDescription = item.fileName,
                contentScale = ContentScale.Fit,
                onSuccess = { s ->
                    // 巨图路径的真实尺寸由 BitmapRegionDecoder 给出（缩略图是方形裁剪，
                    // 比例与原图不一致，会污染 fit 计算）；这里只给普通图用
                    if (!item.isHugeBySize) {
                        val i = s.painter.intrinsicSize
                        zoomState.onIntrinsic(i.width.toInt(), i.height.toInt())
                    }
                },
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = if (originReady) 0f else 1f },
            )

            val origin = originUrl
            if (origin != null) {
                // 超大图（>10MB）→ 分块解码，整张原图不进内存
                if (item.isHugeBySize) {
                    HugeImage(
                        url = origin,
                        // 落盘用稳定标识命名，避免签名 URL 一变就重下一份
                        cacheKey = item.pickCode ?: item.fileId ?: origin,
                        resolver = resolver,
                        zoomState = zoomState,
                        onReady = { originReady = true },
                        onCached = { cachedFile = it },
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = if (originReady) 1f else 0f },
                    )
                } else {
                    AsyncImage(
                        model = originModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { s ->
                            val i = s.painter.intrinsicSize
                            zoomState.onIntrinsic(i.width.toInt(), i.height.toInt())
                            originReady = true
                        },
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = if (originReady) 1f else 0f },
                    )
                }
            }
        }

        // 鸟瞰位置图：只有放大后才出现（scale==1 时视口就是整张图，画出来是废话）。
        // 刻意放在 ZoomableBox **之外**——它必须待在未被变换的图层里，
        // 跟着图片一起缩放平移就没法当"位置参照"了。
        AnimatedVisibility(
            visible = zoomState.isZoomed && originUrl != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                // 下拉关闭时整屏都在渐隐，鸟瞰图也得跟着淡出，否则会孤零零悬在暗下去的图上
                .graphicsLayer { alpha = 1f - zoomState.dismissProgress },
        ) {
                    MiniMap(
                        zoomState = zoomState,
                        model = if (item.isHugeBySize) cachedFile else originModel,
                    )
        }

        // 原图解析中（缩略图已经显示，所以只给一个轻量指示）
        if (active && originUrl == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}

/** 鸟瞰图的最大尺寸：长图 / 全景图都按比例收进这个框，不会顶到屏幕上沿或压成一条线 */
private val MINIMAP_MAX_W = 88.dp
private val MINIMAP_MAX_H = 118.dp

/**
 * 顶部信息（文件名 / 页码）的字形阴影：去掉半透明黑带之后，
 * 在亮色图片上的可读性全靠它。跟字形走的阴影比铺一层背景带干净得多。
 */
private val CHROME_TEXT_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(1f, 1f),
    blurRadius = 3f,
)

/**
 * 鸟瞰位置图：整张图的半透明缩略 + 当前视口框。
 *
 * [model] 就是当前这张图本身，且**容器长宽比 == 图片长宽比**，
 * 配合 ContentScale.Fit 等于原样画整张图（不裁切、不留黑边），位置框才能和画面对得上：
 * - 超大图 → 本地缓存文件（resolver 已落盘，零额外请求）
 * - 普通图 → 原图 URL（命中 Coil 缓存；只在放大后才会组合出来，不是每张图都加载）
 *
 * 注意：这里**不能**用列表自带的 thumb——它是方形裁剪的，比例与原图不一致，
 * 拿它当鸟瞰底图会系统性地框错位置（跟 fitRatio 那个坑同源）。
 */
@Composable
private fun MiniMap(zoomState: ZoomState, model: Any?, modifier: Modifier = Modifier) {
    val iw = zoomState.intrinsic.width
    val ih = zoomState.intrinsic.height
    if (iw <= 0f || ih <= 0f) return

    val density = LocalDensity.current
    val maxW = with(density) { MINIMAP_MAX_W.toPx() }
    val maxH = with(density) { MINIMAP_MAX_H.toPx() }
    val aspectFit = minOf(maxW / iw, maxH / ih)
    val w = with(density) { (iw * aspectFit).toDp() }
    val h = with(density) { (ih * aspectFit).toDp() }
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier
            .size(w, h)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape),
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.8f },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val f = visibleFraction(zoomState) ?: return@Canvas
            drawRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = Offset(f.left * size.width, f.top * size.height),
                size = Size(f.width * size.width, f.height * size.height),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * 当前视口在"整张图"上的归一化位置（0..1），给鸟瞰图的位置框用。
 *
 * 与 [decodeVisible] 是同一套坐标换算，改一处务必同步改另一处：
 * 内容按 ContentScale.Fit 居中放进视口（左上角 left/top），
 * 再绕视口中心缩放到 scale、最后平移 offset（graphicsLayer 默认变换原点就是中心）。
 *
 * 视口坐标 v 反算回内容坐标：
 *   content = (v - 视口中心 - offset) / scale + 视口中心 - 内容左上角
 *
 * 返回 null 表示尺寸还没就位（算不出来），调用方直接不画框。
 */
private fun visibleFraction(zs: ZoomState): ComposeRect? {
    val vw = zs.viewport.width
    val vh = zs.viewport.height
    val cw = zs.content.width
    val ch = zs.content.height
    if (vw <= 0f || vh <= 0f || cw <= 0f || ch <= 0f || zs.scale <= 0f) return null

    val cx = vw / 2f
    val cy = vh / 2f
    val left = (vw - cw) / 2f
    val top = (vh - ch) / 2f
    fun fx(v: Float) = ((v - cx - zs.offset.x) / zs.scale + cx - left).coerceIn(0f, cw)
    fun fy(v: Float) = ((v - cy - zs.offset.y) / zs.scale + cy - top).coerceIn(0f, ch)

    val x1 = fx(0f)
    val y1 = fy(0f)
    val x2 = fx(vw)
    val y2 = fy(vh)
    if (x2 - x1 <= 0.5f || y2 - y1 <= 0.5f) return null
    return ComposeRect(x1 / cw, y1 / ch, x2 / cw, y2 / ch)
}

/**
 * 超大图渲染：BitmapRegionDecoder 只解码可视区域，整张原图永远不进内存。
 *
 * 对齐思路（最容易画错位置的地方）：
 * 块图绘制在 **适配坐标系（fit space）**——即"图片按 ContentScale.Fit 铺满后"的坐标系——
 * 再由外层 ZoomableBox 的 graphicsLayer 统一做缩放/平移。只需要一套变换，
 * 块图与底层整图天然对齐，解码误差只表现为轻微模糊而不是错位。
 *
 * fit 坐标 ↔ 原图像素 的换算：
 *   fit.x = 视口中心.x - 内容宽/2 + 原图.x * fitRatio
 *   原图.x = (fit.x - 视口中心.x + 内容宽/2) / fitRatio
 */
@Composable
private fun HugeImage(
    url: String,
    /**
     * 落盘缓存的稳定 key（pick_code / fid）。**不能用 url**：
     * 115 的原图直链带签名、每次列表响应都会变，用 url 命名会让同一张图反复重下。
     */
    cacheKey: String,
    resolver: ImageUrlResolver,
    zoomState: ZoomState,
    onReady: () -> Unit,
    /** 原图落盘后把本地文件回抛给宿主：鸟瞰图要用同一份文件，避免再下一遍原图 */
    onCached: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var decoder by remember(url) { mutableStateOf<BitmapRegionDecoder?>(null) }
    var base by remember(url) { mutableStateOf<Pair<Bitmap, Rect>?>(null) }
    var tile by remember(url) { mutableStateOf<Pair<Bitmap, Rect>?>(null) }
    var viewportPx by remember(url) { mutableStateOf(IntSize.Zero) }

    // ① 拉取原图字节到缓存文件（BitmapRegionDecoder 需要文件路径 / 文件描述符）
    LaunchedEffect(url) {
        val file = resolver.fetchToCache(cacheKey, url, context.cacheDir)
        onCached(file)
        val d = withContext(Dispatchers.IO) {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }
        if (d == null) return@LaunchedEffect
        decoder = d
        // 关键：fitRatio / 内容尺寸必须用 **原图** 的真实宽高。
        // 缩略图是方形裁剪，比例与原图不一致，用它算会把基础层画得过大（表现为严重放大裁切）。
        zoomState.onIntrinsic(d.width, d.height)

        // 基础层：整张图降采样解码，单张位图控制在 ~32MB 内，任何原图都不会 OOM
        val sample = pickSampleSize(d.width, d.height)
        base = withContext(Dispatchers.Default) {
            val full = Rect(0, 0, d.width, d.height)
            d.decodeRegion(full, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.let { it to Rect(0, 0, d.width, d.height) }
        onReady()

        // 高清块：缩放 / 平移稳定后按可视区域重新解码（250ms 节流；放大到 2 倍以上才需要）
        snapshotFlow {
            Triple(zoomState.scale, zoomState.offset.x, zoomState.offset.y)
        }
            .sample(250)
            .collectLatest { (scale, ox, oy) ->
                if (scale < 2f || zoomState.fitRatio <= 0f) {
                    tile = null
                    return@collectLatest
                }
                tile = withContext(Dispatchers.Default) {
                    decodeVisible(d, zoomState, viewportPx, scale, ox, oy)
                }
            }
    }

    DisposableEffect(url) {
        onDispose { decoder?.recycle() }
    }

    // ③ 绘制：基础层铺满内容区，高清块叠加在上面，外层统一变换
    Box(modifier.onSizeChanged { viewportPx = it }) {
        if (zoomState.fitRatio > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                // fit 坐标系里内容区的左上角（图片按 ContentScale.Fit 居中后的位置）
                val ox = (size.width - zoomState.content.width) / 2f
                val oy = (size.height - zoomState.content.height) / 2f
                base?.let { (bmp, src) ->
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize(
                            (src.width() * zoomState.fitRatio).roundToInt().coerceAtLeast(1),
                            (src.height() * zoomState.fitRatio).roundToInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.High,
                    )
                }
                tile?.let { (bmp, src) ->
                    if (bmp.width > 0 && bmp.height > 0 && src.width() > 0 && src.height() > 0) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(
                                (ox + src.left * zoomState.fitRatio).roundToInt(),
                                (oy + src.top * zoomState.fitRatio).roundToInt(),
                            ),
                            dstSize = IntSize(
                                (src.width() * zoomState.fitRatio).roundToInt().coerceAtLeast(1),
                                (src.height() * zoomState.fitRatio).roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.None, // 1:1 像素，不做插值
                        )
                    }
                }
            }
        }
    }
}

/**
 * 选 inSampleSize：解码后的位图内存控制在 ~32MB 以内（单反原图 / 长图保护）。
 * inSampleSize 只能取 2 的幂，所以逐级放大直到达标。
 */
private fun pickSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (sample < 32) {
        val w = (width / sample.toFloat()).roundToInt().coerceAtLeast(1)
        val h = (height / sample.toFloat()).roundToInt().coerceAtLeast(1)
        if (w.toLong() * h * 4L <= 32L * 1024 * 1024) break
        sample *= 2
    }
    return sample
}

/** 计算当前可视区域对应的原图矩形并只解码这一块 */
private fun decodeVisible(
    decoder: BitmapRegionDecoder,
    zoomState: ZoomState,
    viewport: IntSize,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Bitmap, Rect>? {
    if (viewport.width <= 0 || viewport.height <= 0 || zoomState.fitRatio <= 0f) return null
    val iw = decoder.width
    val ih = decoder.height
    val cx = viewport.width / 2f
    val cy = viewport.height / 2f
    val halfW = zoomState.content.width / 2f
    val halfH = zoomState.content.height / 2f
    val fit = zoomState.fitRatio

    // 视口某点 v -> fit 坐标：f = (v - 视口中心 - offset) / scale + 视口中心
    fun toFit(vx: Float, vy: Float): Pair<Float, Float> = Pair(
        (vx - cx - offsetX) / scale + cx,
        (vy - cy - offsetY) / scale + cy,
    )

    val (fx1, fy1) = toFit(0f, 0f)
    val (fx2, fy2) = toFit(viewport.width.toFloat(), viewport.height.toFloat())
    val src = Rect(
        ((fx1 - cx + halfW) / fit).roundToInt().coerceIn(0, iw),
        ((fy1 - cy + halfH) / fit).roundToInt().coerceIn(0, ih),
        ((fx2 - cx + halfW) / fit).roundToInt().coerceIn(0, iw),
        ((fy2 - cy + halfH) / fit).roundToInt().coerceIn(0, ih),
    )
    if (src.isEmpty) return null

    // 解码尺寸对齐视口：既保证放大后清晰，也不会解码出巨大的 Bitmap
    val sample = max(1, (src.width().toFloat() / viewport.width).roundToInt())
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = decoder.decodeRegion(src, opts) ?: return null
    return bmp to src
}
