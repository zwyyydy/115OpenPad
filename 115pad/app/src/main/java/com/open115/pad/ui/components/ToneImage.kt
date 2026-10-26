package com.open115.pad.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.open115.pad.ui.theme.cachedTone
import com.open115.pad.ui.theme.computeTone
import com.open115.pad.ui.theme.rememberToneColor
import kotlinx.coroutines.launch

/**
 * 带主色占位的 Coil 图片：加载前先铺一层这张图自己的主色（取过色的话），
 * 加载时 180ms 交叉淡入 —— 从灰底/白底直接"啪"一下换成海报是最像半成品的一帧。
 *
 * 首次出现还没有主色缓存，用主题的 surfaceVariant 兜底；加载成功后回填缓存，
 * 同一张图在墙/详情/文件缩略图之间再出现时首帧就是主色。
 *
 * [model] 可以是 String/File/URL，也可以是调用方已经配好的 [ImageRequest]
 * （文件预览帧要带 memoryCacheKey，不能在这里重新包一层把 key 丢掉）。
 *
 * [showPlaceholder] = false 时不画占位/错误色块：调用方自己垫了类型角标这类兜底时用它，
 * 否则加载中与加载失败都会用一块不透明色把兜底图标盖掉（文件夹 fco 为空串踩过这个坑）。
 */
@Composable
fun ToneAwareAsyncImage(
    model: Any,
    modifier: Modifier,
    contentScale: ContentScale,
    showPlaceholder: Boolean = true,
) {
    val ctx = LocalContext.current
    val key = if (model is ImageRequest) model.data.toString() else model.toString()
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    val tone = remember(key) { cachedTone(key) }
    val scope = rememberCoroutineScope()
    val request = remember(key) {
        // ★ 不要开 crossfade：Coil 的交叉淡入 + ColorPainter 占位在"命中内存缓存"的第二次
        //   加载里会把占位色一直留在画面上（实测海报墙变成一格格纯色块）。
        //   占位本身就是主色，首帧已经不会白闪，淡入收益不大。
        if (model is ImageRequest) model.newBuilder().build()
        else ImageRequest.Builder(ctx).data(model).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        placeholder = if (showPlaceholder) ColorPainter(tone ?: fallback) else null,
        error = if (showPlaceholder) ColorPainter(tone ?: fallback) else null,
        onSuccess = {
            // 首次出现：图已经在内存缓存里了，顺手算一次主色，下次就能直接用
            if (tone == null) {
                scope.launch {
                    computeTone(ctx, model)?.let { rememberToneColor(key, it) }
                }
            }
        },
    )
}
