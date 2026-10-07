package com.open115.pad.data

import android.media.MediaMetadataRetriever
import com.open115.pad.util.APP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从视频**头部**读出来的真实参数。
 *
 * 2026-09-30 实测（见 video-version-detection-experiments）：115 直链可以直接喂给
 * `MediaMetadataRetriever`，**但必须带 UA** —— 空 headers 时每个文件都要 ~33 秒超时失败；
 * 带上播放器/下载器同款 UA 后只要 **0.4~2 秒**，说明只取了头部/索引那几百 KB。
 *
 * 容错：实测个别文件 `duration` 为 0、`bitrate` 溢出成 LONG_MIN，所以这里都当"可能是 0/异常值"处理，
 * 取不到就整条回 null（上层退回用列表自带的时长/体积）。
 */
data class VideoMeta(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bitrate: Long,
    /** 0/90/180/270：竖屏片常靠这个标记，宽高要换过来算 */
    val rotation: Int,
    val mime: String?,
) {
    /** 显示用宽（旋转 90/270 时与存储的宽高互换，否则竖屏片会被算成横屏） */
    val dispWidth: Int get() = if (rotation % 180 == 90) height else width
    val dispHeight: Int get() = if (rotation % 180 == 90) width else height

    /** 像素量，用来比"哪一档更高" */
    val pixels: Long get() = dispWidth.toLong() * dispHeight

    val aspectRatio: Double get() = if (dispHeight <= 0) 0.0 else dispWidth.toDouble() / dispHeight

    val resolution: String get() = "${dispWidth}×${dispHeight}"
}

/** 取直链（与 `ImageUrlResolver` 里同一段解析，这里为了不依赖它的私有实现重写一遍） */
internal suspend fun downUrlOf(api: OpenApi, pickCode: String): String {
    val root = api.downUrl(pickCode)
    val data = root.envData() ?: error(root.envMsg() ?: "获取下载地址失败")
    val entry = data.values.firstOrNull() as? JsonObject ?: error("响应格式异常")
    return when (val el = entry["url"]) {
        is JsonObject -> (el["url"] as? JsonPrimitive)?.content
        is JsonPrimitive -> el.content
        else -> null
    } ?: error("未获取到下载地址")
}

/**
 * 读一个视频的真实参数（只读头部，不下载整包）。
 *
 * ★ 必须离开主线程：`setDataSource`/`extractMetadata` 都是**阻塞**调用（大文件一次好几秒），
 *   写临时探测程序时忘了这点，直接把 App 搞 ANR。
 * 取不到（网络/格式/字段缺失）返回 null，调用方退化成"只有列表里的信息"。
 */
internal suspend fun probeVideoMeta(api: OpenApi, pickCode: String): VideoMeta? =
    withContext(Dispatchers.IO) {
        val url = runCatching { downUrlOf(api, pickCode) }.getOrNull() ?: return@withContext null
        val mmr = MediaMetadataRetriever()
        try {
            // 带 UA 是关键；空 headers 会超时（实测 33 秒/个）
            mmr.setDataSource(url, mapOf("User-Agent" to APP_USER_AGENT))
            fun meta(key: Int): String? = runCatching { mmr.extractMetadata(key) }.getOrNull()
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@withContext null
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@withContext null
            if (w <= 0 || h <= 0) return@withContext null
            VideoMeta(
                width = w,
                height = h,
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: 0L,
                bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.takeIf { it in 1..1_000_000_000L } ?: 0L,
                rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                    ?.takeIf { it in 0..359 } ?: 0,
                mime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

/** 深挖之后对一组候选的判断（**只比结构，不比内容** —— 内容近似与否要抽帧，见 C 段） */
enum class VersionVerdict(val label: String) {
    /** 宽高比一致 + 时长（毫秒级）也一致 → 很像"同一部片的不同档" */
    LIKELY_SAME("结构一致：宽高比与时长都对得上"),

    /** 宽高比或时长对不上 → 很可能只是"时长凑巧相同"的不同片子 */
    DIFFERENT_SHAPE("结构对不上：宽高比或时长不一致，可能不是同一部"),

    /** 还没深挖，或只有一条拿到了真实参数 */
    UNKNOWN("未深挖"),
}

/**
 * 深挖后判"同一条时间线"的时长容差。
 *
 * ★ 实测数据决定的：**同一部片换清晰度（以及同一文件的副本），真实时长毫秒级完全一致**；
 * 而剧集相邻两集实测只差 **300ms**（显示成 41:38 / 41:39 是截断的假象）。
 * 所以容差必须卡到亚秒级以下 —— 起初给 2 秒、后来给 0.5 秒，都把相邻那一集并进了同一簇、
 * "建议保留"直接指错文件。现在 100ms：真版本（0 差）进得来，相邻集（300ms）进不来。
 */
private const val DURATION_MS_TOLERANCE = 100L

/**
 * 深挖后：从一组候选里挑出**互相结构一致**的最大子集（宽高比相对差 ≤2% + 时长差 ≤[DURATION_MS_TOLERANCE]）。
 *
 * 为什么必须有它：A 段的桶只按"时长 ±3 秒"圈，里面可能混进时长凑巧相同的别的片子
 * （实测那组 10 个成员里就混着一部剧的好几集）。整组判"一致/不一致"会把真正的两个版本埋掉，
 * 所以判断、推荐保留项、以及删除动作都只在**这个子集**上做。
 * 都没深挖（或只有一个有真实参数）返回空表。
 */
internal fun consistentCluster(
    members: List<VideoVersion>,
    meta: Map<String, VideoMeta>,
): List<VideoVersion> {
    val known = members.mapNotNull { m -> meta[m.fid]?.let { m to it } }
        .sortedBy { it.second.durationMs }
    if (known.size < 2) return emptyList()

    var best = emptyList<VideoVersion>()
    var i = 0
    while (i < known.size) {
        val (_, anchor) = known[i]
        val cluster = mutableListOf(known[i].first)
        var j = i + 1
        while (j < known.size) {
            val (m, vm) = known[j]
            val ratioOk = anchor.aspectRatio > 0 && vm.aspectRatio > 0 &&
                kotlin.math.abs(vm.aspectRatio - anchor.aspectRatio) / anchor.aspectRatio <= 0.02
            val durOk = kotlin.math.abs(vm.durationMs - anchor.durationMs) <= DURATION_MS_TOLERANCE
            if (!ratioOk || !durOk) break
            cluster += m
            j++
        }
        if (cluster.size > best.size) best = cluster
        i += cluster.size
    }
    // 不足两个就没有"结构一致的子集"可言（调用方据此判"结构对不上"，也别拿单个去当推荐保留项）
    return if (best.size >= 2) best else emptyList()
}

/**
 * 判一组候选的结构是否一致 —— 用**结构一致的最大子集**来判（见 [consistentCluster]）。
 *
 * 两条硬判据都用**真实**参数：宽高比一致（同一部片换清晰度不改画幅）、时长毫秒级一致
 * （同一部片的不同压制就是同一条时间线）。实测依据：剧集各集时长差是秒级（2496s vs 2502s），
 * 一进这里就判成"结构对不上"，而同一部片的两个清晰度版本时长差为 0。
 */
internal fun verdictOf(group: VersionGroup, meta: Map<String, VideoMeta>): VersionVerdict {
    val known = group.members.count { meta[it.fid] != null }
    if (known < 2) return VersionVerdict.UNKNOWN
    return if (consistentCluster(group.members, meta).size >= 2) {
        VersionVerdict.LIKELY_SAME
    } else {
        VersionVerdict.DIFFERENT_SHAPE
    }
}

/**
 * 按"哪一档更高"给成员排序：像素量 → 码率 → 体积 → **云端最早**（0 视为未知压后）→ 名字。
 * 没有真实参数的排在后面（还没深挖的不该顶掉已知的高档）。深挖后用它决定"建议保留"；
 * 同分（真·同一内容的多个副本）时保留最早那份，与一期「内容相同」的保留规则保持一致。
 */
internal fun orderByQuality(members: List<VideoVersion>, meta: Map<String, VideoMeta>): List<VideoVersion> =
    members.sortedWith(
        compareByDescending<VideoVersion> { meta[it.fid]?.pixels ?: -1L }
            .thenByDescending { meta[it.fid]?.bitrate ?: -1L }
            .thenByDescending { it.size }
            .thenBy { if (it.item.upt > 0) it.item.upt else Long.MAX_VALUE }
            .thenBy { it.name },
    )
