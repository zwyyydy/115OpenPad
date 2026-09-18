package com.open115.pad.player

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/** 掉帧告警阈值，取值与 DefaultRenderersFactory 默认实现一致 */
private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50

/** 色彩链路日志：adb logcat -s PlayerColor */
private const val TAG = "PlayerColor"

/**
 * 视频渲染器工厂：让解码器把 HDR 片源转成 SDR 再输出。
 *
 * 为什么必须这么做：本播放器的画面走 **TextureView**（为了旋转 90/270 与等比适配，
 * 见 VideoSurface 里的注释），视频帧由应用自己合成到窗口里，而应用窗口是 SDR 的、
 * 也没有 HDR 元数据通道。解码器若按片源色彩原样吐出 10bit 的 PQ/BT.2020 帧，GPU 会
 * 把它们当 sRGB 采样 → 画面泛白、对比度极低、像蒙了一层灰雾（HDR10 片源在 SDR 显示上
 * 的典型表现）。
 *
 * 修法：Android 12（API 31）起可用 `MediaFormat.KEY_COLOR_TRANSFER_REQUEST` 告诉解码器
 * "我要 SDR 输出"，由解码器按片源的 HDR 元数据做 tone mapping。Media3 1.4.1 自己不请求
 * （它只把色彩信息写进 MediaFormat，见 `MediaFormatUtil.maybeSetColorInfo`，之后完全交给
 * 编解码器/显示链路）。走系统 SurfaceView 的路径不需要这一步——SurfaceFlinger 会在 SDR 屏
 * 上自动 tone mapping；TextureView 的画面是应用自己合成的，享受不到这个待遇，只能自己请求。
 */
internal class SdrOutputRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // 先按默认规则建好（扩展渲染器的插入位置与顺序都保持原样），随后只把其中的
        // MediaCodecVideoRenderer 换成挂了"请求 SDR 输出"包装的那个，其余一律不动。
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        val idx = out.indexOfFirst { it is MediaCodecVideoRenderer }
        if (idx < 0) return
        out[idx] = MediaCodecVideoRenderer(
            context,
            sdrOutputAdapterFactory(context),
            mediaCodecSelector,
            allowedVideoJoiningTimeMs,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            MAX_DROPPED_FRAMES_TO_NOTIFY,
        )
    }
}

/**
 * 底层仍用 Media3 默认的适配器工厂（同步/异步模式的默认选择保持不变），只是在它之前
 * 拦一层：需要时往 MediaFormat 里写 SDR 输出请求。
 */
private fun sdrOutputAdapterFactory(context: Context): MediaCodecAdapter.Factory {
    val delegate = DefaultMediaCodecAdapterFactory(context)
    return MediaCodecAdapter.Factory { configuration ->
        val requested = requestSdrOutput(configuration.mediaFormat, configuration.format)
        try {
            delegate.createAdapter(configuration)
        } catch (e: Exception) {
            if (!requested) throw e
            // 少数解码器不接受这个请求、直接配置失败：撤掉请求再试一次。
            // 宁可继续按原色彩播放，也不要因为色彩转换把整个播放搞挂。
            runCatching { configuration.mediaFormat.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) }
            Log.w(TAG, "解码器拒绝 SDR 输出请求（${e.message}），撤销请求后重试")
            delegate.createAdapter(configuration)
        }
    }
}

/**
 * 需要解码器把 HDR 转 SDR 时，往 MediaFormat 写 `KEY_COLOR_TRANSFER_REQUEST`。
 *
 * 判定原则：**只有明确判定为 SDR 时才跳过**，其余一律请求。原因是本播放器没有 HDR 呈现
 * 能力（TextureView 自合成到 SDR 窗口），"给我 SDR 输出"永远是正确诉求，而漏判的代价
 * 是整幅画面泛白。三种情况：
 *  - 传输函数是 PQ（ST2084）/HLG → 确定是 HDR，请求；
 *  - 位深 ≥10bit → 请求（HDR 常见形态；对真正的 10bit SDR 无副作用）；
 *  - 色彩信息缺失（115 的转封装流会丢 HDR 标记）→ 也请求：解码器仍能从码流 VUI 里读到
 *    PQ/HLG，有这个请求它才会做 tone mapping，没有就只会原样吐出泛白的画面；
 *  - 只有"标签齐全且明确是 SDR"（BT709/8bit/SDR 类传输）才什么都不做。
 *
 * @return 是否真的写入了请求（用于失败时精确回滚）
 */
private fun requestSdrOutput(mediaFormat: MediaFormat, format: Format?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val info = format?.colorInfo
    if (info != null) {
        val isHdr = info.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
            info.colorTransfer == C.COLOR_TRANSFER_HLG
        val deepColor = info.lumaBitdepth >= 10
        val knownSdrTransfer = info.colorTransfer == C.COLOR_TRANSFER_SDR ||
            info.colorTransfer == C.COLOR_TRANSFER_SRGB ||
            info.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2
        if (!isHdr && !deepColor && knownSdrTransfer) {
            Log.d(TAG, "SDR 片源（${colorDesc(info)}），按原色彩输出")
            return false
        }
    }
    val ok = runCatching {
        mediaFormat.setInteger(
            MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        )
    }.isSuccess
    if (ok) {
        val desc = info?.let { colorDesc(it) } ?: "无色彩信息"
        Log.i(TAG, "请求解码器输出 SDR（$desc）")
    } else {
        Log.w(TAG, "写入 SDR 输出请求失败，按原色彩输出")
    }
    return ok
}

/** 色彩三要素 + 位深，给人看的短描述（排障时一眼能认出片源到底是什么色彩） */
private fun colorDesc(i: ColorInfo): String {
    val space = when (i.colorSpace) {
        C.COLOR_SPACE_BT709 -> "BT709"
        C.COLOR_SPACE_BT2020 -> "BT2020"
        else -> "space=${i.colorSpace}"
    }
    val transfer = when (i.colorTransfer) {
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_SRGB -> "sRGB"
        C.COLOR_TRANSFER_ST2084 -> "PQ"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_LINEAR -> "Linear"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma2.2"
        else -> "transfer=${i.colorTransfer}"
    }
    val range = if (i.colorRange == C.COLOR_RANGE_FULL) "Full" else "Limited"
    return "$space/$transfer/$range/${i.lumaBitdepth}bit"
}
