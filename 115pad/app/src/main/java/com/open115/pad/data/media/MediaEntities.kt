package com.open115.pad.data.media

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------- 媒体库（类 Yamby）Room 实体 ----------------
// 数据源是 115 网盘上的 Emby 范式目录（两种实测结构：{tmdbid-…} 前缀聚类 / 演员目录番号聚类）。
// DB 是索引缓存，权威仍是云盘上的 .nfo；scan_state 记录每个目录的扫描状态支撑手动扫描可续跑。

@Entity(tableName = "movies", indices = [Index("seriesKey")])
data class MovieEntity(
    // 唯一键：tmdbid（示例影片式）或番号如 ABC-301（演员式）
    @PrimaryKey val mediaKey: String,
    val title: String = "",
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** 番号式资源=剧标记（无 tmdbid、按演员组织）；Emby 式资源=false */
    val isEpisodeLike: Boolean = false,
    /**
     * 所属系列的 mediaKey；**null = 自己就是顶层条目**（影片 / 系列本身）。
     *
     * 剧集包扫出来是"系列根一条 + 每季 N 条分集"，而 DB 里原来没有任何字段把两边连起来
     * （只有目录层级），于是海报墙只能把分集也当卡片铺出来 —— 一部剧显示成 10 张一样的卡。
     * 现在扫描时把"往上第一个有元数据的祖先目录那条 row 的 key"写进分集行，
     * 海报墙只显示 seriesKey IS NULL 的顶层条目，点进系列再列它的分集。
     *
     * 分集自己也有一条 key（它是 `movies` 里的一行），seriesKey 只是额外的"我属于谁"。
     */
    val seriesKey: String? = null,
    /** 云盘里的影片目录 id（cid），直达播放/重新扫描用 */
    val dirCid: String? = null,
    val dirPath: String? = null,
    /** 主视频文件的 pick_code（多视频时取第一个） */
    val videoPickCode: String? = null,
    val videoName: String? = null,
    /** 海报（竖版）/背景（横版）文件的 pick_code，加载时解析直链 */
    val posterPickCode: String? = null,
    val fanartPickCode: String? = null,
    /**
     * 剧照（`extrafanart/fanart1.jpg … fanart15.jpg`）的 pick_code，**按文件名自然序**存成一列，
     * 用 \n 连接（pick_code 里不会出现换行，与 libraries.rootCid 同一套约定）。
     *
     * 为什么挤在一列而不是单开一张表：它是一条影片的**有序附属列表**，除了"详情页按顺序铺出来"
     * 没有别的查询需求（不参与搜索/排序/关联），开表要多一套增删改与联级清理。
     *
     * **扫描期只存 pick_code，不下载字节** —— 剧照一次十几张，建库时全下会把扫描拖长好几倍、
     * 也白占缓存；详情页打开时才按需缓存（见 MediaDetailScreen）。
     */
    val extraFanartPickCodes: String? = null,
    /**
     * 剧照目录（`extrafanart/`）与演员头像目录（`.actors/`）的 cid。
     *
     * 只在「彻底删除」时用得上：这两个目录里的文件**列父目录列不出来**，
     * 要删就得再列一次子目录把 file_id 换回来（同 nfoFid 那套"缺就补"的思路）。
     */
    val extraFanartDirCid: String? = null,
    val actorsDirCid: String? = null,
    /** 自己目录里的缩略图（番号式的 `-thumb.jpg`、分集的剧照）。继承来的不算自己的 */
    val thumbPickCode: String? = null,
    /** nfo 文件的 pick_code 与云盘 upt，增量重扫时跳过未变化的条目 */
    val nfoPickCode: String? = null,
    val nfoUpt: Long = 0,
    /**
     * 主视频 / nfo / 海报 / 背景 / 缩略图的 **file_id**（115 的 `ufile/delete` 只认 file_ids，
     * pick_code 不认）。
     *
     * 只在「彻底删除」时用得上，所以扫描时顺手存下来 —— 不存的话删一个条目还得再列一次
     * 它所在目录去换 id，而那个目录可能已经被用户改过了。
     *
     * 图片也要删：只删视频和 nfo 的话，那个目录里还剩 poster/fanart，
     * 用户看到的"彻底删除"跟没删干净一样。
     */
    val videoFid: String? = null,
    val nfoFid: String? = null,
    val posterFid: String? = null,
    val fanartFid: String? = null,
    val thumbFid: String? = null,
    val sourceDirKey: String = "",
    val scannedAt: Long = 0,
) {
    /** 剧照 pick_code 列表（存的是 \n 连接的串，见 [encodePickCodes]） */
    val extraFanartList: List<String> get() = decodePickCodes(extraFanartPickCodes)
}

/**
 * 多值 pick_code 列的编解码：`\n` 连接。
 *
 * pick_code 是 115 自己生成的短标识（字母数字），**不可能含换行**，
 * 所以用它当分隔符不会歧义 —— 与 [MediaLibraryEntity.rootCid] 是同一套约定。
 * 空列表编码成 null 而不是空串：DB 里"没有剧照"只有一种表示。
 */
fun encodePickCodes(codes: List<String>): String? =
    codes.filter { it.isNotBlank() }.joinToString("\n").ifEmpty { null }

fun decodePickCodes(raw: String?): List<String> =
    raw?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

/**
 * 详情页那张**背景图**用哪个 pick_code —— 也是"没有海报时裁兜底海报"的源。
 *
 * 优先 `fanart.jpg`；没有就用**第一张剧照**（刮了 extrafanart 的目录往往没有单张背景图，
 * 详情页本来就是这么兜的）。扫描器与海报墙必须用同一份判断：两边取到不同的图，
 * 就会出现"详情页背景是 A、裁出来的海报是 B"。
 */
fun backgroundSourceOf(fanartPickCode: String?, extraFanartPickCodes: String?): String? =
    fanartPickCode?.takeIf { it.isNotBlank() } ?: decodePickCodes(extraFanartPickCodes).firstOrNull()

@Entity(tableName = "episodes", primaryKeys = ["mediaKey", "episodeKey"])
data class EpisodeEntity(
    val mediaKey: String,
    /** 剧内集标识：文件名公共前缀（S01E02 / 02 等） */
    val episodeKey: String,
    val season: Int? = null,
    val episode: Int? = null,
    val videoPickCode: String? = null,
    val videoName: String? = null,
    val thumbPickCode: String? = null,
    val nfoPickCode: String? = null,
)

@Entity(tableName = "actors", indices = [Index("name", unique = true)])
data class ActorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /**
     * 演员头像的 pick_code —— 来自影片目录里的 `.actors/<演员名>.jpg`（TMM 那套）。
     *
     * ★ 头像挂在**演员**这一行、而不是挂在影片上，是"同名复用"这条需求的落点：
     *   actors 表按名字唯一，所以 A 片扫到了头像，B 片（同一个演员、目录里没有 `.actors`）
     *   进详情页时直接读到同一份 —— 既不重复下载、也不重复落盘（缓存按 pick_code 命名）。
     *   写入规则见 MediaDao.linkActorWithAvatar：**已有头像就不覆盖**。
     *
     * 没头像的演员保持 null，详情页退回首字母圆底。
     */
    val avatarPickCode: String? = null,
    /** 头像文件所在的 `.actors` 目录 cid：彻底删除时判断"这个头像是不是跟着这条片一起删掉了" */
    val avatarDirCid: String? = null,
)

@Entity(
    tableName = "movie_actors",
    primaryKeys = ["mediaKey", "actorId"],
    indices = [Index("actorId")],
)
data class MovieActorEntity(
    val mediaKey: String,
    val actorId: Long,
)

@Entity(tableName = "tags", indices = [Index("name", unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "movie_tags",
    primaryKeys = ["mediaKey", "tagId"],
    indices = [Index("tagId")],
)
data class MovieTagEntity(
    val mediaKey: String,
    val tagId: Long,
)

/** 每个目录的扫描状态：手动触发、可续跑、upt 增量重扫 */
@Entity(tableName = "scan_state", primaryKeys = ["dirKey"])
data class ScanStateEntity(
    val dirKey: String,
    val dirPath: String = "",
    /** 0 未扫 / 1 进行中 / 2 完成 */
    val status: Int = 0,
    /** 目录的最近修改时间（列表 upt），未变则跳过 */
    val cloudUpt: Long = 0,
    /**
     * 目录指纹（见 [dirFingerprintOf]）：目录里所有条目的名字+大小+upt+pickCode 的哈希。
     *
     * 比 `cloudUpt` 准：删除、改名、移动进来都会让它变，而 upt 的最大值不会。
     * 空串 = 还没算过（v9 之前的老数据），下一次扫描会重扫一遍这个目录并补上。
     */
    val dirFingerprint: String = "",
    val scannedAt: Long = 0,
)

/** 用户建的媒体库：一个或多个云盘根路径 = 一个库（如「示例系列」「演员目录」） */
@Entity(tableName = "libraries")
data class MediaLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 云盘根目录 cid，多根时以 \n 分隔 */
    val rootCid: String,
    /** 云盘根目录完整路径，多根时以 \n 分隔（路径里不会出现换行） */
    val rootPath: String,
    val createdAt: Long = 0,
    /** 扫描限速：相邻两次 115 API 请求的最小间隔（毫秒），0 = 不限 */
    val rateLimitMs: Long = 0,
    /** 程序启动时对这个库自动跑增量扫描 */
    val autoScanOnStart: Boolean = false,
    /**
     * 体积过滤：**视频小于这么多 MB 就不入库**，0 = 不过滤。
     *
     * 为什么按库配而不是全局：同一个账号下的库差异很大 —— 正片库里 100MB 以下的多半是
     * 预告/花絮/样本（不该占海报墙的位置），而番号式短片的正常体积可能就几百 MB，
     * 全局设一个数必然有一边不合适。
     *
     * 过滤发生在**聚类阶段**（见 clusterFiles），不是入库阶段：被滤掉的视频当它不存在，
     * 所以"1 个正片 + 1 个预告"的目录仍然算**单视频**（影片），不会被误判成多集目录。
     */
    val minVideoSizeMb: Int = 0,
) {
    val rootCids: List<String> get() = rootCid.split('\n').filter { it.isNotBlank() }
    val rootPaths: List<String> get() = rootPath.split('\n').filter { it.isNotBlank() }

    companion object {
        fun joinRoots(cids: List<String>, paths: List<String>): Pair<String, String> =
            cids.filter { it.isNotBlank() }.joinToString("\n") to
                paths.filter { it.isNotBlank() }.joinToString("\n")
    }
}
