package com.open115.pad.data

import java.io.File

/**
 * 本地视频预览缓存，两块内容：
 *
 * - **封面**：`cache/images/vpreview/vp_<pick_code 哈希>.jpg`，一张，长边 640、约 40KB
 *   —— 列表左侧小方块 / 网格卡 / 去重面板的缩略图用它；
 * - **预览条**：`cache/images/vstrip/vs_<pick_code 哈希>_<序号>.jpg`，2~10 张（按片长，见
 *   [stripFrameCount]），长边 320、约 10~15KB —— 列表里视频行下面那条宽幅横排图用它。
 *
 * 与已有图片缓存（`huge_img` / `media_img`）**同一套约定**：按 pick_code 命名、挂在
 * `cacheDir/images` 下、占用/上限/清理都走 companion 方法。显示时零请求、纯本地读。
 *
 * 键是**视频的 pick_code**（不是图片的），文件页列表项里现成就有。
 *
 * **上限与淘汰**：上限来自设置页（[PreviewPrefs.maxMb]，注入成 [maxBytes]），
 * **每次写入后自己检查一遍**——到顶就把最久没看的清掉（封面与预览条一起算）。
 * 让 store 自己管（而不是要求各处调用方记得剪）是因为写入点有三个：播放器开播、
 * 文件页批量生成、长按截图设为预览图 —— 少一处就是"上限形同虚设"。
 */
class VideoPreviewStore(
    private val root: File,
    /** 上限字节数（0 = 不限）。默认 [DEFAULT_MAX_BYTES]，运行时由设置页改 */
    private val maxBytes: () -> Long = { DEFAULT_MAX_BYTES },
) {

    /**
     * 缓存版本号：每写进一张新图就 +1。
     *
     * 文件页列表是在组合里"看到"预览的（本地文件命中就直接当缩略图/预览条），而生成发生在别处
     * （播放器里、批量任务里）——没有这个信号，用户从播放器回到列表时那一行还是旧角标。
     */
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /**
     * pick_code 哈希 → 预览条帧数。
     *
     * 数量不写在文件名里，但**必须 O(1) 查到**（列表里每一行都要问一次）；所以启动后列一次
     * 预览条目录、把 `vs_<hash>_<i>.jpg` 解成"每个哈希有几张"，之后只在写入时失效重算。
     */
    @Volatile
    private var stripIndex: Map<Int, Int>? = null

    /** 封面缓存目录（设置页算占用/清理共用同一份定义） */
    fun dir(): File = File(root, COVER_DIR)

    /** 预览条缓存目录 */
    fun stripDir(): File = File(root, STRIP_DIR)

    /**
     * 这个 pick_code 的封面文件（**不保证存在**）。
     *
     * 命名与 [ImageUrlResolver] 的 `img_<hash>.bin` 同规则：`String.hashCode()` 是规范固定的，
     * 跨进程/跨版本稳定。
     */
    fun fileFor(pickCode: String): File = File(dir(), "vp_${pickCode.hashCode()}.jpg")

    /** 预览条第 [index] 张（index 从 0 起，**不保证存在**） */
    fun stripFileFor(pickCode: String, index: Int): File =
        File(stripDir(), "vs_${pickCode.hashCode()}_$index.jpg")

    /** 已缓存封面？显示路径每次都会问（一个目录最多几百次 stat，可忽略） */
    fun has(pickCode: String): Boolean = fileFor(pickCode).let { it.isFile && it.length() > 0L }

    /** 预览条有几张（0 = 没有）。只查内存索引，不列目录 */
    fun stripCount(pickCode: String): Int = index()[pickCode.hashCode()] ?: 0

    /**
     * 这个 pick_code 的预览条**本机文件**（按序号排，已存在的才给）。
     *
     * 给"库里没有剧照的片"当剧照用（详情页兜底，见 MediaDetailScreen）：抓来的帧本来只在
     * 文件页当横排缩略图，这里让它顶替缺失的剧照。张数以内存索引为准，缺号跳过。
     */
    fun stripFiles(pickCode: String): List<File> =
        (0 until stripCount(pickCode))
            .map { stripFileFor(pickCode, it) }
            .filter { it.isFile && it.length() > 0L }

    /** 写一张封面（覆盖旧的，并刷新 mtime 供 Coil 判新旧 + LRU 淘汰）。写完顺手看一遍上限 */
    fun put(pickCode: String, bytes: ByteArray): File? {
        val d = dir()
        if (!d.exists() && !d.mkdirs()) return null
        val f = fileFor(pickCode)
        return try {
            f.writeBytes(bytes)
            f.setLastModified(System.currentTimeMillis())
            _version.value++
            prune()
            f
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写一组预览条（覆盖旧的），返回实际写成功的张数。
     *
     * **多出来的旧张要删掉**：片长变了（换源/重抓）或帧数变少时，留着尾巴会出现
     * "预览条长度和视频对不上"。null 的位跳过（那一帧没抓到）。
     */
    fun putStrip(pickCode: String, frames: List<ByteArray?>): Int {
        val d = stripDir()
        if (!d.exists() && !d.mkdirs()) return 0
        var written = 0
        frames.forEachIndexed { i, bytes ->
            val f = stripFileFor(pickCode, i)
            if (bytes == null || bytes.isEmpty()) return@forEachIndexed
            val ok = try {
                f.writeBytes(bytes)
                f.setLastModified(System.currentTimeMillis())
                true
            } catch (e: Exception) {
                false
            }
            if (ok) written++
        }
        // 删掉这一组之外的尾巴（write 失败时也照删，避免"旧的还在、新的没写成"更乱）
        var i = frames.size
        while (true) {
            val f = stripFileFor(pickCode, i)
            if (!f.exists()) break
            f.delete()
            i++
        }
        if (written > 0) {
            stripIndex = null
            _version.value++
            prune()
        }
        return written
    }

    /** 封面 + 预览条的总占用 */
    fun sizeBytes(): Long =
        (dir().listFiles()?.sumOf { it.length() } ?: 0L) + (stripDir().listFiles()?.sumOf { it.length() } ?: 0L)

    /** 清空（设置页入口）。删不掉的留在原地，调用方重读 size 即可发现 */
    fun clear() {
        dir().listFiles()?.forEach { it.delete() }
        stripDir().listFiles()?.forEach { it.delete() }
        stripIndex = null
        _version.value++
    }

    /**
     * 删掉这些 pick_code 的预览（封面 + 它那组预览条一起），返回删掉的文件数。
     *
     * 用在**云端文件真删掉**的时候：媒体库「彻底删除」、回收站「彻底删除 / 清空」。
     * 那些 pick_code 再也不会被引用，留着只是占地方（每部片 40KB 封面 + 2~10 帧预览条）。
     *
     * **移入回收站不要调**：还能还原回来，还原后重新抓一遍要花流量（每帧 376KB）。
     *
     * 按文件名认哈希而不是按内存索引：索引只记"有几张"，文件被别处删过/索引还没建好时
     * 会漏删；这里列两个目录（每批一次，不是每个 pc 一次）逐个比对。
     */
    fun deleteFor(pickCodes: Collection<String>): Int {
        val hashes = pickCodes.filter { it.isNotBlank() }.map { it.hashCode() }.toSet()
        if (hashes.isEmpty()) return 0
        var removed = 0
        for (d in listOf(dir(), stripDir())) {
            d.listFiles()?.forEach { f ->
                val h = hashOf(f.name) ?: return@forEach
                if (h in hashes && f.delete()) removed++
            }
        }
        if (removed > 0) {
            stripIndex = null
            _version.value++
        }
        return removed
    }

    /**
     * 按"最久没看"淘汰：**超上限就从最旧的一部片开始整组删，删到上限的 [PRUNE_TARGET_RATIO]**。
     *
     * 为什么**整组**删（封面 + 那部片的预览条一起走）而不是按文件删：逐文件删会把一条预览条
     * 删出洞 —— 帧数索引还报着"有 8 张"，列表里就是 8 个格里几个空框。整组删才对应
     * "这部片的预览彻底没了"，下次翻到它会重新抓。
     *
     * 一组的"新旧"取组内**最新**的 mtime：封面在列表里天天被看到（显示路径会 [touch]），
     * 取最旧的那个会把常看的片误判成最旧、优先清掉。
     *
     * 留 10% 余量（与媒体库缓存 MediaCache.sweep 同一套做法）而不是刚好删到上限：不然
     * 每写一张图就立刻又超、又全量列一次目录。
     *
     * 上限 ≤ 0 视为"不限制"（与本项目其它地方对 0 的约定一致），不是"删光"。
     * 写入路径已自动调用；这里保持 public 供"上限调小后立刻生效"用。
     */
    fun prune() {
        val limit = maxBytes()
        if (limit <= 0) return
        val files = (dir().listFiles()?.toList() ?: emptyList()) + (stripDir().listFiles()?.toList() ?: emptyList())
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        val target = limit * PRUNE_TARGET_RATIO / 100
        // 按 pick_code 哈希成组（封面与预览条共用一套命名，见 fileFor/stripFileFor）
        val groups = files.groupBy { hashOf(it.name) }
        var removed = 0
        for (group in groups.entries.sortedBy { (_, fs) -> fs.maxOf { it.lastModified() } }) {
            if (total <= target) break
            for (f in group.value) {
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    removed++
                }
            }
            stripIndex = null
        }
        if (removed > 0) {
            _version.value++
            android.util.Log.i(
                "VPreview",
                "预览缓存淘汰 $removed 个文件，剩 ${total / 1024} KB / 上限 ${limit / 1024} KB",
            )
        }
    }

    /** 标记被看过（LRU 用；显示路径命中时顺手一调） */
    fun touch(pickCode: String) {
        runCatching { fileFor(pickCode).setLastModified(System.currentTimeMillis()) }
    }

    /**
     * 从文件名取 pick_code 哈希（淘汰时按它把"一部片的封面 + 预览条"归成一组）。
     *
     * 命名两套：`vp_<hash>.jpg` / `vs_<hash>_<i>.jpg`。取不出来的（不该存在）归到 null，
     * 单独成一组、算最旧，先被清掉。
     */
    private fun hashOf(name: String): Int? = when {
        name.startsWith("vp_") && name.endsWith(".jpg") ->
            name.removePrefix("vp_").removeSuffix(".jpg").toIntOrNull()

        name.startsWith("vs_") && name.endsWith(".jpg") ->
            name.removePrefix("vs_").removeSuffix(".jpg").substringBefore('_').toIntOrNull()

        else -> null
    }

    /** 列一次预览条目录把索引建起来（列表里每行都要问帧数，不能每次都 listFiles） */
    private fun index(): Map<Int, Int> {
        stripIndex?.let { return it }
        val built = HashMap<Int, Int>()
        stripDir().listFiles()?.forEach { f ->
            val name = f.name
            if (!name.startsWith("vs_") || !name.endsWith(".jpg")) return@forEach
            val body = name.removePrefix("vs_").removeSuffix(".jpg")
            val hash = body.substringBefore('_').toIntOrNull() ?: return@forEach
            val idx = body.substringAfter('_', "").toIntOrNull() ?: return@forEach
            built[hash] = maxOf(built[hash] ?: 0, idx + 1)
        }
        stripIndex = built
        return built
    }

    companion object {
        /** 封面缓存目录名 */
        private const val COVER_DIR = "vpreview"

        /** 预览条目录名 */
        private const val STRIP_DIR = "vstrip"

        /** 默认上限（封面 + 预览条一起算）：一张封面约 40KB、一帧预览条约 12KB，512MB 够整库 */
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024

        /** 淘汰目标＝上限的这个百分比（留余量，别每写一张就再列一次目录） */
        private const val PRUNE_TARGET_RATIO = 90
    }
}
