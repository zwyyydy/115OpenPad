package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.OpenApi
import com.open115.pad.data.PathNode
import com.open115.pad.data.VideoPreviewStore
import com.open115.pad.data.listDirPaged
import com.open115.pad.player.PlayerCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 文件页联动日志：adb logcat -s LibSync（"这个操作到底有没有算出该重扫/该清哪个库"） */
private const val TAG = "LibSync"

/**
 * 文件页改动了某个云端**目录**的内容。
 *
 * @param cid 该目录的 cid
 * @param path 完整云盘路径；**null = 文件页不知道**（"移动到…"选的目标只给了 cid），
 *             由这边自己认（见 [resolveDirPath]）
 * @param deep 要不要往下递归。**移入/复制要**（搬进来的可能是一整个剧集目录，只扫顶层会把
 *             `Season 1/` 里的分集漏掉，与影片迁移同一个理由）；删除后的父目录**不要** ——
 *             它只是"少了几项"，同层清陈旧条目就够了，递归一整棵树纯属浪费请求
 */
data class ChangedDir(val cid: String, val path: String? = null, val deep: Boolean = true)

/**
 * 文件页这次在云端改了什么 —— 媒体库据此收尾（[syncAfterCloudEdits]）。
 *
 * @param dirs 内容变了的目录：重新索引它们
 * @param removedDirPaths 被**删掉**的目录的完整云盘路径。为什么要单独给：扫描器的陈旧清理
 *   是**按目录**走的（`mediaKeysInDir`），而它只扫还存在的目录 —— 一个已经被删掉的子目录，
 *   父目录重扫根本走不进去，它名下的分集/影片行就会永远挂在海报墙上。只能在这里按路径直接清。
 */
data class CloudEdits(
    val dirs: List<ChangedDir> = emptyList(),
    val removedDirPaths: List<String> = emptyList(),
)

/**
 * 文件页那些"改了云端"的操作在媒体库侧的收尾。
 *
 * **为什么需要它**：文件页的写操作（移入 / 移出 / 删除）只动云端，而媒体库是本地索引，
 * 两边本来没有任何连线 —— 把刮好的片拖进库目录、或在库目录里删掉一部片之后，库里还是旧样子
 * （少一部，或者挂着一部已经不存在的片），只能等下一轮整库扫描。
 * （刮削那条路不在这里：[MetadataScraper.writeBack] 写完自己就会作废状态并排重扫。）
 *
 * 两件事，顺序要紧：**先按路径清掉被删目录的索引，再排重扫**（反过来会让重扫先把
 * "已经不存在的目录"记进状态，清起来更绕）。
 *
 * 认不出属于哪个库的路径直接跳过：库里本来就没有它，动了也白动（[libraryForPath] 负责
 * 归一化两边路径的写法，`/库根/x` 与 `库根/x` 都认）。
 */
suspend fun syncAfterCloudEdits(
    dao: MediaDao,
    scanner: MediaScanner,
    api: OpenApi,
    edits: CloudEdits,
    /**
     * 视频预览图缓存。被删掉的目录里那些片的预览图（按视频 pick_code 键）跟着一起清 ——
     * 它们的云端文件进了回收站、本地索引也在这儿清掉了，再没人引用（与 MovieDeleter
     * 「彻底删除」同一条口径）。认不出属于哪部片的（从没入过库）不在这里管。
     */
    videoPreviews: VideoPreviewStore? = null,
) {
    if (edits.dirs.isEmpty() && edits.removedDirPaths.isEmpty()) return
    val libraries = runCatching { dao.libraries().first() }.getOrDefault(emptyList())
    if (libraries.isEmpty()) return

    // ① 被删掉的目录：整棵子树的索引按路径清掉
    edits.removedDirPaths.distinct().forEach { path ->
        val clean = path.trim().trimEnd('/')
        // ★ 必须拿 [libraryForPath] 归一化后的路径去查库（`match.path`）：
        //   文件页给的路径是"云盘写法"（`下载目录/xxx`，没有前导斜杠），而库里存的是**库自己的写法**
        //   （`/下载目录/xxx`，带前导斜杠）—— 拿前者去 LIKE 前缀会一条都匹配不上，
        //   表现就是"日志说清掉了 0 条、卡片却还在"（真机实测踩到过）。
        val match = clean.takeIf { it.isNotEmpty() }?.let { libraryForPath(it, libraries) }
        if (match == null) {
            Log.i(TAG, "文件页改动：删除的目录「$path」不在任何媒体库内，跳过")
            return@forEach
        }
        val libPath = match.path
        // 预览图缓存的键（视频 pick_code）要先收着：索引清完就问不出来了
        var videoPickCodes: List<String> = emptyList()
        runCatching {
            // ★ 先清衍生记录再删索引（与扫描器的陈旧清理、MovieDeleter 同一约定），
            //   不然「继续观看」里会留下孤儿行
            val keys = dao.mediaKeysInPath(libPath)
            videoPickCodes = dao.pickCodesInPath(libPath).mapNotNull { it.videoPickCode }
            dao.purgeMovieTraces(keys)
            dao.deleteMovies(keys)
            dao.deleteScanStateInPath(libPath)
            Log.i(TAG, "文件页改动：清掉「$libPath」下 ${keys.size} 条索引（目录已删）")
        }.onFailure { Log.w(TAG, "清理「$libPath」的索引失败：${it.message}") }
        // 预览图缓存：与索引清理**分开判成败**。云端文件在这之前就已经删掉了（这一步只是收尾），
        // 索引没清干净也不该留着那些没人认领的预览图
        if (videoPickCodes.isNotEmpty()) {
            runCatching { withContext(Dispatchers.IO) { videoPreviews?.deleteFor(videoPickCodes) } }
                .onSuccess { n -> if ((n ?: 0) > 0) Log.i(TAG, "文件页改动：清掉这些片的预览图缓存 $n 个文件") }
                .onFailure { Log.w(TAG, "清理「$libPath」的预览图缓存失败：${it.message}") }
            // 播放缓存：直链档按 pick_code 清（转码档分片 key 是文件 sha1，这里拿不到）
            runCatching {
                val freed = withContext(Dispatchers.IO) { PlayerCache.purge(videoPickCodes) }
                if (freed > 0) Log.i(TAG, "文件页改动：清掉这些片的播放缓存 ${freed / 1024 / 1024}MB")
            }.onFailure { Log.w(TAG, "清理「$libPath」的播放缓存失败：${it.message}") }
        }
    }

    // ② 内容变了的目录：作废扫描状态 + 排一次定向重扫（深扫按 [ChangedDir.deep]）
    edits.dirs.distinctBy { it.cid }.forEach { dir ->
        if (dir.cid.isBlank()) return@forEach
        val path = resolveDirPath(dao, api, dir.cid, dir.path)
        val match = path?.let { libraryForPath(it, libraries) }
        if (match == null) {
            Log.i(TAG, "文件页改动：cid=${dir.cid} path=$path 不在任何媒体库内，跳过")
            return@forEach
        }
        // 作废状态：快速/增量的跳过判据全在这一行上（只清 upt 不够，见该方法的说明）
        runCatching { dao.invalidateScanState(dir.cid) }
            .onFailure { Log.w(TAG, "作废扫描状态失败 cid=${dir.cid}: ${it.message}") }
        scanner.enqueueTargeted(
            match.library,
            listOf(dir.cid to match.path),
            deep = dir.deep,
            // 静默："文件操作顺手触发的"，不写扫描记录、不弹"扫描完成"
            //（弹出来多半是"没有新增影片"这种废话）
            silent = true,
        )
        Log.i(TAG, "文件页改动：已排重扫「${match.library.name}」${match.path}（cid=${dir.cid} deep=${dir.deep}）")
    }
}

/**
 * 这个 cid 的完整云盘路径。三级取值，够用就早返回：
 *
 * ① 调用方顺手给的（文件页自己的目录栈里就有，零成本）；
 * ② 索引里记过的 —— 扫过的目录都在 `scan_state.dirPath` 留着路径，零请求；
 * ③ 都没有就**列一次这个目录**：115 的列表响应带着 `path[]` 祖先链（末项就是它自己）。
 *
 * 三条都落空返回 null，调用方按"认不出来"处理（跳过，不做任何事）。
 */
private suspend fun resolveDirPath(dao: MediaDao, api: OpenApi, cid: String, hint: String?): String? {
    hint?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    runCatching { dao.scanStatePath(cid) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    val page = runCatching { listDirPaged(api, cid) }.getOrNull() ?: return null
    return pathOfAncestors(page.path)
}

/**
 * 把列表响应里的 `path[]`（"根 → … → 这个目录"的祖先链）拼成 `/a/b/c` 形式的完整路径。
 *
 * 链首那个 `cid = 0` 的节点是**云盘根**本身，它带的 `name` 是界面叫法（不同接口还不一样），
 * 不是目录名 —— 拼进去会让"库根是 `下载目录`"这种整库匹配不上，所以先丢掉。
 * 剩下的名字为空（接口偶尔不给）就返回 null：宁可不认，也不要拼出一条假路径去撞库根。
 */
internal fun pathOfAncestors(nodes: List<PathNode>): String? {
    val names = nodes
        .dropWhile { it.cid == null || it.cid == "0" }
        .map { it.name.trim().trim('/') }
        .filter { it.isNotEmpty() }
    return if (names.isEmpty()) null else names.joinToString("/")
}
