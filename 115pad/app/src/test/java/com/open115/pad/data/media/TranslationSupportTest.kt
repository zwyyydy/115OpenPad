package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 翻译的两个纯逻辑：什么算"需要翻"、原文哈希怎么变。
 * 判错了就是"中文片也去请求一次"或者"英文片不出现翻译按钮"，单独测。
 */
class TranslationSupportTest {

    @Test
    fun chineseDoesNotNeedTranslation() {
        assertFalse(TranslationSupport.needsTranslation("致胜女王"))
        assertFalse(TranslationSupport.needsTranslation("本片根据真人真事改编。"))
        // 中英混排但汉字占多数：仍视为中文
        assertFalse(TranslationSupport.needsTranslation("致胜女王 Queenpins"))
    }

    @Test
    fun japaneseWithKanaNeedsTranslation() {
        assertTrue(TranslationSupport.needsTranslation("東京の物語"))
        assertTrue(TranslationSupport.needsTranslation("キングダム"))
        // 纯汉字日文片名无法与中文区分（已知限制）：不翻
        assertFalse(TranslationSupport.needsTranslation("東京物語"))
    }

    @Test
    fun latinAndKoreanNeedTranslation() {
        assertTrue(TranslationSupport.needsTranslation("The Queen of Versailles"))
        assertTrue(TranslationSupport.needsTranslation("기생충"))
        assertTrue(TranslationSupport.needsTranslation("Amélie"))
    }

    @Test
    fun emptyOrSymbolsDoNotNeedTranslation() {
        assertFalse(TranslationSupport.needsTranslation(null))
        assertFalse(TranslationSupport.needsTranslation(""))
        assertFalse(TranslationSupport.needsTranslation("   "))
        assertFalse(TranslationSupport.needsTranslation("2024-01-01"))
    }

    @Test
    fun eitherTitleOrPlotTriggers() {
        assertTrue(TranslationSupport.needsTranslation("致胜女王", "A con artist story."))
        assertFalse(TranslationSupport.needsTranslation("致胜女王", "一群主妇骗优惠券的故事。"))
    }

    @Test
    fun hashIsStableAndSensitiveToBothFields() {
        val a = TranslationSupport.sourceHash("Queenpins", "A story.")
        val b = TranslationSupport.sourceHash("Queenpins", "A story.")
        val c = TranslationSupport.sourceHash("Queenpins", "另一段简介。")
        val d = TranslationSupport.sourceHash("Another", "A story.")
        assertEquals(64, a.length)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }
}
