package com.open115.pad.cast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 与「115 openpad电视端」App（独立工程 `F:\ad\115pad-tv`）之间的局域网协议。
 *
 * 端口与 JSON 字段名必须与对方 `receiver/TvProtocol.kt` 保持一致——改这里就要同步改那边。
 *
 * 一句话说明它凭什么取代中转：115 直链与"申请它时的 UA"绑定，电视端的 DLNA 播放器改不了自己的
 * UA 所以才要平板中转；电视端 App 是我们自己写的，UA 随播放指令带过去原样使用即可。
 */
internal object PadTv {
    /** UDP 发现端口：往这里广播查询 */
    const val DISCOVER_PORT = 48700

    /** HTTP 控制端口 */
    const val HTTP_PORT = 48701

    const val FIND_TYPE = "115pad.tv.find"
    const val HERE_TYPE = "115pad.tv.here"

    /** 电视端 /status 里的播放态字面量（只有这两个算"在播"） */
    const val STATE_PLAYING = "playing"
    const val STATE_BUFFERING = "buffering"

    /** 待机：电视端已经不在播放页了（没在播、也没有停在片尾的片） */
    const val STATE_IDLE = "idle"

    /** 流已交给电视自带播放器：我们拿不到进度、也控不了它（面板只留"停止投屏"） */
    const val STATE_EXTERNAL = "external"

    /** 解码方式（mode: hw/sw）与播放器（mode: builtin/system） */
    const val ACTION_DECODE = "decode"
    const val ACTION_PLAYER = "player"

    const val DECODE_HW = "hw"
    const val DECODE_SW = "sw"

    /** builtin = 电视端 App 内置播放器；system = 交给电视自带的播放器 */
    const val PLAYER_BUILTIN = "builtin"
    const val PLAYER_SYSTEM = "system"

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"
    const val ACTION_STOP = "stop"

    /** 倍速（改电视播放器的播放速度）与音量（走电视的媒体音量） */
    const val ACTION_SPEED = "speed"
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"
    const val ACTION_VOLUME_SET = "volume_set"

    /** 清晰度（quality: def 编号，0 = 原盘）与字幕显隐（mode: off/on）——字段名与电视端 TvProtocol 对齐 */
    const val ACTION_QUALITY = "quality"
    const val ACTION_SUBTITLE = "subtitle"
    const val SUBTITLE_OFF = "off"
    const val SUBTITLE_ON = "on"
}

/** 平板 → 电视的发现查询 */
@Serializable
internal data class PadTvFind(
    val t: String = PadTv.FIND_TYPE,
    val v: Int = 1,
)

/** 电视 → 平板的发现应答 */
@Serializable
internal data class PadTvHere(
    val t: String = PadTv.HERE_TYPE,
    val v: Int = 1,
    val name: String,
    val port: Int = PadTv.HTTP_PORT,
)

/** 一路外挂字幕（115 官方字幕直链）：字段名与电视端 `receiver/TvProtocol.kt` 的 SubtitleTrack 对齐 */
@Serializable
internal data class PadTvSubtitle(
    val url: String,
    /** 115 接口按 type 给的 MIME；电视端拿不到会按扩展名兜底 */
    val mime: String? = null,
    val name: String? = null,
)

/** 播放指令：直链 + 拉流要用的 UA + 起播位置 */
@Serializable
internal data class PadTvPlay(
    val url: String,
    val title: String = "",
    val mime: String? = null,
    val positionMs: Long = 0,
    val ua: String? = null,
    /** 这一集的 pick_code：电视存进连播队列，并在 /status 里回传（平板据此判断电视播到哪一集） */
    val pickCode: String? = null,
    /**
     * 115 access token：电视用它自己调 open/video/play 与 open/ufile/downurl
     * （二期：可选清晰度 + 直链过期自愈）。没有它电视只能播投屏带来的原盘直链。
     * 只走局域网明文，与整个投屏能力同一份信任模型；电视端只放内存、不落盘。
     */
    val token: String? = null,
    /** 这一集的外挂字幕（直链已解析好）；电视端挂进 MediaItem 当文本轨。老版本电视端会忽略 */
    val subs: List<PadTvSubtitle> = emptyList(),
)

/** 连播队列里的一集（直链已由平板解析好） */
@Serializable
internal data class PadTvQueueItem(
    val url: String,
    val title: String = "",
    val mime: String? = null,
    val pickCode: String? = null,
    /** 补推的后续集暂不带字幕（每集还要多一次字幕接口，先只服务当前集） */
    val subs: List<PadTvSubtitle> = emptyList(),
)

/**
 * 连播队列补推（POST /queue）。
 *
 * 为什么不在 /play 里一次带全：每集直链都要走一次 115 接口，日常 300ms 限速下几十集要好几秒，
 * 会把"点投屏到出画"拖长。这里先投当前集，后续集解析好一段补推一段。
 */
@Serializable
internal data class PadTvQueue(
    /** 按播放顺序整段下发（含当前在切的那集）；电视按 pick_code 去重后只追加 */
    val queue: List<PadTvQueueItem> = emptyList(),
    /** 锚点 = 这次投的那一集：电视用它挡掉上一场投屏的迟到包 */
    val anchor: String? = null,
)

/** 通用应答（/play、/control） */
@Serializable
internal data class PadTvOk(
    val ok: Boolean,
    val error: String? = null,
)

/** 一档可选画质（与电视端 TvProtocol.QualityOption 字段对齐） */
@Serializable
internal data class PadTvQualityOption(
    val def: Int,
    val label: String,
)

/** /status 应答 */
@Serializable
internal data class PadTvStatus(
    val state: String = "idle",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val error: String? = null,
    /** 电视侧当前倍速（1.0 = 正常） */
    val speed: Float = 1f,
    /** 电视侧媒体音量与上限（-1 = 读不到） */
    val volume: Int = -1,
    val volumeMax: Int = -1,
    /** 电视侧解码方式（hw/sw）与播放器（builtin/system）：遥控面板高亮用 */
    val decode: String = PadTv.DECODE_HW,
    val player: String = PadTv.PLAYER_BUILTIN,
    /** 电视侧当前画质档（def 编号）与可选档列表：遥控面板 chips 高亮用 */
    val quality: Int = 0,
    val qualityOptions: List<PadTvQualityOption> = emptyList(),
    /** 电视侧字幕开关（off/on）：遥控 chips 高亮用 */
    val subtitle: String = PadTv.SUBTITLE_ON,
    /** 电视当前播到连播队列的第几条（0 起）、队列总条数 */
    val index: Int = 0,
    val queueSize: Int = 0,
    /** 电视当前这一集的 pick_code：自动连播换集后会变 */
    val pickCode: String? = null,
)

/** 遥控指令 */
@Serializable
internal data class PadTvControl(
    val action: String,
    val positionMs: Long = 0,
    /** action=speed 的目标倍速 */
    val speed: Float = 1f,
    /** action=volume_set 的目标音量 */
    val volume: Int = -1,
    /** action=decode / action=player / action=subtitle 的目标值（hw/sw、builtin/system 或 off/on） */
    val mode: String? = null,
    /** action=quality 的目标清晰度（def 编号，0 = 原盘） */
    val quality: Int = 0,
)

/** 外挂字幕推送（POST /subs）：平板代下载好的字幕内容 base64，字段与电视端 SubsPushRequest 对齐 */
@Serializable
internal data class PadTvSubsPush(
    /** 展示名：电视端轨道 label；空则电视端兜底命名 */
    val name: String? = null,
    val mime: String? = null,
    /** UTF-8 字节的 base64（平板侧已做 GBK→UTF-8 归一） */
    val data: String,
)

internal val padTvJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun encodePadTvFind(): String =
    padTvJson.encodeToString(PadTvFind.serializer(), PadTvFind())

internal fun parsePadTvHere(text: String): PadTvHere? =
    runCatching { padTvJson.decodeFromString(PadTvHere.serializer(), text) }
        .getOrNull()
        ?.takeIf { it.t == PadTv.HERE_TYPE }
