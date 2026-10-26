package com.open115.tv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.SubtitleTrack

/**
 * 软解优先的解码器排序：把设备自带的**软件**解码器排到硬解前面。
 *
 * 与 115pad 播放器同一套做法：有些片源的硬解会花屏/黑屏/直接起播失败（尤其冷门编码或
 * 电视厂商解码器实现有问题的机型），切软解绕过。CPU 软解可能跟不动高码率 4K，
 * 所以它是个"能出画就行"的开关，不是默认值。
 */
private val SOFTWARE_FIRST_DECODER = MediaCodecSelector { mime, secure, tunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        .sortedBy { it.hardwareAccelerated }
}

// 缓冲参数照 115pad 播放器（PlayerActivity 的 BUFFER_* 常量，是真机调出来的）：
// 后退 30s 让"往回拖十几秒"零等待；前向 90s / 192MB 让快进少撞未完成分片；
// 起播门槛 1.2s 是"seek 后明明有缓存还要转圈"的对策
private const val BACK_BUFFER_MS = 30_000L
private const val BUFFER_MAX_MS = 90_000
private const val BUFFER_FOR_PLAYBACK_MS = 1_200
private const val BUFFER_FOR_REBUFFER_MS = 3_000
private const val BUFFER_BYTES_CAP = 192 * 1024 * 1024

/** 上游连接超时：与平板侧播放器同一档（15s 连接 / 30s 读） */
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

/**
 * 建一个"拿平板给的 UA 直拉"的播放器。
 *
 * 与 115pad 播放器的两个关键区别，都是有意的：
 * 1. **UA 不是常量而是随播放指令带过来的**——115 直链与"申请它时的 UA"绑定，
 *    这个 UA 的归属方在平板那边（常量 `115OpenPad/0.1 (Android)`），电视端保持与它同步的唯一办法
 *    就是"平板给什么就用什么"，以后 115 改 UA 只改平板一端。
 * 2. **不做 HDR→SDR 转换**（115pad 用 SdrOutputRenderersFactory 是因为它 TextureView 自合成后会泛白）：
 *    电视自己就是显示设备，走 SurfaceView 直通、HDR 元数据交给电视做 tone mapping 才是对的。
 *    如果实测某些电视/盒子上 HDR 片源发灰，再把这一层换回 SDR 工厂（改一处）。
 */
internal fun buildTvPlayer(
    context: Context,
    request: PlayRequest,
    /** true = 软解优先（见 SOFTWARE_FIRST_DECODER） */
    softwareDecode: Boolean = false,
): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)
    if (!request.ua.isNullOrBlank()) {
        httpFactory.setUserAgent(request.ua)
    }
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    val loadControl = DefaultLoadControl.Builder()
        .setBackBuffer(BACK_BUFFER_MS.toInt(), true)
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            BUFFER_MAX_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_REBUFFER_MS,
        )
        .setTargetBufferBytes(BUFFER_BYTES_CAP)
        .build()
    return ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                // 首选解码器报错时自动换下一个：默认硬解遇到不兼容片源也能落到软解，不至于直接播不了
                .setEnableDecoderFallback(true)
                .apply { if (softwareDecode) setMediaCodecSelector(SOFTWARE_FIRST_DECODER) },
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .build()
}

/**
 * 播放项：优先用平板给的 mime；没给就从地址（去掉 query）推。
 * [subs] 是平板解析好的外挂字幕直链：作为旁路文本轨挂进 MediaItem，
 * 与片源自带的内封字幕一起出现在轨道表里（控制条「字幕」键循环切换）。
 *
 * 参数是 url/mime/subs 而不是整个 PlayRequest：连播换集时换的只是这几样，
 * 播放器实例与会话状态都沿用。
 */
internal fun buildMediaItem(url: String, mimeHint: String?, subs: List<SubtitleTrack> = emptyList()): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    val mime = mimeHint?.takeIf { it.isNotBlank() } ?: mimeOfUrl(url)
    if (mime != null) builder.setMimeType(mime)
    if (subs.isNotEmpty()) {
        builder.setSubtitleConfigurations(
            subs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                    .setMimeType(s.mime?.takeIf(String::isNotBlank) ?: mimeOfSubtitleUrl(s.url))
                    .setLabel(s.name?.takeIf(String::isNotBlank))
                    // 与 115pad 播放器同一份口径：官方字幕默认挂上，不想要用「字幕」键切到关
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
            },
        )
    }
    return builder.build()
}

/**
 * 外挂字幕的 MIME：旁路字幕媒体3不会自己嗅探，必须声明。
 * 平板通常已按 115 接口给的 type 声明好；这里的扩展名兜底是同一张表的复刻。
 */
internal fun mimeOfSubtitleUrl(url: String): String {
    val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when (ext) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/**
 * 从地址猜 MIME。
 *
 * 115 的转码（HLS）地址**没有 .m3u8 后缀**，那种情况必须靠平板在请求里显式给 MIME——
 * 与 115pad 播放器同样的坑：不声明就会被当普通容器解析、直接起不来。
 */
internal fun mimeOfUrl(url: String): String? {
    val name = url.substringBefore('?').substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv", "asf" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        else -> null
    }
}
