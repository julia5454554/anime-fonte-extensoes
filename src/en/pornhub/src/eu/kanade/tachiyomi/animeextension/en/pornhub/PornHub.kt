package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"

    override val baseUrl = "https://pt.pornhub.com"

    override val lang = "en"

    override val supportsLatest = true

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/video?page=$page")

    override fun popularAnimeSelector() =
        "div.gridWrapper li.pcVideoListItem"

    override fun popularAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun popularAnimeNextPageSelector() =
        "a.page_next"

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/video?o=mr&page=$page")

    override fun latestUpdatesSelector() =
        popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        element.toAnime()

    override fun latestUpdatesNextPageSelector() =
        popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = GET("$baseUrl/video/search?search=$query&page=$page")

    override fun searchAnimeSelector() =
        "div.gridWrapper li.pcVideoListItem"

    override fun searchAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun searchAnimeNextPageSelector() =
        "a.page_next"

    // ============================== Details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                .orEmpty()

            thumbnail_url = getPosterUrl(document)

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
    }

    private fun getPosterUrl(document: Document): String? {
        val directPoster = document
            .selectFirst("img.videoElementPoster")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }

        if (directPoster != null) {
            return directPoster
        }

        val noscript = document
            .selectFirst("noscript:has(img.videoElementPoster)")
            ?: return null

        return org.jsoup.Jsoup
            .parse(noscript.html())
            .selectFirst("img")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
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

    // =============================== Vídeos ===============================

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
            rawJson.parseAs<PhubPlayer>()
        }.getOrNull()
            ?: return emptyList()

        return playerData.mediaDefinitions
            .orEmpty()
            .mapNotNull { media ->
                val videoUrl = media.videoUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val quality = media.quality
                    ?.toString()
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown"

                Video(
                    videoUrl,
                    quality,
                    videoUrl,
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

    private fun Element.toAnime(): SAnime {
        val link = selectFirst("a")
            ?: error("Card sem link")

        val href = link
            .absUrl("href")
            .takeIf { it.isNotBlank() }
            ?: error("Card sem URL")

        val image = selectFirst("img")

        val title = image
            ?.attr("alt")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link
                .attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: "Sem título"

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title

            thumbnail_url = image
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
        }
    }
}

@Serializable
data class PhubPlayer(
    val mediaDefinitions: List<PhubVideo>? = null,
)

@Serializable
data class PhubVideo(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
)
