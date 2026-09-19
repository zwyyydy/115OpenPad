package com.open115.pad.data

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    private const val TAG = "OSSUploader"

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
        currentCoroutineContext()[Job]?.let { log.registerJob(id, it) }
        return try {
            val r = uploadSmall(api, fileName, bytes, target = "U_1_$targetCid")
            log.finishUpload(id, ok = true, reused = r.reused)
            r
        } catch (e: Exception) {
            // 小文件没有可续传的会话，取消（用户/外部）直接清记录，不留「已取消」尸体；
            // 协程已取消，落库必须包 NonCancellable，否则 DataStore edit 被取消打断
            if (e is kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { log.removeUpload(id) }
            } else log.finishUpload(id, ok = false, error = e.message)
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
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val preid = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(0, minOf(bytes.size, 131072)))
            .joinToString("") { "%02x".format(it) }

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
                    // 二次认证：取 sign_check 区间（含两端）字节算 SHA1——sign_val 官方要求大写
                    // （fileid/preid 小写，但 sign_val 是例外，语雀文档明确标注"(大写)"）
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
        if (bucket.isBlank() || obj.isBlank()) {
            Log.w(TAG, "init 响应未含存储位置 resp=${initRoot.toString().take(600)}")
            error("上传调度未返回存储位置")
        }
        if (callback.isBlank()) error("上传调度未返回回调参数")

        // 3. OSS PUT（V1 签名）
        // callbackBody 模板里的 ${sha1} 不在阿里云 OSS 系统变量表内（官方仅支持
        // bucket/object/etag/size/mimeType/imageInfo），OSS 不填充会让 115 收到
        // 空 sha1 → "校验文件失败"(code 10002)。客户端预替换成真实 SHA1。
        val cbB64 = Base64.encodeToString(callback.replace("\${sha1}", sha1).toByteArray(), Base64.NO_WRAP)
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

    // ==================== 大文件分片上传 ====================
    // 协议与 uploadSmall 同源（open/upload/init + STS + 阿里云 OSS），差别在执行层：
    // 小文件一次性 PUT（整体读内存）；大文件 InitiateMultipartUpload → 逐片
    // UploadPart → Complete（携带 callback，115 收到回调才入库）。
    // 流程参考 115-plus-desktop 的 src-tauri/src/upload/（oss.rs 的分片策略、
    // STS 提前 5 分钟过期判定），调度/持久化层未移植（见 2026-09-19 调研记录）。

    /** OSS 单片基准 5MB；超过 10000 片的文件动态放大片尺寸（OSS 分片数硬上限） */
    private const val OSS_PART_SIZE = 5L * 1024 * 1024
    private const val OSS_MAX_PARTS = 10000L
    /** STS 凭证提前 5 分钟视为失效，避免分片传到一半踩过期 */
    private const val STS_EXPIRE_MARGIN_MS = 5 * 60 * 1000L

    /** get_token 返回的 OSS STS 凭证最小集 */
    private data class OssCred(
        val endpoint: String,
        val ak: String,
        val sk: String,
        val token: String,
        val deadlineMs: Long,
    ) {
        fun expired(): Boolean = System.currentTimeMillis() >= deadlineMs
    }

    private suspend fun fetchCred(api: OpenApi): OssCred {
        val root = api.uploadGetToken()
        val cred = root.envData()?.takeIf { it.isNotEmpty() } ?: error(root.envMsg() ?: "获取上传凭证失败")
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val ak = s(cred, "AccessKeyId")
        val sk = s(cred, "AccessKeySecret")
        if (ak.isBlank() || sk.isBlank()) error("上传凭证缺失")
        val expiration = s(cred, "Expiration")
        val deadlineMs = runCatching {
            // ISO8601（如 2026-09-19T08:00:00Z）→ 毫秒；解析失败按"已过期"处理，
            // 让每个分片都走一次续证，宁可多换证也不能带过期凭证上传
            java.time.Instant.parse(expiration).toEpochMilli() - STS_EXPIRE_MARGIN_MS
        }.getOrDefault(0L)
        return OssCred(
            endpoint = s(cred, "endpoint").removePrefix("https://").removePrefix("http://"),
            ak = ak,
            sk = sk,
            token = s(cred, "SecurityToken"),
            deadlineMs = deadlineMs,
        )
    }

    /** OSS V1 签名：stringToSign = VERB \n MD5 \n Content-Type \n Date \n ossHeaders+canonResource */
    private fun ossSignature(
        sk: String,
        verb: String,
        contentType: String,
        ossHeaders: String,
        canonResource: String,
    ): Pair<String, String> {
        val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        dateFmt.timeZone = TimeZone.getTimeZone("GMT")
        val date = dateFmt.format(Date())
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sk.toByteArray(), "HmacSHA1"))
        val sig = Base64.encodeToString(
            mac.doFinal("$verb\n\n$contentType\n$date\n$ossHeaders$canonResource".toByteArray()),
            Base64.NO_WRAP,
        )
        return date to sig
    }

    private class OssResp(val code: Int, val body: String, val etag: String?)

    /**
     * 统一的 OSS 请求执行：V1 签名 + https 失败回退 http（与小文件 PUT 的实测
     * 行为一致——部分网络下 https 到 OSS 会被干扰）。只重试传输异常，非 2xx
     * 直接带响应片段抛错（会话级错误重试没有意义）。
     */
    private fun ossRequest(
        cred: OssCred,
        verb: String,
        bucket: String,
        objPath: String,
        contentType: String,
        body: ByteArray?,
        bodyLen: Int = body?.size ?: 0,
        extraHeaders: List<Pair<String, String>>,
        canonResource: String,
    ): OssResp {
        var lastError: Exception? = null
        // Content-Type 必须显式设置且与签名一致：HttpURLConnection 在 doOutput 且未显式
        // 设置时会自动补 application/x-www-form-urlencoded，OSS 按实际请求头重算签名
        // → SignatureDoesNotMatch（POST ?uploads 实测踩过）。空值统一规范化成
        // application/octet-stream，签名与请求头用同一个值。
        val ct = contentType.ifEmpty { "application/octet-stream" }
        // 自定义头（x-oss-*）既参与签名也必须真实发送——complete 的 callback 头
        // 只签名不发送时，OSS 按收到的头重算签名 → 403（实测踩过）
        val sorted = extraHeaders.sortedBy { it.first }
        val ossHeaders = sorted.joinToString("") { "${it.first}:${it.second}\n" }
        for (scheme in listOf("https", "http")) {
            try {
                Log.i(TAG, "OSS $verb $scheme://$bucket.${cred.endpoint}/$objPath body=$bodyLen")
                val (date, sig) = ossSignature(cred.sk, verb, ct, ossHeaders, canonResource)
                val conn = URL("$scheme://$bucket.${cred.endpoint}/$objPath").openConnection() as HttpURLConnection
                conn.requestMethod = verb
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("Date", date)
                conn.setRequestProperty("Authorization", "OSS ${cred.ak}:$sig")
                conn.setRequestProperty("Content-Type", ct)
                sorted.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bodyLen)
                    conn.outputStream.use { it.write(body, 0, bodyLen) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                Log.i(TAG, "OSS $verb $objPath -> HTTP $code")
                return OssResp(code, text, conn.getHeaderField("ETag"))
            } catch (e: Exception) {
                Log.w(TAG, "OSS $verb $objPath ($scheme) 失败", e)
                lastError = e
            }
        }
        throw lastError ?: error("OSS 请求失败")
    }

    /** 从文件通道的绝对偏移读满 len 字节（positional read，不动通道位置） */
    private fun fillFromChannel(ch: FileChannel, dst: ByteArray, position: Long, len: Int = dst.size) {
        val bb = ByteBuffer.wrap(dst, 0, len)
        var pos = position
        while (bb.hasRemaining()) {
            val n = ch.read(bb, pos)
            if (n <= 0) error("文件读取提前结束（offset=$pos，文件可能被移动或改小）")
            pos += n
        }
    }

    /**
     * 大文件分片上传 + 传输中心留痕。进度按整数百分比变化回写
     * （DataStore 每次 edit 都是一次事务，不按片节流会高频小步写盘）。
     *
     * 断点续传：init/resume 拿到会话（sha1/pick_code/oss_upload_id）后立刻落库；
     * 本进程内失败自动 ListParts 探测续传重试一次，进程被杀的残留记录由
     * [resumeLargeLogged] 恢复。
     */
    suspend fun uploadLargeLogged(
        log: TransferLog,
        api: OpenApi,
        fileName: String,
        pfd: ParcelFileDescriptor,
        size: Long,
        targetCid: String,
        targetName: String?,
        uri: String? = null,
    ): UploadResult {
        val id = log.beginUpload(fileName, size, targetCid, targetName, uri)
        currentCoroutineContext()[Job]?.let { log.registerJob(id, it) }
        var lastPct = -1L
        suspend fun prog(uploaded: Long) {
            val pct = uploaded * 100 / size
            if (pct != lastPct) {
                lastPct = pct
                log.updateUploadProgress(id, uploaded)
            }
        }
        suspend fun runOnce(
            resumeSha1: String?, resumePickCode: String?, resumeUploadId: String?,
        ): UploadResult = uploadLarge(
            api, fileName, pfd, size, target = "U_1_$targetCid",
            resumeSha1 = resumeSha1, resumePickCode = resumePickCode, resumeUploadId = resumeUploadId,
            onProgress = { prog(it) },
            onSession = { s, p, o -> log.updateUploadSession(id, s, p, o) },
        )
        return try {
            val r = runOnce(null, null, null)
            log.finishUpload(id, ok = true, reused = r.reused)
            r
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // 取消请求：清记录（会话缺 bucket/object/STS 无法 abort，交给服务端
                // 生命周期回收，与 ListParts 降级路径同一策略）；暂停或意外取消：
                // 保留会话标记暂停，传输中心可「继续」或启动扫描按暂停状态保持不动。
                // 协程已取消，落库必须包 NonCancellable，否则 edit 被取消打断静默失败
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (log.consumeCancelRequest(id)) log.removeUpload(id)
                    else log.pauseUpload(id)
                }
                throw e
            }
            // 已拿到会话才值得续传重试（否则连 init 都没过，重试就是从头再来）
            val rec = log.uploadRecord(id)
            if (rec?.pickCode == null) {
                log.finishUpload(id, ok = false, error = e.message)
                throw e
            }
            Log.w(TAG, "分片上传失败，ListParts 断点续传重试：${e.message}")
            try {
                val r = runOnce(rec.fileSha1, rec.pickCode, rec.ossUploadId)
                log.finishUpload(id, ok = true, reused = r.reused)
                r
            } catch (e2: Exception) {
                if (e2 !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "续传重试仍失败：${e2.message}")
                }
                log.finishUpload(id, ok = false, error = e2.message)
                throw e2
            }
        }
    }

    /**
     * 进程重启后恢复中断的大文件上传（传输中心扫描"上传中"残留记录时调用）。
     * pfd 由调用方按记录里落库的 SAF uri 重新打开；恢复时 uploadLarge 会重算
     * SHA1 与记录比对，文件变了就自动走全新上传。
     */
    suspend fun resumeLargeLogged(
        log: TransferLog,
        api: OpenApi,
        rec: UploadRecord,
        pfd: ParcelFileDescriptor,
    ): UploadResult {
        if (rec.size != pfd.statSize) {
            error("文件大小与中断记录不一致（${pfd.statSize} ≠ ${rec.size}），放弃续传")
        }
        currentCoroutineContext()[Job]?.let { log.registerJob(rec.id, it) }
        Log.i(TAG, "恢复中断上传 id=${rec.id} ${rec.name} size=${rec.size} 已传≈${rec.uploaded}")
        var lastPct = -1L
        return try {
            val r = uploadLarge(
                api, rec.name, pfd, rec.size, target = "U_1_${rec.targetCid}",
                resumeSha1 = rec.fileSha1, resumePickCode = rec.pickCode, resumeUploadId = rec.ossUploadId,
                onProgress = { up ->
                    val pct = up * 100 / rec.size
                    if (pct != lastPct) {
                        lastPct = pct
                        log.updateUploadProgress(rec.id, up)
                    }
                },
                onSession = { s, p, o -> log.updateUploadSession(rec.id, s, p, o) },
            )
            log.finishUpload(rec.id, ok = true, reused = r.reused)
            r
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // 与 uploadLargeLogged 同规则：取消清记录；暂停/意外取消保留会话标记暂停
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (log.consumeCancelRequest(rec.id)) log.removeUpload(rec.id)
                    else log.pauseUpload(rec.id)
                }
            } else {
                log.finishUpload(rec.id, ok = false, error = e.message)
            }
            throw e
        }
    }

    /**
     * 大文件分片上传（流式，不占内存，无大小上限）：
     * 1. 全量 SHA1 + 前 128K preid（流式哈希，两次哈希一遍读完成）
     * 2. init 协商（秒传判定 / sign_check 二次认证，协议与 uploadSmall 完全一致，
     *    区间读取走 FileChannel 而非内存切片）；带 resumePickCode 时优先走
     *    /open/upload/resume 换回调度，失败回退全新 init
     * 3. OSS 分片：POST ?sequential&uploads 建会话 → 逐片 PUT ?partNumber&uploadId →
     *    POST ?uploadId complete（携带 callback/callback_var，115 回调入库）
     *    带 resumeUploadId 时先 ListParts 探测旧会话，已完成分片直接跳过；
     *    NoSuchUpload（会话过期）或分片尺寸不符则重建会话从头传
     *
     * 取消：每个分片边界 ensureActive()（协程取消即停，OSS 会话交给服务端过期回收）
     * 进度：每片完成回调一次已上传字节（恢复时起始回调一次已完成量）
     * STS：提前 5 分钟判定过期，每片开传前检查，过期自动换新凭证——OSS uploadId
     *      与 STS 凭证无关，换证不破坏会话
     */
    suspend fun uploadLarge(
        api: OpenApi,
        fileName: String,
        pfd: ParcelFileDescriptor,
        size: Long,
        target: String = "U_1_0",
        resumeSha1: String? = null,
        resumePickCode: String? = null,
        resumeUploadId: String? = null,
        onProgress: suspend (Long) -> Unit = {},
        onSession: suspend (sha1: String, pickCode: String, ossUploadId: String?) -> Unit = { _, _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        // dup 一个私有 fd 再开通道：FileChannel 的关闭语义作用于底层描述符，
        // 直接用调用方的 pfd 开 channel，channel.close() 会把别人的 fd 关掉
        ParcelFileDescriptor.dup(pfd.fileDescriptor).use { dup ->
            java.io.FileInputStream(dup.fileDescriptor).channel.use { ch ->
                // ---- 1. 全量 SHA1 + 前 128K preid ----
                val sha1: String
                val preSha1: String
                run {
                    val md = MessageDigest.getInstance("SHA-1")
                    val preMd = MessageDigest.getInstance("SHA-1")
                    val preLen = minOf(size, 131072L)
                    val buf = ByteArray(1 shl 23) // 8MB 哈希缓冲
                    var read = 0L
                    while (read < size) {
                        currentCoroutineContext().ensureActive()
                        val n = ch.read(
                            ByteBuffer.wrap(buf, 0, minOf(buf.size.toLong(), size - read).toInt()),
                            read,
                        )
                        if (n <= 0) error("文件读取提前结束（hash 阶段 offset=$read）")
                        if (read < preLen) preMd.update(buf, 0, minOf(n.toLong(), preLen - read).toInt())
                        md.update(buf, 0, n)
                        read += n
                    }
                    sha1 = md.digest().joinToString("") { "%02x".format(it) }
                    preSha1 = preMd.digest().joinToString("") { "%02x".format(it) }
                }

                // ---- 2. 调度协商（秒传 / 二次认证 / 拿 bucket+object+callback）----
                // 断点续传优先：resumeSha1 与重算结果一致（文件没变）才尝试 resume，
                // pick_code 换回新调度；失败（过期/无响应）回退全新 init。
                var bucket = ""
                var obj = ""
                var callback = ""
                var callbackVar = ""
                var pickCode = ""
                val resumeId = resumeUploadId?.takeIf { it.isNotBlank() }
                val canResume = !resumePickCode.isNullOrBlank() &&
                    (resumeSha1 == null || resumeSha1 == sha1)
                if (canResume) {
                    runCatching { api.uploadResume(size, target, sha1, resumePickCode!!) }
                        .getOrNull()
                        ?.takeIf { it.envOk() }
                        ?.envData()
                        ?.let { d ->
                            fun f(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                            bucket = f("bucket")
                            obj = f("object")
                            val cbObj = (d["callback"] as? JsonObject)
                                ?: ((d["callback"] as? JsonArray)?.firstOrNull() as? JsonObject)
                            if (cbObj != null) {
                                fun c(k: String) = (cbObj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                                callback = c("callback")
                                callbackVar = c("callback_var")
                            }
                            pickCode = f("pick_code").ifBlank { resumePickCode!! }
                        }
                }
                if (bucket.isNotBlank() && callback.isNotBlank()) {
                    Log.i(TAG, "resume 调度成功 pick=$pickCode（续传）")
                } else {
                    if (canResume) Log.w(TAG, "resume 调度不可用，回退全新 init")
                    Log.i(TAG, "init sha1=$sha1 preSha1=$preSha1 size=$size fileName=$fileName")
                    var initRoot = api.uploadInit(fileName, size, target, sha1, preSha1, null, null, null)
                    loop@ for (round in 0 until 3) {
                        currentCoroutineContext().ensureActive()
                        if (!initRoot.envOk()) error(initRoot.envMsg() ?: "上传初始化失败")
                        val d = initRoot.envData() ?: error("上传初始化响应异常")
                        fun f(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                        when {
                            f("status").toIntOrNull() == 2 -> { // 秒传
                                return@withContext UploadResult(f("file_id"), f("pick_code"), fileName, true)
                            }
                            d["sign_check"] !is JsonNull && f("sign_check").isNotBlank() -> {
                                // 二次认证：读 sign_check 区间（含两端）字节算 SHA1——sign_val 官方要求大写
                                val range = f("sign_check").split("-")
                                val a = range.getOrNull(0)?.toLongOrNull() ?: error("二次认证参数异常")
                                val b = range.getOrNull(1)?.toLongOrNull() ?: error("二次认证参数异常")
                                if (b < a || b >= size) error("二次认证区间越界：$a-$b")
                                val seg = ByteArray((b - a + 1).toInt())
                                fillFromChannel(ch, seg, a)
                                val signVal = MessageDigest.getInstance("SHA-1").digest(seg)
                                    .joinToString("") { "%02X".format(it) }
                                initRoot = api.uploadInit(
                                    fileName, size, target, sha1, preSha1,
                                    f("pick_code"), f("sign_key"), signVal,
                                )
                            }
                            else -> {
                                pickCode = f("pick_code")
                                bucket = f("bucket")
                                obj = f("object")
                                val cbObj = (d["callback"] as? JsonObject)
                                    ?: ((d["callback"] as? JsonArray)?.firstOrNull() as? JsonObject)
                                if (cbObj != null) {
                                    fun c(k: String) = (cbObj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                                    callback = c("callback")
                                    callbackVar = c("callback_var")
                                }
                                break@loop
                            }
                        }
                    }
                }
                if (bucket.isBlank() || obj.isBlank()) {
                    Log.w(TAG, "调度未返回存储位置 pick=$pickCode resume=$canResume callbackBlank=${callback.isBlank()}")
                    error("上传调度未返回存储位置")
                }
                if (callback.isBlank()) error("上传调度未返回回调参数")

                // ---- 3. OSS 分片（ListParts 断点探测 → 跳过已完成片）----
                val partSize = if (size > OSS_PART_SIZE * OSS_MAX_PARTS) {
                    val mb = 1024L * 1024L
                    ((size + OSS_MAX_PARTS - 1) / OSS_MAX_PARTS / mb + 1) * mb
                } else {
                    OSS_PART_SIZE
                }
                val totalParts = ((size + partSize - 1) / partSize).toInt()

                var cred = fetchCred(api)
                var uploadId: String? = resumeId
                var completedParts = LinkedHashMap<Int, String>()
                if (uploadId != null) {
                    // 旧会话探测：200 + 分片尺寸与当前划分一致 → 沿用并跳片；
                    // 404（会话过期）/ 网络失败 / 尺寸不符 → 丢弃旧会话，下面新建。
                    // 旧会话服务端会自行回收（生命周期），不主动 abort 也无害。
                    var keepOld = false
                    try {
                        val (code, parts) = listPartsAll(cred, bucket, obj, uploadId)
                        if (code !in 200..299) {
                            Log.i(TAG, "ListParts HTTP $code，旧分片会话不可用，重建")
                        } else {
                            val consistent = parts.all { p ->
                                p.size == minOf(partSize, size - (p.n - 1L) * partSize)
                            }
                            if (!consistent) {
                                Log.w(TAG, "ListParts 分片尺寸与当前划分不一致（${parts.size} 片），重建会话从头传")
                            } else {
                                parts.forEach { completedParts[it.n] = it.etag }
                                keepOld = true
                                if (parts.isNotEmpty()) {
                                    Log.i(TAG, "断点恢复：ListParts 命中 ${parts.size}/$totalParts 片")
                                } else {
                                    Log.i(TAG, "ListParts 成功：旧会话尚无已完成分片，沿用会话")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "ListParts 探测失败，重建分片会话：${e.message}")
                    }
                    if (!keepOld) {
                        uploadId = null
                        completedParts = LinkedHashMap()
                    }
                }
                if (uploadId == null) uploadId = createSequentialSession(cred, bucket, obj)
                onSession(sha1, pickCode, uploadId)

                val eTags = ArrayList<Pair<Int, String>>(totalParts)
                val partBuf = ByteArray(partSize.toInt())
                var completedBytes = completedParts.keys.sumOf { n ->
                    minOf(partSize, size - (n - 1L) * partSize)
                }
                if (completedBytes > 0) onProgress(completedBytes)
                var restartFromScratch = true
                while (restartFromScratch) {
                    restartFromScratch = false
                    eTags.clear()
                    var acc = 0L
                    for (n in 1..totalParts) {
                        currentCoroutineContext().ensureActive()
                        if (cred.expired()) cred = fetchCred(api)
                        val offset = (n - 1L) * partSize
                        val len = minOf(partSize, size - offset).toInt()
                        val doneEtag = completedParts[n]
                        if (doneEtag != null) {
                            eTags.add(n to doneEtag)
                            continue
                        }
                        // 逐片 PUT：partNumber/uploadId 都是签名子资源（字典序在前）。
                        // 重试只针对传输异常（IOException 类）；非 2xx 是会话级错误，
                        // 重试无意义，直接失败——唯一例外 PartAlreadyExist（顺序分片
                        // 冲突），废弃会话重建从头传（参考 115-plus-desktop oss.rs）。
                        val uid = uploadId ?: error("分片会话 ID 缺失")
                        var etag: String? = null
                        var lastErr: Exception? = null
                        var rebuild = false
                        for (attempt in 1..3) {
                            try {
                                val resp = ossRequest(
                                    cred, "PUT", bucket, "$obj?partNumber=$n&uploadId=$uid",
                                    contentType = "", body = partBuf, bodyLen = len,
                                    extraHeaders = listOf("x-oss-security-token" to cred.token),
                                    canonResource = "/$bucket/$obj?partNumber=$n&uploadId=$uid",
                                )
                                if (resp.code in 200..299) {
                                    etag = resp.etag?.trim()
                                    break
                                }
                                if (resp.code == 409 && resp.body.contains("PartAlreadyExist")) {
                                    rebuild = true
                                    break
                                }
                                lastErr = IllegalStateException("分片 $n 上传失败 HTTP ${resp.code}：${resp.body.take(600)}")
                                break
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                lastErr = e
                            }
                        }
                        if (rebuild) {
                            Log.w(TAG, "分片 $n 顺序冲突，废弃会话重建从头传")
                            runCatching {
                                ossRequest(
                                    cred, "DELETE", bucket, "$obj?uploadId=$uid",
                                    contentType = "", body = null,
                                    extraHeaders = listOf("x-oss-security-token" to cred.token),
                                    canonResource = "/$bucket/$obj?uploadId=$uid",
                                )
                            }
                            uploadId = createSequentialSession(cred, bucket, obj)
                            onSession(sha1, pickCode, uploadId)
                            completedParts = LinkedHashMap()
                            completedBytes = 0
                            restartFromScratch = true
                            break
                        }
                        if (etag.isNullOrBlank()) error("分片 $n 上传失败：${lastErr?.message ?: "无 ETag"}")
                        eTags.add(n to etag)
                        acc += len
                        onProgress(completedBytes + acc)
                    }
                }
                eTags.sortBy { it.first }
                onProgress(size)

                // ---- 4. complete（携带 callback，115 收到回调才入库）----
                currentCoroutineContext().ensureActive()
                if (cred.expired()) cred = fetchCred(api)
                val xml = buildString {
                    append("<CompleteMultipartUpload>")
                    eTags.forEach { (n, et) ->
                        append("<Part><PartNumber>$n</PartNumber><ETag>$et</ETag></Part>")
                    }
                    append("</CompleteMultipartUpload>")
                }.toByteArray(Charsets.UTF_8)
                val cbB64 = Base64.encodeToString(
                    callback.replace("\${sha1}", sha1).toByteArray(), Base64.NO_WRAP,
                )
                val cbvB64 = Base64.encodeToString(callbackVar.toByteArray(), Base64.NO_WRAP)
                val completeResp = ossRequest(
                    cred, "POST", bucket, "$obj?uploadId=${uploadId ?: error("分片会话 ID 缺失")}",
                    contentType = "application/xml", body = xml,
                    extraHeaders = listOf(
                        "x-oss-callback" to cbB64,
                        "x-oss-callback-var" to cbvB64,
                        "x-oss-security-token" to cred.token,
                    ),
                    canonResource = "/$bucket/$obj?uploadId=$uploadId",
                )
                if (completeResp.code !in 200..299) {
                    // 203 = OSS 收完文件但回调被 115 拒绝，文件实际没入库
                    error("完成分片上传失败 HTTP ${completeResp.code}：${completeResp.body.take(600)}")
                }
                Log.i(TAG, "complete body=${completeResp.body.take(800)}")
                val resp = uploadJson.decodeFromString(
                    ApiEnvelope.serializer(JsonObject.serializer()), completeResp.body,
                )
                if (!resp.state) error(resp.message ?: "上传回调失败")
                val dd = resp.data
                fun g(k: String) = (dd?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                UploadResult(g("file_id"), g("pick_code"), fileName, false)
            }
        }
    }

    // ==================== 断点续传辅助（ListParts / 建会话） ====================

    /** ListParts 返回的单个已完成分片（etag 保留 OSS 原样，与 PUT 响应头一致可直接进 complete XML） */
    private data class ListedPart(val n: Int, val etag: String, val size: Long)

    /** 分页拉取分片会话的全部已完成分片；返回 HTTP code（404 = NoSuchUpload 会话过期） */
    private fun listPartsAll(
        cred: OssCred,
        bucket: String,
        obj: String,
        uploadId: String,
    ): Pair<Int, List<ListedPart>> {
        val out = ArrayList<ListedPart>()
        var marker = 0L
        // OSS 分片上限 10000，1000/页最多 10 页；12 页留余量
        for (page in 0 until 12) {
            // URL query 带全部三个参数；但 V1 签名子资源白名单只含 uploadId——
            // max-parts / part-number-marker 是列举参数不参与签名（aliyun-oss-go-sdk
            // signKeyList 确认），混入签名串 → SignatureDoesNotMatch 403（实测踩过）
            val query = buildString {
                append("?max-parts=1000")
                if (marker > 0) append("&part-number-marker=$marker")
                append("&uploadId=$uploadId")
            }
            val resp = ossRequest(
                cred, "GET", bucket, "$obj$query",
                contentType = "", body = null,
                extraHeaders = listOf("x-oss-security-token" to cred.token),
                canonResource = "/$bucket/$obj?uploadId=$uploadId",
            )
            if (resp.code !in 200..299) return resp.code to emptyList()
            for (m in Regex("<Part>(.*?)</Part>", RegexOption.DOT_MATCHES_ALL).findAll(resp.body)) {
                val seg = m.groupValues[1]
                val n = Regex("<PartNumber>(\\d+)</PartNumber>").find(seg)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val et = Regex("<ETag>(.*?)</ETag>").find(seg)?.groupValues?.get(1)?.trim() ?: continue
                val sz = Regex("<Size>(\\d+)</Size>").find(seg)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                out += ListedPart(n, et, sz)
            }
            if (!resp.body.contains("<IsTruncated>true</IsTruncated>")) break
            marker = Regex("<NextPartNumberMarker>(\\d+)</NextPartNumberMarker>")
                .find(resp.body)?.groupValues?.get(1)?.toLongOrNull() ?: break
        }
        return 200 to out
    }

    /**
     * 建顺序分片会话：POST /{obj}?sequential&uploads（sequential 是 115 特有参数，
     * 在 OSS V1 签名白名单内，必须参与 CanonicalizedResource；多子资源按字典序
     * 拼接且空值不带等号——与 aliyun-oss-go-sdk getSubResource 一致）
     */
    private fun createSequentialSession(cred: OssCred, bucket: String, obj: String): String {
        val initResp = ossRequest(
            cred, "POST", bucket, "$obj?sequential&uploads",
            contentType = "", body = ByteArray(0),
            extraHeaders = listOf("x-oss-security-token" to cred.token),
            canonResource = "/$bucket/$obj?sequential&uploads",
        )
        if (initResp.code !in 200..299) {
            error("创建分片会话失败 HTTP ${initResp.code}：${initResp.body.take(600)}")
        }
        return Regex("<UploadId>(.*?)</UploadId>")
            .find(initResp.body)?.groupValues?.get(1)?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: error("分片会话响应缺少 UploadId：${initResp.body.take(600)}")
    }
}
