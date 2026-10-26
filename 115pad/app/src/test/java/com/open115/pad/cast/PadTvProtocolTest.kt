package com.open115.pad.cast

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与「115Pad 电视端」之间的协议编解码单测。
 *
 * 样例 JSON 与电视端工程 `TvProtocolTest` 用的是同一份——两边各自盯住字段名，
 * 真机联调时就只剩网络问题可查，不用再怀疑"是不是字段名拼错了"。
 */
class PadTvProtocolTest {

    @Test
    fun hereBeacon_parsesNameAndPort() {
        val here = parsePadTvHere("""{"t":"115pad.tv.here","v":1,"name":"客厅电视","port":48701}""")
        assertEquals("115pad.tv.here", here?.t)
        assertEquals("客厅电视", here?.name)
        assertEquals(48701, here?.port)
    }

    @Test
    fun hereBeacon_rejectsForeignDatagrams() {
        // 48700 端口上可能混进别家广播；find 报文自己也会被自己收到，同样要拒
        assertNull(parsePadTvHere("M-SEARCH * HTTP/1.1"))
        assertNull(parsePadTvHere("""{"t":"115pad.tv.find","v":1,"name":"x","port":1}"""))
        assertNull(parsePadTvHere(""))
    }

    @Test
    fun findBeacon_encodesProtocolType() {
        assertTrue(encodePadTvFind().contains("115pad.tv.find"))
    }

    @Test
    fun play_encodesFieldsTvSideExpects() {
        val json = padTvJson.encodeToString(
            PadTvPlay.serializer(),
            PadTvPlay(
                url = "http://x/a.mkv",
                title = "片名",
                mime = "video/x-matroska",
                positionMs = 1234,
                ua = "115OpenPad/0.1 (Android)",
            ),
        )
        // 电视端逐字段解析，字段名一个都不能错
        assertTrue(json.contains("\"url\""))
        assertTrue(json.contains("\"mime\""))
        assertTrue(json.contains("\"positionMs\":1234"))
        assertTrue(json.contains("\"ua\":\"115OpenPad/0.1 (Android)\""))
    }

    @Test
    fun play_encodesSubtitles() {
        val json = padTvJson.encodeToString(
            PadTvPlay.serializer(),
            PadTvPlay(
                url = "http://x/a.mkv",
                subs = listOf(
                    PadTvSubtitle(url = "http://x/a.chs.srt", mime = "application/x-subrip", name = "简体中文"),
                    PadTvSubtitle(url = "http://x/a.ass", mime = "text/x-ssa"),
                ),
            ),
        )
        // 电视端 SubtitleTrack 逐字段解析：url/mime/name 三个字段名都不能错
        assertTrue(json.contains("\"subs\":["))
        assertTrue(json.contains("\"url\":\"http://x/a.chs.srt\""))
        assertTrue(json.contains("\"mime\":\"application/x-subrip\""))
        assertTrue(json.contains("\"name\":\"简体中文\""))
        val back = padTvJson.decodeFromString(PadTvPlay.serializer(), json)
        assertEquals(2, back.subs.size)
        assertEquals("http://x/a.chs.srt", back.subs[0].url)
        assertEquals("简体中文", back.subs[0].name)
        assertNull(back.subs[1].name)
    }

    @Test
    fun play_subtitlesRoundTrip() {
        val play = PadTvPlay(
            url = "http://x/a.mkv",
            subs = listOf(PadTvSubtitle(url = "http://x/a.vtt", mime = "text/vtt", name = "English")),
        )
        val back = padTvJson.decodeFromString(
            PadTvPlay.serializer(),
            padTvJson.encodeToString(PadTvPlay.serializer(), play),
        )
        assertEquals(play.subs, back.subs)
    }

    @Test
    fun status_parsesTvResponse() {
        val s = padTvJson.decodeFromString(
            PadTvStatus.serializer(),
            """{"state":"playing","positionMs":3453,"durationMs":52209,"title":"投屏自测"}""",
        )
        assertEquals(PadTv.STATE_PLAYING, s.state)
        assertEquals(3453L, s.positionMs)
        assertEquals(52209L, s.durationMs)
    }

    @Test
    fun status_toleratesMissingFields() {
        // 电视端以后加/减字段，平板端不能因为少一个就崩
        val s = padTvJson.decodeFromString(PadTvStatus.serializer(), """{"state":"idle"}""")
        assertEquals("idle", s.state)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun parseAddress_acceptsCommonForms() {
        assertEquals("192.0.2.110" to 48701, parsePadTvAddress("192.0.2.110"))
        assertEquals("192.0.2.110" to 48701, parsePadTvAddress("192.0.2.110:48701"))
        assertEquals("192.0.2.110" to 48701, parsePadTvAddress("http://192.0.2.110:48701/"))
        assertEquals("192.0.2.110" to 51234, parsePadTvAddress(" 192.0.2.110:51234 "))
    }

    @Test
    fun parseAddress_rejectsGarbage() {
        assertNull(parsePadTvAddress(""))
        assertNull(parsePadTvAddress("   "))
        assertNull(parsePadTvAddress("http://"))
        assertNull(parsePadTvAddress(":48701"))
        assertNull(parsePadTvAddress("192.0.2.110:abc"))
    }

    @Test
    fun manualDevice_isPadTvTarget() {
        // 手动填地址构造出来的设备必须与广播发现的走同一条通道
        val d = padTvDeviceOf("10.0.2.2", 48701)
        assertEquals(CastTarget.PadTv, d.target)
        assertEquals("http://10.0.2.2:48701", d.avTransportUrl)
        assertEquals("10.0.2.2", d.name)
    }

    @Test
    fun control_encodesSpeedAndVolume() {
        val json = padTvJson.encodeToString(
            PadTvControl.serializer(),
            PadTvControl(action = PadTv.ACTION_SPEED, speed = 1.5f),
        )
        assertTrue(json.contains("\"action\":\"speed\""))
        assertTrue(json.contains("\"speed\":1.5"))
    }

    @Test
    fun status_parsesSpeedAndVolume() {
        val s = padTvJson.decodeFromString(
            PadTvStatus.serializer(),
            """{"state":"playing","speed":1.25,"volume":9,"volumeMax":15}""",
        )
        assertEquals(1.25f, s.speed)
        assertEquals(9, s.volume)
        assertEquals(15, s.volumeMax)
    }

    @Test
    fun control_encodesDecodeAndPlayerMode() {
        val json = padTvJson.encodeToString(
            PadTvControl.serializer(),
            PadTvControl(action = PadTv.ACTION_PLAYER, mode = PadTv.PLAYER_SYSTEM),
        )
        assertTrue(json.contains("\"mode\":\"system\""))
    }

    @Test
    fun control_encodesQualityAndSubtitle() {
        val quality = padTvJson.encodeToString(
            PadTvControl.serializer(),
            PadTvControl(action = PadTv.ACTION_QUALITY, quality = 1080),
        )
        assertTrue(quality.contains("\"action\":\"quality\""))
        assertTrue(quality.contains("\"quality\":1080"))

        val sub = padTvJson.encodeToString(
            PadTvControl.serializer(),
            PadTvControl(action = PadTv.ACTION_SUBTITLE, mode = PadTv.SUBTITLE_OFF),
        )
        assertTrue(sub.contains("\"action\":\"subtitle\""))
        assertTrue(sub.contains("\"mode\":\"off\""))
    }

    @Test
    fun status_parsesQualityAndSubtitle() {
        val s = padTvJson.decodeFromString(
            PadTvStatus.serializer(),
            """{"state":"playing","quality":0,
                "qualityOptions":[{"def":0,"label":"原盘"},{"def":1080,"label":"1080P"}],
                "subtitle":"off"}""",
        )
        assertEquals(0, s.quality)
        assertEquals(2, s.qualityOptions.size)
        assertEquals("1080P", s.qualityOptions[1].label)
        assertEquals(PadTv.SUBTITLE_OFF, s.subtitle)
    }

    @Test
    fun status_toleratesMissingQualityFields() {
        // 老版本电视端不回报画质/字幕：缺字段走默认值，面板相应功能收起/默认开
        val s = padTvJson.decodeFromString(PadTvStatus.serializer(), """{"state":"playing"}""")
        assertEquals(0, s.quality)
        assertEquals(0, s.qualityOptions.size)
        assertEquals(PadTv.SUBTITLE_ON, s.subtitle)
    }

    @Test
    fun subsPush_encodesContentForTv() {
        val json = padTvJson.encodeToString(
            PadTvSubsPush.serializer(),
            PadTvSubsPush(name = "简体中文", mime = "text/x-ssa", data = "QUJD"),
        )
        // 电视端 SubsPushRequest 逐字段解析：name/mime/data 一个都不能错
        assertTrue(json.contains("\"name\":\"简体中文\""))
        assertTrue(json.contains("\"mime\":\"text/x-ssa\""))
        assertTrue(json.contains("\"data\":\"QUJD\""))
        val back = padTvJson.decodeFromString(
            PadTvSubsPush.serializer(),
            padTvJson.encodeToString(PadTvSubsPush.serializer(), PadTvSubsPush(data = "QUJD")),
        )
        assertNull(back.name)
        assertNull(back.mime)
        assertEquals("QUJD", back.data)
    }

    @Test
    fun status_parsesDecodePlayerAndExternal() {
        val s = padTvJson.decodeFromString(
            PadTvStatus.serializer(),
            """{"state":"external","decode":"sw","player":"system"}""",
        )
        assertEquals(PadTv.STATE_EXTERNAL, s.state)
        assertEquals(PadTv.DECODE_SW, s.decode)
        assertEquals(PadTv.PLAYER_SYSTEM, s.player)
    }
}
