package com.open115.pad.data.media.scrape

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * 补充刮削的 nfo 合并：**保留现有文档的一切，只补它没有的内容**。
 *
 * 为什么在 XML 层合并、而不是"解析成模型再重写"：现有 nfo 多半来自服务器流水线（MDCx），
 * 里面还有 `<fileinfo>`、`<dateadded>`、厂商自定义标签等本工程的 [NfoWriter] 不会写的字段。
 * 先解析再重写会把它们全丢掉 —— 那正是"补充"最不该发生的事。这里只往现有 DOM 上追加缺失元素，
 * 未知标签原样保留。
 *
 * 只补的粒度：
 *  - 标量字段（title/plot/rating/year/uniqueid…）：现有已有非空值就跳过；
 *  - 列表字段（genre/country/studio/director/credits…）：按值去重后追加缺的；
 *  - actor：按 `<name>` 去重追加；
 *  - `set`：现有已有 `<set><name>` 就跳过；
 *  - `uniqueid`：按 `type` 区分，同一 type 已有就跳过。
 *
 * 解析 Existing nfo 失败（不是合法 XML）时返回 null —— 调用方据此**完全不动原 nfo**，
 * 宁可这次不补，也不能覆盖掉唯一一份元数据。
 */
internal object NfoMerger {

    /** [added] = 实际补进去的顶层元素数；0 表示现有内容已经齐全（调用方不必重传） */
    data class Result(val xml: String, val added: Int)

    fun merge(existingXml: String, incomingXml: String): Result? {
        val existing = runCatching { parse(existingXml) }.getOrNull() ?: return null
        val incoming = runCatching { parse(incomingXml) }.getOrNull() ?: return null
        val root = existing.documentElement ?: return null
        val incomingRoot = incoming.documentElement ?: return null

        var added = 0
        for (el in childElements(incomingRoot)) {
            val appended = when (el.tagName.lowercase(Locale.ROOT)) {
                "genre", "country", "language", "studio", "director", "credits" ->
                    mergeTextList(existing, root, el)
                "actor" -> mergeActor(existing, root, el)
                "set" -> mergeSet(existing, root, el)
                "uniqueid" -> mergeUniqueId(existing, root, el)
                else -> mergeScalar(existing, root, el)
            }
            if (appended) added++
        }
        if (added == 0) return Result(existingXml, 0)
        val xml = runCatching { serialize(existing) }.getOrNull() ?: return null
        return Result(xml, added)
    }

    private fun mergeScalar(doc: Document, root: Element, incoming: Element): Boolean {
        val existing = childElements(root, incoming.tagName)
        // 已有空标签（如 <plot></plot>）就地填值：再追加一个会让解析器读到空的那个
        val blank = existing.firstOrNull { it.textContent.orEmpty().isBlank() }
        if (blank != null) {
            blank.textContent = incoming.textContent.orEmpty()
            return true
        }
        if (existing.isNotEmpty()) return false
        root.appendChild(doc.importNode(incoming, true))
        return true
    }

    /** 列表字段：同一个值（忽略大小写与首尾空白）只保留一份 */
    private fun mergeTextList(doc: Document, root: Element, incoming: Element): Boolean {
        val text = incoming.textContent.orEmpty().trim()
        if (text.isEmpty()) return false
        val existing = childElements(root, incoming.tagName)
        val blank = existing.firstOrNull { it.textContent.orEmpty().isBlank() }
        if (blank != null) {
            blank.textContent = text
            return true
        }
        val exists = existing.any { it.textContent.orEmpty().trim().equals(text, ignoreCase = true) }
        if (exists) return false
        root.appendChild(doc.importNode(incoming, true))
        return true
    }

    private fun mergeActor(doc: Document, root: Element, incoming: Element): Boolean {
        val name = childText(incoming, "name").trim()
        if (name.isEmpty()) return false
        val exists = childElements(root, "actor")
            .any { childText(it, "name").trim().equals(name, ignoreCase = true) }
        if (exists) return false
        root.appendChild(doc.importNode(incoming, true))
        return true
    }

    private fun mergeSet(doc: Document, root: Element, incoming: Element): Boolean {
        val sets = childElements(root, "set")
        val blank = sets.firstOrNull { childText(it, "name").isBlank() }
        if (blank != null) {
            // 补进空的 <set>：找它自己的 name 元素，没有就新建
            val nameEl = childElements(blank, "name").firstOrNull()
                ?: (blank.appendChild(doc.createElement("name")) as Element)
            nameEl.textContent = childText(incoming, "name")
            return true
        }
        if (sets.isNotEmpty()) return false
        root.appendChild(doc.importNode(incoming, true))
        return true
    }

    private fun mergeUniqueId(doc: Document, root: Element, incoming: Element): Boolean {
        val type = incoming.getAttribute("type").lowercase(Locale.ROOT)
        val sameType = childElements(root, "uniqueid").filter {
            val t = it.getAttribute("type").lowercase(Locale.ROOT)
            // type 为空视为同一类（有些写工具不写 type）
            type.isEmpty() || t.isEmpty() || t == type
        }
        // 同 type 已有空值 → 就地填；已有非空值 → 保留旧值
        val blank = sameType.firstOrNull { it.textContent.orEmpty().isBlank() }
        if (blank != null) {
            blank.textContent = incoming.textContent.orEmpty()
            return true
        }
        if (sameType.isNotEmpty()) return false
        root.appendChild(doc.importNode(incoming, true))
        return true
    }

    private fun childElements(parent: Node, tag: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && (tag == null || n.tagName.equals(tag, ignoreCase = true))) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun childText(parent: Element, tag: String): String =
        childElements(parent, tag).firstOrNull()?.textContent.orEmpty()

    /**
     * 手动编辑的写回：**表单里的字段说了算**，表单没涉及的标签原样保留。
     *
     * 与 [merge]（补充模式）的区别：这里不是"只补空"，而是用表单值整体替换受管字段 ——
     * 用户在表单里改了标题/清空了简介，就该按表单落盘；而 `<fileinfo>`、`<dateadded>`、
     * 厂商自定义标签这些表单没有的东西必须留着，不能被一次手编抹掉。
     *
     * 受管标签只包括表单里真的能编辑的字段。`votes`/`sorttitle`/`outline`/`tag` 等不在表单里，
     * 一律不碰。`uniqueid` 只替换 tmdb/imdb 两类，其它（tvdb 等）保留。
     *
     * 返回 null = 现有 nfo 解析失败（调用方应中止写入，避免把原文件冲掉）。
     */
    fun replaceManaged(existingXml: String, incomingXml: String): String? {
        val existing = runCatching { parse(existingXml) }.getOrNull() ?: return null
        val incoming = runCatching { parse(incomingXml) }.getOrNull() ?: return null
        val root = existing.documentElement ?: return null
        val incomingRoot = incoming.documentElement ?: return null

        for (tag in MANAGED_TAGS) {
            childElements(root, tag).forEach { root.removeChild(it) }
            childElements(incomingRoot, tag).forEach { root.appendChild(existing.importNode(it, true)) }
        }
        // 外部 id：只动 tmdb/imdb，tvdb 等其它类型保留
        childElements(root, "uniqueid").filter {
            val t = it.getAttribute("type").lowercase(Locale.ROOT)
            t.isEmpty() || t == "tmdb" || t == "imdb"
        }.forEach { root.removeChild(it) }
        childElements(incomingRoot, "uniqueid").forEach { root.appendChild(existing.importNode(it, true)) }

        return runCatching { serialize(existing) }.getOrNull()
    }

    /** 表单里能编辑的字段（唯一一份清单）：写回时这些标签整体替换，其余标签原样保留 */
    private val MANAGED_TAGS = listOf(
        "title", "originaltitle", "tagline", "rating", "plot", "runtime", "mpaa", "premiered", "year",
        "genre", "country", "language", "studio", "director", "credits", "set", "trailer",
        "actor", "tmdbid", "imdbid",
    )

    private fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        // 不解析外部实体：nfo 来自云端，DTD 引用可能是恶意/不可达的
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun serialize(doc: Document): String {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        val out = StringWriter()
        t.transform(DOMSource(doc), StreamResult(out))
        return out.toString()
    }
}
