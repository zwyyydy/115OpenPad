package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * nfo 解析：**尽量全**（四十多个字段）且**容错**（畸形 nfo 不能整部片丢元数据）。
 *
 * 两条都踩过坑：
 * - 字段少 → 详情页没东西可显示（时长/国家/导演/技术参数全空）；
 * - 容错没兑现 → 畸形处之前收到的 title/plot/演员**全丢**，整部片变成没简介没评分
 *   （2026-09-22 实测：真实库的 nfo 根节点会出现在文件中途、actor 开 9 个闭 10 个）。
 *
 * 单测里跑真 XML 需要 XmlPullParser 的实现（android.jar 里那个是空壳），
 * 用 kxml2 —— 与 Android 运行时同一套 `org.xmlpull.v1` API。
 */
class NfoParserTest {

    private fun parse(xml: String): NfoMeta = NfoParser.parse(StringReader(xml))

    // ---------------- Kodi 风格：电影 ----------------

    private val kodiMovie = """
        <?xml version="1.0" encoding="UTF-8"?>
        <movie>
          <title>示例影片</title>
          <originaltitle>Sample Movie</originaltitle>
          <sorttitle>Sample Movie 1</sorttitle>
          <tagline>示例标语：一句宣传语</tagline>
          <year>1979</year>
          <premiered>1979-05-25</premiered>
          <runtime>117</runtime>
          <rating>8.5</rating>
          <votes>912000</votes>
          <top250>7</top250>
          <mpaa>R</mpaa>
          <plot>一支勘探队在返航途中收到一段不明信号。</plot>
          <genre>科幻</genre>
          <genre>恐怖 / 惊悚</genre>
          <tag>示例系列</tag>
          <country>英国</country>
          <country>美国</country>
          <studio>示例影业</studio>
          <director>示例导演</director>
          <credits>示例编剧</credits>
          <set>
            <name>示例系列</name>
            <overview>示例合集</overview>
          </set>
          <actor><name>示例演员甲</name><role>示例角色乙</role><order>0</order></actor>
          <actor><name>示例演员乙</name><role>示例角色甲</role><order>1</order></actor>
          <actor><name>示例演员丙</name></actor>
          <fileinfo>
            <streamdetails>
              <video><codec>h264</codec><aspect>2.35:1</aspect><width>1920</width><height>1080</height><durationinseconds>7020</durationinseconds></video>
              <audio><codec>dts</codec><language>eng</language><channels>6</channels></audio>
              <audio><codec>aac</codec><language>chi</language><channels>2</channels></audio>
              <subtitle><language>chi</language></subtitle>
              <subtitle><language>eng</language></subtitle>
            </streamdetails>
          </fileinfo>
          <uniqueid type="tmdb">348</uniqueid>
          <uniqueid type="imdb">tt0000000</uniqueid>
          <dateadded>2026-09-20 10:00:00</dateadded>
        </movie>
    """.trimIndent()

    @Test
    fun `电影_nfo_长尾字段全都收进来`() {
        val m = parse(kodiMovie)
        assertEquals("示例影片", m.title)
        assertEquals("Sample Movie", m.originalTitle)
        assertEquals("Sample Movie 1", m.sortTitle)
        assertEquals("示例标语：一句宣传语", m.tagline)
        assertEquals(1979, m.year)
        assertEquals("1979-05-25", m.premiered)
        assertEquals(117, m.runtimeMinutes)
        assertEquals(8.5, m.rating!!, 0.001)
        assertEquals(912000, m.votes)
        assertEquals(7, m.top250)
        assertEquals("R", m.contentRating)
        assertEquals(listOf("科幻", "恐怖", "惊悚"), m.genres)  // 一个 genre 里塞多个也要拆开
        assertEquals(listOf("示例系列"), m.tags)
        assertEquals(listOf("英国", "美国"), m.countries)
        assertEquals(listOf("示例影业"), m.studios)
        assertEquals(listOf("示例导演"), m.directors)
        assertEquals(listOf("示例编剧"), m.writers)
        assertEquals("示例系列", m.set)          // <set><name> 不能被当成演员名
        assertEquals("示例合集", m.setOverview)
        assertEquals("348", m.uniqueTmdbid)
        assertEquals("tt0000000", m.imdbId)
        assertEquals("2026-09-20 10:00:00", m.dateAdded)
        // 技术参数
        assertEquals("h264", m.videoCodec)
        assertEquals(1920, m.videoWidth)
        assertEquals(1080, m.videoHeight)
        assertEquals("2.35:1", m.videoAspect)
        assertEquals(7020, m.videoDurationSeconds)
        assertEquals("dts", m.audioCodec)         // 第一条音轨的编码
        assertEquals("6", m.audioChannels)
        assertEquals(listOf("eng", "chi"), m.audioLanguages)
        assertEquals(listOf("chi", "eng"), m.subtitleLanguages)
    }

    @Test
    fun `演员与角色名一一对应_没写角色的补空串`() {
        val m = parse(kodiMovie)
        assertEquals(listOf("示例演员甲", "示例演员乙", "示例演员丙"), m.actors)
        assertEquals(listOf("示例角色乙", "示例角色甲", ""), m.actorRoles)
        assertEquals(listOf("示例演员甲" to "示例角色乙", "示例演员乙" to "示例角色甲"), m.actorPairs.take(2))
    }

    // ---------------- Emby / Jellyfin 风格：<ratings> 块 ----------------

    @Test
    fun `Emby的ratings块_分项评分按名字归位`() {
        val m = parse(
            """
            <movie>
              <title>示例影片二</title>
              <rating>8.0</rating>
              <ratings>
                <rating name="imdb" max="10" default="true">8.0</rating>
                <rating name="tmdb" max="10">7.9</rating>
                <rating name="critic" max="10">6.5</rating>
                <rating name="user" max="10">9.0</rating>
              </ratings>
              <runtime>2h 35m</runtime>
            </movie>
            """.trimIndent(),
        )
        assertEquals(8.0, m.rating!!, 0.001)
        assertEquals(6.5, m.criticRating!!, 0.001)
        assertEquals(9.0, m.userRating!!, 0.001)
        assertEquals(155, m.runtimeMinutes)   // "2h 35m" → 155 分
    }

    @Test
    fun `没有顶层rating时_用ratings里的imdb当主评分`() {
        val m = parse(
            """
            <movie><title>X</title>
              <ratings><rating name="imdb">7.1</rating></ratings>
            </movie>
            """.trimIndent(),
        )
        assertEquals(7.1, m.rating!!, 0.001)
    }

    // ---------------- 剧集 ----------------

    @Test
    fun `分集nfo_剧名季集与季名状态电视台`() {
        val m = parse(
            """
            <episodedetails>
              <title>示例剧集标题</title>
              <showtitle>示例剧集三</showtitle>
              <season>1</season>
              <episode>3</episode>
              <runtime>01:05:30</runtime>
              <aired>2021-02-16</aired>
              <status>Continuing</status>
              <network>爱奇艺</network>
              <namedseason number="1">第一季</namedseason>
              <actor><name>示例演员戊</name><role>示例角色</role></actor>
            </episodedetails>
            """.trimIndent(),
        )
        assertEquals("示例剧集三", m.showTitle)
        assertEquals(1, m.season)
        assertEquals(3, m.episode)
        assertEquals(2021, m.year)             // <aired> 只取年份
        assertEquals("2021-02-16", m.premiered)
        assertEquals(66, m.runtimeMinutes)      // 01:05:30 → 66（秒四舍五入到分）
        assertEquals("Continuing", m.status)
        assertEquals("爱奇艺", m.network)
        assertEquals(mapOf(1 to "第一季"), m.namedSeasons)
        assertEquals(listOf("示例演员戊" to "示例角色"), m.actorPairs)
    }

    // ---------------- 容错 ----------------

    @Test
    fun `畸形nfo_畸形处之前收到的字段照常返回`() {
        // 复刻实测现场：</movie> 出现在文件中途，后面还接着 <tmdbid>/<director>，
        // actor 开 9 个闭 10 个 —— 解析器会抛，但前面收好的元数据不能丢
        val broken = """
            <movie>
              <title>结构畸形的片</title>
              <year>2020</year>
              <rating>7.2</rating>
              <plot>简介要保住</plot>
              <actor><name>张三</name></actor>
            </movie>
              <tmdbid>12345</tmdbid>
              <director>李四</director>
              <actor><name>王五</name>
            </movie>
        """.trimIndent()
        val m = parse(broken)
        assertEquals("结构畸形的片", m.title)
        assertEquals(2020, m.year)
        assertEquals(7.2, m.rating!!, 0.001)
        assertEquals("简介要保住", m.plot)
        assertTrue("畸形之前收的演员要留住", m.actors.contains("张三"))
    }

    @Test
    fun `空nfo_hasContent 为假`() {
        assertTrue(!parse("<movie></movie>").hasContent)
        assertTrue(parse("<movie><title>有标题</title></movie>").hasContent)
    }

    // ---------------- 时长文案 ----------------

    @Test
    fun `评分归一化_百分制折算_票数脏值丢掉`() {
        // 实测：某库的 `<rating name="critic">7029</rating>`（票数写进了评分槽）
        assertNull(NfoParser.normRating("7029", "10"))
        // 百分制折算回 10 分制
        assertEquals(7.8, NfoParser.normRating("78", "100")!!, 0.001)
        assertEquals(4.7, NfoParser.normRating("4.7", "10")!!, 0.001)
        assertEquals(4.7, NfoParser.normRating("4.7", null)!!, 0.001)
        assertNull(NfoParser.normRating("0", "10"))
        assertNull(NfoParser.normRating(null, "10"))
    }

    @Test
    fun `时长三种写法都认`() {
        assertEquals(90, NfoParser.parseRuntimeMinutes("90"))
        assertEquals(90, NfoParser.parseRuntimeMinutes("90 min"))
        assertEquals(90, NfoParser.parseRuntimeMinutes("90mins"))
        assertEquals(92, NfoParser.parseRuntimeMinutes("01:32:00"))
        assertEquals(93, NfoParser.parseRuntimeMinutes("01:32:40"))   // 秒进位
        assertEquals(92, NfoParser.parseRuntimeMinutes("1h 32m"))
        assertEquals(120, NfoParser.parseRuntimeMinutes("2h"))
        assertNull(NfoParser.parseRuntimeMinutes(null))
        assertNull(NfoParser.parseRuntimeMinutes(""))
        assertNull(NfoParser.parseRuntimeMinutes("未知"))
    }

    @Test
    fun `时长与分辨率文案`() {
        val m = parse("<movie><title>X</title><runtime>128</runtime><fileinfo><streamdetails><video><width>3840</width><height>2160</height></video></streamdetails></fileinfo></movie>")
        assertEquals("2 小时 8 分", m.runtimeText)
        assertEquals("3840×2160", m.resolutionText)
        assertEquals("45 分钟", parse("<movie><runtime>45</runtime></movie>").runtimeText)
    }
}
