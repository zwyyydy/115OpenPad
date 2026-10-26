package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinTextTest {

    @Test
    fun chineseTitleToCompactPinyinAndInitials() {
        val (pinyin, initials) = PinyinText.of("庆余年")
        assertEquals("qingyunian", pinyin)
        assertEquals("qyn", initials)
    }

    @Test
    fun mixedChineseAndSeason() {
        val (pinyin, initials) = PinyinText.of("庆余年 第二季")
        assertEquals("qingyuniandierji", pinyin)
        assertEquals("qyndej", initials)
    }

    @Test
    fun englishWordsUseWordInitials() {
        val (pinyin, initials) = PinyinText.of("The Last of Us")
        assertEquals("thelastofus", pinyin)
        assertEquals("tlou", initials)
    }

    @Test
    fun digitsAndPunctuation() {
        val (pinyin, initials) = PinyinText.of("3 Body Problem")
        assertEquals("3bodyproblem", pinyin)
        assertEquals("3bp", initials)
    }

    @Test
    fun blankTitle() {
        assertEquals("" to "", PinyinText.of(""))
        assertEquals("" to "", PinyinText.of("   "))
    }

    @Test
    fun normalizeQueryStripsSpacesAndCase() {
        // 分写的 "Qing Yun Nian" 连起来是 qingyunnian（两个 n 相邻），不是拼音音节合并
        assertEquals("qingyunnian2", PinyinText.normalizeQuery(" Qing Yun Nian 2 "))
        assertEquals("qyn", PinyinText.normalizeQuery("Q Y N"))
    }
}
