package com.open115.pad.ui.files

import com.open115.pad.data.FileItem

/**
 * 批量重命名的**纯逻辑**：算名字、判合法，不碰网络也不碰 UI。
 *
 * 单独拆出来是因为这里全是字符串边界（扩展名怎么切、中文数字怎么写、Excel 式字母怎么进位），
 * 最容易写错又最难靠肉眼看出错 —— 面板里的预览列表就是它的直接输出，可以逐条对照。
 */

/** 重命名模式（对应界面上的 4 个页签）。智能重命名不做 */
enum class RenameMode(val label: String) {
    FIND_REPLACE("查找替换"),
    INSERT("插入内容"),
    AUTO_NUMBER("自动编号"),
    MANUAL("手动编辑"),
}

/**
 * 查找匹配方式。
 *
 * 七种「选一段」的写法本质上都是**从主体名里圈出一个区间**，所以内部统一成
 * [findSpan] 一个函数，只有 [TEXT] 例外（字面量，要替换**所有**出现位置而不是一段）。
 */
enum class FindPattern(val label: String) {
    TEXT("文本"),
    FIRST_N("前 N 位"),
    LAST_N("后 N 位"),
    FROM_X_N("从第 X 位开始的 N 位"),
    AFTER_CHAR("某字符之后的内容"),
    BEFORE_CHAR("某字符之前的内容"),
    AFTER_CHAR_N("某字符之后的 N 位"),
    BEFORE_CHAR_N("某字符之前的 N 位"),
    ;

    /** 该方式是否要用到「N」（前 N 位 / N 位 …） */
    val usesN: Boolean
        get() = this in setOf(FIRST_N, LAST_N, FROM_X_N, AFTER_CHAR_N, BEFORE_CHAR_N)

    /** 该方式是否要用到「第 X 位」 */
    val usesX: Boolean get() = this == FROM_X_N

    /** 该方式是否要用到「某字符」—— 复用「查找」输入框当这个字符 */
    val usesChar: Boolean
        get() = this in setOf(AFTER_CHAR, BEFORE_CHAR, AFTER_CHAR_N, BEFORE_CHAR_N)
}

/** 序号写法 */
enum class NumberKind(val label: String) {
    ARABIC("阿拉伯数字"),
    CN_LOWER("中文小写数字"),
    CN_UPPER("中文大写数字"),
    EN_LOWER("英文小写字母"),
    EN_UPPER("英文大写字母"),
}

/** 插入位置 */
enum class InsertPosition(val label: String) {
    HEAD("开头"),
    AT("指定位置"),
    TAIL("末尾"),
}

/** 插入内容的类型 */
enum class InsertKind(val label: String) {
    TEXT("文本"),
    NUMBER("序号"),
    FILE_INFO("文件信息"),
}

/**
 * 一条查找替换规则。
 *
 * [find] 一物两用：字面量方式下是「查找的内容」，字符类方式下是「某字符」。
 * 界面上也确实是同一个输入框（截图里「查找」带下拉），没必要拆成两个字段。
 */
data class RenameRule(
    val pattern: FindPattern = FindPattern.TEXT,
    val find: String = "",
    val replaceWith: String = "",
    /** 「前/后 N 位」的 N */
    val n: Int = 1,
    /** 「从第 X 位开始」的 X，从 1 起数 */
    val x: Int = 1,
)

/** 序号的生成参数 */
data class NumberConfig(
    val start: Int = 1,
    val kind: NumberKind = NumberKind.ARABIC,
    /** 固定位数，补零；0 = 不补 */
    val pad: Int = 0,
    val prefix: String = "",
    val suffix: String = "",
)

data class RenameConfig(
    val mode: RenameMode = RenameMode.FIND_REPLACE,
    /** 「替换文件扩展名」：默认关，运算只在主体名上做、扩展名原样保留 */
    val includeExt: Boolean = false,
    val rules: List<RenameRule> = listOf(RenameRule()),
    val insertKind: InsertKind = InsertKind.TEXT,
    val insertText: String = "",
    val insertPos: InsertPosition = InsertPosition.TAIL,
    /** 「指定位置」的插入下标，从 0 起数 */
    val insertAt: Int = 0,
    val number: NumberConfig = NumberConfig(),
    /**
     * 手动编辑：fid → 用户填的**完整新名**（含扩展名，和截图一致）。
     *
     * 用 map 而不是按行下标的 List：面板里删掉一行会让后面的下标整体前移，
     * List 很容易把 A 的名字错安到 B 上；按 fid 存就没这个问题。
     */
    val manualByFid: Map<String, String> = emptyMap(),
)

/** 预览的一行 */
data class RenamePreview(
    val item: FileItem,
    val oldName: String,
    val newName: String,
) {
    /** 名字没变的行不会发请求 —— 既省调用，也避免无谓的写操作 */
    val changed: Boolean get() = newName != oldName
}

/** 打开批量重命名面板所需的全部输入 */
data class BatchRenameRequest(
    /** 初始参与改名的条目，**顺序即编号顺序**（调用方按列表显示顺序传） */
    val items: List<FileItem>,
    /**
     * 当前目录的完整显示列表，供面板里的「全选本目录」用。
     * 传它而不是让用户回文件页重新选一遍 —— 批量改名的典型场景就是"整个目录一起改"。
     */
    val allFiles: List<FileItem>,
    /** 所在目录，只用于写操作记录（侧栏那条记录要能点回原目录） */
    val cid: String,
    val dirName: String,
)

/**
 * 按 [config] 算出每个文件的新名字。
 *
 * 序号**按列表顺序**从「开始序号」递增（预览所见即所得），不用文件在目录里的位置：
 * 后者会让预览出现「从 7 开始、还不连续」的编号，很难解释。
 */
fun computeNewNames(items: List<FileItem>, config: RenameConfig): List<RenamePreview> =
    items.mapIndexed { index, item ->
        val old = item.fn
        val newName = when (config.mode) {
            RenameMode.FIND_REPLACE -> {
                val (base, ext) = if (config.includeExt) old to "" else splitExt(old)
                val replaced = config.rules.fold(base) { acc, rule -> applyRule(acc, rule) }
                replaced + ext
            }

            RenameMode.INSERT -> {
                val (base, ext) = splitExt(old)
                val insert = when (config.insertKind) {
                    InsertKind.TEXT -> config.insertText
                    // 「文件信息」目前只提供原文件名（不含扩展名）一种字段
                    InsertKind.FILE_INFO -> base
                    InsertKind.NUMBER -> numberText(config.number, index)
                }
                val at = when (config.insertPos) {
                    InsertPosition.HEAD -> 0
                    InsertPosition.TAIL -> base.length
                    InsertPosition.AT -> config.insertAt.coerceIn(0, base.length)
                }
                base.substring(0, at) + insert + base.substring(at) + ext
            }

            // 整个主体名换成序号，扩展名保留（截图里 1.txt→1.txt、ip.py→2.py 就是这个行为）
            RenameMode.AUTO_NUMBER -> numberText(config.number, index) + splitExt(old).second

            RenameMode.MANUAL ->
                config.manualByFid[item.fid].orEmpty().takeIf { it.isNotBlank() } ?: old
        }
        RenamePreview(item, old, newName)
    }

/**
 * 拆成「主体 + 扩展名」。扩展名带点，且只认最后一段。
 *
 *   a.tar.gz    → (a.tar, .gz)
 *   .gitignore  → (.gitignore, "")   ← 前导点不算扩展名，否则会得到「空主体 + .gitignore」
 *   noext       → (noext, "")
 *   trailing.   → (trailing., "")    ← 点后面为空，不算扩展名
 */
fun splitExt(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

/** 序号文本 = 前面字符 + 序号 + 后面字符 */
private fun numberText(cfg: NumberConfig, offset: Int): String =
    cfg.prefix + formatNumber(cfg.start + offset, cfg.kind, cfg.pad) + cfg.suffix

/** 把序号格式化成指定写法。[pad] > 0 时补零（只对阿拉伯数字有意义） */
fun formatNumber(value: Int, kind: NumberKind, pad: Int = 0): String = when (kind) {
    NumberKind.ARABIC -> if (pad > 0) value.toString().padStart(pad, '0') else value.toString()
    NumberKind.CN_LOWER -> cnNumber(value, upper = false)
    NumberKind.CN_UPPER -> cnNumber(value, upper = true)
    NumberKind.EN_LOWER -> enLetters(value).lowercase()
    NumberKind.EN_UPPER -> enLetters(value)
}

/**
 * Excel 列式字母进位：1→A、26→Z、27→AA、52→AZ、53→BA。
 * 注意不是普通的 26 进制（没有「0 位」，所以每步要先减 1）。
 */
private fun enLetters(value: Int): String {
    if (value <= 0) return ""
    var x = value
    val sb = StringBuilder()
    while (x > 0) {
        val r = (x - 1) % 26
        sb.append('A' + r)
        x = (x - 1) / 26
    }
    return sb.reverse().toString()
}

/** 中文数字。按「四位一节」处理，支持万/亿进位 */
private fun cnNumber(value: Int, upper: Boolean): String {
    if (value <= 0) return "零"
    val digits = if (upper) "零壹贰叁肆伍陆柒捌玖" else "零一二三四五六七八九"
    val units = if (upper) arrayOf("", "拾", "佰", "仟") else arrayOf("", "十", "百", "千")
    val bigUnits = arrayOf("", "万", "亿")
    val pow = intArrayOf(1, 10, 100, 1000)

    // 先切成四位一节，低位在前
    val sections = ArrayList<Int>()
    var rest = value
    while (rest > 0) {
        sections.add(rest % 10000)
        rest /= 10000
    }

    val sb = StringBuilder()
    for (i in sections.indices.reversed()) {
        val section = sections[i]
        if (section == 0) {
            // 整节为零：后面还有非零节时补一个「零」占位（100000 → 一十万）
            if (sb.isNotEmpty() && !sb.endsWith("零")) sb.append("零")
            continue
        }
        // 低位节不足千位时也要补「零」：10001 读作「一万零一」，不补就成了「一万一」，
        // 和「一万一千」听不出区别。整节为零的情况上面已经处理过了。
        if (section < 1000 && sb.isNotEmpty() && !sb.endsWith("零")) sb.append("零")
        val local = StringBuilder()
        var pendingZero = false
        for (pos in 3 downTo 0) {
            val digit = (section / pow[pos]) % 10
            if (digit == 0) {
                pendingZero = true
            } else {
                if (pendingZero && local.isNotEmpty()) local.append("零")
                pendingZero = false
                local.append(digits[digit]).append(units[pos])
            }
        }
        sb.append(local).append(bigUnits[i])
    }

    var out = sb.toString().trimEnd('零')
    // 口语习惯：小写且在最前面时「一十」写成「十」（10→十、11→十一，但 110→一百一十 不动）
    if (!upper && out.startsWith("一十")) out = out.removePrefix("一")
    return out
}

/** 字面量方式：替换**所有**出现位置；其余方式：圈出一段再替换 */
private fun applyRule(base: String, rule: RenameRule): String {
    if (rule.pattern == FindPattern.TEXT) {
        if (rule.find.isEmpty()) return base
        return base.replace(rule.find, rule.replaceWith)
    }
    val span = findSpan(base, rule) ?: return base
    if (span.isEmpty()) return base
    return base.replaceRange(span.first, span.last + 1, rule.replaceWith)
}

/**
 * 按匹配方式圈出要替换的区间；返回 null = 没匹配上（原样保留）。
 *
 * 区间一律是**左闭右闭**（`IntRange`），方便 `replaceRange` 直接吃。
 */
private fun findSpan(base: String, rule: RenameRule): IntRange? {
    if (base.isEmpty()) return null
    val len = base.length
    val n = rule.n
    return when (rule.pattern) {
        FindPattern.TEXT -> null

        FindPattern.FIRST_N ->
            if (n <= 0) null else 0..(minOf(n, len) - 1)

        FindPattern.LAST_N ->
            if (n <= 0) null else maxOf(0, len - n)..(len - 1)

        FindPattern.FROM_X_N -> {
            val from = rule.x - 1
            if (n <= 0 || from !in 0 until len) null else from..(minOf(from + n, len) - 1)
        }

        FindPattern.AFTER_CHAR -> {
            val c = rule.find
            if (c.isEmpty()) null
            else {
                val i = base.indexOf(c)
                if (i < 0) null else (i + c.length)..(len - 1)
            }
        }

        FindPattern.BEFORE_CHAR -> {
            val c = rule.find
            if (c.isEmpty()) null
            else {
                val i = base.indexOf(c)
                // i == 0 时前面什么都没有，返回 null 而不是空区间
                if (i <= 0) null else 0..(i - 1)
            }
        }

        FindPattern.AFTER_CHAR_N -> {
            val c = rule.find
            if (c.isEmpty() || n <= 0) null
            else {
                val i = base.indexOf(c)
                val from = if (i < 0) -1 else i + c.length
                if (from < 0 || from >= len) null else from..(minOf(from + n, len) - 1)
            }
        }

        FindPattern.BEFORE_CHAR_N -> {
            val c = rule.find
            if (c.isEmpty() || n <= 0) null
            else {
                val i = base.indexOf(c)
                if (i <= 0) null else maxOf(0, i - n)..(i - 1)
            }
        }
    }
}

/**
 * 名字是否合法；返回 null 表示合法，否则是给用户看的原因。
 *
 * 255 字节是 115 文档明写的上限（注意是**字节**不是字符，一个汉字 3 字节）。
 * 非法字符沿用 Windows 那一套 —— 115 没写，但云盘普遍会拒，提前拦掉比让接口报错好。
 */
fun nameError(name: String): String? {
    if (name.isBlank()) return "名称为空"
    if (name.toByteArray(Charsets.UTF_8).size > 255) return "超过 255 字节"
    if (name.any { it in ILLEGAL_NAME_CHARS }) return "含非法字符 $ILLEGAL_NAME_CHARS"
    return null
}

private const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"

/**
 * 同一批里重名的行下标。
 *
 * 只查批内，不查「与目录里没选中的文件是否撞名」—— 那需要把整个目录列表传进来，
 * 而目录是分页加载的，拿不全。撞名时接口会拒，届时按单条失败如实报出来。
 */
fun duplicateIndices(previews: List<RenamePreview>): Set<Int> {
    val byName = HashMap<String, MutableList<Int>>()
    previews.forEachIndexed { i, p ->
        if (p.changed) byName.getOrPut(p.newName) { mutableListOf() }.add(i)
    }
    return byName.values.filter { it.size > 1 }.flatten().toSet()
}
