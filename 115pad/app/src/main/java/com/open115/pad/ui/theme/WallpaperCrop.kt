package com.open115.pad.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 壁纸文件的基准路径：横/竖两份裁切结果是基准路径 + _land / _port 后缀 */
fun wallpaperBasePath(context: Context): String =
    File(File(context.filesDir, "wallpaper").apply { mkdirs() }, "wallpaper").absolutePath

/** 删除壁纸拷贝文件（恢复默认背景时调用） */
fun clearWallpaperFiles(context: Context) {
    File(context.filesDir, "wallpaper").deleteRecursively()
}

/**
 * 壁纸裁切流程：选图后横竖各裁一次——
 * 第 1 步以横屏宽高比、第 2 步以竖屏宽高比各选取一次显示范围，
 * 结果分别存 wallpaper_land.jpg / wallpaper_port.jpg，展示层按当前屏幕方向取用。
 * 全流程走完才回调 [onFinished]（此时才写壁纸偏好），取消则什么都不改。
 */
@Composable
fun WallpaperCropFlow(
    uri: String,
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val short = min(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val long = max(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()

    var step by remember { mutableIntStateOf(0) } // 0=横向 1=竖向
    var source by remember { mutableStateOf<ImageBitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    // 经 Coil 解码：自动处理 EXIF 方向，并按 2048 长边下采样（裁切源够用、内存可控）
    LaunchedEffect(uri) {
        source = withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(uri).allowHardware(false).size(2048).build()
                )
                (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
            }.getOrNull()
        }
        decodeFailed = source == null
    }

    Dialog(
        onDismissRequest = onCancelled,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0F12)) {
            when {
                source == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (decodeFailed) {
                        Text("图片读取失败，请重新选择", color = Color.White)
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                else -> {
                    val bitmap = source!!
                    val state = remember(step) { CropState() }
                    var frameSize by remember { mutableStateOf(IntSize.Zero) }
                    val aspect = if (step == 0) long / short else short / long

                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (step == 0) "横向壁纸 · 拖动/双指缩放选取显示范围"
                                else "竖向壁纸 · 拖动/双指缩放选取显示范围",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "第 ${step + 1}/2 步",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CropFrame(
                                bitmap = bitmap,
                                aspect = aspect,
                                state = state,
                                onFrameSize = { frameSize = it },
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            )
                        }

                        if (saveError) {
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
                            TextButton(onClick = onCancelled) { Text("取消", color = Color.White) }
                            if (step == 1) {
                                TextButton(onClick = { step = 0; saveError = false }) {
                                    Text("上一步", color = Color.White)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = frameSize != IntSize.Zero && !saving,
                                onClick = {
                                    scope.launch {
                                        saving = true
                                        val cropped = cropFromFrame(
                                            bitmap.asAndroidBitmap(), frameSize, state, step,
                                        )
                                        val ok = cropped != null &&
                                            saveCrop(context, cropped, if (step == 0) "_land" else "_port")
                                        saving = false
                                        if (ok) {
                                            saveError = false
                                            if (step == 0) step = 1 else onFinished()
                                        } else {
                                            saveError = true
                                        }
                                    }
                                },
                            ) {
                                Text(if (saving) "保存中…" else if (step == 0) "下一步" else "完成")
                            }
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
    var offset by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
}

/**
 * 裁切画框：框的宽高比 = 目标壁纸比例，图片在框内 Fit + 缩放平移，框外全暗。
 * 所见即所得——确定时框内可见区域就是裁切结果。
 */
@Composable
private fun CropFrame(
    bitmap: ImageBitmap,
    aspect: Float,
    state: CropState,
    onFrameSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val frameW = min(maxW, maxH * aspect)
        val frameH = frameW / aspect
        val frameDpSize = with(density) {
            androidx.compose.ui.unit.DpSize(frameW.toDp(), frameH.toDp())
        }

        // Fit 基准下的绘制尺寸与"恰好铺满框"的最小缩放
        val fit = min(frameW / imgW, frameH / imgH)
        val drawnW0 = imgW * fit
        val drawnH0 = imgH * fit
        val cover = max(frameW / drawnW0, frameH / drawnH0)
        fun clampOffset(o: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset {
            val maxX = (drawnW0 * state.scale - frameW) / 2f
            val maxY = (drawnH0 * state.scale - frameH) / 2f
            return androidx.compose.ui.geometry.Offset(
                o.x.coerceIn(-maxX, maxX),
                o.y.coerceIn(-maxY, maxY),
            )
        }

        LaunchedEffect(bitmap, frameW, frameH) {
            state.scale = cover
            state.offset = androidx.compose.ui.geometry.Offset.Zero
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

/** 按当前变换从源图裁出框内可见区域，长边超过 2560 时等比缩小 */
private fun cropFromFrame(source: Bitmap, frameSize: IntSize, state: CropState, step: Int): Bitmap? = runCatching {
    val frameW = frameSize.width.toFloat()
    val frameH = frameSize.height.toFloat()
    val imgW = source.width.toFloat()
    val imgH = source.height.toFloat()
    val fit = min(frameW / imgW, frameH / imgH)
    val drawnW0 = imgW * fit
    val drawnH0 = imgH * fit
    val dw = drawnW0 * state.scale
    val dh = drawnH0 * state.scale
    val x0 = frameW / 2f + state.offset.x - dw / 2f
    val y0 = frameH / 2f + state.offset.y - dh / 2f
    val srcX = ((0f - x0) * imgW / dw).roundToInt().coerceIn(0, source.width - 1)
    val srcY = ((0f - y0) * imgH / dh).roundToInt().coerceIn(0, source.height - 1)
    val srcW = (frameW * imgW / dw).roundToInt().coerceIn(1, source.width - srcX)
    val srcH = (frameH * imgH / dh).roundToInt().coerceIn(1, source.height - srcY)
    var out = Bitmap.createBitmap(source, srcX, srcY, srcW, srcH)
    val longSide = max(out.width, out.height)
    if (longSide > 2560) {
        val k = 2560f / longSide
        out = Bitmap.createScaledBitmap(
            out,
            (out.width * k).roundToInt().coerceAtLeast(1),
            (out.height * k).roundToInt().coerceAtLeast(1),
            true,
        )
    }
    out
}.getOrNull()

private suspend fun saveCrop(context: Context, bitmap: Bitmap, suffix: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
            File(dir, "wallpaper$suffix.jpg").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
        }.isSuccess
    }
