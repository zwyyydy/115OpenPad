package com.open115.pad.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投屏协议层的纯逻辑单测。
 *
 * 这些错误不会抛异常，只会表现成"搜得到电视但投不上去"：
 * 标题里的 `&` 把 SOAP 拼坏、控制地址没按 LOCATION 解析、Range 边界差一（拖进度黑屏）、
 * 时间格式不是 HH:MM:SS（seek 被电视忽略）。真电视上很难逐个定位，所以在这里先钉死。
 */
class CastProtocolTest {

    // ---- SSDP 报文 ----

    @Test
    fun ssdpHeadersAreCaseInsensitive() {
        val text = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "Location: http://192.0.2.20:49152/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val h = parseSsdpHeaders(text)
        assertEquals("http://192.0.2.20:49152/desc.xml", h["location"])
        assertEquals("max-age=1800", h["cache-control"])
        assertTrue(h["usn"]!!.startsWith("uuid:abc"))
    }

    @Test
    fun ssdpHeadersIgnoreGarbageLines() {
        val h = parseSsdpHeaders("HTTP/1.1 200 OK\n\nnot-a-header\nX: y")
        assertEquals("y", h["x"])
        assertEquals(1, h.size)
    }

    // ---- UPnP 设备描述 ----

    private val sampleDescription = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>客厅电视</friendlyName>
            <modelName>Xiaomi TV</modelName>
            <UDN>uuid:11111111-2222-3333-4444-555555555555</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun deviceDescriptionFindsTransportAndResolvesRelativeControlUrl() {
        val d = parseDeviceDescription(
            sampleDescription.toByteArray(),
            "http://192.0.2.20:49152/description.xml",
        )
        assertNotNull(d)
        assertEquals("客厅电视", d!!.name)
        assertEquals("Xiaomi TV", d.model)
        assertEquals("http://192.0.2.20:49152/upnp/control/AVTransport1", d.avTransportUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", d.avTransportType)
        assertEquals(
            "http://192.0.2.20:49152/upnp/control/RenderingControl1",
            d.renderingControlUrl,
        )
    }

    @Test
    fun deviceWithoutAvTransportIsNotCastable() {
        val xml = sampleDescription.replace(":service:AVTransport:", ":service:ConnectionManager:")
        assertNull(parseDeviceDescription(xml.toByteArray(), "http://192.0.2.20/d.xml"))
    }

    @Test
    fun brokenDeviceDescriptionReturnsNull() {
        assertNull(parseDeviceDescription("<root><device>".toByteArray(), "http://x/d.xml"))
    }

    // ---- DIDL / SOAP ----

    @Test
    fun didlEscapesTitleAndUrl() {
        val didl = didlLite("A & B <C>", "http://192.0.2.1:8080/t/a%20b.mp4", "video/mp4")
        assertTrue(didl.contains("A &amp; B &lt;C&gt;"))
        assertTrue(didl.contains("video/mp4"))
        assertTrue(didl.contains("object.item.videoItem"))
        // 标题里的原始字符不能出现，否则整段 XML 非法
        assertTrue(!didl.contains("A & B"))
    }

    @Test
    fun soapEscapesNestedDidl() {
        val didl = didlLite("T", "http://h/x.mp4", "video/mp4")
        val env = soapEnvelope(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURIMetaData" to didl),
        )
        assertTrue(env.contains("&lt;DIDL-Lite"))
        // 整段 DIDL 只能以转义形式出现；出现裸标签说明少转了一层
        assertTrue(!env.contains("<DIDL-Lite"))
        assertTrue(env.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
    }

    // ---- 时间 ----

    @Test
    fun dlnaTimeFormatting() {
        assertEquals("00:00:00", formatDlnaTime(0))
        assertEquals("00:01:02", formatDlnaTime(62_000))
        assertEquals("01:02:03", formatDlnaTime(3_723_000))
        // 负数按 0 处理，不能出现 "-1:59:59"
        assertEquals("00:00:00", formatDlnaTime(-5000))
    }

    @Test
    fun dlnaTimeParsing() {
        assertEquals(3_723_000L, parseDlnaTime("01:02:03"))
        assertEquals(123_000L, parseDlnaTime("02:03"))
        assertEquals(62_000L, parseDlnaTime("00:01:02.456"))
        assertEquals(0L, parseDlnaTime("00:00:00"))
        assertNull(parseDlnaTime(""))
        assertNull(parseDlnaTime("NOT_A_TIME"))
        assertNull(parseDlnaTime("99:99"))
    }

    @Test
    fun xmlTextTagIgnoresNamespacePrefix() {
        val xml = "<s:Envelope><u:Body><RelTime>00:01:02</RelTime></u:Body></s:Envelope>"
        assertEquals("00:01:02", xmlTextTag(xml, "RelTime"))
        assertNull(xmlTextTag(xml, "TrackDuration"))
    }

    @Test
    fun upnpErrorDescriptionIsExtracted() {
        val xml = "<s:Envelope><s:Body><s:Fault><detail><UPnPError>" +
            "<errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>" +
            "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        assertEquals("Transition not available（701）", parseUpnpError(xml))
    }

    // ---- Range ----

    @Test
    fun byteRangeParsing() {
        assertEquals(ByteRange(0, 1023), parseByteRange("bytes=0-1023"))
        assertEquals(ByteRange(500, null), parseByteRange("bytes=500-"))
        assertEquals(ByteRange(0, 0), parseByteRange("BYTES=0-0"))
        // 末尾 N 字节与多段 Range 都不支持：当整段处理
        assertNull(parseByteRange("bytes=-500"))
        assertNull(parseByteRange("bytes=0-1,5-6"))
        assertNull(parseByteRange("bytes=10-5"))
        assertNull(parseByteRange("items=0-1"))
        assertNull(parseByteRange(null))
    }

    @Test
    fun byteRangeHeaderValue() {
        assertEquals("bytes 0-99", ByteRange(0, 99).headerValue(1000))
        assertEquals("bytes 500-999", ByteRange(500, null).headerValue(1000))
        assertEquals("bytes 500-", ByteRange(500, null).headerValue(null))
    }

    @Test
    fun contentRangeTotalParsing() {
        assertEquals(12345L, contentRangeTotal("bytes 0-0/12345"))
        assertEquals(12345L, contentRangeTotal("bytes 100-200/12345"))
        assertNull(contentRangeTotal("bytes 0-0/*"))
        assertNull(contentRangeTotal(null))
    }

    // ---- 文件名 / MIME / 路径编码 ----

    @Test
    fun mimeByExtension() {
        assertEquals("video/mp4", castMimeOf("abc.MP4"))
        assertEquals("video/x-matroska", castMimeOf("abc.mkv"))
        assertEquals("video/x-msvideo", castMimeOf("abc.avi"))
        assertEquals("video/mp2t", castMimeOf("abc.ts"))
        assertEquals("application/octet-stream", castMimeOf("abc.bin"))
        assertEquals("application/octet-stream", castMimeOf("noext"))
    }

    @Test
    fun pathEncodingKeepsExtensionAndEncodesSpecials() {
        assertEquals("abc.mkv", pathEncode("abc.mkv"))
        assertEquals("a%20b.mp4", pathEncode("a b.mp4"))
        assertEquals("%E6%B5%8B%E8%AF%95.mp4", pathEncode("测试.mp4"))
        assertEquals("a%2Fb.mp4", pathEncode("a/b.mp4"))
        assertEquals("100%25.mp4", pathEncode("100%.mp4"))
    }
}
