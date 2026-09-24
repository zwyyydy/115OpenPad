package com.open115.pad.data.media

import android.util.Log
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.parseFilesResponse
import java.io.File

private const val TAG = "MovieDeleter"

/** 删单个条目的结果，给 UI 决定提示什么 */
sealed interface MovieDeleteResult {
    /** 删干净了 */
    data object Ok : MovieDeleteResult

    /** 云端删除失败（**本地没动**，条目还在，用户可以直接重试） */
    data class CloudFailed(val message: String) : MovieDeleteResult

    /**
     * 这条索引是升级前的旧数据，没存 file_id，删不了云端。
     * 本地也**没动** —— 只删本地的话用户会以为云端也删了，那是这条路上最危险的一种"成功"。
     */
    data object NoCloudId : MovieDeleteResult
}

/**
 * 删一个媒体库条目（影片 / 系列卡）。
 *
 * [alsoCloud] = false：只删本机索引与落盘缓存，云端文件一个不动。
 * [alsoCloud] = true：**先删云端、成功了再删本地**。
 *
 * ★ 顺序不能反。本地索引是"云端有什么"的缓存 —— 先删本地再删云端的话，一旦云端那步失败
 *   （网络抖动 / 权限），这条就变成"云盘里还在、媒体库里再也看不见"的幽灵条目，
 *   而用户以为自己删掉了。所以云端失败时**本地保持原样**并把错误抛给 UI。
 *
 * ★ 只删这个条目**自己的文件**（视频 + 同名 nfo + 海报/背景/缩略图 + 剧照 + 演员头像），
 *   **不删它所在的目录**：
 *   很多库的视频就直接躺在库里（`/test/多视频目录/` 下 50 个视频），删目录等于删掉整季。
 *   删 nfo 是为了让这条**下次扫描不会再冒出来** —— 只删视频的话，那个目录还剩 nfo，
 *   重扫会建出一条"有海报有简介、点播放说没有可播放文件"的卡片。
 *   图片同理：删完只在目录里留一张 poster.jpg，用户看到的"彻底删除"跟没删干净一样。
 *   剧照（`extrafanart/`）与演员头像（`.actors/`）也是这一条自己的文件，一并删掉；
 *   但**目录本身不动**（115 删目录要单独调、且这里不敢替用户决定），
 *   所以全删完可能剩几个空目录，那个留给用户自己在网盘里处置。
 *   这两个子目录是**整个目录共用**的（一季的剧照挂在每一条上），所以只在目录被删空时才清
 *   —— 删一集就顺手删掉整季剧照，会让别的集全是死链。
 *   ★ 头像还要多看一眼：**这个演员在库里还有别的作品就不删**（见 [avatarsToDelete]）——
 *     头像按名字全局唯一、跨影片复用，删了别的片就再也配不上；
 *     只有一部作品都不剩时，才把文件删掉、引用清掉。
 *   目录里那些**没有归属**的素材图由 [sweepOrphanArt] 收尾。
 *
 * ★ 两种模式都要把条目所在目录的扫描状态作废（见 dao.invalidateScanState）：
 *   增量扫描跳过目录的依据是"目录列表 upt 没变"，而删除**不会**让目录 upt 变
 *   （它是剩余项 upt 的最大值，删掉一个旧文件根本不改它）——
 *   不作废的话，本地删掉的片在增量扫描里永远回不来，只有全量扫描能救。
 */
suspend fun deleteMovie(
    api: OpenApi,
    dao: MediaDao,
    mediaCache: MediaCache?,
    imageUrlResolver: ImageUrlResolver,
    cacheDir: File,
    mediaKey: String,
    alsoCloud: Boolean,
): MovieDeleteResult {
    // 系列卡会把整季分集一起带出来 —— 删系列就该连分集一起删，
    // 否则留下的是"没有系列的分集"，海报墙上再也不显示，也永远不会被清理
    val files = dao.filesOf(mediaKey)
    if (files.isEmpty()) return MovieDeleteResult.Ok // 已经没了，当成成功

    val dirKeys = files.map { it.sourceDirKey }.filter { it.isNotEmpty() }.distinct()
    val doomedKeys = files.map { it.mediaKey }.toSet()
    // 剧照（`extrafanart/`）与演员头像（`.actors/`）是**整个目录共用**的素材，
    // 只有当这个目录被**删空**时才连它们一起删：
    // 删一季里的一集时不能碰 —— 别的集还在用那些图，删了它们的行就成了死链。
    // （删空 = 这个目录里现有的条目全在本次删除里）
    val emptiedDirs = dirKeys.filter { cid -> dao.mediaKeysInDir(cid).all { it in doomedKeys } }.toSet()
    val sideRows = files.filter { it.sourceDirKey in emptiedDirs }
    val sideDirs = sideRows.flatMap { listOfNotNull(it.extraFanartDirCid, it.actorsDirCid) }
        .filter { it.isNotEmpty() }.distinct()

    // ★ 头像能不能删，取决于**这个演员在库里还有没有别的作品**（同一个演员跨库共用一份头像）。
    //   还有作品在用就留着 —— 删了别的片就再也配不上头像（同名复用靠的就是这一份文件），
    //   要等它们各自的目录被重扫才会好。只有"一部作品都不剩"时才连文件一起删。
    //   这几个查询都必须在删本地索引**之前**跑。
    val usedAvatars = if (alsoCloud) dao.actorAvatarsOfMovies(doomedKeys.toList()) else emptyList()
    val stillUsedNames = if (usedAvatars.isEmpty()) {
        emptySet()
    } else {
        dao.actorNamesStillUsed(usedAvatars.map { it.id }, doomedKeys.toList()).toSet()
    }
    val doomedAvatars = avatarsToDelete(usedAvatars, stillUsedNames)
    // 还要用的头像，文件必须从删除批次里排除（它们在本次要清理的 .actors 目录里躺着）
    val keptAvatarPickCodes = usedAvatars.filter { it.name in stillUsedNames }
        .map { it.avatarPickCode }.toSet()

    var deletedIds = emptySet<String>()

    if (alsoCloud) {
        // v7 之前的索引只有 pick_code（`ufile/delete` 不认），图片的 id 更是本次才存。
        // 缺谁就按它自己的目录列一次文件表把 id 换回来，否则"彻底删除"会留下图片，
        // 或者干脆拿不到 id 白跑一趟。
        val resolved = resolveMissingFids(api, files)
        val ids = files.flatMap { it.cloudFileIds(resolved) }.distinct().toMutableList()
        // 剧照 + 没人再用的头像（在要删空的那些子目录里）
        sideDirs.flatMap { imageIdsInDir(api, it, exceptPickCodes = keptAvatarPickCodes) }
            .forEach { if (it !in ids) ids += it }
        // 头像文件可能**不在**本次要清理的目录里（它来自之前已经删掉的那部片）——
        // 那时要单独去它所在的目录把这一张删掉，只删这一个 pick_code 对应的文件
        val strayAvatarDirs = doomedAvatars.mapNotNull { it.avatarDirCid }
            .filter { it.isNotEmpty() && it !in sideDirs }.distinct()
        if (strayAvatarDirs.isNotEmpty()) {
            val doomedPcs = doomedAvatars.map { it.avatarPickCode }.toSet()
            strayAvatarDirs.flatMap { imageIdsInDir(api, it, onlyPickCodes = doomedPcs) }
                .forEach { if (it !in ids) ids += it }
        }
        if (ids.isEmpty()) return MovieDeleteResult.NoCloudId
        val resp = runCatching { api.deleteFiles(ids.joinToString(","), parentId = null) }.getOrNull()
            ?: return MovieDeleteResult.CloudFailed("网络异常，云端未删除")
        if (!resp.envOk()) {
            return MovieDeleteResult.CloudFailed(resp.envMsg() ?: "云端删除失败")
        }
        deletedIds = ids.toSet()
    }

    // 本地：索引 + 落盘缓存（图按 pickCode 精确删；nfo 按 nfo|<pickCode>| 前缀失效）
    dao.deleteMovies(files.map { it.mediaKey })
    dirKeys.forEach { dao.invalidateScanState(it) }
    mediaCache?.invalidateAll(
        files.mapNotNull { it.nfoPickCode }.distinct().map { MediaScanner.nfoCachePrefix(it) },
    )
    imageUrlResolver.evictPosterCache(
        files.flatMap { listOfNotNull(it.posterPickCode, it.fanartPickCode, it.thumbPickCode) + decodePickCodes(it.extraFanartPickCodes) },
        cacheDir,
    )
    // 头像文件真删掉了的那些演员，引用也要清（不然详情页拿着一个不存在的文件反复解析直链）。
    // 只在**云端真的删了**时清：只删本地索引时云端文件还在，引用留着下次还能用。
    if (alsoCloud && doomedAvatars.isNotEmpty()) {
        dao.clearActorAvatars(doomedAvatars.map { it.id })
        imageUrlResolver.evictPosterCache(doomedAvatars.map { it.avatarPickCode }, cacheDir)
    }
    // 云端已删干净的那些目录，顺手把没有归属的素材图也清了（云端删除失败时压根走不到这里）
    if (alsoCloud) sweepOrphanArt(api, dao, dirKeys, deletedIds)
    return MovieDeleteResult.Ok
}

/**
 * 列一个子目录，取里面**图片**文件的 file_id。
 *
 * 为什么要列：这两个目录里的文件是子目录的内容，列父目录列不出来，而扫描期只存了
 * pick_code（`ufile/delete` 只认 file_id）。删除只在用户点「彻底删除」时发生，
 * 多花 1~2 次列表请求换"云端真删干净"，比留一堆剧照在那儿划算。
 *
 * [onlyPickCodes] / [exceptPickCodes] 是给头像用的：**还要给别的作品复用的头像不能删**，
 * 得按 pick_code 把它们从批次里挑出来（或排除掉）。
 *
 * 列不到（目录已被删/改名）就当它已经没了，静默跳过 —— 这一步是收尾，不该拦住删除本身。
 */
private suspend fun imageIdsInDir(
    api: OpenApi,
    dirCid: String,
    onlyPickCodes: Set<String> = emptySet(),
    exceptPickCodes: Set<String> = emptySet(),
): List<String> {
    val page = runCatching { parseFilesResponse(api.files(cid = dirCid, limit = 500)) }.getOrNull()
        ?: return emptyList()
    return page.items.filter { !it.isDir && isArtImageFile(it.fn) }
        .filter { shouldDeleteImage(it.pc.orEmpty(), onlyPickCodes, exceptPickCodes) }
        .mapNotNull { it.fid?.takeIf { f -> f.isNotEmpty() } }
}

/**
 * 这个文件该不该进删除批次。
 *
 * [onlyPickCodes] 非空 = **只要**这些（删一个散落在别的目录里的头像时用）；
 * [exceptPickCodes] 里的**一定不要** —— 那些是还要给别的作品复用的头像，
 * 误删了别的片就再也配不上头像（这正是这次要修的 bug）。
 *
 * 纯逻辑，单独提出来测：两个参数都是"缩小范围"的意思，但一个取交集一个取差集，
 * 写反了不报错、只是删错文件。
 */
internal fun shouldDeleteImage(
    pickCode: String,
    onlyPickCodes: Set<String> = emptySet(),
    exceptPickCodes: Set<String> = emptySet(),
): Boolean = (onlyPickCodes.isEmpty() || pickCode in onlyPickCodes) && pickCode !in exceptPickCodes

/**
 * 收尾：某个目录里**再没有任何本库条目**时，把还留在那儿的素材图一并清掉。
 *
 * 上面的删除只删"索引里有归属"的文件，可目录里往往还剩两类没有归属的素材：
 * 被同簇裸图顶掉的目录级 `folder.jpg`、以及 `xxx-logo.png` / `season01-poster.jpg`
 * 这种扫描器刻意不挂给任何条目的图（挂给哪一集都是张冠李戴）。
 * 用户把整部片删了，看到的却是目录里还剩两张图 —— 跟没删干净一样。
 *
 * ★ 只在**这个目录已经没有任何条目**时才动：目录里还有别的片时，那张 `poster.jpg`
 *   是那个目录的、不是这一条的，一张都不能碰。
 * ★ 只认图片扩展名（见 isArtImageFile），视频 / 字幕 / nfo 一律不碰。
 * ★ 这一步失败不影响结论：条目自己的文件已经删掉了，这里只是收尾，失败记日志。
 */
private suspend fun sweepOrphanArt(
    api: OpenApi,
    dao: MediaDao,
    dirKeys: List<String>,
    alreadyDeleted: Set<String>,
) {
    for (cid in dirKeys) {
        if (dao.rowsInDir(cid) > 0) continue
        val page = runCatching { parseFilesResponse(api.files(cid = cid, limit = 500)) }.getOrNull()
            ?: continue
        val ids = page.items
            .filter { it.fc != 0 && isArtImageFile(it.fn) }
            .mapNotNull { it.fid?.takeIf { f -> f.isNotEmpty() && f !in alreadyDeleted } }
        if (ids.isEmpty()) continue
        val resp = runCatching { api.deleteFiles(ids.joinToString(","), parentId = null) }.getOrNull()
        if (resp == null || !resp.envOk()) {
            Log.w(TAG, "残留素材图清理失败 $cid: ${resp?.envMsg() ?: "网络异常"}")
        } else {
            Log.i(TAG, "清理 $cid 下 ${ids.size} 个无归属素材图")
        }
    }
}

/**
 * 这条在云盘上的全部 file_id：视频 + nfo + 海报 + 背景 + 缩略图。
 *
 * `resolved` 是补齐来的（pick_code → file_id），补上了就用补的。
 */
internal fun MovieFiles.cloudFileIds(resolved: Map<String, String>): List<String> =
    listOfNotNull(
        videoFid ?: resolved[videoPickCode],
        nfoFid ?: resolved[nfoPickCode],
        posterFid ?: resolved[posterPickCode],
        fanartFid ?: resolved[fanartPickCode],
        thumbFid ?: resolved[thumbPickCode],
    )

/**
 * 把"有 pick_code 但没 file_id"的文件补上 id —— 按条目**自己所在目录**列一次文件表来对。
 *
 * pick_code 是文件级的唯一标识，对上了就是同一个文件，不会误删别人。
 * 补不到的**就这样跳过**，不报错：
 *  ① 分集从系列继承来的海报/背景在父目录，自己的目录里当然找不到 —— 这正是期望
 *     （删一集不该连带删掉整个系列的封面）；
 *  ② 云端文件本来就已经被人删了。
 *
 * 每个涉及的目录只列一次（一部剧的各集通常同目录，删系列时只多花 1~3 次请求）。
 * 索引里的 id 齐了（重扫过的条目）就完全不会走这条路。
 */
private suspend fun resolveMissingFids(
    api: OpenApi,
    files: List<MovieFiles>,
): Map<String, String> {
    val missing = mutableListOf<Pair<String, String>>() // 目录 cid to pick_code
    for (f in files) {
        if (f.sourceDirKey.isEmpty()) continue
        if (f.videoFid == null) f.videoPickCode?.let { missing += f.sourceDirKey to it }
        if (f.nfoFid == null) f.nfoPickCode?.let { missing += f.sourceDirKey to it }
        if (f.posterFid == null) f.posterPickCode?.let { missing += f.sourceDirKey to it }
        if (f.fanartFid == null) f.fanartPickCode?.let { missing += f.sourceDirKey to it }
        if (f.thumbFid == null) f.thumbPickCode?.let { missing += f.sourceDirKey to it }
    }
    if (missing.isEmpty()) return emptyMap()

    val out = mutableMapOf<String, String>()
    for ((cid, pcs) in missing.groupBy({ it.first }, { it.second })) {
        val wanted = pcs.toSet()
        val page = runCatching { parseFilesResponse(api.files(cid = cid, limit = 500)) }.getOrNull()
            ?: continue
        for (item in page.items) {
            val pc = item.pc ?: continue
            val fid = item.fid ?: continue
            if (pc in wanted && fid.isNotEmpty()) out[pc] = fid
        }
    }
    return out
}