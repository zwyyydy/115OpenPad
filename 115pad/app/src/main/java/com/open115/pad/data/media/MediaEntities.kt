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
     * **nfo 的完整解析结果**（[NfoMeta] 的 JSON）。
     *
     * 为什么整份存 JSON 而不是每个字段一列：nfo 里能刮出来的字段有四十多个（原名/标语/时长/
     * 国家/语言/编剧/合集/技术参数/音轨字幕…），逐列建的话每加一个字段就要一次建表迁移；
     * 而这里面**只有标题/年份/评分/简介/分类/演员**这几样要参与查询与排序（它们已经是独立的列、
     * 也是海报墙与检索在用的），其余都是"详情页展示用"的长尾 —— 一列 JSON 就够。
     *
     * 取用见 MediaDetailScreen（`Json.decodeFromString<NfoMeta>`），写入见 MediaScanner。
     * 老库升级后这一列是空的：详情页那时只显示原有的字段，重扫一次就补上。
     */
    val nfoJson: String? = null,
    /**
     * 首映日期（nfo 的 `<premiered>`，原样 `yyyy-MM-dd`）。
     *
     * 提成独立列只为**排序**（作品列表可按首映时间排）—— 字符串序就是时间序，
     * 不用转时间戳。详情页显示的那份仍在 [nfoJson] 里。
     */
    val premiered: String? = null,
    /**
     * 入库时间（nfo 的 `<dateadded>`，**epoch 毫秒**）。
     *
     * 存毫秒而不是原字符串：排序时要跟本机索引时间 [scannedAt]（毫秒）兜底混用，
     * 字符串和数字没法比大小。解析见 [parseNfoDateMillis]。
     */
    val dateAdded: Long? = null,
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
     * 自动扫描的**最小间隔（小时）**：距上次扫描不到这么久就跳过，0 = 不限（每次启动都扫）。
     *
     * 只在程序启动时判断一次（见 App115 的启动块）：115 有频控，一轮增量扫描的代价是
     * "每个目录一次列表请求"，一个几千文件的库跑起来是分钟级 —— 每次开 App 都扫并不合适，
     * 而这个开关的用户预期就是"别太频繁"。判断本身是纯本地的（比较 [lastScanAt]），零请求。
     */
    val autoScanIntervalHours: Int = 0,
    /**
     * 上次扫描**跑完**的时间（毫秒，0 = 从未扫过）。由 [MediaScanner.runScan] 在收尾时写。
     *
     * 记的是"结束"而不是"开始"：开始就记的话，一轮卡在列目录阶段失败的扫描也算数，
     * 库里就会在这之后 N 小时内不再自动扫。反过来，一轮连一个目录都没跑完（列目录就抛了）
     * 也不记 —— 那种情况就是网络问题，下次启动应该再试。
     */
    val lastScanAt: Long = 0,
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

/**
 * 这次启动该不该自动扫这个库。判据纯本地（只比时间戳），零请求。
 *
 * - 间隔 0 = 不限 → 每次启动都扫（这个开关本来的行为）
 * - 从未扫过（[MediaLibraryEntity.lastScanAt] = 0）→ 要扫
 * - 正好等于间隔 → 要扫（`>=` 而不是 `>`：设 1 小时就是"满 1 小时可以扫"）
 *
 * 抽成函数而不是写在启动块里，是为了能被单测钉住 —— 时间相关的判断最容易差一个边界。
 */
fun MediaLibraryEntity.autoScanDue(now: Long): Boolean {
    // 从未扫过：**必须显式判**，不能靠"now - 0 很大"—— 那依赖机器时钟是真实时间
    // （单测里的小时钟就会翻车，设备时钟被改小也一样）
    if (lastScanAt <= 0L) return true
    val intervalMs = autoScanIntervalHours.coerceAtLeast(0) * 3_600_000L
    return intervalMs <= 0L || now - lastScanAt >= intervalMs
}

/**
 * 观影历史：一条 = 在**媒体库里**播过一次的视频（key 就是它的 pick_code）。 *
 * 为什么自己记：115 那边只有「按 pick_code 查某一条的进度」（`GET open/video/history`），
 * **没有"列出看过的片"的接口** —— 媒体库页里那份「观影历史」只能靠本机记。
 *
 * **只收媒体库的播放**（见 PlayerActivity.intent 的 recordHistory）：文件页的播放记录在
 * 「操作记录」里已经有一份，两处都记是重复；本机文件（传输中心/外部分享进来的）也不在媒体库里。
 *
 * 主键就是播放链路自己用的那个 key，所以"再看一次"天然是更新同一条、不会重复堆。
 * 海报/标题**不存这里**（存了会跟库不同步）：列表查询时 join movies 现取，
 * 见 [MediaDao.watchHistoryRows]。
 */
@Entity(tableName = "watch_history", indices = [Index("updatedAt")])
data class WatchHistoryEntity(
    @PrimaryKey val itemKey: String,
    /** 播放时看到的名字。列表里优先显示库里的标题（见 watchHistoryRows） */
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    /** 最后一次有进度的时间：列表按它倒序，"刚才看的"在最上面 */
    val updatedAt: Long,
    /**
     * 本机/外部源（uri 形式）—— 播放时不能再当 pick_code 去解析直链。
     *
     * 现在**恒为 false**：写入链路只记媒体库（云端）播放。留着这一列有两个原因：
     * ① 表结构已经随 v13 落盘，为了它再迁移一次不值当；
     * ② 列表页仍然认它（历史行万一是本机源，点下去要走 localIntent 而不是解析直链）。
     */
    val local: Boolean = false,
)

/**
 * 观影历史 + **媒体库里的元数据**（海报 / 更像片名的标题），列表页直接用。
 *
 * join 规则：历史行的 key 就是视频的 pick_code，而 `movies.videoPickCode` 正是它 ——
 * 命中说明这部片在某个库里，于是海报、标题都能现取（库里改了名、换了海报，历史页立刻跟着变）。
 *  - 分集（剧集包里的某一集、分 CD 的某张盘）自己那条也带海报（扫描时从系列继承的），
 *    所以影片这层只 join 一次
 *  - 标题取 `COALESCE(s.title, m.title)`：分集显示**系列名**（列表里"示例剧集三"比"第 7 集"好认），
 *    集名当副标题（[ownTitle]，跟主标题不一样时才显示）
 */
data class WatchHistoryRow(
    val itemKey: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val local: Boolean,
    val posterPickCode: String?,
    /** 库里的标题（分集时是所属系列/影片的名字）；不在任何库里为 null */
    val libraryTitle: String?,
    /** 这条视频自己那行的标题（分集就是集名）；不在库里为 null */
    val ownTitle: String?,
)

/**
 * 一条扫描记录：**什么时候、扫的哪个库、结果如何、新增了哪些片**。
 *
 * 为什么单独一张表而不是塞进「操作记录」（OpLog）：那是**文件操作**的流水（复制/移动/上传…），
 * 混在一起既冲淡它、又受它 1000 条上限的牵连；而扫描记录要能带**海报图**展示新增影片，
 * 它需要 join `movies` —— 那是媒体库自己的事，就该待在媒体库这一侧。
 *
 * [libraryName] 冗余存一份：库被删了记录也还看得懂"这条是哪个库的"。
 * [newKeys] 存的是**影片键**不是标题 —— 标题与海报在界面里 join `movies` 现取，
 * 库里改了名、换了海报，旧记录里也跟着变（存快照的话记录一多就会跟库不一致）。
 */
@Entity(tableName = "scan_log", indices = [Index("at")])
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 库 id（0 = 没有对应库，例如手工指定根目录扫的） */
    val libraryId: Long = 0,
    val libraryName: String,
    /** 完成时间（毫秒）—— 记录列表按它倒序 */
    val at: Long,
    val elapsedMs: Long = 0,
    val totalDirs: Int = 0,
    val doneDirs: Int = 0,
    val skippedDirs: Int = 0,
    val indexed: Int = 0,
    val postersFetched: Int = 0,
    val stopped: Boolean = false,
    /** 新增影片的 mediaKey，`\n` 分隔（路径里不会出现换行，键同理） */
    val newKeys: String = "",
) {
    val newKeyList: List<String> get() = newKeys.split('\n').filter { it.isNotBlank() }

    /** 交给界面拼摘要（文案统一在 [ScanReport] 里，见它的注释） */
    fun toReport(): ScanReport = ScanReport(
        libraryName = libraryName,
        startedAt = at - elapsedMs,
        finishedAt = at,
        totalDirs = totalDirs,
        doneDirs = doneDirs,
        skippedDirs = skippedDirs,
        indexed = indexed,
        postersFetched = postersFetched,
        newCount = newKeyList.size,
        stopped = stopped,
    )
}
