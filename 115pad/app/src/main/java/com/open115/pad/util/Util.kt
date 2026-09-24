package com.open115.pad.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * 全局 User-Agent。API 请求与"把下载直链交给系统下载器"必须用**同一个精确字符串**：
 * 115 的下载直链与申请它时的 UA 绑定，换任何其它 UA（含 DownloadManager 自带的
 * AndroidDownloadManager/… ）取文件都会被 CDN 判 403。改这里就够，别再各处硬编码。
 */
const val APP_USER_AGENT = "115OpenPad/0.1 (Android)"

/** PKCE 工具：verifier 生成 + challenge 计算 */
object Pkce {
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

fun qrBitmap(content: String, size: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

object Format {
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${bytes} B"
        else String.format(Locale.CHINA, "%.1f %s", v, units[i])
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.CHINA, "%02d:%02d", m, s)
    }

    /**
     * 播放器时间戳格式化（入参毫秒，兼容 media3 的 C.TIME_UNSET）：
     * - 不足 1 小时 → mm:ss
     * - 满 1 小时 → HH:mm:ss
     * - 无效值（<=0 或 TIME_UNSET）→ 回退 placeholder
     */
    fun playTime(ms: Long, placeholder: String = "--:--"): String {
        if (ms <= 0L) return placeholder
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.CHINA, "%02d:%02d", m, s)
    }

    fun dateTime(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return fmt.format(java.util.Date(epochSeconds * 1000))
    }

    /** 任务进度可能是 0-100 或 0-10000，做归一化 */
    fun percent(v: Double): Float = (if (v > 100) v / 100.0 else v).toFloat().coerceIn(0f, 100f)

    /**
     * 相对时间：`刚刚 / 5 分钟前 / 3 小时前 / 2 天前 / 2026-09-25`。
     *
     * 「上次扫描是什么时候」「这条片多久之前看的」这类问题的答案都是"多久以前"，
     * 直接给一个完整时间戳反而要心算。超过一周落回日期 —— 再往上"23 天前"也不比日期好读。
     *
     * [ms] <= 0（没记过）返回「从未」，调用方直接拼进文案即可。
     */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "从未"
        val d = now - ms
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000} 小时前"
            d < 7 * 86_400_000L -> "${d / 86_400_000} 天前"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(java.util.Date(ms))
        }
    }
}
