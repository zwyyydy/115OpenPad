package com.open115.pad.data

import com.open115.pad.util.AppLog as Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.concurrent.thread

/**
 * 目录列表缓存：进程级 LRU + **落盘**。
 *
 * 为什么需要它：文件列表原本**零缓存**——每进/出一个目录、每次改排序筛选都是一次真实
 * 网络往返（115 有频控，快速点面包屑有被限流的风险）。而且 [com.open115.pad.ui.files.FilesViewModel.popDir]
 * 会把 items 换成第一页，listScroll[cid] 却记着旧偏移，两者对不上就会"返回上级位置错乱"。
 * 按 cid 缓存之后，这两件事一起解决。
 *
 * 存的是**服务端返回的原始条目**，不是 display：
 * display 是"过滤 + 置顶重排"派生出来的本地结果，会随开关变化，存它就会和重算逻辑打架。
 *
 * **落盘**（[store] 非空时）：早先是刻意纯内存的（怕"启动时列表是旧的"），后来用户明确要
 * 反过来——冷启动先显示上一份目录、后台再静默刷新，空转圈的首页才是最丑的。落盘之后
 * 冷启动天然继承"命中过期缓存 → 先渲染再刷新"的那条路（[ttlMs] 早已过期），旧的体感问题
 * 由后台刷新兜住。写入是防抖的（2 秒合一次），进程被杀丢最后一批可接受——它只是缓存。
 */
class DirCache(
    /** 缓存目录数上限（按"目录 × 查询参数组合"计），LRU 淘汰最久未用的 */
    private val maxEntries: Int = 24,
    /** 新鲜期：TTL 内直接用缓存、连后台请求都省掉；过期则先渲染再静默刷新 */
    private val ttlMs: Long = 60_000,
    /** 落盘文件（null = 纯内存，单测用）。冷启动后台线程从这恢复上一份缓存 */
    private val store: File? = null,
) {

    /** 一次目录请求的结果 */
    @Serializable
    data class Entry(
        val items: List<FileItem>,
        val count: Long,
        val at: Long,
        /**
         * 这一层的真实祖先链（列目录响应里的 `path[]`，见 [PathNode]）。
         *
         * 存它是因为：按 cid 跳转（操作记录 / 快捷目录 / 云下载 / 离线任务）时只有一个 cid，
         * 祖先只能从响应里拿；而**命中新鲜缓存时根本不会发请求**，不存这里就会"跳回去只有两级、
         * 跳一次新的才有完整链"——时好时坏比不做还难解释。
         * 老条目 / 搜索没有它就是空的，调用方按"没有祖先信息"处理即可。
         */
        val path: List<PathNode> = emptyList(),
    )

    @Serializable
    private data class PersistedEntry(val key: String, val entry: Entry)

    @Serializable
    private data class Persisted(val v: Int = PERSIST_VERSION, val entries: List<PersistedEntry> = emptyList())

    // accessOrder = true：连读取也会把条目挪到队尾，淘汰时先丢最久没用过的
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 落盘去抖：一个待写标记 + 一条写线程，2 秒内的多次 put 合成一次写 */
    private val flushGate = Object()
    private var flushPending = false

    init {
        if (store != null) {
            // 守护线程：读失败（文件损坏 / 升级格式变了）就当没有，缓存从头攒
            thread(name = "DirCache-load", isDaemon = true) { loadFromDisk() }
        }
    }

    @Synchronized
    private fun loadFromDisk() {
        val f = store ?: return
        if (!f.isFile) return
        runCatching {
            val persisted = json.decodeFromString<Persisted>(f.readText())
            if (persisted.v != PERSIST_VERSION) return
            var restored = 0
            for (e in persisted.entries) {
                // 内存里已有的（本轮进程已经真请求过的）更新，不拿盘上的旧货覆盖
                if (map.containsKey(e.key)) continue
                map[e.key] = e.entry
                restored++
            }
            // 盘上的份数可能超过现在的上限（调小过 maxEntries）：从最久没用的那头淘汰
            while (map.size > maxEntries) {
                map.remove(map.keys.first())
            }
            if (restored > 0) Log.i(TAG, "目录缓存从盘上恢复 $restored 个目录（过期条目进目录时先渲染再刷新）")
        }.onFailure { Log.w(TAG, "目录缓存读取失败（当没有）：${it.message}") }
    }

    private fun scheduleFlush() {
        if (store == null) return
        synchronized(flushGate) {
            if (flushPending) return
            flushPending = true
        }
        thread(name = "DirCache-flush", isDaemon = true) {
            try {
                Thread.sleep(FLUSH_DEBOUNCE_MS)
            } catch (_: InterruptedException) {
            }
            synchronized(flushGate) { flushPending = false }
            writeNow()
        }
    }

    private fun writeNow() {
        val f = store ?: return
        val snapshot = synchronized(this) { map.entries.map { PersistedEntry(it.key, it.value) } }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            if (snapshot.isEmpty()) {
                tmp.delete()
                f.delete()
            } else {
                tmp.writeText(json.encodeToString(Persisted(entries = snapshot)))
                if (f.exists()) f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "目录缓存落盘失败：${it.message}") }
    }

    /**
     * 缓存 key。**必须带上查询参数**：o / asc / type / star 任何一个不同，服务端返回的
     * 就是另一份列表；只用 cid 当 key 会让"改了排序却拿到旧排序"这种 bug 极难排查。
     *
     * offset 不进 key——它就是"已加载到第几条"，等于 [Entry.items] 的长度。
     */
    fun key(cid: String, order: String, asc: Int, type: Int?, star: Boolean): String =
        "$cid|$order|$asc|${type ?: -1}|${if (star) 1 else 0}"

    @Synchronized
    fun get(key: String): Entry? = map[key]

    /** 是否还在新鲜期内 */
    @Synchronized
    fun isFresh(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.at < ttlMs

    @Synchronized
    fun put(key: String, entry: Entry) {
        map[key] = entry
        scheduleFlush()
    }

    /**
     * 精确失效某个目录：前缀匹配会把该 cid 下**所有排序/筛选组合**一起清掉。
     * 移动、复制的目标目录靠它失效——当前目录由随后的强刷顺带更新，不用另说。
     */
    @Synchronized
    fun invalidateDir(cid: String) {
        if (map.keys.removeAll { it.startsWith("$cid|") }) scheduleFlush()
    }

    @Synchronized
    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        scheduleFlush()
    }

    companion object {
        private const val TAG = "DirCache"

        /** 落盘格式版本：条目结构变了就 +1，旧文件直接当没有 */
        private const val PERSIST_VERSION = 1

        private const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
