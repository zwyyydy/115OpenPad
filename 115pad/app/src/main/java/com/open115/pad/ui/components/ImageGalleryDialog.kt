package com.open115.pad.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.ImageUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
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

        // 顶部栏：关闭 + 文件名 + 页码（单击屏幕显隐）
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "关闭", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            items.getOrNull(pagerState.currentPage)?.fileName ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pagerState.currentPage + 1} / ${items.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
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
                val url = resolver.resolveOrigin(n) ?: continue
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(url).build(),
                )
            }
        }
    }
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
    val zoomState = remember(item) { ZoomState() }
    var originUrl by remember(item) { mutableStateOf<String?>(null) }
    var originReady by remember(item) { mutableStateOf(false) }

    // 只有成为当前页才解析，避免 Pager 预组合相邻页时抢跑打满频控
    LaunchedEffect(item, active) {
        if (active && originUrl == null) originUrl = resolver.resolveOrigin(item)
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
                model = item.preview,
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
                // 超大图：>15MB 或最长边 > 4096px → 分块解码，整张原图不进内存
                if (item.isHugeBySize) {
                    HugeImage(
                        url = origin,
                        resolver = resolver,
                        zoomState = zoomState,
                        onReady = { originReady = true },
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = if (originReady) 1f else 0f },
                    )
                } else {
                    AsyncImage(
                        model = origin,
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
    resolver: ImageUrlResolver,
    zoomState: ZoomState,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var decoder by remember(url) { mutableStateOf<BitmapRegionDecoder?>(null) }
    var base by remember(url) { mutableStateOf<Pair<Bitmap, Rect>?>(null) }
    var tile by remember(url) { mutableStateOf<Pair<Bitmap, Rect>?>(null) }
    var viewportPx by remember(url) { mutableStateOf(IntSize.Zero) }

    // ① 拉取原图字节到缓存文件（BitmapRegionDecoder 需要文件路径 / 文件描述符）
    LaunchedEffect(url) {
        val file = resolver.fetchToCache(url, context.cacheDir)
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
