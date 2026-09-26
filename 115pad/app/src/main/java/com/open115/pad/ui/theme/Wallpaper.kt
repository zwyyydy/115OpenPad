package com.open115.pad.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.transform.Transformation
import java.io.File
import kotlin.math.roundToInt

/**
 * 壁纸层：铺满应用最底层，明亮模式且有壁纸时由 MainActivity 挂载。
 *
 * - [blur] 0..1 的模糊分两条路径：
 *   * API 31+ → `Modifier.blur`（RenderEffect，GPU 实时、改强度零重载零闪烁——
 *     走 Coil 变换的话每档半径都要重新解码+糊化整屏大图，loading 期壁纸消失会闪）；
 *   * API <31 → Coil + RenderScript 变换（半径 5px 一档量化，Coil 按 cacheKey 缓存，
 *     代价是换档瞬间重载一下，老设备上可接受）。
 * - [mask] 0..1 的白色蒙版压住壁纸，越高文字越可读；明亮模式专用。
 */
@Composable
fun WallpaperLayer(uri: String, mask: Float, blur: Float) {
    val context = LocalContext.current
    val renderEffectBlur = Build.VERSION.SDK_INT >= 31
    val blurDp = blur * 24f
    val radius = ((blur * 25f) / 5f).roundToInt() * 5f
    // 壁纸形态三选一：横/竖两份裁切结果按当前屏幕方向取用（裁切流程产物）；
    // 旧版单文件路径兜底；外部 content URI 兜底
    val portrait = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    val model: Any = when {
        !uri.startsWith("/") -> uri
        else -> {
            val land = File("${uri}_land.jpg")
            val port = File("${uri}_port.jpg")
            when {
                land.exists() && port.exists() -> if (portrait) port else land
                File(uri).exists() -> File(uri)
                land.exists() -> land
                port.exists() -> port
                else -> File(uri)
            }
        }
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .transformations(
                if (!renderEffectBlur && radius >= 5f) listOf(WallpaperBlurTransformation(context, radius))
                else emptyList()
            )
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(if (renderEffectBlur && blurDp >= 1f) Modifier.blur(blurDp.dp) else Modifier),
        loading = { Box(Modifier.fillMaxSize().background(AppColors.Bg)) },
        error = { Box(Modifier.fillMaxSize().background(AppColors.Bg)) },
    )
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = mask)))
}

/** RenderScript 高斯模糊（API 17+，31 起标记弃用但仍可用；半径上限 25f 是内核硬限制）。仅 API<31 的模糊路径 */
@Suppress("DEPRECATION")
private class WallpaperBlurTransformation(private val context: Context, private val radius: Float) : Transformation {

    /** 半径参与缓存键：不同模糊强度各自只算一次 */
    override val cacheKey: String = "wallpaper_blur_$radius"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val rs = RenderScript.create(context)
        try {
            val inputAlloc = Allocation.createFromBitmap(rs, input)
            val outputAlloc = Allocation.createFromBitmap(rs, output)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(radius.coerceIn(0.1f, 25f))
            script.setInput(inputAlloc)
            script.forEach(outputAlloc)
            outputAlloc.copyTo(output)
        } finally {
            rs.destroy()
        }
        return output
    }
}
