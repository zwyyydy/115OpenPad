package com.open115.pad.player

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放失败的归因：这次到底是"挂上去的字幕"把媒体准备带崩的，还是视频地址/档位的问题。
 *
 * 两侧判错的代价都不小：判成视频问题 → 为一条坏字幕连换四次视频地址（原盘档还一直弹
 * 「原盘地址已刷新」），黑屏十几秒才轮到"关掉字幕重试"；判成字幕问题 → 明明地址过期却去
 * 摘字幕，白折腾一轮。所以这几条判据单独盯住。
 *
 * ⚠️ "失败地址"只能以字符串喂进来测：单测跑在 mockable android.jar 上，
 * `Uri.parse` 返回 null，构造不出带 URI 的 DataSpec（[failingUrlOf] 只是个取值，
 * 那种构造在单测里做不了，也不必做）。
 */
class SubtitleFailureAttributionTest {

    private val subUrl = "https://aps.115.com/transcode/1792029362/28438164e28b8e8aa497448aebe18ecf/?type="
    private val videoUrl = "https://videoplay.115.com/m3u8/abcdef?definition=4&play_from=3"

    @Test
    fun `失败地址就是某条字幕地址 → 字幕问题`() {
        assertTrue(isSubtitleFailure(subUrl, subtitleParserFailed = false, subtitleUrls = listOf(subUrl)))
    }

    @Test
    fun `视频地址失败（字幕地址也在挂着）→ 不算字幕问题`() {
        assertFalse(isSubtitleFailure(videoUrl, subtitleParserFailed = false, subtitleUrls = listOf(subUrl)))
    }

    @Test
    fun `都没挂字幕时的 HTTP 失败 → 不算字幕问题`() {
        assertFalse(isSubtitleFailure(videoUrl, subtitleParserFailed = false, subtitleUrls = emptyList()))
    }

    @Test
    fun `没有失败地址、字幕解析器报错 → 字幕问题`() {
        assertTrue(isSubtitleFailure(null, subtitleParserFailed = true, subtitleUrls = listOf(subUrl)))
    }

    @Test
    fun `没有失败地址、也不是字幕解析器报错 → 不算字幕问题`() {
        assertFalse(isSubtitleFailure(null, subtitleParserFailed = false, subtitleUrls = listOf(subUrl)))
    }

    @Test
    fun `视频地址失败优先按地址判，即使同时有字幕解析错`() {
        assertFalse(isSubtitleFailure(videoUrl, subtitleParserFailed = true, subtitleUrls = listOf(subUrl)))
    }

    @Test
    fun `字幕解析器的文案才算字幕解析失败`() {
        assertTrue(isSubtitleParserFailure(parseError("SubtitleParser failed.{contentIsMalformed=true, dataType=1}")))
        assertFalse(isSubtitleParserFailure(parseError("Unsupported content type")))
        assertFalse(isSubtitleParserFailure(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    }

    private fun parseError(message: String) = PlaybackException(
        "Source error",
        ParserException.createForMalformedContainer(message, null),
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )
}
