package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「彻底删除」到底删哪些文件。
 *
 * 这条规则出错的方向很不对称：**删多了**是把系列封面连坐删掉（分集行的海报是从系列根继承的，
 * 那个文件在父目录、不属于这一集）；**删少了**就是用户实测反馈的"彻底删除后图片还在"。
 * 两个方向都钉住。
 *
 * 顺带钉住一件事：pick_code 不能当成 file_id 混进删除参数 —— 115 的 `ufile/delete` 只认 file_id，
 * 拿 pick_code 去删要么失败、要么删错。
 */
class DeleteCloudIdsTest {

    private fun row(
        videoFid: String? = null,
        nfoFid: String? = null,
        posterFid: String? = null,
        fanartFid: String? = null,
        thumbFid: String? = null,
        videoPickCode: String? = null,
        nfoPickCode: String? = null,
        posterPickCode: String? = null,
        fanartPickCode: String? = null,
        thumbPickCode: String? = null,
    ) = MovieFiles(
        mediaKey = "k",
        videoFid = videoFid,
        nfoFid = nfoFid,
        posterFid = posterFid,
        fanartFid = fanartFid,
        thumbFid = thumbFid,
        videoPickCode = videoPickCode,
        nfoPickCode = nfoPickCode,
        posterPickCode = posterPickCode,
        fanartPickCode = fanartPickCode,
        thumbPickCode = thumbPickCode,
        sourceDirKey = "cid1",
    )

    @Test
    fun `视频_nfo_海报_背景_缩略图一起删`() {
        val f = row(videoFid = "1", nfoFid = "2", posterFid = "3", fanartFid = "4", thumbFid = "5")
        assertEquals(listOf("1", "2", "3", "4", "5"), f.cloudFileIds(emptyMap()))
    }

    @Test
    fun `升级前没存图片id的_用pickCode查回来的id`() {
        val f = row(
            videoFid = "1", nfoFid = "2",
            posterPickCode = "p1", fanartPickCode = "p2",
        )
        assertEquals(
            listOf("1", "2", "9", "8"),
            f.cloudFileIds(mapOf("p1" to "9", "p2" to "8")),
        )
    }

    @Test
    fun `查不到id的图一个都不删_分集继承的系列海报就走这条路`() {
        val f = row(videoFid = "1", posterPickCode = "p1", fanartPickCode = "p2")
        assertEquals(listOf("1"), f.cloudFileIds(emptyMap()))
    }

    @Test
    fun `目录里本来就没有图_不会凭空多出要删的id`() {
        val f = row(videoFid = "1", nfoFid = "2")
        assertEquals(listOf("1", "2"), f.cloudFileIds(emptyMap()))
    }
}