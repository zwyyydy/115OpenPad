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
    /**
     * 媒体库海报/背景图缓存的上限（字节）与总开关，由容器从设置里读。
     * 做成 lambda 而不是构造时取一次值：设置改完要**立刻生效**，不能等重启。
     */
    private val mediaCacheMaxBytes: () -> Long = { MEDIA_CACHE_MAX_BYTES },
    private val mediaCacheEnabled: () -> Boolean = { true },
) {

    private data class Entry(val at: Long, val url: String)

    /** 等待中的解析：owner=true 表示由自己发起请求，false 表示等别人的结果 */
    private class PendingSlot {
        var owner = false
        val deferred = CompletableDeferred<String?>()
    }

    private val lock = Mutex()

    /** 串行化「取字节落盘」（见 fetchToCache 的注释：稳定 key 让同一张图的多个 URL 指向同一文件） */
    private val fetchLock = Mutex()

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
     * 走的是与 API 相同的 OkHttpClient，鉴权 / 401 重试链一致。
     *
     * [stableKey] 必须是**与 URL 签名无关**的稳定标识（用 pick_code / fid，别用 URL）：
     * 115 的原图直链带签名、每次列表响应都会变，早先用 `url.hashCode()` 命名文件，
     * 于是同一张图每重新解析一次就被当成新文件重下一遍——实测攒了 5 份字节数完全相同的
     * 文件、白占 103MB。换成稳定 key 后同一张图永远命中同一份缓存。
     */
    suspend fun fetchToCache(stableKey: String, url: String, cacheDir: File): File =
        fetchBytesToCache(stableKey, url, cacheDir, HUGE_DIR, HUGE_CACHE_MAX_BYTES)

    /**
     * 海报/背景图落盘（媒体库专用目录与容量）。
     * 与 fetchToCache 同一套"稳定 key + 先写临时再改名"的规则，只是目录分开：
     * 海报是常看常新的（海报墙每次进库都加载），和"超大图偶尔回看"分开计量互不挤占。
     */
    suspend fun fetchPosterToCache(stableKey: String, url: String, cacheDir: File): File =
        fetchBytesToCache(stableKey, url, cacheDir, MEDIA_DIR, mediaCacheMaxBytes())

    /**
     * 媒体库图片（海报/背景）的统一加载入口，返回可直接交给图片加载器的 model。
     *
     * ★★ **先查本地字节，命中就返回本地路径，连直链都不解析。**
     * 顺序反了会白打接口：早先是先 resolveOrigin 再 fetchPosterToCache，而直链 LRU
     * 只在内存、冷启动必 miss —— 于是字节明明已经躺在磁盘上，每次冷启动进海报墙
     * 还是要为**每一张**图打一次 downurl（滚一遍 200 部片 = 200 次，每次冷启动重来）。
     * downurl 是 115 最容易触发频控的接口，这是媒体库侧最大的一处浪费。
     *
     * 返回三态：
     *  - 命中 / 下载成功 → 本地文件路径（Coil 读本地文件，零网络）
     *  - 没缓存且落盘失败 → 直链（让 Coil 自己去取，宁慢勿白屏）
     *  - 连直链都拿不到 → null（调用方留占位底）
     *
     * 缓存关掉时**既不读本地也不落盘**，直接给直链，交给 Coil 自己的缓存去管。
     */
    suspend fun posterFor(pickCode: String?, cacheDir: File): Any? {
        if (pickCode.isNullOrBlank()) return null
        val enabled = mediaCacheEnabled()
        if (enabled) {
            cachedPoster(pickCode, cacheDir)?.let { return it.absolutePath }
        }
        val direct = resolveFromDownUrl(pickCode) ?: return null
        if (!enabled) return direct
        return runCatching { fetchPosterToCache(pickCode, direct, cacheDir).absolutePath }
            .getOrDefault(direct)
    }

    /**
     * 这张图是不是已经落在本地了。
     *
     * 给扫描期"预取海报"用：命中就不必去解析直链，也就**不必占一次限速等待** ——
     * 分集继承系列海报时，一季 36 集查的是同一张图，每集白等 500ms 就是 18 秒。
     * 顺带把 mtime 顶一下（同 [cachedPoster]）。
     */
    suspend fun hasCachedPoster(pickCode: String?, cacheDir: File): Boolean {
        if (pickCode.isNullOrBlank() || !mediaCacheEnabled()) return false
        return cachedPoster(pickCode, cacheDir) != null
    }

    /**
     * 已落盘的海报文件；命中时把 mtime 顶到现在。**调用方负责判缓存开关**。
     *
     * 顶 mtime 不能省：pruneCache 的"最久未用"就是靠它判断的，而走了本方法的调用方
     * **不会再经过 fetchBytesToCache 的复用分支** —— 不在这里顶，常看的海报反而会被
     * 当成最旧的先淘汰掉。
     */
    private suspend fun cachedPoster(pickCode: String, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val target = targetOf(File(cacheDir, MEDIA_DIR), pickCode)
        if (!target.exists() || target.length() <= 0L) return@withContext null
        target.setLastModified(System.currentTimeMillis())
        target
    }

    /** 落盘目标文件：命名规则的**唯一来源**，cachedPoster 与 fetchBytesToCache 必须一致 */
    private fun targetOf(dir: File, stableKey: String): File =
        File(dir, "img_${stableKey.hashCode()}.bin")

    /**
     * 按当前上限立刻淘汰海报目录。
     *
     * pruneCache 平时只在写入后跑，所以设置里把上限调小时必须显式调一次 ——
     * 否则占用会一直超着，直到用户下次下载一张新海报才降下来。
     */
    suspend fun sweepPosterCache(cacheDir: File) = withContext(Dispatchers.IO) {
        pruneCache(File(cacheDir, MEDIA_DIR), mediaCacheMaxBytes())
    }

    /**
     * 删掉这些 pick_code 对应的海报/背景图落盘文件（删媒体库时清该库的缓存）。
     *
     * 只删文件、不动直链 LRU：那里面只是字符串、且 20 分钟就过期，留着不影响正确性。
     * 文件名走 [targetOf]，和 cachedPoster/fetchBytesToCache 共用同一份命名规则 ——
     * 这里各写一遍的话，哪天命名规则改了就会静默删不掉。
     */
    suspend fun evictPosterCache(pickCodes: Collection<String>, cacheDir: File) = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, MEDIA_DIR)
        pickCodes.filter { it.isNotBlank() }.distinct().forEach { targetOf(dir, it).delete() }
    }

    private suspend fun fetchBytesToCache(stableKey: String, url: String, cacheDir: File, dirName: String, maxBytes: Long): File =
        // 加了稳定 key 之后，同一张图的**不同 URL**会指向同一个文件（以前是不同文件、互不干扰），
        // 所以这里必须串行化，否则两次并发下载会往同一个文件里交错写。
        // 实际只有一个当前页在下载，串行化的开销可以忽略。
        fetchLock.withLock {
            withContext(Dispatchers.IO) {
                val dir = File(cacheDir, dirName).apply { mkdirs() }
                val target = targetOf(dir, stableKey)
                if (target.exists() && target.length() > 0L) {
                    // 复用时把 mtime 顶到现在：pruneCache 的"最久未用"就是靠它判断的
                    target.setLastModified(System.currentTimeMillis())
                } else {
                    val tmp = File(dir, "${target.name}.tmp")
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        val body = resp.body ?: error("空响应")
                        body.byteStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    // 先写临时文件再改名：下载中途被杀不会留下"半个文件"被当成有效缓存。
                    // 那种坏文件永远不会被重下（exists() && length>0 就复用），这张图会永久打不开。
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
                pruneCache(dir, maxBytes)
                target
            }
        }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** LRU 上限：一次浏览相册通常几十张，128 足够且内存开销可忽略（只是字符串） */
        private const val MAX_ENTRIES = 128

        /** 直链签名有效期按 30 分钟算，留 10 分钟余量避免拿到临期链接 */
        private const val SIGN_TTL_MS = 20L * 60 * 1000

        /** 大图落盘目录名 */
        private const val HUGE_DIR = "huge_img"

        /** 海报/背景图落盘目录名（媒体库：进库即加载，常看常新） */
        const val MEDIA_DIR = "media_img"

        /**
         * 海报落盘目录的体积上限：竖版海报一张多则 1-2MB，500 张海报也就几百 MB。
         * 超了按"最久未用"淘汰，与 huge_img 同一套规则。
         */
        const val MEDIA_CACHE_MAX_BYTES = 500L * 1024 * 1024

        /**
         * 大图落盘目录的体积上限，超了就按"最久未用"淘汰旧文件。
         * 该目录只放 >10MB 的超大图，200MB 大约十来张——够回看，又不至于把用户空间吃光。
         * （改造前没有任何清理，一周就攒到 103MB，而且其中大部分是同一张图的重复副本。）
         */
        const val HUGE_CACHE_MAX_BYTES = 200L * 1024 * 1024

        /** 大图落盘目录：fetchToCache 与设置页的"清除大图缓存"共用同一份定义 */
        fun hugeCacheDir(cacheDir: File): File = File(cacheDir, HUGE_DIR)

        fun hugeCacheSizeBytes(cacheDir: File): Long =
            hugeCacheDir(cacheDir).listFiles()?.sumOf { it.length() } ?: 0L

        /** 清空大图落盘目录。删不掉的文件会留在原地，调用方重新读一次 size 即可发现 */
        fun clearHugeCache(cacheDir: File) {
            hugeCacheDir(cacheDir).listFiles()?.forEach { it.delete() }
        }

        /** 海报落盘目录：媒体页与设置页（若加"清海报缓存"）共用同一份定义 */
        fun mediaCacheDir(cacheDir: File): File = File(cacheDir, MEDIA_DIR)

        fun mediaCacheSizeBytes(cacheDir: File): Long =
            mediaCacheDir(cacheDir).listFiles()?.sumOf { it.length() } ?: 0L

        /** 清空海报落盘目录（设置页清理入口用；下次进海报墙会重新解析+下载） */
        fun clearMediaCache(cacheDir: File) {
            mediaCacheDir(cacheDir).listFiles()?.forEach { it.delete() }
        }

        /** 按"最久未用"把目录压回上限以内（mtime 兼作最近使用时间，由 fetchBytesToCache 维护） */
        private fun pruneCache(dir: File, maxBytes: Long) {
            runCatching {
                val files = dir.listFiles()?.filter { it.isFile } ?: return
                var total = files.sumOf { it.length() }
                if (total <= maxBytes) return
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= maxBytes) break
                    val len = f.length()
                    if (f.delete()) total -= len
                }
            }
        }
    }
}
