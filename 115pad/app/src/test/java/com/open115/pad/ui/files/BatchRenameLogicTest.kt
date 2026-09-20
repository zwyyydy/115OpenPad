package com.open115.pad.ui.files

import com.open115.pad.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量重命名的算名规则。
 *
 * 全是纯函数，不需要 Android 环境，所以放 test/ 走 JVM 单测 —— 这些字符串边界
 * （扩展名怎么切、中文数字怎么写、Excel 式字母怎么进位）靠肉眼对预览很难发现错。
 */
class BatchRenameLogicTest {

    private fun item(name: String, fid: String = name) = FileItem(fid = fid, fn = name)

    private fun names(vararg input: String) = input.map { item(it) }

    // ---------------- 扩展名切分 ----------------

    @Test
    fun `splitExt 只认最后一段扩展名`() {
        assertEquals("a.tar" to ".gz", splitExt("a.tar.gz"))
        assertEquals("platform" to ".zip", splitExt("platform.zip"))
        assertEquals("noext" to "", splitExt("noext"))
        // 前导点不算扩展名，否则会得到「空主体 + .gitignore」
        assertEquals(".gitignore" to "", splitExt(".gitignore"))
        // 点后面为空也不算
        assertEquals("trailing." to "", splitExt("trailing."))
        assertEquals("" to "", splitExt(""))
    }

    // ---------------- 序号写法 ----------------

    @Test
    fun `阿拉伯数字支持补零`() {
        assertEquals("1", formatNumber(1, NumberKind.ARABIC, pad = 0))
        assertEquals("007", formatNumber(7, NumberKind.ARABIC, pad = 3))
        assertEquals("100", formatNumber(100, NumberKind.ARABIC, pad = 3))
    }

    @Test
    fun `中文小写数字`() {
        val cases = mapOf(
            1 to "一", 9 to "九", 10 to "十", 11 to "十一", 20 to "二十",
            99 to "九十九", 100 to "一百", 101 to "一百零一", 110 to "一百一十",
            1000 to "一千", 10000 to "一万", 10001 to "一万零一", 12345 to "一万二千三百四十五",
        )
        cases.forEach { (n, expect) ->
            assertEquals("中文小写 $n", expect, formatNumber(n, NumberKind.CN_LOWER))
        }
    }

    @Test
    fun `中文大写数字`() {
        assertEquals("壹", formatNumber(1, NumberKind.CN_UPPER))
        // 大写不套用「一十 → 十」的口语化简
        assertEquals("壹拾", formatNumber(10, NumberKind.CN_UPPER))
        assertEquals("壹佰零壹", formatNumber(101, NumberKind.CN_UPPER))
    }

    @Test
    fun `英文字母按 Excel 列式进位`() {
        // 不是普通 26 进制：没有「0 位」，所以 26→Z、27→AA（不是 BA）
        assertEquals("a", formatNumber(1, NumberKind.EN_LOWER))
        assertEquals("z", formatNumber(26, NumberKind.EN_LOWER))
        assertEquals("aa", formatNumber(27, NumberKind.EN_LOWER))
        assertEquals("az", formatNumber(52, NumberKind.EN_LOWER))
        assertEquals("ba", formatNumber(53, NumberKind.EN_LOWER))
        assertEquals("A", formatNumber(1, NumberKind.EN_UPPER))
        assertEquals("AA", formatNumber(27, NumberKind.EN_UPPER))
    }

    // ---------------- 自动编号 ----------------

    /**
     * 对照参考截图里的硬期望值：选 7 个文件、开始序号 1、阿拉伯数字，
     * 应得 1.txt / 2.py / 3.zip / 4.jpg / 5.html / 6.xlsx / 7.txt。
     */
    @Test
    fun `自动编号整个主体名换成序号且保留扩展名`() {
        val items = names("1.txt", "ip.py", "platform.zip", "R-C.jpg", "rce_page.html", "url_to_ip.xlsx", "urls.txt")
        val cfg = RenameConfig(
            mode = RenameMode.AUTO_NUMBER,
            number = NumberConfig(start = 1, kind = NumberKind.ARABIC),
        )
        val got = computeNewNames(items, cfg).map { it.newName }
        assertEquals(
            listOf("1.txt", "2.py", "3.zip", "4.jpg", "5.html", "6.xlsx", "7.txt"),
            got,
        )
    }

    @Test
    fun `自动编号支持前后字符与固定位数`() {
        val items = names("a.txt", "b.txt")
        val cfg = RenameConfig(
            mode = RenameMode.AUTO_NUMBER,
            number = NumberConfig(start = 3, kind = NumberKind.ARABIC, pad = 3, prefix = "第", suffix = "集"),
        )
        assertEquals(listOf("第003集.txt", "第004集.txt"), computeNewNames(items, cfg).map { it.newName })
    }

    // ---------------- 查找替换 ----------------

    private fun replace(name: String, rule: RenameRule, includeExt: Boolean = false): String =
        computeNewNames(
            listOf(item(name)),
            RenameConfig(mode = RenameMode.FIND_REPLACE, includeExt = includeExt, rules = listOf(rule)),
        ).single().newName

    @Test
    fun `文本方式替换所有出现位置且不动扩展名`() {
        assertEquals("Platform.zip", replace("platform.zip", RenameRule(find = "p", replaceWith = "P")))
        // 扩展名不参与，所以 zip 里的 p 不受影响
        assertEquals("Platform.zip", replace("platform.zip", RenameRule(find = "p", replaceWith = "P")))
        // 勾上扩展名才动它（只有 p 变大写，z 不受影响）
        assertEquals("Platform.ziP", replace("platform.zip", RenameRule(find = "p", replaceWith = "P"), includeExt = true))
    }

    @Test
    fun `七种选段方式`() {
        val n = "platform.zip"
        fun rule(p: FindPattern, find: String = "", n: Int = 1, x: Int = 1, to: String = "X") =
            RenameRule(pattern = p, find = find, replaceWith = to, n = n, x = x)

        // platform 去掉前 3 位「pla」剩「tform」
        assertEquals("Xtform.zip", replace(n, rule(FindPattern.FIRST_N, n = 3)))
        assertEquals("platfX.zip", replace(n, rule(FindPattern.LAST_N, n = 3)))          // orm
        assertEquals("pXform.zip", replace(n, rule(FindPattern.FROM_X_N, n = 3, x = 2))) // lat
        assertEquals("plaX.zip", replace(n, rule(FindPattern.AFTER_CHAR, find = "a")))   // tform
        assertEquals("Xform.zip", replace(n, rule(FindPattern.BEFORE_CHAR, find = "f"))) // plat
        assertEquals("plaXorm.zip", replace(n, rule(FindPattern.AFTER_CHAR_N, find = "a", n = 2)))  // tf
        assertEquals("platfXm.zip", replace(n, rule(FindPattern.BEFORE_CHAR_N, find = "m", n = 2))) // or
    }

    @Test
    fun `匹配不上就原样保留`() {
        assertEquals("platform.zip", replace("platform.zip", RenameRule(find = "zzz", replaceWith = "X")))
        assertEquals("platform.zip", replace("platform.zip", RenameRule(FindPattern.BEFORE_CHAR, find = "z", replaceWith = "X")))
        // 'a' 在开头，前面没内容
        assertEquals("abc.zip", replace("abc.zip", RenameRule(FindPattern.BEFORE_CHAR, find = "a", replaceWith = "X")))
        // N 为 0 不合法，不做事
        assertEquals("platform.zip", replace("platform.zip", RenameRule(FindPattern.FIRST_N, n = 0, replaceWith = "X")))
    }

    @Test
    fun `多条规则按顺序叠加`() {
        val cfg = RenameConfig(
            mode = RenameMode.FIND_REPLACE,
            rules = listOf(
                RenameRule(find = "a", replaceWith = "A"),
                RenameRule(find = "A", replaceWith = "AA"),
            ),
        )
        // 第一条把 a 变 A，第二条再把 A 变 AA —— 顺序有意义
        assertEquals("pAA.zip", computeNewNames(listOf(item("pa.zip")), cfg).single().newName)
    }

    // ---------------- 插入内容 ----------------

    @Test
    fun `插入序号在末尾且保留扩展名`() {
        val items = names("1.txt", "ip.py", "platform.zip")
        val cfg = RenameConfig(
            mode = RenameMode.INSERT,
            insertKind = InsertKind.NUMBER,
            insertPos = InsertPosition.TAIL,
            number = NumberConfig(start = 1),
        )
        assertEquals(listOf("11.txt", "ip2.py", "platform3.zip"), computeNewNames(items, cfg).map { it.newName })
    }

    @Test
    fun `插入文本到开头`() {
        val cfg = RenameConfig(
            mode = RenameMode.INSERT,
            insertKind = InsertKind.TEXT,
            insertText = "新_",
            insertPos = InsertPosition.HEAD,
        )
        assertEquals("新_platform.zip", computeNewNames(listOf(item("platform.zip")), cfg).single().newName)
    }

    @Test
    fun `插入文件信息即原文件名`() {
        val cfg = RenameConfig(
            mode = RenameMode.INSERT,
            insertKind = InsertKind.FILE_INFO,
            insertPos = InsertPosition.TAIL,
        )
        assertEquals("platformplatform.zip", computeNewNames(listOf(item("platform.zip")), cfg).single().newName)
    }

    @Test
    fun `指定位置插入且越界自动夹紧`() {
        fun at(pos: Int) = computeNewNames(
            listOf(item("abcd.zip")),
            RenameConfig(mode = RenameMode.INSERT, insertKind = InsertKind.TEXT, insertText = "X", insertPos = InsertPosition.AT, insertAt = pos),
        ).single().newName
        assertEquals("abXcd.zip", at(2))
        assertEquals("Xabcd.zip", at(0))
        // 越界夹到末尾，而不是崩
        assertEquals("abcdX.zip", at(99))
    }

    // ---------------- 手动编辑 ----------------

    @Test
    fun `手动编辑按 fid 取名而不是按下标`() {
        val a = item("a.txt", fid = "1")
        val b = item("b.txt", fid = "2")
        val cfg = RenameConfig(mode = RenameMode.MANUAL, manualByFid = mapOf("2" to "B.txt"))
        val got = computeNewNames(listOf(a, b), cfg).map { it.newName }
        // 只填了 b 的名字，a 原样保留
        assertEquals(listOf("a.txt", "B.txt"), got)
    }

    @Test
    fun `手动编辑留空则回退原名`() {
        val cfg = RenameConfig(mode = RenameMode.MANUAL, manualByFid = mapOf("1" to "   "))
        assertEquals("a.txt", computeNewNames(listOf(item("a.txt", fid = "1")), cfg).single().newName)
    }

    // ---------------- 校验 ----------------

    @Test
    fun `名字合法性`() {
        assertNull(nameError("正常名字.mp4"))
        assertNull(nameError("with space.txt"))
        assertEquals("名称为空", nameError("   "))
        assertEquals("含非法字符 \\/:*?\"<>|", nameError("a/b.txt"))
        assertEquals("含非法字符 \\/:*?\"<>|", nameError("a:b.txt"))
        // 255 字节是上限，汉字算 3 字节 → 85 个汉字 = 255 字节，刚好合法
        assertNull(nameError("字".repeat(85)))
        assertEquals("超过 255 字节", nameError("字".repeat(86)))
    }

    @Test
    fun `批内重名只标记改动过的行`() {
        val items = names("a.txt", "b.txt", "c.txt")
        // 三条都改成同一个名字
        val cfg = RenameConfig(
            mode = RenameMode.MANUAL,
            manualByFid = mapOf("a.txt" to "same.txt", "b.txt" to "same.txt", "c.txt" to "same.txt"),
        )
        val previews = computeNewNames(items, cfg)
        assertEquals(setOf(0, 1, 2), duplicateIndices(previews))

        // 两条改成同一个名字，第三条没动 → 不参与重名判定
        val cfg2 = RenameConfig(
            mode = RenameMode.MANUAL,
            manualByFid = mapOf("a.txt" to "same.txt", "b.txt" to "same.txt"),
        )
        assertEquals(setOf(0, 1), duplicateIndices(computeNewNames(items, cfg2)))
    }

    @Test
    fun `名字没变的行标为 unchanged`() {
        // 开始序号 1 时第一条 1.txt 正好等于原名
        val cfg = RenameConfig(mode = RenameMode.AUTO_NUMBER, number = NumberConfig(start = 1))
        val previews = computeNewNames(names("1.txt", "ip.py"), cfg)
        assertTrue(!previews[0].changed)
        assertTrue(previews[1].changed)
    }
}
