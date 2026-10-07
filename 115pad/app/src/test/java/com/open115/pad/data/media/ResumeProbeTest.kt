package com.open115.pad.data.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 续扫询问的**准入条件**：问之前先探活（[probeApiHealthy] 与 [resumeProbeDelayMs]）。
 *
 * 为什么值得钉住：判据松一点（只看"请求没抛异常"）就会在"HTTP 200 但 state=false"
 * （115 的报错就是这么回来的，频控/封禁正属此类）时照样弹窗，用户点了「继续扫描」
 * 又立刻失败一次 —— 那正是这次要消灭的行为。判据紧一点只是晚问一会儿，不伤。
 */
class ResumeProbeTest {

    private fun body(raw: String): JsonObject =
        MediaScanner.queueJson.parseToJsonElement(raw).jsonObject

    @Test
    fun `接口正常_探活通过`() = runBlocking {
        // 列目录的真实成功响应：state=true + data 是数组
        assertTrue(probeApiHealthy { body("""{"state":true,"data":[],"count":0}""") })
    }

    @Test
    fun `请求本身失败_探活不通过`() = runBlocking {
        // 断网 / 超时 / DNS 失败走到这里的样子
        assertFalse(probeApiHealthy { throw IOException("no route to host") })
    }

    @Test
    fun `HTTP 200 但 state=false_探活不通过`() = runBlocking {
        assertFalse(
            probeApiHealthy { body("""{"state":false,"message":"请求过于频繁，请稍后再试"}""") },
        )
    }

    @Test
    fun `响应结构异常_探活不通过`() = runBlocking {
        // 网关返回了别的东西（登录页 HTML、代理错误页）—— 解析失败即视为不通，
        // 宁可晚问，也不要在这种时候弹"要不要接着扫"
        val err = runCatching { probeApiHealthy { body("""{"code":503}""") } }
        assertTrue(err.getOrNull() == false || err.isFailure)
    }

    @Test
    fun `重试节奏_第一次立即_之后逐步拉开并封顶一分钟`() {
        assertEquals(0L, resumeProbeDelayMs(0))
        assertEquals(5_000L, resumeProbeDelayMs(1))
        assertEquals(15_000L, resumeProbeDelayMs(2))
        assertEquals(30_000L, resumeProbeDelayMs(3))
        assertEquals(60_000L, resumeProbeDelayMs(4))
        // 一直不通就一直等（不设上限）：这份现场要留到接口恢复，或者留到下次启动再探
        assertEquals(60_000L, resumeProbeDelayMs(50))
        // 负数（不该出现）也按"立即"处理 —— 别把负 delay 传进 delay()
        assertEquals(0L, resumeProbeDelayMs(-1))
    }
}
