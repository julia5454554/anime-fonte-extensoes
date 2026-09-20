package eu.kanade.tachiyomi.animeextension.pt.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"
    override val baseUrl = "https://www.pornhub.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/video?page=$page")

    override fun popularAnimeSelector() =
        "div.gridWrapper li.pcVideoListItem"

    override fun popularAnimeFromElement(element: Element) =
        element.toAnime()

    override fun popularAnimeNextPageSelector() =
        "a.page_next"

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/video?o=mr&page=$page")

    override fun latestUpdatesSelector() =
        popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) =
        element.toAnime()

    override fun latestUpdatesNextPageSelector() =
        popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun getFilterList() =
        AnimeFilterList()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = GET("$baseUrl/video/search?search=$query&page=$page")

    override fun searchAnimeSelector() =
        popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) =
        element.toAnime()

    override fun searchAnimeNextPageSelector() =
        popularAnimeNextPageSelector()

    // ============================== Details ==============================

    override fun animeDetailsParse(document: Document) =
        SAnime.create().apply {
            title = document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                .orEmpty()

            thumbnail_url = document
                .selectFirst("img.videoElementPoster")
                ?.absUrl("src")
                ?: document
                    .selectFirst("noscript:has(img.videoElementPoster)")
                    ?.let {
                        org.jsoup.Jsoup.parse(it.html())
                            .selectFirst("img")
                            ?.absUrl("src")
                    }

            description = document
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()

            genre = document
                .select("div.tagsWrapper a")
                .eachText()
                .joinToString()

            status = SAnime.COMPLETED
        }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Vídeo"
                episode_number = 1F
            },
        )
    }

    override fun episodeListSelector(): String =
        throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    // =============================== Vídeo ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val rawJson = document
            .selectFirst("script:containsData(var flashvars)")
            ?.data()
            ?.substringAfter(" = ")
            ?.substringBefore(";")
            ?.trim()
            ?: return emptyList()

        val playerData = runCatching {
            json.decodeFromString<PhubPlayer>(rawJson)
        }.getOrNull() ?: return emptyList()

        return playerData.mediaDefinitions.orEmpty()
            .mapNotNull { item ->
                val url = item.videoUrl ?: return@mapNotNull null

                Video(
                    url = url,
                    quality = item.quality?.toString() ?: "Unknown",
                    videoUrl = url,
                )
            }
    }

    override fun videoListSelector(): String =
        throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    // ============================== Utilities ==============================

    private fun Element.toAnime(): SAnime? {
        val link = selectFirst("a") ?: return null
        val href = link.absUrl("href").ifBlank { return null }

        val image = selectFirst("img")

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = image?.attr("alt")
                ?.trim()
                .orEmpty()

            thumbnail_url = image?.absUrl("src")
        }
    }

    @Serializable
    data class PhubPlayer(
        val mediaDefinitions: List<MediaDefinition>? = null,
    )

    @Serializable
    data class MediaDefinition(
        val format: String? = null,
        val videoUrl: String? = null,
        val quality: String? = null,
    )
}
