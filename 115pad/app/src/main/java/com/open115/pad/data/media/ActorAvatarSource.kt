package com.open115.pad.data.media

import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader

/**
 * 演员头像补全：**本地 `.actors` 没给头像**的演员，按名字去"头像仓库"取一张。
 *
 * 数据源是 gfriends 那一类仓库（社区维护的演员头像集），形态是：
 * `<基地址>/Filetree.json` 一份大索引（十万条量级、十几 MB）+ 图片按 `Content/<分组>/<文件名>` 放。
 * 索引是唯一的映射来源 —— 所以这里**下到本地做一份紧凑索引**，之后查名字就是纯本地操作。
 *
 * ★ 三条纪律：
 *  1. **地址不写死**：[DEFAULT_BASE] 只是首次的预填值，真正生效的是设置里那份（见 `MediaPrefs`）。
 *  2. **只在用户手动点「更新头像索引」时联网**，扫描期/进详情页都不额外打这个源 ——
 *     和"扫描期零额外请求"那条纪律一致。
 *  3. **绝不猜脸**：名字精确/归一化后对不上就返回 null，界面上退回首字母圆底。
 *     配错一张别人的脸，比没有脸糟得多。
 *
 * 图片本身交给 Coil 加载（它自带磁盘缓存，同一张只下一次、之后离线可用）。
 */
object ActorAvatarSource {

    /**
     * 首次预填的基地址（jsDelivr 上那个第三方镜像，README 给的调用地址就是它）。
     *
     * **不是硬编码**：用户在实验室里改一次就覆盖掉了，改地址不用改代码 —— 第三方地址说变就变。
     */
    const val DEFAULT_BASE = "https://cdn.jsdelivr.net/gh/xinxin8816/gfriends/"

    private const val TREE_NAME = "Filetree.json"

    /** 本地紧凑索引的文件名（TSV：归一化后的演员名 \t 相对路径） */
    private const val INDEX_NAME = "actors.idx"

    /** 一次刷新索引的结果：总条数 + 本库演员命中数/总数 */
    data class Refresh(val count: Int, val hit: Int, val total: Int)

    fun indexFile(filesDir: File): File = File(filesDir, INDEX_NAME)

    /** 索引在不在（没有就别去查名字，省一次无意义的文件扫描） */
    fun hasIndex(filesDir: File): Boolean = indexFile(filesDir).let { it.exists() && it.length() > 0L }

    fun treeUrl(base: String): String = base.trimEnd('/') + "/" + TREE_NAME

    fun imageUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * 名字归一化：匹配用，**不改变展示**。
     *
     * 规则尽量保守 —— 只做"同一名字的不同写法"这种确定性归并，**不猜**：
     * 去掉所有空白、去掉中点/间隔号、去掉 `.`、丢掉括号里的内容（`示例演员（别名）` → `示例演员`）、
     * 全角字母数字折半角、最后转小写。
     *
     * 做不到的（必须在文档里认）：简繁/中日汉字差异（`悠亜` vs `悠亚`）、罗马字与汉字互换 ——
     * 这些只能靠人工指定，**这里一律不猜**。
     */
    fun normalize(name: String): String {
        val sb = StringBuilder(name.length)
        var depth = 0
        for (ch in name) {
            when {
                ch == '(' || ch == '（' || ch == '[' || ch == '【' || ch == '{' -> depth++
                ch == ')' || ch == '）' || ch == ']' || ch == '】' || ch == '}' -> if (depth > 0) depth--
                depth > 0 -> Unit
                ch.isWhitespace() || ch == '\u3000' -> Unit
                ch == '.' || ch == '·' || ch == '・' || ch == '･' || ch == '_' -> Unit
                ch in 'Ａ'..'Ｚ' -> sb.append(ch - 0xFEE0)
                ch in 'ａ'..'ｚ' -> sb.append(ch - 0xFEE0)
                ch in '０'..'９' -> sb.append(ch - 0xFEE0)
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase()
    }

    /**
     * 拉索引并落成紧凑文件，顺便算**本库覆盖率**。
     *
     * 流式解析（`android.util.JsonReader`）而不是先建成对象树：十万条的对象树进内存是几十 MB，
     * 而这里只需要边读边写一行文本。[libraryNames] 用来在一次遍历里数出命中数 ——
     * 覆盖率是"这个源对你这个库到底有没有用"的唯一答案，顺手算掉，不额外扫一遍。
     *
     * 先写临时文件再改名：中途被杀不会留下半个文件被当成索引（同海报落盘那套规矩）。
     */
    suspend fun refresh(
        client: OkHttpClient,
        base: String,
        filesDir: File,
        libraryNames: Collection<String>,
    ): Refresh = withContext(Dispatchers.IO) {
        val want = libraryNames.mapTo(HashSet()) { normalize(it) }
        val target = indexFile(filesDir)
        val tmp = File(filesDir, "$INDEX_NAME.tmp")
        var count = 0
        val hit = HashSet<String>()
        client.newCall(Request.Builder().url(treeUrl(base)).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("响应为空")
            BufferedWriter(tmp.writer()).use { out ->
                JsonReader(BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (reader.peek().name != "BEGIN_OBJECT") {
                            reader.skipValue()
                            continue
                        }
                        // 形状兼容：README 示例外面包一层 `Content`，实测也可能顶层直接就是分组
                        when {
                            name.equals("Content", true) ->
                                count += readLevel(reader, out, want, hit, group = null)
                            name.equals("Information", true) -> reader.skipValue()
                            else -> count += readLevel(reader, out, want, hit, group = null)
                        }
                    }
                    reader.endObject()
                }
            }
        }
        if (count == 0) error("索引里没读到条目（地址或格式可能变了）")
        if (target.exists() && !target.delete()) error("旧索引删不掉")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Refresh(count = count, hit = hit.size, total = want.size)
    }

    /**
     * 递归按层读：**见到对象就往下走一层，见到字符串就是叶子**（键=文件名、值=相对路径）。
     *
     * 为什么不写死层数：实测这份索引是 `Content → 分组 → 文件名`（README 示例也是这个形状），
     * 而顶层也可能直接就是分组 —— 第一版按"分组 → 文件名"写死两层，结果一条都没读到
     * （靠 refresh 里那个"0 条就报错"的守卫才发现，不然会静默生成一个空索引）。
     * `Content` 只是外壳，不算分组名。
     */
    private fun readLevel(
        reader: JsonReader,
        out: BufferedWriter,
        want: Set<String>,
        hit: MutableSet<String>,
        group: String?,
    ): Int {
        var n = 0
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek().name) {
                "STRING" -> {
                    val value = reader.nextString()
                    val actor = normalize(name.substringBeforeLast('.'))
                    if (actor.isEmpty()) continue
                    out.write(actor + "	" + pathFor(group, value))
                    out.newLine()
                    n++
                    if (actor in want) hit += actor
                }
                "BEGIN_OBJECT" -> {
                    val next = group ?: name.takeIf { !it.equals("Content", true) }
                    n += readLevel(reader, out, want, hit, next)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return n
    }

    /**
     * 相对路径 = `Content/<分组>/<值>`。
     *
     * 值有时已经是完整相对路径（自己带了目录），那就直接用 —— 猜错也别慌：图加载不出来时
     * 界面会退回首字母圆底，不会显示错图。
     */
    private fun pathFor(group: String?, value: String): String = when {
        value.startsWith("Content/") -> value
        group.isNullOrBlank() -> value
        else -> "Content/$group/$value"
    }

    /**
     * 查一批演员名 → 头像相对路径（原始名字做键，查不到就没有这个键）。
     *
     * **一次扫描**而不是每个名字各扫一遍：详情页一屏就是十几个演员。
     */
    suspend fun lookup(filesDir: File, names: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val file = indexFile(filesDir)
            if (names.isEmpty() || !file.exists()) return@withContext emptyMap()
            val want = names.associateBy { normalize(it) }
            val found = HashMap<String, String>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val original = want[line.substring(0, tab)] ?: return@forEach
                    found.putIfAbsent(original, line.substring(tab + 1))
                }
            }
            found
        }
}
