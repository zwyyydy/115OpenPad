package com.open115.pad.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 图片直链解析：三级降级链 + downurl 防频控缓存。
 *
 * ① 首选直读：列表自带的 uo（原图）有效就直接用，零额外请求
 * ② 兜底解析：uo 为空 → POST /open/ufile/downurl（表单 pick_code）
 *    注意返回是特殊的 map：data.<fid>.url.url 才是直链，
 *    且 url 字段有时是对象、有时直接就是字符串（两种都要兼容）
 * ③ 最后回退：解析失败退回 thumb —— 宁可糊，也不要白屏
 *
 * downurl 是最容易触发 115 频控的接口之一，所以：
 * - 解析结果放 LRU（上限 128 条），签名有效期内同一文件绝不重复调用
 * - 同一文件的并发解析合并成一次，后到者直接等先到者的结果
 */
class ImageUrlResolver(
    private val api: OpenApi,
    /** 大图分块解码需要先把原图字节拉到本地，统一走带鉴权链的客户端 */
    private val client: OkHttpClient,
) {

    private data class Entry(val at: Long, val url: String)

    /** 等待中的解析：owner=true 表示由自己发起请求，false 表示等别人的结果 */
    private class PendingSlot {
        var owner = false
        val deferred = CompletableDeferred<String?>()
    }

    private val lock = Mutex()
    private val lru = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_ENTRIES
    }
    private val inFlight = HashMap<String, PendingSlot>()

    /**
     * 解析可直接交给图片加载器的原图地址。
     * 永不抛异常：失败时退回缩略图（宁糊勿白屏）。
     */
    suspend fun resolveOrigin(item: ImageMediaItem): String? {
        // ① 列表自带的原图直链：零成本，优先
        item.originUrl?.takeIf { it.isNotBlank() }?.let { return it }

        val key = item.pickCode ?: return item.thumbnailUrl
        // ② downurl 解析（缓存 + 并发合并），失败 ③ 回退缩略图
        return resolveFromDownUrl(key) ?: item.thumbnailUrl
    }

    /**
     * 严格版解析（失败抛异常，带服务端 message）。
     * 给"下载到本机"这类需要明确报错的场景用。
     */
    suspend fun downloadUrl(pickCode: String): String {
        val url = parseDownUrl(pickCode)
        cache(pickCode, url)
        return url
    }

    /**
     * 批量解析直链（文档 §25：pick_code 支持逗号分隔多个）。
     * 用于画廊翻页时预取相邻图片：把多次 downurl 合成一次，降低频控压力。
     * 结果写入 LRU；失败的条目静默跳过（预取失败不影响展示路径，resolveOrigin 会兜底）。
     */
    suspend fun prefetch(pickCodes: List<String>) {
        val missing = lock.withLock {
            pickCodes.filter { it.isNotBlank() }.distinct().filter { code ->
                lru[code]?.let { now() - it.at < SIGN_TTL_MS } != true
            }
        }
        if (missing.isEmpty()) return
        runCatching {
            val root = api.downUrl(missing.joinToString(","))
            val data = root.envData() ?: return
            for ((_, entry) in data) {
                val o = entry as? JsonObject ?: continue
                val pc = (o["pick_code"] as? JsonPrimitive)?.content ?: continue
                val el = o["url"]
                val url = when (el) {
                    is JsonObject -> (el["url"] as? JsonPrimitive)?.content
                    is JsonPrimitive -> el.content
                    else -> null
                } ?: continue
                cache(pc, url)
            }
        }
    }

    private suspend fun resolveFromDownUrl(key: String): String? {
        val slot = lock.withLock {
            lru[key]?.let { e ->
                if (now() - e.at < SIGN_TTL_MS) return e.url
                lru.remove(key) // 签名过期：当成没有
            }
            inFlight.getOrPut(key) {
                PendingSlot().also { it.owner = true }
            }
        }
        // 不是发起者：等先到的那次解析结束，绝不再发一次请求
        if (!slot.owner) return slot.deferred.await()

        val url = runCatching { parseDownUrl(key) }.getOrNull()
        lock.withLock {
            url?.let { lru[key] = Entry(now(), it) }
            inFlight.remove(key)
        }
        slot.deferred.complete(url)
        return url
    }

    private suspend fun parseDownUrl(pickCode: String): String {
        val root = api.downUrl(pickCode)
        val data = root.envData() ?: error(root.envMsg() ?: "获取下载地址失败")
        val entry = data.values.firstOrNull() as? JsonObject ?: error("下载地址响应格式异常")
        val el = entry["url"]
        return when (el) {
            is JsonObject -> (el["url"] as? JsonPrimitive)?.content
            is JsonPrimitive -> el.content
            else -> null
        } ?: error("未获取到下载地址")
    }

    private suspend fun cache(pickCode: String, url: String) {
        lock.withLock { lru[pickCode] = Entry(now(), url) }
    }

    /**
     * 把某个 URL 的内容下载到缓存目录（超大图分块解码需要本地文件）。
     * 走的是与 API 相同的 OkHttpClient，鉴权 / 401 重试链一致；同 URL 复用同一份文件。
     */
    suspend fun fetchToCache(url: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "huge_img").apply { mkdirs() }
        val target = File(dir, "img_${url.hashCode()}.bin")
        if (!target.exists() || target.length() == 0L) {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: error("空响应")
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        target
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** LRU 上限：一次浏览相册通常几十张，128 足够且内存开销可忽略（只是字符串） */
        const val MAX_ENTRIES = 128

        /** 直链签名有效期按 30 分钟算，留 10 分钟余量避免拿到临期链接 */
        const val SIGN_TTL_MS = 20L * 60 * 1000
    }
}
