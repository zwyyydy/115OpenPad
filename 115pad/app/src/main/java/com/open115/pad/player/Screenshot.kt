package com.open115.pad.player

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 截图落地的相册子目录（Pictures/115OpenPad/） */
private const val ALBUM_DIR = "115OpenPad"

private const val STAMP_PATTERN = "yyyyMMdd_HHmmss"

/**
 * 截图要用的渲染视图引用。
 *
 * 刻意**不**把 TextureView / SubtitleView 提到 PlayerScreen 里持有：横竖屏切换时
 * VideoSurface 会在另一个调用点重组，同一个 View 实例被两个 AndroidView 先后
 * 拿去 addView 会撞 "already has a parent"。所以只透出引用，实例仍然由
 * VideoSurface 自己创建和销毁，这里拿到的可能是 null（未就绪/已销毁）。
 */
internal class FrameRefs {
    var texture: TextureView? = null
    var subtitle: View? = null
}

/**
 * 抓当前视频帧（普通渲染路径）。
 *
 * 直接读 TextureView 的位图，但**旋转要自己补**：画面旋转是外层 graphicsLayer
 * 做的，TextureView 的布局尺寸始终按未旋转的帧算，读出来的位图是"躺着的"。
 *
 * 字幕层在屏幕上与画面同组（同一个 Box 的 graphicsLayer），所以单独画一张同尺寸的
 * 图、用同样的角度转过去再叠上——先转后叠与屏幕上的观感一致（字幕跟着画面转）。
 */
internal fun captureTextureFrame(
    textureView: TextureView,
    rotationDeg: Int,
    subtitleView: View?,
    subtitleBottomPercent: Int,
): Bitmap? {
    val frame = runCatching { textureView.bitmap }.getOrNull() ?: return null
    val out = rotateBitmap(frame, rotationDeg)
    runCatching {
        val sv = subtitleView ?: return@runCatching
        if (sv.width <= 0 || sv.height <= 0) return@runCatching
        val sub = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sub)
        // 字幕整层的垂直偏移（设置页「外挂字幕位置」），与屏幕上的 graphicsLayer 同义
        canvas.translate(0f, -frame.height * subtitleBottomPercent / 100f)
        sv.draw(canvas)
        Canvas(out).drawBitmap(rotateBitmap(sub, rotationDeg), 0f, 0f, null)
    }
    return out
}

/**
 * 抓当前画面（VR 路径）。
 *
 * VR 视窗是 GLSurfaceView（SurfaceView 子类），没有 View 位图可取，只能走 PixelCopy
 * 从它的 Surface 上拷一帧；反投影与旋转都已经在着色器里做完，拷出来就是所见即所得。
 * PixelCopy 是异步的，所以用回调而不是返回值。
 */
internal fun captureSurfaceFrame(surfaceView: SurfaceView, onResult: (Bitmap?) -> Unit) {
    val w = surfaceView.width
    val h = surfaceView.height
    if (w <= 0 || h <= 0) {
        onResult(null)
        return
    }
    val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
    if (bitmap == null) {
        onResult(null)
        return
    }
    runCatching {
        PixelCopy.request(
            surfaceView,
            bitmap,
            { result -> onResult(if (result == PixelCopy.SUCCESS) bitmap else null) },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { onResult(null) }
}

/** 把字幕层叠到已经抓好的位图上（VR 路径：字幕不参与旋转，整层位移即可） */
internal fun overlaySubtitle(
    bitmap: Bitmap,
    subtitleView: View?,
    subtitleBottomPercent: Int,
): Bitmap {
    runCatching {
        val sv = subtitleView ?: return@runCatching
        if (sv.width <= 0 || sv.height <= 0) return@runCatching
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.translate(0f, -bitmap.height * subtitleBottomPercent / 100f)
        sv.draw(canvas)
        canvas.restore()
    }
    return bitmap
}

/**
 * 需要先申请写权限才能存进公共相册吗。
 * 只有 API 28 及以下（MediaStore 的 RELATIVE_PATH 从 29 才有）、且还没授权时为真。
 */
internal fun needsLegacyWritePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED

/**
 * 存进系统相册，返回展示用的位置描述；失败返回 null。
 *
 * 29+ 走 MediaStore（IS_PENDING 两段式：先占位、写完再转正，避免相册扫到半张图）；
 * 26~28 直接写公共 Pictures 目录再让 MediaScanner 登记，没有写权限就退到应用自己的
 * 图片目录——相册里看不到，但不至于让截图功能整个不可用。
 */
internal fun saveScreenshot(context: Context, bitmap: Bitmap): String? {
    val name = "115OpenPad_" + SimpleDateFormat(STAMP_PATTERN, Locale.CHINA).format(Date()) + ".png"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, bitmap, name)
        else saveViaFile(context, bitmap, name)
    } catch (_: Exception) {
        null
    }
}

private fun saveViaMediaStore(context: Context, bitmap: Bitmap, name: String): String? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/" + ALBUM_DIR,
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    val written = resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    } ?: false
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return "相册 / $ALBUM_DIR / $name"
}

private fun saveViaFile(context: Context, bitmap: Bitmap, name: String): String? {
    val base = if (needsLegacyWritePermission(context)) {
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    } ?: return null
    val dir = File(base, ALBUM_DIR)
    if (!dir.exists() && !dir.mkdirs()) return null
    val file = File(dir, name)
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    // 公共目录下不登记的话相册里看不到（Android 10 之前没有 MediaStore 自动索引）
    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    return file.absolutePath
}

/** 按顺时针角度把位图摆正；0 度直接返回原图（调用方可以据此判断要不要回收） */
private fun rotateBitmap(src: Bitmap, deg: Int): Bitmap {
    val d = ((deg % 360) + 360) % 360
    if (d == 0) return src
    return runCatching {
        Bitmap.createBitmap(
            src,
            0,
            0,
            src.width,
            src.height,
            Matrix().apply { postRotate(d.toFloat()) },
            true,
        )
    }.getOrDefault(src)
}
