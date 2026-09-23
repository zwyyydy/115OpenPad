package com.open115.pad.data.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 媒体库落盘缓存：文本类媒体库数据（目前是 .nfo 的解析结果）按容量上限 LRU 淘汰。
 *
 * 为什么需要：媒体库的元数据虽然落在 Room 里，但**取它的过程**每次都要打接口 ——
 * 重扫一个库时每部片都要重新 downurl + 下载一遍 .nfo，哪怕文件一个字节都没变；
 * 详情页的"按需自愈"更糟：判据是 `plot == null && rating == null`，而 nfo 本来就没写
 * 简介的片，每次进详情页都会重打一次。缓存住之后，内容没变就是零请求。
 * （downurl 是 115 最容易触发频控的接口之一。）
 *
 * **图片字节不在这里**：海报必须是一个裸图片文件（要交给 Coil 按魔数嗅探），而本类要在
 * 文件头写 key/at 两个元数据，两者不兼容。海报仍存在 ImageUrlResolver 的 media_img 下。
 *
 * 布局：`<root>/<sha1(key)>`，文件内容 = `key=<原key>\nat=<毫秒>\n<正文>`。
 * - 文件名用哈希：key 里带 `|` 等字符、而且可能很长，不能直接当文件名。
 * - 头部存**原 key**：磁盘名是哈希，按前缀失效与占用统计只能靠它。
 * - `at` 判 TTL，**mtime 专用于 LRU**。两者混用会出事：每次命中都顶 mtime 的话，
 *   连 TTL 一起被顶松，缓存就永远不会过期了。
 */
class MediaCache(
    private val root: File,
    /** 当前容量上限（字节）。由调用方从设置里读；改小之后调 [sweep] 立即生效 */
    private val maxBytes: () -> Long,
    /**
     * 总开关。关掉 = **不读也不写**（已存的不动，用户想清就点"清空缓存"）。
     * 判在这里而不是各个调用点：调用点有六七个，漏一个就是"关了还在缓存"。
     */
    private val enabled: () -> Boolean = { true },
) {

    /** 一次读取的结果。是否算"过期"由调用方用 [isFresh] 判 —— 各 key 的 TTL 不一样 */
    data class Entry(val at: Long, val body: String)

    private val lock = Mutex()

    /**
     * key → 文件。磁盘名是哈希，所以前缀失效与占用统计都只能靠这份索引。
     * 懒加载：首次用到时扫一遍文件头建立，之后随增删维护。
     */
    private var index: MutableMap<String, File>? = null

    /** 攒够这么多新字节才扫一次目录（每次写入都全量扫目录在大缓存下很贵） */
    private val addedSinceSweep = AtomicLong(0)

    /** 是否还在新鲜期内 */
    fun isFresh(entry: Entry, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.at < ttlMs

    /**
     * 读文本。**不存在或文件损坏返回 null**（损坏的顺手删掉——它永远不会被重写，
     * 留着只会每次读都失败一次）。不判过期，过期与否交给调用方。
     */
    suspend fun getText(key: String): Entry? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        val file = lock.withLock { ensureIndexLocked()[key] } ?: return@withContext null
        val parsed = runCatching {
            file.bufferedReader().use { r ->
                val keyLine = r.readLine() ?: return@use null
                val atLine = r.readLine() ?: return@use null
                if (!keyLine.startsWith(HEAD_KEY) || !atLine.startsWith(HEAD_AT)) return@use null
                val at = atLine.removePrefix(HEAD_AT).toLongOrNull() ?: return@use null
                Entry(at = at, body = r.readText())
            }
        }.getOrNull()

        if (parsed == null) {
            Log.w(TAG, "缓存文件头部异常，丢弃并重取：${file.name}")
            file.delete()
            lock.withLock { index?.remove(key) }
            return@withContext null
        }

        // 命中且 mtime 够旧才顶：每次命中都 touch 是拿磁盘 IO 换空气，
        // 但完全不顶的话淘汰就退化成"按存入时间先进先出"，常看的条目照样被淘汰。
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_AFTER_MS) file.setLastModified(now)
        parsed
    }

    /** 写文本（先写临时文件再改名，见下） */
    suspend fun putText(key: String, body: String) = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext
        if (!root.exists()) root.mkdirs()
        val target = lock.withLock { ensureIndexLocked(); fileOf(key) }
        // 临时名带纳秒后缀：同一个 key 并发写（扫描与详情页自愈可能同时跑）时不会
        // 往同一个临时文件里交错写。谁最后改名成功谁算数，不需要为此加锁。
        val tmp = File(root, "${target.name}.${System.nanoTime()}$TMP_SUFFIX")
        val now = System.currentTimeMillis()
        runCatching {
            tmp.bufferedWriter().use { w ->
                w.write(HEAD_KEY); w.write(key); w.newLine()
                w.write(HEAD_AT); w.write(now.toString()); w.newLine()
                w.write(body)
            }
            // 先写临时文件再改名：写到一半被杀不会留下"半个文件"被后续读取当成有效缓存。
            // 那种坏文件永远不会被重下，这条数据会永久缺失。
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            Log.w(TAG, "写缓存失败 key=$key: ${it.message}")
            tmp.delete()
            return@withContext
        }
        lock.withLock { index?.put(key, target) }
        // 淘汰失败不能连累调用方：缓存是优化，它挂了扫描照样得跑完
        if (addedSinceSweep.addAndGet(target.length()) >= SWEEP_AFTER_BYTES) {
            runCatching { sweep() }.onFailure { Log.w(TAG, "缓存淘汰失败: ${it.message}") }
        }
    }

    /**
     * 按前缀失效。**粗粒度是刻意的**：精确失效要维护 cid ↔ key 的反向索引，
     * 而写操作本来就不频繁，多清一点只是多一次网络往返。
     */
    suspend fun invalidate(keyPrefix: String) = withContext(Dispatchers.IO) {
        val doomed = lock.withLock {
            val idx = ensureIndexLocked()
            idx.keys.filter { it.startsWith(keyPrefix) }.also { keys ->
                keys.forEach { idx.remove(it) }
            }
        }
        doomed.forEach { fileOf(it).delete() }
    }

    /** 清空（登出、设置页"清空缓存"）。下次用到会重新下载 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            index = null
            addedSinceSweep.set(0)
        }
        root.listFiles()?.forEach { it.delete() }
    }

    /** 当前占用（含头部字节） */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        lock.withLock { ensureIndexLocked() }
            .values
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    /**
     * 按容量上限淘汰：先按 mtime 从旧到新删到 90%。
     *
     * 留 10% 余量而不是刚好删到上限：不然每次写入都会立刻触发一次全量扫目录。
     */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        val limit = maxBytes()
        val files = lock.withLock { ensureIndexLocked() }.entries
            .filter { it.value.exists() }
            .map { it.key to it.value }

        var total = files.sumOf { (_, f) -> f.length() }
        addedSinceSweep.set(0)
        // 上限 <= 0 视为"不限"（与本项目其它地方对 0 的约定一致），不是"删光"
        if (limit <= 0 || total <= limit) return@withContext

        val target = (limit * 9 / 10).coerceAtLeast(0)
        var removed = 0
        for ((key, file) in files.sortedBy { (_, f) -> f.lastModified() }) {
            if (total <= target) break
            val len = file.length()
            if (file.delete()) {
                total -= len
                removed++
                lock.withLock { index?.remove(key) }
            }
        }
        if (removed > 0) {
            Log.i(TAG, "媒体库缓存淘汰 $removed 个文件，${total / 1024} KB / 上限 ${limit / 1024} KB")
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 建索引（懒加载，只扫一次）。**必须在持有 [lock] 时调用**。
     * 头部读不出来（上次写到一半、或文件被外部改坏）的直接删掉。
     */
    private fun ensureIndexLocked(): MutableMap<String, File> {
        index?.let { return it }
        val built = HashMap<String, File>()
        root.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.endsWith(TMP_SUFFIX)) return@forEach
            val key = readKeyOnly(f)
            if (key == null) f.delete() else built[key] = f
        }
        index = built
        return built
    }

    /** 只读第一行拿 key，不把正文读进内存 */
    private fun readKeyOnly(file: File): String? = runCatching {
        file.bufferedReader().use { r ->
            val line = r.readLine() ?: return@use null
            if (!line.startsWith(HEAD_KEY)) return@use null
            line.removePrefix(HEAD_KEY)
        }
    }.getOrNull()

    private fun fileOf(key: String): File = File(root, sha1Hex(key))

    private companion object {
        const val TAG = "MediaCache"

        const val HEAD_KEY = "key="
        const val HEAD_AT = "at="
        const val TMP_SUFFIX = ".tmp"

        /** 攒够这么多新写入才扫一次目录做淘汰 */
        const val SWEEP_AFTER_BYTES = 16L * 1024 * 1024

        /** 命中且 mtime 比这还旧才顶 —— 太频繁地改 mtime 是拿磁盘 IO 换空气 */
        const val TOUCH_AFTER_MS = 24L * 60 * 60 * 1000

        fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
