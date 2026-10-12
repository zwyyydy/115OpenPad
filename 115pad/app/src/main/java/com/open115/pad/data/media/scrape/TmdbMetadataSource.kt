package com.open115.pad.data.media.scrape

/**
 * TMDB 作为内置源：只是 [TmdbClient] 的一层薄适配 —— 检索/取详情两边一模一样。
 *
 * 单独留这一层是为了"源"这个身份：以后加站点源/用户插件时，接口就是 [MetadataSource]，
 * 编排层（[MetadataScraper]）不知道也不关心下面是谁。
 */
class TmdbMetadataSource(private val client: TmdbClient) : MetadataSource {
    override val id: String = SOURCE_ID
    override val displayName: String = "TMDB"
    override val apiVersion: Int = 1

    fun configured(): Boolean = client.configured()

    override suspend fun search(query: ScrapeQuery): List<ScrapeCandidate> =
        if (query.kind == ScrapeKind.TV) client.searchShows(query.title, query.year)
        else client.searchMovies(query.title, query.year)

    override suspend fun fetch(candidate: ScrapeCandidate): ScrapedMetadata =
        if (candidate.kind == ScrapeKind.TV) client.fetchTvDetail(candidate.id)
        else client.fetchDetail(candidate.id)

    override suspend fun fetchBackdropUrls(candidate: ScrapeCandidate, max: Int): List<String> =
        client.fetchBackdropUrls(candidate.id, max, candidate.kind)

    override suspend fun fetchSeason(candidate: ScrapeCandidate, season: Int): ScrapedSeason? =
        if (candidate.kind == ScrapeKind.TV) client.fetchTvSeason(candidate.id, season) else null

    companion object {
        const val SOURCE_ID = "tmdb"
    }
}
