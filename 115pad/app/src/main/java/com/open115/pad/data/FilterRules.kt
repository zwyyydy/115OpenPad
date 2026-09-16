package com.open115.pad.data

import kotlinx.serialization.Serializable

/**
 * 高级文件过滤系统 —— 数据模型与求值。
 *
 * 设计要点（对应需求）：
 * - [RuleGroup] 是可嵌套的 AND/OR 条件组（树），叶子是 [RuleCondition]
 * - [FilterScheme] 是一条"方案"：条件树 + 生效范围（全局默认 / 指定目录绑定）
 * - [resolveScheme] 实现"子目录就近继承"：当前目录 > 最近祖先 > 全局默认 > 不过滤
 * - [evaluateRule] 是纯函数；**文件夹(fc=0)强制保留**——否则一旦没命中，
 *   用户连子目录都进不去，整个网盘就成了死胡同
 */
object FilterRules {

    // ---------------- 条件树 ----------------

    /** 组内条件的组合方式 */
    @Serializable
    enum class Combinator { ALL, ANY } // ALL = 且(AND)，ANY = 或(OR)

    /** 命中之后的动作：保留命中的 / 排除命中的 */
    @Serializable
    enum class FilterAction { KEEP_MATCHED, EXCLUDE_MATCHED }

    /** 叶子条件的种类 */
    @Serializable
    enum class ConditionKind(val label: String, val needsText: Boolean, val needsSize: Boolean, val needsType: Boolean) {
        NAME_CONTAINS("文件名包含", needsText = true, needsSize = false, needsType = false),
        NAME_EXCLUDE("文件名排除", needsText = true, needsSize = false, needsType = false),
        NAME_REGEX("文件名正则", needsText = true, needsSize = false, needsType = false),
        SIZE_GT("大小大于", needsText = false, needsSize = true, needsType = false),
        SIZE_LT("大小小于", needsText = false, needsSize = true, needsType = false),
        TYPE_IS("类型为", needsText = false, needsSize = false, needsType = true),
    }

    /** 叶子条件（参数按 kind 取用） */
    @Serializable
    data class RuleCondition(
        val kind: ConditionKind,
        val text: String = "",        // 关键词 / 正则
        val bytes: Long = 0,          // 大小阈值（字节）；TYPE_IS 时存类型枚举值
        val ignoreCase: Boolean = true,
    )

    /** 条件组（树节点）：conditions 与 groups 一起参与 AND/OR 组合 */
    @Serializable
    data class RuleGroup(
        val mode: Combinator = Combinator.ALL,
        val conditions: List<RuleCondition> = emptyList(),
        val groups: List<RuleGroup> = emptyList(),
        val action: FilterAction = FilterAction.KEEP_MATCHED,
    ) {
        val isEmpty: Boolean get() = conditions.isEmpty() && groups.isEmpty()
    }

    // ---------------- 方案与生效范围 ----------------

    /** 生效范围：全局默认兜底，或绑定到具体目录（含子目录就近继承） */
    @Serializable
    enum class Scope(val label: String) {
        GLOBAL("全局默认"),
        DIRS("指定目录"),
    }

    /** 绑定的生效目录（来自可视化目录选择器，绝不手输 ID） */
    @Serializable
    data class BoundDir(
        val cid: String,
        val name: String,
        val fullPath: String,
    )

    /** 一条过滤方案 */
    @Serializable
    data class FilterScheme(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val group: RuleGroup = RuleGroup(),
        val scope: Scope = Scope.GLOBAL,
        val dirs: List<BoundDir> = emptyList(),
    )

    // ---------------- 求值（纯函数） ----------------

    /**
     * 单个文件是否通过方案。
     * 文件夹（fc=0）无条件保留：目录是导航骨架，不参与文件名/大小过滤。
     */
    fun evaluate(file: FileItem, group: RuleGroup): Boolean {
        if (file.isDir) return true
        return evalGroup(file, group)
    }

    private fun evalGroup(file: FileItem, group: RuleGroup): Boolean {
        // 空组视为"不过滤"。尤其空 OR 组若按"全不命中"处理，会把整个目录筛成空的
        if (group.isEmpty) return true

        val results = sequence {
            yieldAll(group.conditions.map { evalCondition(file, it) })
            yieldAll(group.groups.map { evalGroup(file, it) })
        }.toList()

        val hit = when (group.mode) {
            Combinator.ALL -> results.all { it }
            Combinator.ANY -> results.any { it }
        }
        return when (group.action) {
            FilterAction.KEEP_MATCHED -> hit
            FilterAction.EXCLUDE_MATCHED -> !hit
        }
    }

    private fun evalCondition(file: FileItem, c: RuleCondition): Boolean = when (c.kind) {
        ConditionKind.NAME_CONTAINS -> file.fn.contains(c.text, c.ignoreCase)
        ConditionKind.NAME_EXCLUDE -> !file.fn.contains(c.text, c.ignoreCase)
        // 非法正则按"未命中"处理：绝不能让一条写错的规则把整个列表搞崩
        ConditionKind.NAME_REGEX -> runCatching {
            if (c.ignoreCase) Regex(c.text, RegexOption.IGNORE_CASE) else Regex(c.text)
        }.getOrNull()?.containsMatchIn(file.fn) ?: false
        ConditionKind.SIZE_GT -> file.fs > c.bytes
        ConditionKind.SIZE_LT -> file.fs in 1 until c.bytes
        ConditionKind.TYPE_IS -> file.guessType() == c.bytes.toInt()
    }

    // ---------------- 子目录就近继承 ----------------

    /** 与目录层级无关的轻量描述，避免 filter 包反向依赖 UI 的 UiState */
    data class DirRef(val cid: String, val name: String)

    /**
     * 就近继承解析（优先级从高到低）：
     * ① 当前目录显式绑定 ② 沿祖先链由近及远 ③ 全局默认 ④ 无规则（不过滤）
     *
     * @param stack 根 → 当前 的目录链（FilesViewModel 的 stack 恰好就是这个语义）
     * @return 生效的方案；null 表示该目录不执行过滤
     *
     * 同一目录被多个已启用方案绑定时，取方案列表中靠前的（列表顺序即优先级）。
     */
    fun resolveScheme(schemes: List<FilterScheme>, stack: List<DirRef>): FilterScheme? {
        val enabled = schemes.filter { it.enabled }
        if (enabled.isEmpty() || stack.isEmpty()) return null
        val current = stack.last()

        // ① 当前目录专属
        boundTo(enabled, current.cid)?.let { return it }

        // ② 沿 path[] 由近及远追溯父级（不含当前目录）
        for (dir in stack.dropLast(1).reversed()) {
            boundTo(enabled, dir.cid)?.let { return it }
        }

        // ③ 全局默认兜底；④ 没有则返回 null
        return enabled.firstOrNull { it.scope == Scope.GLOBAL }
    }

    private fun boundTo(schemes: List<FilterScheme>, cid: String): FilterScheme? =
        schemes.firstOrNull { it.scope == Scope.DIRS && it.dirs.any { d -> d.cid == cid } }

    // ---------------- 类型推断（TYPE_IS 条件用） ----------------

    /**
     * 按后缀推断 115 的文件类型枚举（1文档 2图片 3音频 4视频 5压缩包 6应用 7书籍）。
     * 列表接口不返回 type 字段，这里用后缀近似；未识别的后缀返回 null（TYPE_IS 视为不命中）。
     */
    fun FileItem.guessType(): Int? {
        val ext = ico?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (ext) {
            in TYPE_DOC -> 1
            in TYPE_IMAGE -> 2
            in TYPE_AUDIO -> 3
            in TYPE_VIDEO -> 4
            in TYPE_ARCHIVE -> 5
            in TYPE_APP -> 6
            in TYPE_BOOK -> 7
            else -> null
        }
    }

    val TYPE_LABELS = listOf(
        1 to "文档", 2 to "图片", 3 to "音频", 4 to "视频", 5 to "压缩包", 6 to "应用", 7 to "书籍",
    )

    private val TYPE_VIDEO = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "m4v", "ts", "m2ts", "rmvb", "rm",
        "webm", "mpg", "mpeg", "vob", "3gp", "asf",
    )
    private val TYPE_AUDIO = setOf("mp3", "flac", "ape", "wav", "aac", "m4a", "dts", "ogg", "wma", "opus")
    private val TYPE_IMAGE = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif", "svg", "avif",
    )
    private val TYPE_DOC = setOf(
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "chm", "caj", "csv", "rtf", "wps", "et", "dps",
    )
    private val TYPE_ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso", "cab", "jar")
    private val TYPE_APP = setOf("apk", "exe", "msi", "dmg", "pkg", "ipa", "deb", "rpm", "appimage", "bat", "sh")
    private val TYPE_BOOK = setOf("pdf", "epub", "mobi", "azw3", "azw", "djvu", "fb2")
}
