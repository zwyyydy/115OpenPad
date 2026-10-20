package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移的**判据与路径拼接**（纯函数部分）。
 *
 * 这些分支真跑一遍要动云盘文件，所以把"怎么搬"抽出来单独钉住：整搬 / 建夹搬 / 拒绝三条路，
 * 以及库根那道最容易写错的判据（库根上落的片看着像"共享目录"，但整搬会把整个库搬走）。
 */
class MovieMigratorTest {

    private fun row(key: String, cid: String, path: String, series: String? = null, title: String = "") =
        MigrationRow(mediaKey = key, seriesKey = series, dirCid = cid, dirPath = path, title = title)

    @Test
    fun `独占目录整搬`() {
        val self = row("M1", "c1", "lib/A")
        val plan = planMigration("M1", self, listOf(self), listOf(self), isLibraryRoot = false)
        assertEquals(MigrationPlan.MoveDir("c1", "A"), plan)
    }

    @Test
    fun `系列卡连分集一起整搬（分集在子目录里也照搬，因为搬的是父目录）`() {
        val self = row("SHOW", "c1", "lib/Show (2023)")
        val ep1 = row("E1", "c2", "lib/Show (2023)/Season 01", series = "SHOW")
        val plan = planMigration("SHOW", self, listOf(self, ep1), listOf(self), isLibraryRoot = false)
        assertEquals(MigrationPlan.MoveDir("c1", "Show (2023)"), plan)
    }

    @Test
    fun `库根上的片不能整搬 要走建夹搬`() {
        // 它在库根上：整搬会把整个库搬进另一个库（库里只有一部片时，下面那道"独占"判据拦不住）
        val self = row("M1", "root", "lib")
        val other = row("M2", "root", "lib")
        val plan = planMigration("M1", self, listOf(self), listOf(self, other), isLibraryRoot = true)
        assertEquals(MigrationPlan.BuildDir("root"), plan)
    }

    @Test
    fun `和别的片挤在同一目录 走建夹搬`() {
        val self = row("M1", "c1", "lib/A")
        val other = row("M2", "c1", "lib/A")
        val plan = planMigration("M1", self, listOf(self), listOf(self, other), isLibraryRoot = false)
        assertEquals(MigrationPlan.BuildDir("c1"), plan)
    }

    @Test
    fun `作品的行散在别的目录 拒绝`() {
        val self = row("M1", "c1", "lib/A")
        val stray = row("M1b", "c9", "lib/别处", series = "M1")
        val plan = planMigration("M1", self, listOf(self, stray), listOf(self), isLibraryRoot = false)
        assertTrue(plan is MigrationPlan.Refuse)
        assertTrue((plan as MigrationPlan.Refuse).reason.contains("别的目录"))
    }

    @Test
    fun `共享目录下 分集散在子目录里 拒绝（挑不齐）`() {
        val self = row("M1", "c1", "lib/A")
        val ep = row("M1e", "c2", "lib/A/Season 01", series = "M1")
        val other = row("M2", "c1", "lib/A")
        val plan = planMigration("M1", self, listOf(self, ep), listOf(self, other), isLibraryRoot = false)
        assertTrue(plan is MigrationPlan.Refuse)
        assertTrue((plan as MigrationPlan.Refuse).reason.contains("子目录"))
    }

    @Test
    fun `没记云盘目录 拒绝`() {
        val self = MigrationRow(mediaKey = "M1", seriesKey = null, dirCid = "", dirPath = "")
        val plan = planMigration("M1", self, listOf(self), listOf(self), isLibraryRoot = false)
        assertTrue(plan is MigrationPlan.Refuse)
    }

    @Test
    fun `同一目录里自己的行不算"别的作品"`() {
        // 分 CD 的一部片：各张盘挂在同一个目录、seriesKey 指向合成行 —— 不能算撞车
        val self = row("BASE", "c1", "lib/A")
        val cd2 = row("BASE-cd2", "c1", "lib/A", series = "BASE")
        val plan = planMigration("BASE", self, listOf(self, cd2), listOf(self, cd2), isLibraryRoot = false)
        assertEquals(MigrationPlan.MoveDir("c1", "A"), plan)
    }

    @Test
    fun `拼路径保留库根的写法（前导斜杠不能丢）`() {
        assertEquals("下载目录/子/片名", joinPath("下载目录", "子", "片名"))
        // 库根写成 /下载目录 的，拼出来也必须带斜杠 —— 否则 byLibraryPath 的前缀匹配会认不出这部片
        assertEquals("/下载目录/片名", joinPath("/下载目录", "片名"))
        assertEquals("/下载目录/异形/异形2 (1986)", joinPath("/下载目录", "异形", "异形2 (1986)"))
        // 尾斜杠、空段、子路径里带斜杠都要能接
        assertEquals("test/刮削测试/片名", joinPath("test/刮削测试/", "", "片名"))
        assertEquals("/下载目录/a/b/片名", joinPath("/下载目录", "a/b", "片名"))
        assertEquals("/下载目录/片名", joinPath("/下载目录", "", "片名"))
        // 只有根时不能多出一个斜杠
        assertEquals("/下载目录", joinPath("/下载目录"))
        assertEquals("/下载目录", joinPath("/下载目录", ""))
    }

    @Test
    fun `建夹撞名就加序号`() {
        assertEquals("片名", uniqueFolderName("片名", listOf("别的", "另一个")))
        assertEquals("片名（1）", uniqueFolderName("片名", listOf("片名")))
        assertEquals("片名（2）", uniqueFolderName("片名", listOf("片名", "片名（1）")))
        // 大小写不算区别（115 的名字不区分大小写地比更稳妥）
        assertEquals("abc（1）", uniqueFolderName("abc", listOf("ABC")))
        // 空名字不能建出一个没名字的夹
        assertEquals("迁移影片", uniqueFolderName("   ", emptyList()))
    }
}
