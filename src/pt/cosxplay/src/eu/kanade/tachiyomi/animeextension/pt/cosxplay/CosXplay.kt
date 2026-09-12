package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import eu.kanade.tachiyomi.animeextension.pt.cosxplay.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val universalExtractor by lazy { UniversalExtractor(client, headers) }

    // =========================== Populares ===========================

    override fun popularAnimeSelector(): String = "article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val link = element.selectFirst("h2 a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: ""
        val title = element.selectFirst("h2 a")?.text() ?: element.selectFirst("h2")?.text() ?: ""
        val img = element.selectFirst("img")
        val thumb = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return SAnime.create().apply {
            setUrlWithoutDomain(link)
            this.title = title
            this.thumbnail_url = thumb
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    // =========================== Recentes ===========================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Busca ===========================

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Detalhes ===========================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
            genre = document.select("a[rel=tag]").joinToString { it.text() }
        }
    }

    // =========================== Episódios ===========================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "Vídeo"
                episode_number = 1f
            },
        )
    }

    // =========================== Vídeos ===========================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val id = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('-')
        val embedUrl = "$baseUrl/embed/$id/"
        return universalExtractor.videosFromUrl(embedUrl, pageUrl)
    }

    override fun List<Video>.sortByQuality(): List<Video> = sortedByDescending { extractResolution(it.quality) }

    private fun extractResolution(quality: String): Int = when {
        quality.contains("1080") -> 1080
        quality.contains("720") -> 720
        quality.contains("high", ignoreCase = true) -> 720
        quality.contains("480") -> 480
        quality.contains("360") -> 360
        quality.contains("low", ignoreCase = true) -> 360
        else -> 0
    }
}
