package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流水线同步（[PadSync]）里那几段**纯逻辑**：信号解析、游标过滤、路径归属、逐级上溯。
 *
 * 抽出来测的理由：这几段各自都有一个"错了很难当场发现"的失效方式 ——
 *  - 信号读错：这一批变动的目录全丢，App 那边表现为"服务器明明刮完了，媒体库没动"；
 *  - 游标比错：要么重复处理（每次轮询都把同一批目录排一遍），要么**永远跳过**新信号；
 *  - 路径归属算错：变动的目录被丢给错误的库（甚至丢掉），条目进不了海报墙。
 *
 * 需要真网络/真库的那一半（列目录、换 cid、清缓存、入队）不在这里测 —— 真机手测更实在。
 */
class PadSyncTest {

    // ---- 信号解析 ----

    @Test
    fun `解析服务器写的信号`() {
        val s = parseSignal(
            """
            {
              "v": 1, "ts": "2026-09-28T10:31:38", "source": "pipeline", "run": "20260928-103138",
              "codes": ["ABC-123"], "actors": ["演员名"],
              "dirs": ["/媒体库根/JAV_output/演员名/ABC-123 演员名", "/媒体库根/failed"],
              "gone": ["/媒体库根/ABC-123"]
            }
            """.trimIndent(),
        )!!
        assertEquals("20260928-103138", s.run)
        assertEquals(listOf("/媒体库根/JAV_output/演员名/ABC-123 演员名", "/媒体库根/failed"), s.dirs)
        assertEquals(listOf("/媒体库根/ABC-123"), s.gone)
        assertEquals(listOf("ABC-123"), s.codes)
    }

    @Test
    fun `信号里多出不认识的字段也能读`() {
        // 服务器以后加字段（补上来的版本比 App 新）不该让整批信号读不出来
        val s = parseSignal("""{"run":"x","dirs":["/a"],"futureField":{"deep":1}}""")!!
        assertEquals(listOf("/a"), s.dirs)
        assertTrue(s.gone.isEmpty())
    }

    @Test
    fun `只有一个 run 的骨架信号_读出来是空目录而不是 null`() {
        // 服务器加了"只报进度、没有目录变更"的信号时，App 不该把它当坏文件退化成整库扫描
        val s = parseSignal("""{"v":1,"run":"20260928-103138"}""")!!
        assertTrue(s.dirs.isEmpty())
        assertTrue(s.gone.isEmpty())
    }

    @Test
    fun `格式坏的信号解析成 null_由上层退化成整库扫描`() {
        assertNull(parseSignal("""{"run": """))
        assertNull(parseSignal("不是 JSON"))
        assertNull(parseSignal(""))
    }

    // ---- 游标 ----

    @Test
    fun `游标只放行更新的信号_并按时间序处理`() {
        val names = listOf("20260928-104000.json", "20260928-103000.json", "20260928-102000.json")
        assertEquals(
            listOf("20260928-103000.json", "20260928-104000.json"),
            newSignalNames(cursor = "20260928-102000.json", names = names),
        )
    }

    @Test
    fun `游标为空时全部算新`() {
        assertEquals(listOf("a.json"), newSignalNames(cursor = "", names = listOf("a.json")))
    }

    @Test
    fun `已经处理过的游标不放行任何东西`() {
        assertTrue(newSignalNames("20260928-104000.json", listOf("20260928-104000.json")).isEmpty())
    }

    @Test
    fun `非 json 的文件不算信号`() {
        assertTrue(newSignalNames("", listOf("readme.txt", ".DS_Store", "tmp.json.tmp")).isEmpty())
    }

    // ---- 路径归属（取最长匹配的库根）----

    private fun library(id: Long, path: String) =
        MediaLibraryEntity(id = id, name = "库$id", rootCid = "c$id", rootPath = path)

    @Test
    fun `库根嵌套时认最长的那个`() {
        val libs = listOf(library(1, "/媒体库根"), library(2, "/媒体库根/JAV_output"))
        assertEquals(2L, libraryForPath("/媒体库根/JAV_output/演员/番号 演员", libs)?.library?.id)
        assertEquals(1L, libraryForPath("/媒体库根/failed", libs)?.library?.id)
    }

    @Test
    fun `不在任何库根下的路径丢掉`() {
        val libs = listOf(library(1, "/媒体库根/JAV_output"))
        assertNull(libraryForPath("/别的目录/x", libs))
        // 前缀相同但不是一个目录：`/媒体库根/JAV_output2` 不属于 `/媒体库根/JAV_output`
        assertNull(libraryForPath("/媒体库根/JAV_output2/x", libs))
        // 库根自己也算（多根库里它就是一根）
        assertEquals(1L, libraryForPath("/媒体库根/JAV_output", libs)?.library?.id)
    }

    @Test
    fun `多根库的每一根都能命中`() {
        val lib = MediaLibraryEntity(
            id = 1, name = "库1",
            rootCid = "c1\nc2",
            rootPath = "/媒体库根/JAV_output\n/另一棵树",
        )
        assertEquals(1L, libraryForPath("/另一棵树/演员/番号", listOf(lib))?.library?.id)
        assertEquals(1L, libraryForPath("/媒体库根/JAV_output/演员/番号", listOf(lib))?.library?.id)
    }

    @Test
    fun `库根不带前导斜杠也能匹配_并换算成该库的写法`() {
        // 实测同一个账号下两种写法都有：`媒体库/子目录/...` 与 `/另一个库`。
        // 服务器写的永远是 `/媒体库/子目录/JAV_output/...` 这种带斜杠的绝对路径 ——
        // 只比字符串的话，不带斜杠的那个库整库匹配不上。
        val lib = library(3, "媒体库/子目录/示例演员/JAV_output/示例演员")
        val m = libraryForPath(
            "/媒体库/子目录/示例演员/JAV_output/示例演员/ABC-123-4K-C 示例演员", listOf(lib),
        )!!
        assertEquals(3L, m.library.id)
        // 换写成库自己的写法：写进 dirPath 的形式必须跟历史行一致，否则同一目录会出现两份索引
        assertEquals("媒体库/子目录/示例演员/JAV_output/示例演员/ABC-123-4K-C 示例演员", m.path)
    }

    @Test
    fun `库根带前导斜杠时路径保持原样`() {
        val lib = library(1, "/另一个库")
        val m = libraryForPath("/另一个库/示例剧集 (2021)", listOf(lib))!!
        assertEquals("/另一个库/示例剧集 (2021)", m.path)
    }

    // ---- 逐级上溯 ----

    @Test
    fun `上溯候选从最深的父目录开始_到库根为止`() {
        assertEquals(
            listOf("/媒体库根/JAV_output/演员", "/媒体库根/JAV_output", "/媒体库根"),
            ancestorPaths("/媒体库根/JAV_output/演员/番号 演员", floor = "/媒体库根"),
        )
    }

    @Test
    fun `上溯不会越过库根`() {
        // 越过库根就等于去问别的树，白花请求还可能撞上同名的目录
        assertEquals(listOf("/媒体库根/JAV_output"), ancestorPaths("/媒体库根/JAV_output/x", floor = "/媒体库根/JAV_output"))
        assertTrue(ancestorPaths("/媒体库根/x", floor = "/媒体库根/x").isEmpty())
    }
}
