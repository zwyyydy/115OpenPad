package com.open115.pad.data.media

import java.security.MessageDigest

/**
 * AI 译文相关的纯逻辑：判断"这段文字要不要翻"和生成原文缓存键。
 *
 * 放在 data 层、不依赖 Android，方便单测；UI 与翻译调用都走这里，判据只有一份。
 */
object TranslationSupport {

    /**
     * 需不需要翻译成中文。
     *
     * 判据刻意简单（不引语言识别库）：
     *  - 含平假名/片假名 → 日文（哪怕汉字占多数，比如「東京物語」这种片名也带假名）；
     *  - 有两个及以上汉字 → 当作已经有中文，不翻；
     *  - 其余有字母的（英文 / 韩文 / 俄文…）→ 翻。
     *
     * 纯数字、符号、空串一律不翻（没有可翻的内容）。
     */
    fun needsTranslation(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        if (s.any { it in '\u3040'..'\u30FF' }) return true // 平假名 + 片假名
        val letters = s.count { it.isLetter() }
        if (letters == 0) return false
        val cjk = s.count { it in '\u4E00'..'\u9FFF' }
        // 两个及以上汉字就当作已经有中文标题（中英混排的片名很常见，如「致胜女王 Queenpins」）
        return cjk < 2
    }

    /** 标题或简介任一需要翻 */
    fun needsTranslation(title: String?, plot: String?): Boolean =
        needsTranslation(title) || needsTranslation(plot)

    /**
     * 原文缓存键：标题 + 简介一起哈希。
     * 重新刮削后原文变了，旧译文（存着上一版 hash）就自动失效，不会把旧翻译挂在新内容上。
     */
    fun sourceHash(title: String, plot: String?): String {
        val raw = title.trim() + "\u0000" + plot.orEmpty().trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
