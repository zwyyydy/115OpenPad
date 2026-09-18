package com.open115.pad.data

/**
 * 目录列表缓存：进程级、纯内存、LRU。
 *
 * 为什么需要它：文件列表原本**零缓存**——每进/出一个目录、每次改排序筛选都是一次真实
 * 网络往返（115 有频控，快速点面包屑有被限流的风险）。而且 [com.open115.pad.ui.files.FilesViewModel.popDir]
 * 会把 items 换成第一页，listScroll[cid] 却记着旧偏移，两者对不上就会"返回上级位置错乱"。
 * 按 cid 缓存之后，这两件事一起解决。
 *
 * 存的是**服务端返回的原始条目**，不是 display：
 * display 是"过滤 + 置顶重排"派生出来的本地结果，会随开关变化，存它就会和重算逻辑打架。
 *
 * 刻意**不落盘**：DataStore 里已经持久化了排序/筛选这些**状态**，数据再落盘会引入
 * "启动时列表是旧的"这类体感问题，收益不值。
 */
class DirCache(
    /** 缓存目录数上限（按"目录 × 查询参数组合"计），LRU 淘汰最久未用的 */
    private val maxEntries: Int = 24,
    /** 新鲜期：TTL 内直接用缓存、连后台请求都省掉；过期则先渲染再静默刷新 */
    private val ttlMs: Long = 60_000,
) {

    /** 一次目录请求的结果 */
    data class Entry(
        val items: List<FileItem>,
        val count: Long,
        val at: Long,
    )

    // accessOrder = true：连读取也会把条目挪到队尾，淘汰时先丢最久没用过的
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
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
    }

    /**
     * 精确失效某个目录：前缀匹配会把该 cid 下**所有排序/筛选组合**一起清掉。
     * 移动、复制的目标目录靠它失效——当前目录由随后的强刷顺带更新，不用另说。
     */
    @Synchronized
    fun invalidateDir(cid: String) {
        map.keys.removeAll { it.startsWith("$cid|") }
    }

    @Synchronized
    fun clear() = map.clear()
}
