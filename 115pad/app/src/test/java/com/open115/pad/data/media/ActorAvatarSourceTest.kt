package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 头像仓库的名字归一化与 URL 拼装。
 *
 * 归一化是这套东西**唯一会悄悄出错的地方**：多归并一步就会把两个演员的名字撞成一个
 * （配错脸比没有脸糟得多），少归并一步又大面积对不上。所以规则钉在这里。
 */
class ActorAvatarSourceTest {

    @Test
    fun `去掉空白与中点 —— 日文名里这些写法都常见`() {
        assertEquals("示例かな", ActorAvatarSource.normalize("示例かな"))
        assertEquals("示例かな", ActorAvatarSource.normalize(" 示例 かな "))
        assertEquals("示例かな", ActorAvatarSource.normalize("示例・かな"))
        assertEquals("示例ももmomo", ActorAvatarSource.normalize("示例もも・MOMO"))
    }

    @Test
    fun `括号里的内容丢掉 —— 别名与译名常写在括号里`() {
        assertEquals("示例演员", ActorAvatarSource.normalize("示例演员（别名）"))
        assertEquals("示例演员", ActorAvatarSource.normalize("示例演员(别名)"))
        assertEquals("名前", ActorAvatarSource.normalize("名前【别名】"))
    }

    @Test
    fun `全角字母数字折半角并转小写`() {
        assertEquals("abcd", ActorAvatarSource.normalize("ＡＢＣＤ"))
        assertEquals("abc123", ActorAvatarSource.normalize("ＡＢＣ１２３"))
    }

    @Test
    fun `点号与下划线不算名字的一部分`() {
        assertEquals("abcdef", ActorAvatarSource.normalize("abc.def"))
        assertEquals("abc", ActorAvatarSource.normalize("a_b_c"))
    }

    @Test
    fun `归一化是幂等的 —— 反复用不会越归越短`() {
        val samples = listOf("示例かな", "示例演员（别名）", " 示例・かな ", "ＡＢＣＤ", "")
        samples.forEach { s ->
            val once = ActorAvatarSource.normalize(s)
            assertEquals(once, ActorAvatarSource.normalize(once))
        }
    }

    @Test
    fun `空名字归一化还是空 —— 调用方靠它跳过`() {
        assertEquals("", ActorAvatarSource.normalize(""))
        assertEquals("", ActorAvatarSource.normalize("  "))
        assertEquals("", ActorAvatarSource.normalize("（只有括号）"))
    }

    @Test
    fun `不认识的字一律原样保留 —— 绝不为了多匹配而删字`() {
        // 连字符保留：它可能是名字的一部分，删了会把两个人撞成一个
        assertEquals("abc-def", ActorAvatarSource.normalize("abc-def"))
        // 空白去掉，其余原样
        assertEquals("名字1", ActorAvatarSource.normalize("名字 1"))
        assertEquals("示例いちか", ActorAvatarSource.normalize("示例いちか"))
    }

    @Test
    fun `中日异体字不会被归并 —— 这是已知限制，写在这里免得以后误当 bug 修`() {
        // 東（日）vs 东（中）：编码不同，归一化**不敢**把它们当成同一个词
        assertNotEquals(
            ActorAvatarSource.normalize("東京"),
            ActorAvatarSource.normalize("东京"),
        )
    }

    @Test
    fun `URL 拼装不会出现双斜杠或丢斜杠`() {
        assertEquals("https://x/y/Filetree.json", ActorAvatarSource.treeUrl("https://x/y/"))
        assertEquals("https://x/y/Filetree.json", ActorAvatarSource.treeUrl("https://x/y"))
        assertEquals("https://x/y/Content/A/1.jpg", ActorAvatarSource.imageUrl("https://x/y", "Content/A/1.jpg"))
        assertEquals("https://x/y/Content/A/1.jpg", ActorAvatarSource.imageUrl("https://x/y/", "/Content/A/1.jpg"))
    }
}
