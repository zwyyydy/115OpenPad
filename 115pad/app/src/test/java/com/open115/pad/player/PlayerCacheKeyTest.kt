package com.open115.pad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放缓存的 key 推导：115 的 HLS 分片地址要剥成内容标识（跨会话稳定），
 * 其余 URL 一律不改写。
 *
 * 这条规则错了的表现很隐蔽 —— 要么缓存永不命中（跟修之前一样，用户只觉得"缓存没用"），
 * 要么更糟：不同内容算出同一个 key，播放器读出别人的分片。所以逐条盯住。
 *
 * 夹具全部用合成占位串（仓库要开源，不放真实文件的 sha1 / 用户号）。
 */
class PlayerCacheKeyTest {

    private val sha1 = "aa11bb22cc33dd44ee55ff66aa77bb88cc99dd00"
    private val sha1Upper = sha1.uppercase()

    /** 造一条 115 CDN 分片地址：`/<32位签名>/<8位会话号>/<sha1>/<sha1>_<档位>-<分片号>.ts?query` */
    private fun seg(
        sig: String,
        session: String,
        name: String = sha1Upper,
        dir: String = sha1Upper,
        tier: String = "1920",
        index: String = "00107",
        host: String = "cpats01.115.com",
        query: String = "u=100000000&se=u&s=6291456&ck=",
    ): String = "https://$host/$sig/$session/$dir/${name}_${tier}-$index.ts?$query"

    /** 同一分片在两次会话里的地址：签名 / 会话号 / 时间戳全不一样，内容标识一样 */
    @Test
    fun `同一分片跨会话算出同一个 key`() {
        val a = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", query = "u=100000000&se=u&s=6291456&ck=&t=111")
        val b = seg("ffee99887766554433221100ffeeddcc", "5e6f7a8b", query = "u=100000000&se=u&s=6291456&ck=&t=222")
        val ka = PlayerCache.contentKey(a)
        val kb = PlayerCache.contentKey(b)
        assertEquals(ka, kb)
        // key 只留内容标识：签名、会话号、query 都不该带进去
        assertTrue(ka!!.endsWith("${sha1}_1920-00107.ts"))
        assertTrue(!ka.contains("00112233445566778899aabbccddeeff") && !ka.contains("1a2b3c4d"))
    }

    /** sha1 大小写两种写法都指向同一份内容，别算出两个 key */
    @Test
    fun `sha1 大小写不影响 key`() {
        val upper = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", name = sha1Upper, dir = sha1Upper)
        val lower = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", name = sha1, dir = sha1)
        assertEquals(PlayerCache.contentKey(upper), PlayerCache.contentKey(lower))
    }

    /** 片号 / 档位不同 = 不同内容，必须不同 key（不然回退会读出别处的画面） */
    @Test
    fun `不同分片号与不同档位是不同的 key`() {
        val base = seg("00112233445566778899aabbccddeeff", "1a2b3c4d")
        val nextIndex = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", index = "00108")
        val otherTier = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", tier = "1280")
        assertNotEquals(PlayerCache.contentKey(base), PlayerCache.contentKey(nextIndex))
        assertNotEquals(PlayerCache.contentKey(base), PlayerCache.contentKey(otherTier))
    }

    /**
     * 播放列表不该被改写 —— 这是踩过的坑：旧列表被命中会吐出带过期签名的分片地址，
     * 跳到未缓冲区域必然 403，而且换几次地址都救不回来。
     */
    @Test
    fun `播放列表与字幕地址不改写`() {
        assertNull(
            PlayerCache.contentKey(
                "https://cpats01.115.com/00112233445566778899aabbccddeeff/1a2b3c4d/" +
                    "$sha1Upper/${sha1Upper}_1920.m3u8?u=100000000&se=u&s=6291456&ck=",
            ),
        )
        assertNull(
            PlayerCache.contentKey(
                "http://videoplay.115.com/m3u8/abcdefghijklmnop?filesha1=$sha1Upper" +
                    "&userid=100000000&rsa=0123456789abcdef0123456789abcdef&definition=4",
            ),
        )
        assertNull(
            PlayerCache.contentKey(
                "https://aps.115.com/transcode/1700000000/0123456789abcdef0123456789abcdef/" +
                    "${sha1Upper}00?type=srt",
            ),
        )
    }

    /** 不在 115 域名下的一律不动（同形状的第三方 CDN 不该被误伤） */
    @Test
    fun `非 115 域名的同形地址不改写`() {
        val other = seg("00112233445566778899aabbccddeeff", "1a2b3c4d", host = "cdn.example.com")
        assertNull(PlayerCache.contentKey(other))
        assertNull(PlayerCache.contentKey(seg("00112233445566778899aabbccddeeff", "1a2b3c4d", host = "not115.com")))
    }

    /** 目录名与文件名里的 sha1 对不上 → 不认（防误伤恰好同形的资源） */
    @Test
    fun `目录名与文件名的 sha1 不一致时不改写`() {
        val mismatch = seg(
            "00112233445566778899aabbccddeeff",
            "1a2b3c4d",
            name = sha1Upper,
            dir = "ff11ee22dd33cc44bb55aa66ff77ee88dd99cc00",
        )
        assertNull(PlayerCache.contentKey(mismatch))
    }

    /** 115 域名下但形状不对的（音频分片、直链文件、无路径）也不能改写 */
    @Test
    fun `形状不符的 115 地址不改写`() {
        assertNull(PlayerCache.contentKey("https://cpats01.115.com/audio/0.ts?ck="))
        assertNull(PlayerCache.contentKey("https://cdnfhnfile.115.com/$sha1Upper/movie.mkv?t=1&u=1"))
        assertNull(PlayerCache.contentKey("https://115.com"))
        assertNull(PlayerCache.contentKey("file:///sdcard/a.ts"))
    }
}
