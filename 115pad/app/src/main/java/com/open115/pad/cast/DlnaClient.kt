package com.open115.pad.cast

import com.open115.pad.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "CastDlna"

/** 电视回了非 2xx（带它自己的 UPnP 错误码/描述） */
internal class DlnaException(message: String) : Exception(message)

/**
 * DLNA 的 AVTransport 控制（投屏的"遥控器"）。
 *
 * 只做投屏必需的那几个动作：设置地址、播放、暂停、停止、跳进度、查状态。
 * 每个动作都是一次 SOAP POST，服务类型与地址都取自设备描述（不同电视的版本/路径不一样）。
 *
 * 所有调用都带独立短超时：电视掉线时 UI 最多卡 5 秒，不能把播放页的协程拖死。
 */
internal class DlnaClient(
    http: OkHttpClient,
    private val device: CastDevice,
) {
    private val client = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private suspend fun av(action: String, args: List<Pair<String, String>>): String =
        soap(device.avTransportUrl, device.avTransportType, action, args)

    private suspend fun soap(
        url: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
    ): String = withContext(Dispatchers.IO) {
        val body = soapEnvelope(serviceType, action, args).toRequestBody(XML_TYPE)
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw DlnaException(parseUpnpError(text) ?: "电视拒绝请求（HTTP ${resp.code}）")
            }
            text
        }
    }

    /** 把中转地址推给电视；电视据此开始缓冲 */
    suspend fun setAvTransportUri(url: String, title: String, mime: String) {
        av(
            "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to url,
                "CurrentURIMetaData" to didlLite(title, url, mime),
            ),
        )
    }

    suspend fun play() {
        av("Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun pause() {
        av("Pause", listOf("InstanceID" to "0"))
    }

    suspend fun stop() {
        av("Stop", listOf("InstanceID" to "0"))
    }

    suspend fun seekTo(positionMs: Long) {
        av(
            "Seek",
            listOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to formatDlnaTime(positionMs),
            ),
        )
    }

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT */
    suspend fun transportState(): String? =
        xmlTextTag(av("GetTransportInfo", listOf("InstanceID" to "0")), "CurrentTransportState")

    suspend fun positionMs(): Long? =
        parseDlnaTime(xmlTextTag(av("GetPositionInfo", listOf("InstanceID" to "0")), "RelTime"))

    suspend fun durationMs(): Long? =
        parseDlnaTime(xmlTextTag(av("GetPositionInfo", listOf("InstanceID" to "0")), "TrackDuration"))

    /** 一次拿进度与时长：轮询每 2 秒一次，少发一个请求 */
    suspend fun positionAndDuration(): Pair<Long?, Long?> {
        val xml = runCatching { av("GetPositionInfo", listOf("InstanceID" to "0")) }
            .getOrElse {
                Log.d(TAG, "GetPositionInfo 失败: ${it.message}")
                return null to null
            }
        return parseDlnaTime(xmlTextTag(xml, "RelTime")) to
            parseDlnaTime(xmlTextTag(xml, "TrackDuration"))
    }

    companion object {
        private val XML_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
