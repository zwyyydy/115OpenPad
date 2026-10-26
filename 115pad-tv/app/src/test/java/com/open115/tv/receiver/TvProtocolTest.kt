package com.open115.tv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层纯逻辑单测：这条协议的"改错一处两边就对不上"，先在后端把编解码盯住，
 * 免得真机联调时在"是网络问题还是 JSON 字段名打错了"之间来回猜。
 */
class TvProtocolTest {

    @Test
    fun findBeacon_roundTrip() {
        val parsed = parseFindBeacon(encodeFindBeacon())
        assertEquals(TvProtocol.FIND_TYPE, parsed?.t)
        assertEquals(TvProtocol.PROTOCOL_VERSION, parsed?.v)
    }

    @Test
    fun findBeacon_rejectsForeignDatagrams() {
        // 48700 这个 UDP 端口上可能混进别家的广播包，必须只认自己的
        assertNull(parseFindBeacon("M-SEARCH * HTTP/1.1"))
        assertNull(parseFindBeacon("""{"t":"something.else","v":1}"""))
        assertNull(parseFindBeacon(""))
        assertNull(parseFindBeacon("not json at all"))
    }

    @Test
    fun playRequest_parsesUaAndPosition() {
        val json = """
            {"url":"http://192.0.2.5:115/cdn/a.mkv","title":"示例片名",
             "mime":"video/x-matroska","positionMs":12345,
             "ua":"115OpenPad/0.1 (Android)","someFutureField":true}
        """.trimIndent()
        val req = protocolJson.decodeFromString(PlayRequest.serializer(), json)
        assertEquals("http://192.0.2.5:115/cdn/a.mkv", req.url)
        assertEquals("示例片名", req.title)
        assertEquals("video/x-matroska", req.mime)
        assertEquals(12345L, req.positionMs)
        // UA 必须原样带过来：115 直链与它绑定，电视端一个字都不能改
        assertEquals("115OpenPad/0.1 (Android)", req.ua)
        // 二期字段缺失不能影响解析（ignoreUnknownKeys 的反面：缺字段走默认值）
        assertNull(req.pickCode)
        assertNull(req.token)
    }

    @Test
    fun playRequest_parsesMinimalBody() {
        val req = protocolJson.decodeFromString(PlayRequest.serializer(), """{"url":"http://x/v.mp4"}""")
        assertEquals("http://x/v.mp4", req.url)
        assertEquals("", req.title)
        assertEquals(0L, req.positionMs)
        assertNull(req.ua)
    }

    @Test
    fun playRequest_parsesSubtitles() {
        val json = """
            {"url":"http://115/a.mkv",
             "subs":[{"url":"http://115/a.chs.srt","mime":"application/x-subrip","name":"简体中文"},
                     {"url":"http://115/a.ass","mime":"text/x-ssa"}]}
        """.trimIndent()
        val req = protocolJson.decodeFromString(PlayRequest.serializer(), json)
        assertEquals(2, req.subs.size)
        assertEquals("http://115/a.chs.srt", req.subs[0].url)
        assertEquals("application/x-subrip", req.subs[0].mime)
        assertEquals("简体中文", req.subs[0].name)
        assertEquals("http://115/a.ass", req.subs[1].url)
        assertNull(req.subs[1].name)
    }

    @Test
    fun playRequest_oldPadWithoutSubsStillParses() {
        // 老版本平板不认得 subs 字段：缺字段走默认空列表，播放不受影响
        val req = protocolJson.decodeFromString(
            PlayRequest.serializer(),
            """{"url":"http://115/a.mkv","title":"老平板"}""",
        )
        assertEquals("老平板", req.title)
        assertEquals(0, req.subs.size)
    }

    @Test
    fun statusResponse_defaultsToIdle() {
        val text = protocolJson.encodeToString(StatusResponse.serializer(), StatusResponse())
        assertTrue(text.contains("\"state\":\"${TvProtocol.STATE_IDLE}\""))
        val back = protocolJson.decodeFromString(StatusResponse.serializer(), text)
        assertEquals(TvProtocol.STATE_IDLE, back.state)
        assertEquals(0L, back.positionMs)
    }

    @Test
    fun hereBeacon_carriesNameAndPort() {
        val text = protocolJson.encodeToString(HereBeacon.serializer(), HereBeacon(name = "客厅电视"))
        val back = protocolJson.decodeFromString(HereBeacon.serializer(), text)
        assertEquals(TvProtocol.HERE_TYPE, back.t)
        assertEquals("客厅电视", back.name)
        assertEquals(TvProtocol.HTTP_PORT, back.port)
    }

    @Test
    fun controlRequest_parsesActions() {
        val ctl = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"seek","positionMs":99000}""",
        )
        assertEquals(TvProtocol.ACTION_SEEK, ctl.action)
        assertEquals(99_000L, ctl.positionMs)
    }

    @Test
    fun controlRequest_parsesSpeedAndVolume() {
        val speed = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"speed","speed":1.5}""",
        )
        assertEquals(TvProtocol.ACTION_SPEED, speed.action)
        assertEquals(1.5f, speed.speed)

        val volume = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"volume_set","volume":3}""",
        )
        assertEquals(TvProtocol.ACTION_VOLUME_SET, volume.action)
        assertEquals(3, volume.volume)
    }

    @Test
    fun statusResponse_carriesSpeedAndVolume() {
        val text = protocolJson.encodeToString(
            StatusResponse.serializer(),
            StatusResponse(state = TvProtocol.STATE_PLAYING, speed = 2f, volume = 7, volumeMax = 15),
        )
        assertTrue(text.contains("\"speed\":2.0"))
        val back = protocolJson.decodeFromString(StatusResponse.serializer(), text)
        assertEquals(2f, back.speed)
        assertEquals(7, back.volume)
        assertEquals(15, back.volumeMax)
    }

    @Test
    fun controlRequest_parsesDecodeAndPlayerMode() {
        val decode = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"decode","mode":"sw"}""",
        )
        assertEquals(TvProtocol.ACTION_DECODE, decode.action)
        assertEquals(TvProtocol.DECODE_SW, decode.mode)

        val player = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"player","mode":"system"}""",
        )
        assertEquals(TvProtocol.ACTION_PLAYER, player.action)
        assertEquals(TvProtocol.PLAYER_SYSTEM, player.mode)
    }

    @Test
    fun controlRequest_parsesQualityAndSubtitle() {
        val quality = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"quality","quality":1080}""",
        )
        assertEquals(TvProtocol.ACTION_QUALITY, quality.action)
        assertEquals(1080, quality.quality)

        val subOff = protocolJson.decodeFromString(
            ControlRequest.serializer(),
            """{"action":"subtitle","mode":"off"}""",
        )
        assertEquals(TvProtocol.ACTION_SUBTITLE, subOff.action)
        assertEquals(TvProtocol.SUBTITLE_OFF, subOff.mode)
    }

    @Test
    fun subsPushRequest_roundTrip() {
        val push = SubsPushRequest(name = "简体中文", mime = "text/x-ssa", data = "QUJD")
        val back = protocolJson.decodeFromString(
            SubsPushRequest.serializer(),
            protocolJson.encodeToString(SubsPushRequest.serializer(), push),
        )
        assertEquals("简体中文", back.name)
        assertEquals("text/x-ssa", back.mime)
        assertEquals("QUJD", back.data)
    }

    @Test
    fun statusResponse_carriesQualityAndSubtitle() {
        val text = protocolJson.encodeToString(
            StatusResponse.serializer(),
            StatusResponse(
                state = TvProtocol.STATE_PLAYING,
                quality = 0,
                qualityOptions = listOf(QualityOption(0, "原盘"), QualityOption(1080, "1080P")),
                subtitle = TvProtocol.SUBTITLE_OFF,
            ),
        )
        val back = protocolJson.decodeFromString(StatusResponse.serializer(), text)
        assertEquals(0, back.quality)
        assertEquals(2, back.qualityOptions.size)
        assertEquals("1080P", back.qualityOptions[1].label)
        assertEquals(TvProtocol.SUBTITLE_OFF, back.subtitle)
    }

    @Test
    fun statusResponse_carriesDecodeAndPlayer() {
        val text = protocolJson.encodeToString(
            StatusResponse.serializer(),
            StatusResponse(
                state = TvProtocol.STATE_EXTERNAL,
                decode = TvProtocol.DECODE_SW,
                player = TvProtocol.PLAYER_SYSTEM,
            ),
        )
        val back = protocolJson.decodeFromString(StatusResponse.serializer(), text)
        assertEquals(TvProtocol.STATE_EXTERNAL, back.state)
        assertEquals(TvProtocol.DECODE_SW, back.decode)
        assertEquals(TvProtocol.PLAYER_SYSTEM, back.player)
    }
}
