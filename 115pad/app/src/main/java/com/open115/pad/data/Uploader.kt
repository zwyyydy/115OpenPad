package com.open115.pad.data

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
     */
    suspend fun uploadLargeLogged(
        log: TransferLog,
        api: OpenApi,
        fileName: String,
        pfd: ParcelFileDescriptor,
        size: Long,
        targetCid: String,
        targetName: String?,
    ): UploadResult {
        val id = log.beginUpload(fileName, size, targetCid, targetName)
        var lastPct = -1L
        return try {
            val r = uploadLarge(api, fileName, pfd, size, target = "U_1_$targetCid") { uploaded ->
                val pct = uploaded * 100 / size
                if (pct != lastPct) {
                    lastPct = pct
                    log.updateUploadProgress(id, uploaded)
                }
            }
            log.finishUpload(id, ok = true, reused = r.reused)
            r
        } catch (e: Exception) {
            log.finishUpload(id, ok = false, error = e.message)
            throw e
        }
    }

    /**
     * 大文件分片上传（流式，不占内存，无大小上限）：
     * 1. 全量 SHA1 + 前 128K preid（流式哈希，两次哈希一遍读完成）
     * 2. init 协商（秒传判定 / sign_check 二次认证，协议与 uploadSmall 完全一致，
     *    区间读取走 FileChannel 而非内存切片）
     * 3. OSS 分片：POST ?uploads 建会话 → 逐片 PUT ?partNumber&uploadId →
     *    POST ?uploadId complete（携带 callback/callback_var，115 回调入库）
     *
     * 取消：每个分片边界 ensureActive()（协程取消即停，OSS 会话交给服务端过期回收）
     * 进度：每片完成回调一次已上传字节
     * STS：提前 5 分钟判定过期，每片开传前检查，过期自动换新凭证——OSS uploadId
     *      与 STS 凭证无关，换证不破坏会话
     * 未做（后续迭代）：断点续传（oss_upload_id 落库 + resume/ListParts）、
     *      单片失败的会话级重试，当前失败即整任务重传
     */
    suspend fun uploadLarge(
        api: OpenApi,
        fileName: String,
        pfd: ParcelFileDescriptor,
        size: Long,
        target: String = "U_1_0",
        onProgress: suspend (Long) -> Unit = {},
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

                // ---- 2. init 协商（秒传 / 二次认证 / 拿 bucket+object+callback）----
                Log.i(TAG, "init sha1=$sha1 preSha1=$preSha1 size=$size fileName=$fileName")
                var initRoot = api.uploadInit(fileName, size, target, sha1, preSha1, null, null, null)
                Log.i(TAG, "init resp=${initRoot.toString().take(1500)}")
                var bucket = ""
                var obj = ""
                var callback = ""
                var callbackVar = ""
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
                if (bucket.isBlank() || obj.isBlank()) {
                    Log.w(TAG, "init 响应未含存储位置 resp=${initRoot.toString().take(600)}")
                    error("上传调度未返回存储位置")
                }
                if (callback.isBlank()) error("上传调度未返回回调参数")

                // ---- 3. OSS 分片 ----
                val partSize = if (size > OSS_PART_SIZE * OSS_MAX_PARTS) {
                    val mb = 1024L * 1024L
                    ((size + OSS_MAX_PARTS - 1) / OSS_MAX_PARTS / mb + 1) * mb
                } else {
                    OSS_PART_SIZE
                }
                val totalParts = ((size + partSize - 1) / partSize).toInt()

                var cred = fetchCred(api)
                // 建分片会话：POST /{obj}?sequential&uploads（sequential 是 115 特有参数，
                // 在 OSS V1 签名白名单内，必须参与 CanonicalizedResource；多子资源按
                // 字典序拼接且空值不带等号——与 aliyun-oss-go-sdk getSubResource 一致）
                val initResp = ossRequest(
                    cred, "POST", bucket, "$obj?sequential&uploads",
                    contentType = "", body = ByteArray(0),
                    extraHeaders = listOf("x-oss-security-token" to cred.token),
                    canonResource = "/$bucket/$obj?sequential&uploads",
                )
                if (initResp.code !in 200..299) {
                    error("创建分片会话失败 HTTP ${initResp.code}：${initResp.body.take(600)}")
                }
                val uploadId = Regex("<UploadId>(.*?)</UploadId>")
                    .find(initResp.body)?.groupValues?.get(1)?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: error("分片会话响应缺少 UploadId：${initResp.body.take(600)}")

                val eTags = ArrayList<Pair<Int, String>>(totalParts)
                val partBuf = ByteArray(partSize.toInt())
                var uploaded = 0L
                for (n in 1..totalParts) {
                    currentCoroutineContext().ensureActive()
                    if (cred.expired()) cred = fetchCred(api)
                    val offset = (n - 1L) * partSize
                    val len = minOf(partSize, size - offset).toInt()
                    fillFromChannel(ch, partBuf, offset, len)
                    // 逐片 PUT：partNumber/uploadId 都是签名子资源（字典序在前）。
                    // 重试只针对传输异常（IOException 类）；非 2xx 是会话级错误，
                    // 重试无意义，直接失败。
                    var etag: String? = null
                    var lastErr: Exception? = null
                    for (attempt in 1..3) {
                        try {
                            val resp = ossRequest(
                                cred, "PUT", bucket, "$obj?partNumber=$n&uploadId=$uploadId",
                                contentType = "", body = partBuf, bodyLen = len,
                                extraHeaders = listOf("x-oss-security-token" to cred.token),
                                canonResource = "/$bucket/$obj?partNumber=$n&uploadId=$uploadId",
                            )
                            if (resp.code in 200..299) {
                                etag = resp.etag?.trim()
                                break
                            }
                            lastErr = IllegalStateException("分片 $n 上传失败 HTTP ${resp.code}：${resp.body.take(600)}")
                            break
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            lastErr = e
                        }
                    }
                    if (etag.isNullOrBlank()) error("分片 $n 上传失败：${lastErr?.message ?: "无 ETag"}")
                    eTags.add(n to etag)
                    uploaded += len
                    onProgress(uploaded)
                }

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
                    cred, "POST", bucket, "$obj?uploadId=$uploadId",
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
}
