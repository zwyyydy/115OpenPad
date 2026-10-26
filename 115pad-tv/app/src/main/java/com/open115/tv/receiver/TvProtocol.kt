package com.open115.tv.receiver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 115 openpad 平板 ⇄ 电视端 的局域网协议（v1）。
 *
 * 与 115pad 仓库里的 `cast/PadTvClient.kt`、`cast/PadTvDiscovery.kt` 成对：
 * 常量与 JSON 字段名改这里就必须同步改那一侧。
 *
 * 设计口径：
 * - 纯局域网、无鉴权（与现有 DLNA 投屏同口径，家里的网络内部工具）；
 * - **UA 不写死在电视端**：115 直链与"申请它时的 UA"绑定，这个 UA 由平板在 /play 里带过来，
 *   以后 115 改了 UA 只改平板一端；
 * - `pickCode` / `token` 是二期"直链过期自取续播"的预留字段，本版平板不发、电视端忽略。
 */
internal object TvProtocol {
    /** UDP 发现端口：平板往这里发广播查询 */
    const val DISCOVER_PORT = 48700

    /** HTTP 控制端口：/play /status /control /ping */
    const val HTTP_PORT = 48701

    const val FIND_TYPE = "115pad.tv.find"
    const val HERE_TYPE = "115pad.tv.here"
    const val PROTOCOL_VERSION = 1

    /** 设备名兜底（拿不到系统设备名时用） */
    const val FALLBACK_NAME = "Android TV"

    /** /status 与通知里用的播放状态字面量 */
    const val STATE_IDLE = "idle"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_BUFFERING = "buffering"
    const val STATE_ERROR = "error"

    /**
     * 流已经交给**电视自带播放器**在放：我们既拿不到进度，也控制不了它。
     * 平板据此把遥控按钮置灰、只留"停止投屏"。
     */
    const val STATE_EXTERNAL = "external"

    /** /control 的动作字面量 */
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"
    const val ACTION_STOP = "stop"

    /** 倍速：改播放器的 playbackParameters.speed（不影响电视系统音量） */
    const val ACTION_SPEED = "speed"

    /** 音量：走电视的媒体音量（STREAM_MUSIC），与遥控器音量键同一档位体系 */
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"

    /** 直接设定音量（0..volumeMax，值在 [ControlRequest.volume]） */
    const val ACTION_VOLUME_SET = "volume_set"

    /** 解码方式（值在 [ControlRequest.mode]：hw / sw） */
    const val ACTION_DECODE = "decode"

    /** 播放器（值在 [ControlRequest.mode]：builtin = 本 App 内置 / system = 交给电视自带播放器） */
    const val ACTION_PLAYER = "player"

    /** 清晰度（值在 [ControlRequest.quality]：def 编号，0 = 原盘） */
    const val ACTION_QUALITY = "quality"

    /** 字幕显隐（值在 [ControlRequest.mode]：off / on） */
    const val ACTION_SUBTITLE = "subtitle"

    /** 字幕模式字面量 */
    const val SUBTITLE_OFF = "off"
    const val SUBTITLE_ON = "on"

    /** 解码方式字面量 */
    const val DECODE_HW = "hw"
    const val DECODE_SW = "sw"

    /** 播放器字面量 */
    const val PLAYER_BUILTIN = "builtin"
    const val PLAYER_SYSTEM = "system"
}

/** 平板 → 电视的发现查询（UDP 广播） */
@Serializable
internal data class FindBeacon(
    val t: String = TvProtocol.FIND_TYPE,
    val v: Int = TvProtocol.PROTOCOL_VERSION,
)

/** 电视 → 平板的发现应答（UDP 单播回给查询的来源地址） */
@Serializable
internal data class HereBeacon(
    val t: String = TvProtocol.HERE_TYPE,
    val v: Int = TvProtocol.PROTOCOL_VERSION,
    val name: String,
    val port: Int = TvProtocol.HTTP_PORT,
)

/** 一路外挂字幕：直链 + MIME（空则电视端按扩展名猜）+ 展示名（空则按轨号显示） */
@Serializable
internal data class SubtitleTrack(
    val url: String,
    val mime: String? = null,
    val name: String? = null,
)

/** 平板 → 电视：播放指令（POST /play） */
@Serializable
internal data class PlayRequest(
    val url: String,
    val title: String = "",
    /** 由平板按文件名推出来的 MIME；空则交给 media3 自己嗅探 */
    val mime: String? = null,
    val positionMs: Long = 0,
    /** 拉流必须带的 UA（115 直链与它绑定）；空则不带 UA。外挂字幕直链同一份 UA */
    val ua: String? = null,
    /** 这一集在 115 的（可选）：电视用它当连播队列里的身份，/status 回给平板 */
    val pickCode: String? = null,
    /** 二期预留：直链过期后自取新地址用 */
    val token: String? = null,
    /** 外挂字幕（115 官方字幕直链，已由平板解析好）；老版本平板不发此字段 */
    val subs: List<SubtitleTrack> = emptyList(),
)

/** 连播队列里的一条（直链都已由平板解析好） */
@Serializable
internal data class QueueItem(
    val url: String,
    val title: String = "",
    val mime: String? = null,
    val pickCode: String? = null,
    /** 这一路的外挂字幕；连播补推的老批次没有它（当前集的字幕在 /play 里随播种条目带来） */
    val subs: List<SubtitleTrack> = emptyList(),
)

/**
 * 平板 → 电视：连播队列（POST /queue）。
 *
 * 为什么不在 /play 里一次带全：每集直链都要走一次 115 接口，日常限速下 30 集要好几秒，
 * 会把"点投屏到出画"拖长。现在 /play 只管当前集，平板解析好一段就补推一段。
 */
@Serializable
internal data class QueueRequest(
    /** 按播放顺序整段下发（含平板当前在切的那集）；电视按身份去重后只追加新条目 */
    val queue: List<QueueItem> = emptyList(),
    /** 锚点 = 平板这次投的那一集的 pick_code：电视据此挡掉上一场投屏的迟到包 */
    val anchor: String? = null,
)

/** 通用应答（/play、/control） */
@Serializable
internal data class OkResponse(
    val ok: Boolean,
    val error: String? = null,
)

/** 一档可选画质（/status 的 qualityOptions 里用）：def 编号 + 显示名（与电视端画质面板同源） */
@Serializable
internal data class QualityOption(
    val def: Int,
    val label: String,
)

/** GET /status：平板据此显示投屏进度与播放态 */
@Serializable
internal data class StatusResponse(
    val state: String = TvProtocol.STATE_IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val error: String? = null,
    /** 当前倍速（1.0 = 正常），平板面板据此高亮档位 */
    val speed: Float = 1f,
    /** 电视的媒体音量与上限（读不到为 -1）；平板面板据此画音量数值 */
    val volume: Int = -1,
    val volumeMax: Int = -1,
    /** 当前解码方式（hw/sw）：平板面板高亮用 */
    val decode: String = TvProtocol.DECODE_HW,
    /** 当前播放器（builtin/system）：平板面板高亮用 */
    val player: String = TvProtocol.PLAYER_BUILTIN,
    /** 当前画质档（def 编号）：平板遥控面板高亮用 */
    val quality: Int = 0,
    /** 可选画质档（含当前）：平板遥控面板画 chips 用；没取到清晰度列表时只有原盘一项 */
    val qualityOptions: List<QualityOption> = emptyList(),
    /** 字幕开关（off/on）：平板遥控 chips 高亮用 */
    val subtitle: String = TvProtocol.SUBTITLE_ON,
    /** 当前这一集在连播队列里的位置（0 起）与队列总条数；单集投屏恒为 0/1 */
    val index: Int = 0,
    val queueSize: Int = 0,
    /** 当前这一集的 pick_code（电视自动连播到下一集后会变）：平板据此判断要不要拽本地续播 */
    val pickCode: String? = null,
)

/** 平板 → 电视：遥控指令（POST /control） */
@Serializable
internal data class ControlRequest(
    val action: String,
    /** action=seek 时的目标位置 */
    val positionMs: Long = 0,
    /** action=speed 时的目标倍速 */
    val speed: Float = 1f,
    /** action=volume_set 时的目标音量（0..volumeMax） */
    val volume: Int = -1,
    /** action=decode / action=player / action=subtitle 时的目标值 */
    val mode: String? = null,
    /** action=quality 时的目标清晰度（def 编号，0 = 原盘） */
    val quality: Int = 0,
)

/**
 * 平板 → 电视：推送一路外挂字幕（POST /subs）。
 *
 * 字幕是平板从在线字幕库下载后转好编码的**内容本身**（base64），不是直链——
 * 在线字幕站的直链有 Referer/UA 门槛，电视直拉大概率 403，由平板代下载最稳。
 * 字幕都是文本，几 MB 封顶，走局域网 JSON 一发即至。
 */
@Serializable
internal data class SubsPushRequest(
    /** 展示名（也是电视端轨道 label / 自动选轨的匹配键）；空则电视端兜底命名 */
    val name: String? = null,
    val mime: String? = null,
    /** 字幕内容（UTF-8 字节的 base64；平板侧已做 GBK→UTF-8 归一） */
    val data: String,
)

/** GET /ping：调试与"这台是不是本协议的电视端"自检 */
@Serializable
internal data class PingResponse(
    val app: String = "115 openpad电视端",
    val ver: String,
)

internal val protocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun encodeFindBeacon(): String =
    protocolJson.encodeToString(FindBeacon.serializer(), FindBeacon())

/** 解析 UDP 报文；不是本协议的查询返回 null（rf 广播式 UDP 端口上可能混进别家的包） */
internal fun parseFindBeacon(text: String): FindBeacon? =
    runCatching { protocolJson.decodeFromString(FindBeacon.serializer(), text) }
        .getOrNull()
        ?.takeIf { it.t == TvProtocol.FIND_TYPE }
