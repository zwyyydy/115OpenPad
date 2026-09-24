package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---------------- 头像：还有别的作品就不能删 ----------------

    private fun avatar(id: Long, name: String, pc: String = "pc:$name", dir: String? = "d1") =
        ActorAvatarRef(id = id, name = name, avatarPickCode = pc, avatarDirCid = dir)

    @Test
    fun `演员还有别的作品_头像留着`() {
        // 实测场景：示例演员的库 83 部片共用一份 `.actors/示例演员.jpg`。
        // 删掉其中一部时如果把头像删了，剩下 82 部就全都没头像了。
        val avatars = listOf(avatar(1, "示例演员"))
        assertEquals(
            emptyList<ActorAvatarRef>(),
            avatarsToDelete(avatars, stillUsedNames = setOf("示例演员")),
        )
    }

    @Test
    fun `演员一部作品都不剩了_头像才删`() {
        val avatars = listOf(avatar(1, "示例演员"))
        assertEquals(
            listOf("示例演员"),
            avatarsToDelete(avatars, stillUsedNames = emptySet()).map { it.name },
        )
    }

    @Test
    fun `同一次删除里_有的演员还有作品有的没有`() {
        val avatars = listOf(avatar(1, "A"), avatar(2, "B"), avatar(3, "C"))
        assertEquals(
            listOf("B"),
            avatarsToDelete(avatars, stillUsedNames = setOf("A", "C")).map { it.name },
        )
    }

    @Test
    fun `要复用的头像不进删除批次`() {
        // 普通剧照：全删
        assertTrue(shouldDeleteImage("fanart1", exceptPickCodes = setOf("pc:示例演员")))
        // 还要复用的头像：一定不删（即使它和剧照躺在同一个 .actors/extrafanart 目录里）
        assertFalse(shouldDeleteImage("pc:示例演员", exceptPickCodes = setOf("pc:示例演员")))
        // 散落在别的目录里的头像：只删指定的那一张，同目录里别人的头像不碰
        assertTrue(shouldDeleteImage("pc:张三", onlyPickCodes = setOf("pc:张三")))
        assertFalse(shouldDeleteImage("pc:李四", onlyPickCodes = setOf("pc:张三")))
    }
}