package com.open115.pad.ui.media

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.PosterCropRect
import com.open115.pad.data.defaultPosterCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 海报的固定宽高比（与海报墙/详情页的海报框一致） */
private const val POSTER_ASPECT = 2f / 3f

/**
 * 「调整兜底封面」浮层：没有海报的片，用背景图裁一张当海报。
 *
 * 默认范围是**背景图右半张按 2:3 居中**（与旧版直接在 2:3 框里显示右半张的效果一致）；
 * 用户可以拖动/双指缩放重新选一块，保存后写进 [ImageUrlResolver] 的裁剪规格并重建文件。
 *
 * 页面内全屏浮层（不是 Dialog）：详情页本身已经是浮层，Dialog 会另开窗口、还会和
 * 沉浸式状态栏打架 —— 剧照/画廊那几处踩过同样的坑，这里沿用同样的做法。
 */
@Composable
fun PosterCropOverlay(
    backgroundPickCode: String,
    resolver: ImageUrlResolver,
    cacheDir: File,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { CropState() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedRect by remember { mutableStateOf<PosterCropRect?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    /** 点「恢复默认」后重建一次初始变换：初始值变成默认右半张，而不是上次保存的那块 */
    var resetTick by remember { mutableIntStateOf(0) }
    var resetToDefault by remember { mutableStateOf(false) }

    LaunchedEffect(backgroundPickCode) {
        val model = resolver.posterFor(backgroundPickCode, cacheDir)
        savedRect = resolver.posterCrop(backgroundPickCode)
        if (model == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        // 与壁纸裁切同一条解码链：Coil 处理 EXIF 方向，长边 2048 足够裁海报
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(model)
                        .allowHardware(false)
                        .size(2048)
                        .build(),
                )
                (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
            }.getOrNull()
        }
        if (bitmap == null) loadFailed = true
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // 空 pointerInput 挡住点击：浮层不是模态窗口，不加的话下面的返回/播放按钮会被点到
    Surface(
        Modifier.fillMaxSize().pointerInput(Unit) {},
        color = Color(0xFF0E0F12),
    ) {
        when {
            loadFailed -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("背景图加载失败，暂时没法调整封面", color = Color.White)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text("返回", color = Color.White) }
            }

            bitmap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            else -> {
                val bmp = bitmap!!
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("调整封面", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "这部片没有海报，用背景图裁一张 · 拖动/双指缩放选取范围",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PosterCropFrame(
                            bitmap = bmp,
                            state = state,
                            initialRect = if (resetToDefault) null else savedRect,
                            resetTick = resetTick,
                            onFrameSize = { frameSize = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (saveFailed) {
                        Text(
                            "保存失败，请重试",
                            color = Color(0xFFF87171),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            resetToDefault = true
                            resetTick++
                        }) { Text("恢复默认", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = frameSize != IntSize.Zero && !saving,
                            onClick = {
                                scope.launch {
                                    val rect = cropRectFromState(bmp, frameSize, state)
                                    val default = defaultPosterCrop(bmp.width, bmp.height)
                                    val initial = savedRect
                                    // 没动过：直接关，不白重建一次文件
                                    if (initial != null && rect.approxEquals(initial)) {
                                        onSaved()
                                        return@launch
                                    }
                                    if (initial == null && rect.approxEquals(default)) {
                                        onSaved()
                                        return@launch
                                    }
                                    saving = true
                                    saveFailed = false
                                    // 回到默认范围时顺手删掉规格（以后重裁也是默认，不依赖这条记录）
                                    val ok = resolver.savePosterCrop(
                                        backgroundPickCode = backgroundPickCode,
                                        rect = rect.takeUnless { it.approxEquals(default) },
                                        cacheDir = cacheDir,
                                    )
                                    saving = false
                                    if (ok) onSaved() else saveFailed = true
                                }
                            },
                        ) {
                            Text(if (saving) "保存中…" else "保存")
                        }
                    }
                }
            }
        }
    }
}

/** 单步裁切的变换状态：缩放 + 平移（graphicsLayer 围绕中心应用） */
private class CropState {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * 裁切画框：2:3，图片 Fit 后按 cover 缩放铺满，拖动/双指缩放；框内可见区域即保存结果。
 *
 * [initialRect] 是归一化的源图范围（null = 默认右半张）；[resetTick] 变化时按初始值重设。
 */
@Composable
private fun PosterCropFrame(
    bitmap: ImageBitmap,
    state: CropState,
    initialRect: PosterCropRect?,
    resetTick: Int,
    onFrameSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val frameW = min(maxW, maxH * POSTER_ASPECT)
        val frameH = frameW / POSTER_ASPECT
        val frameDpSize = with(density) {
            androidx.compose.ui.unit.DpSize(frameW.toDp(), frameH.toDp())
        }

        // Fit 基准下的绘制尺寸与"恰好铺满框"的最小缩放
        val fit = min(frameW / imgW, frameH / imgH)
        val drawnW0 = imgW * fit
        val drawnH0 = imgH * fit
        val cover = max(frameW / drawnW0, frameH / drawnH0)
        fun clampOffset(o: Offset): Offset {
            val maxX = (drawnW0 * state.scale - frameW) / 2f
            val maxY = (drawnH0 * state.scale - frameH) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        LaunchedEffect(bitmap, frameW, frameH, initialRect, resetTick) {
            val rect = initialRect ?: defaultPosterCrop(bitmap.width, bitmap.height)
            val scale = (frameW / (drawnW0 * rect.width)).coerceIn(cover, cover * 6f)
            val dw = drawnW0 * scale
            val dh = drawnH0 * scale
            state.scale = scale
            state.offset = Offset(
                dw * (0.5f - rect.centerX),
                dh * (0.5f - rect.centerY),
            ).let(::clampOffset)
        }
        LaunchedEffect(frameW, frameH) {
            onFrameSize(IntSize(frameW.roundToInt(), frameH.roundToInt()))
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .size(frameDpSize)
                .clipToBounds()
                .background(Color.Black)
                .pointerInput(bitmap, frameW, frameH) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state.scale = (state.scale * zoom).coerceIn(cover, cover * 6f)
                        state.offset = clampOffset(state.offset + pan)
                    }
                },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = state.scale
                        scaleY = state.scale
                        translationX = state.offset.x
                        translationY = state.offset.y
                    },
            )
            // 三分线辅助构图
            Box(Modifier.matchParentSize()) {
                val thirdW = with(density) { (frameW / 3f).toDp() }
                val twoThirdW = with(density) { (frameW * 2f / 3f).toDp() }
                val thirdH = with(density) { (frameH / 3f).toDp() }
                val twoThirdH = with(density) { (frameH * 2f / 3f).toDp() }
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = thirdW)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.2f)),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = twoThirdW)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.2f)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = thirdH)
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.2f)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = twoThirdH)
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.2f)),
                )
            }
        }
    }
}

/**
 * 当前框内可见区域 → 归一化源图矩形。
 *
 * 与 [PosterCropFrame] 的 Fit+cover+平移是同一套坐标；框的宽高比固定 2:3，
 * 所以这个矩形也必然是 2:3。
 */
private fun cropRectFromState(bitmap: ImageBitmap, frameSize: IntSize, state: CropState): PosterCropRect {
    val frameW = frameSize.width.toFloat()
    val frameH = frameSize.height.toFloat()
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    val fit = min(frameW / imgW, frameH / imgH)
    val drawnW0 = imgW * fit
    val drawnH0 = imgH * fit
    val dw = drawnW0 * state.scale
    val dh = drawnH0 * state.scale
    val x0 = frameW / 2f + state.offset.x - dw / 2f
    val y0 = frameH / 2f + state.offset.y - dh / 2f
    val left = (-x0 / dw).coerceIn(0f, 1f)
    val top = (-y0 / dh).coerceIn(0f, 1f)
    val right = ((frameW - x0) / dw).coerceIn(0f, 1f)
    val bottom = ((frameH - y0) / dh).coerceIn(0f, 1f)
    return PosterCropRect(left, top, right, bottom).clamped()
}
