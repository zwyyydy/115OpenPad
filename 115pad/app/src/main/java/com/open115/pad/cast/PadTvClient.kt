package com.open115.pad.cast

import com.open115.pad.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "PadTvClient"

/** 电视端回了业务失败（ok=false） */
internal class PadTvException(message: String) : Exception(message)

/**
 * 电视端接收器的 HTTP 客户端：一条 /play 就把片交给电视，之后只剩轮询与遥控。
 *
 * 超时比 DLNA 那条通道短一档：这些请求都只是小 JSON，电视端处理是毫秒级；
 * 电视没开机时用户要的是"投屏失败"的快速反馈，不该被十几秒的默认连接超时拖住。
 */
internal class PadTvClient(http: OkHttpClient, private val baseUrl: String) {

    private val client = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 把片交给电视：直链与 UA 一起过去，电视端自己拉流；外挂字幕直链随同一条指令带上 */
    suspend fun play(
        url: String,
        title: String,
        mime: String,
        positionMs: Long,
        ua: String,
        pickCode: String?,
        token: String?,
        subs: List<PadTvSubtitle> = emptyList(),
    ) {
        val body = padTvJson.encodeToString(
            PadTvPlay.serializer(),
            PadTvPlay(
                url = url,
                title = title,
                mime = mime,
                positionMs = positionMs,
                ua = ua,
                pickCode = pickCode,
                token = token,
                subs = subs,
            ),
        )
        val ok = withContext(Dispatchers.IO) { postOkJson("/play", body) }
        if (!ok.ok) throw PadTvException(ok.error ?: "电视端拒绝了播放指令")
    }

    /** 补推连播队列：电视已经在播当前集，这里只追加后面几集（可分批多次调用） */
    suspend fun queue(items: List<PadTvQueueItem>, anchor: String?) {
        val body = padTvJson.encodeToString(
            PadTvQueue.serializer(),
            PadTvQueue(queue = items, anchor = anchor),
        )
        val ok = withContext(Dispatchers.IO) { postOkJson("/queue", body) }
        if (!ok.ok) throw PadTvException(ok.error ?: "电视端拒绝了连播队列")
    }

    /** 电视侧播放状态；拿不到返回 null（调用方保持上一次进度即可） */
    suspend fun status(): PadTvStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(
                Request.Builder().url("$baseUrl/status").get().build(),
            ).execute()
            resp.use {
                if (!it.isSuccessful) return@use null
                val text = it.body?.string().orEmpty()
                padTvJson.decodeFromString(PadTvStatus.serializer(), text)
            }
        }.onFailure { Log.d(TAG, "status 失败: ${it.message}") }.getOrNull()
    }

    /** 遥控（play/pause/seek/stop/倍速/音量/解码/播放器/画质/字幕）；返回是否被接受 */
    suspend fun control(
        action: String,
        positionMs: Long = 0L,
        speed: Float = 1f,
        volume: Int = -1,
        mode: String? = null,
        quality: Int = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = padTvJson.encodeToString(
                PadTvControl.serializer(),
                PadTvControl(
                    action = action,
                    positionMs = positionMs,
                    speed = speed,
                    volume = volume,
                    mode = mode,
                    quality = quality,
                ),
            )
            postOkJson("/control", body).ok
        }.onFailure { Log.d(TAG, "control($action) 失败: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 推一路外挂字幕给电视（在线字幕库代下载好的内容）。
     * 内容是 base64 文本、局域网直发，几 MB 也在秒级内；失败返回 false（电视端失联等）。
     */
    suspend fun pushSubs(name: String?, mime: String?, bytes: ByteArray): Boolean {
        val body = padTvJson.encodeToString(
            PadTvSubsPush.serializer(),
            PadTvSubsPush(
                name = name,
                mime = mime,
                data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            ),
        )
        val ok = withContext(Dispatchers.IO) { runCatching { postOkJson("/subs", body) }.getOrNull() }
        return ok?.ok == true
    }

    /** 阻塞式 POST（调用方负责切到 IO 线程） */
    private fun postOkJson(path: String, json: String): PadTvOk {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(json.toRequestBody(JSON_TYPE))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw PadTvException("电视端返回 HTTP ${resp.code}")
            return padTvJson.decodeFromString(PadTvOk.serializer(), text)
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
