package com.open115.pad.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun redactsAuthorizationHeader() {
        val out = AppLog.redact("Authorization: Bearer abc.def-123")
        assertEquals("Authorization: ***", out)
        assertFalse(out.contains("abc.def"))
    }

    @Test
    fun redactsTokenQueryParams() {
        val out = AppLog.redact("https://x/y?access_token=ABC123&refresh_token=DEF456&x=1")
        assertFalse(out.contains("ABC123"))
        assertFalse(out.contains("DEF456"))
        assertTrue(out.contains("x=1"))
    }

    @Test
    fun redactsSessionCookies() {
        val out = AppLog.redact("seid=verysecret&kid=alsosecret&normal=ok")
        assertFalse(out.contains("verysecret"))
        assertFalse(out.contains("alsosecret"))
        assertTrue(out.contains("normal=ok"))
    }

    @Test
    fun leavesNormalMessagesUntouched() {
        val s = "扫描完成：已入库 3 部，用时 12s"
        assertEquals(s, AppLog.redact(s))
    }
}
