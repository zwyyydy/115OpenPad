package com.open115.pad.cast

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envData
import com.open115.pad.data.parseFileDownloadUrl
import com.open115.pad.player.CastMenu
import com.open115.pad.player.mimeForSubtitleExt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * 任意页面发起的投屏：打开就并行搜设备（115 openpad电视端 + DLNA），选中即投。
 *
 * 播放页有自己嵌在控制排上的面板；这里是给"还没有播放页"的入口用的——
 * 媒体库详情页的投屏键、文件页长按（多选）后的投屏键。
 * 发现与投递走的是播放页同一套（[discoverPadTvHosts] / [discoverCastDevices] / [startCastFrom]），
 * 所以"免中转直连 vs DLNA 中转"的分通道行为完全一致。
 */
@Composable
internal fun CastPickerOverlay(
    http: OkHttpClient,
    onDismiss: () -> Unit,
    onPicked: (CastDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(emptyList<CastDevice>()) }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(scanTick) {
        scanning = true
        error = null
        devices = runCatching {
            coroutineScope {
                val padTv = async { runCatching { discoverPadTvHosts() }.getOrDefault(emptyList()) }
                val dlna = async {
                    runCatching { discoverCastDevices(context, http) }.getOrDefault(emptyList())
                }
                padTv.await() + dlna.await()
            }
        }.getOrDefault(emptyList())
        scanning = false
        if (devices.isEmpty()) error = "没找到可投屏设备（电视与本机需在同一 Wi-Fi）"
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        CastMenu(
            devices = devices,
            scanning = scanning,
            error = error,
            onPick = onPicked,
            onRescan = { scanTick++ },
            onDismiss = onDismiss,
            modifier = Modifier.padding(bottom = 28.dp),
        )
    }
}

/**
 * 从任意页面投屏：取原盘直链 → 顺路取这一集的官方字幕 → [CastSession.start]
 * （与播放页里那条链路完全一致）。
 *
 * @return 失败原因（成功为 null），调用方直接 toast/snackbar 给用户。
 */
internal suspend fun startCastFrom(
    context: Context,
    http: OkHttpClient,
    api: OpenApi,
    device: CastDevice,
    pickCode: String,
    title: String,
    startMs: Long = 0L,
): String? {
    val url = runCatching { parseFileDownloadUrl(api.downUrl(pickCode)) }.getOrNull()
        ?: return "取播放地址失败（115 频控或权限），稍后再试"
    // fileName 只用来推 MIME：媒体库/文件页给过来的常常是没有扩展名的片名，
    // 从直链路径补一个真实的文件名（115 的直链路径末尾就是原文件名）。
    val urlName = url.substringBefore('?').substringAfterLast('/')
    val fileName = if (title.contains('.')) title else urlName.ifBlank { "video" }
    // 字幕匹配要拿真实文件名，所以排在直链之后（字幕失败不挡投屏，见 fetchCastSubtitles）
    val subs = fetchCastSubtitles(api, pickCode, fileName)
    return CastSession.start(
        context = context,
        http = http,
        device = device,
        sourceUrl = url,
        fileName = fileName,
        title = title,
        pickCode = pickCode,
        startMs = startMs,
        subtitles = subs,
    )
}

/** 投屏外挂字幕（115 官方字幕直链）：字段与电视端 TvProtocol.SubtitleTrack 对齐 */
internal data class CastSubtitle(
    val url: String,
    val mime: String,
    val name: String?,
)

/**
 * 取一集的「115 官方字幕」（115 给视频配的同目录伴随字幕，接口自带直链，投电视用）。
 * 播放页的 [com.open115.pad.player] 侧已有同源逻辑（fetchOfficialSubs），这里是给
 * 没有播放页状态的入口（媒体库详情页/文件页多选投屏）用的独立实现。
 *
 * 任何失败都返回空列表——字幕只是锦上添花，绝不该挡投屏。
 */
internal suspend fun fetchCastSubtitles(api: OpenApi, pickCode: String, videoName: String): List<CastSubtitle> =
    runCatching {
        val list = api.videoSubtitles(pickCode).envData()?.get("list") as? JsonArray
            ?: return@runCatching emptyList()
        val entries = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val url = s("url") ?: return@mapNotNull null
            val type = s("type")?.lowercase().orEmpty()
            // 占位条目的判据：type 与 sha1 都空（真字幕一定有类型和内容指纹）
            if (type.isBlank() || s("sha1").isNullOrBlank()) return@mapNotNull null
            if (type !in SUBTITLE_TYPES) return@mapNotNull null
            CastSubtitle(url = url, mime = mimeForSubtitleExt(type), name = s("title"))
        }
        // 与本视频同名的（`<片名>.chi.zh-cn.ass` 这类）优先；一条都对不上时退回接口
        // 排序的第一条——接口按相关度给的，第一条就是它认的那个。
        val base = videoName.substringBeforeLast('.').takeIf(String::isNotBlank)
        val matched = base
            ?.let { b -> entries.filter { it.name?.startsWith(b, ignoreCase = true) == true } }
            .orEmpty()
        matched.ifEmpty { entries.take(1) }
    }.getOrDefault(emptyList())

/** 接口 type 字段里认作字幕的类型（与播放器侧 fetchOfficialSubs 同一张表） */
private val SUBTITLE_TYPES = setOf("srt", "vtt", "ass", "ssa")
