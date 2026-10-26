package com.open115.pad.data.media

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * 片名 → 拼音 / 首字母。
 *
 * 只做两件事：
 * - `pinyin`：汉字转无声调拼音、小写、去掉空格标点后**连成一串**（"庆余年" → "qingyunian"）——
 *   用户输入整段拼音或其中一段都能子串命中；
 * - `initials`：每个汉字的声母 + 英文单词首字母（"庆余年 第二季" → "qyndej"，"The Last of Us" → "tlou"）。
 *
 * 计算只在入库/一次性回填时发生，查询侧只做 LIKE，不引入运行期转换开销。
 *
 * 多音字取 pinyin4j 返回的第一个读音（"重"取 zhong）—— 拼音搜索本身就是模糊匹配，
 * 另一个读音命不中时用片名原文也能搜到，不值得为多音字建一张读音表。
 */
object PinyinText {

    private val TONE_DIGIT = Regex("\\d")

    /** @return (pinyin, initials)；空标题返回 ("", "") */
    fun of(title: String): Pair<String, String> {
        if (title.isBlank()) return "" to ""
        val pinyin = StringBuilder(title.length * 3)
        val initials = StringBuilder(title.length)
        var wordStart = true
        for (ch in title) {
            val readings = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch) }.getOrNull()
            if (!readings.isNullOrEmpty()) {
                val py = readings[0].replace(TONE_DIGIT, "").lowercase()
                if (py.isNotEmpty()) {
                    pinyin.append(py)
                    initials.append(py[0])
                    wordStart = false
                }
            } else if (ch.isLetterOrDigit()) {
                val c = ch.lowercaseChar()
                pinyin.append(c)
                // 英文/数字只在词首进首字母：The Last of Us → tlou，而不是 thelastofus
                if (wordStart) initials.append(c)
                wordStart = false
            } else {
                // 空格、标点：下一个字母重新算词首
                wordStart = true
            }
        }
        return pinyin.toString() to initials.toString()
    }

    /** 查询词归一化：小写、只留字母数字（拼音串里不含空格/标点） */
    fun normalizeQuery(query: String): String =
        query.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 老库升级到 v21 后 `titlePinyin` 全是空串：启动时在 IO 线程分批补齐。
     * 每批在同一个事务里写完（见 MediaDao.setMoviePinyinBatch），大库升级时不会把
     * 升级后的第一次搜索拖住。
     */
    suspend fun backfill(dao: MediaDao, batch: Int = 500) {
        while (true) {
            val rows = dao.moviesWithoutPinyin(batch)
            if (rows.isEmpty()) return
            val updates = rows.map { r ->
                val (py, initials) = of(r.title)
                // 纯标点标题算不出拼音：写一个归一化查询永远匹配不到的哨兵，
                // 否则它每一轮都会被重新捞出来（空串 = 未回填）
                MediaDao.PinyinUpdate(r.mediaKey, py.ifEmpty { "-" }, initials)
            }
            dao.setMoviePinyinBatch(updates)
        }
    }
}
