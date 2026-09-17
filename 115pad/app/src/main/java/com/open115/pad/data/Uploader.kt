package com.open115.pad.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val uploadJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * 小文件上传（实测验证的协议，详见 docs/115-api-notes.md）：
 * get_token(STS) → init(sha1+preid, 秒传判定/二次认证) → 阿里云 OSS PUT(V1签名 + callback头)
 * 当前限制：文件需整体读入内存，调用方限制 ≤ 32MB。
 */
object Uploader {

    data class UploadResult(
        val fileId: String,
        val pickCode: String,
        val fileName: String,
        val reused: Boolean, // 是否秒传
    )

    /**
     * 上传并在传输中心留一条记录（时间 / 大小 / 目标目录 / 成败 / 秒传）。
     *
     * 两条上传入口（文件页 SAF 上传、播放器"字幕传到视频目录"）都走这里，免得各自
     * 记一遍导致漏记或字段不一致。记录先以"上传中"落库、结束再回填结果——上传是
     * 一次性 PUT 拿不到进度，但至少让用户看得到"正在传、传给谁"。
     */
    suspend fun uploadSmallLogged(
        log: TransferLog,
        api: OpenApi,
        fileName: String,
        bytes: ByteArray,
        targetCid: String,
        targetName: String?,
    ): UploadResult {
        val id = log.beginUpload(fileName, bytes.size.toLong(), targetCid, targetName)
        return try {
            val r = uploadSmall(api, fileName, bytes, target = "U_1_$targetCid")
            log.finishUpload(id, ok = true, reused = r.reused)
            r
        } catch (e: Exception) {
            log.finishUpload(id, ok = false, error = e.message)
            throw e
        }
    }

    suspend fun uploadSmall(
        api: OpenApi,
        fileName: String,
        bytes: ByteArray,
        target: String = "U_1_0",
    ): UploadResult = withContext(Dispatchers.IO) {
        val size = bytes.size.toLong()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
        val preid = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(0, minOf(bytes.size, 131072)))
            .joinToString("") { "%02X".format(it) }

        // 1. STS 凭证
        val tokenRoot = api.uploadGetToken()
        val cred = tokenRoot.envData()?.takeIf { it.isNotEmpty() } ?: error(tokenRoot.envMsg() ?: "获取上传凭证失败")
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val endpoint = s(cred, "endpoint").removePrefix("https://").removePrefix("http://")
        val ak = s(cred, "AccessKeyId")
        val sk = s(cred, "AccessKeySecret")
        val securityToken = s(cred, "SecurityToken")
        if (ak.isBlank() || sk.isBlank()) error("上传凭证缺失")

        // 2. init（二次认证最多重试 3 轮）
        var form = linkedMapOf("file_name" to fileName, "file_size" to size.toString(),
            "target" to target, "fileid" to sha1, "preid" to preid)
        var initRoot = api.uploadInit(
            fileName, size, target, sha1, preid,
            form["pick_code"], form["sign_key"], form["sign_val"],
        )
        var reused = false
        var pickCode = ""
        var bucket = ""
        var obj = ""
        var callback = ""
        var callbackVar = ""
        loop@ for (round in 0 until 3) {
            if (!initRoot.envOk()) error(initRoot.envMsg() ?: "上传初始化失败")
            val d = initRoot.envData() ?: error("上传初始化响应异常")
            fun f(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
            val status = f("status").toIntOrNull() ?: 0
            when {
                status == 2 -> { // 秒传
                    reused = true
                    pickCode = f("pick_code")
                    return@withContext UploadResult(f("file_id"), pickCode, fileName, true)
                }
                d["sign_check"] !is JsonNull && f("sign_check").isNotBlank() -> {
                    // 二次认证：取 sign_check 区间（含两端）字节算 SHA1 大写
                    val range = f("sign_check").split("-")
                    val a = range.getOrNull(0)?.toIntOrNull() ?: error("二次认证参数异常")
                    val b = range.getOrNull(1)?.toIntOrNull() ?: error("二次认证参数异常")
                    val seg = bytes.copyOfRange(a.coerceIn(0, bytes.size), (b + 1).coerceIn(0, bytes.size))
                    val signVal = MessageDigest.getInstance("SHA-1").digest(seg)
                        .joinToString("") { "%02X".format(it) }
                    form["sign_key"] = f("sign_key")
                    form["sign_val"] = signVal
                    initRoot = api.uploadInit(
                        fileName, size, target, sha1, preid,
                        form["pick_code"], form["sign_key"], form["sign_val"],
                    )
                }
                else -> {
                    pickCode = f("pick_code")
                    bucket = f("bucket")
                    obj = f("object")
                    val cb = d["callback"]
                    val cbObj = cb as? JsonObject
                    if (cbObj != null) {
                        fun c(k: String) = (cbObj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                        callback = c("callback")
                        callbackVar = c("callback_var")
                    } else if (cb is JsonArray && cb.isNotEmpty()) {
                        val cbObj2 = cb[0] as? JsonObject
                        if (cbObj2 != null) {
                            fun c(k: String) = (cbObj2[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                            callback = c("callback")
                            callbackVar = c("callback_var")
                        }
                    }
                    break@loop
                }
            }
        }
        if (bucket.isBlank() || obj.isBlank()) error("上传调度未返回存储位置")
        if (callback.isBlank()) error("上传调度未返回回调参数")

        // 3. OSS PUT（V1 签名）
        val cbB64 = Base64.encodeToString(callback.toByteArray(), Base64.NO_WRAP)
        val cbvB64 = Base64.encodeToString(callbackVar.toByteArray(), Base64.NO_WRAP)
        val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        dateFmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val date = dateFmt.format(Date())
        val canonHeaders = "x-oss-callback:$cbB64\nx-oss-callback-var:$cbvB64\nx-oss-security-token:$securityToken\n"
        val contentType = "application/octet-stream"
        val stringToSign = "PUT\n\n$contentType\n$date\n$canonHeaders/$bucket/$obj"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sk.toByteArray(), "HmacSHA1"))
        val signature = Base64.encodeToString(mac.doFinal(stringToSign.toByteArray()), Base64.NO_WRAP)

        val schemes = listOf("https", "http") // 本机/部分网络 https 到 OSS 可能被干扰，失败回退 http
        var lastError: Exception? = null
        for (scheme in schemes) {
            try {
                val conn = URL("$scheme://$bucket.$endpoint/$obj").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("Date", date)
                conn.setRequestProperty("Authorization", "OSS $ak:$signature")
                conn.setRequestProperty("x-oss-security-token", securityToken)
                conn.setRequestProperty("x-oss-callback", cbB64)
                conn.setRequestProperty("x-oss-callback-var", cbvB64)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val resp = uploadJson.decodeFromString(
                        ApiEnvelope.serializer(JsonObject.serializer()), body,
                    )
                    if (!resp.state) error(resp.message ?: "上传回调失败")
                    val d = resp.data
                    fun g(k: String) = (d?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                    return@withContext UploadResult(g("file_id"), g("pick_code"), fileName, false)
                }
                error("OSS 上传失败 HTTP $code：${body.take(200)}")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: error("OSS 上传失败")
    }
}
