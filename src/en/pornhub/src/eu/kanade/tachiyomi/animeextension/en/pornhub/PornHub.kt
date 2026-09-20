package eu.kanade.tachiyomi.animeextension.all.pornhub

import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"
    override val baseUrl = "https://www.pornhub.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/video?page=$page", headers)

    override fun popularAnimeSelector() =
        "div.gridWrapper li.pcVideoListItem"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("img")?.attr("alt").orEmpty()

            thumbnail_url =
                element.selectFirst("img")?.attr("src")

            setUrlWithoutDomain(
                element.selectFirst("a")?.attr("href").orEmpty()
            )
        }
    }

    override fun popularAnimeNextPageSelector(): String? =
        "a.page_next"

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        return GET(
            "$baseUrl/video/search?search=$query&page=$page",
            headers,
        )
    }

    override fun searchAnimeSelector() =
        popularAnimeSelector()

    override fun searchAnimeFromElement(
        element: Element,
    ): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() =
        popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title =
                document.selectFirst("h1")?.text().orEmpty()

            description =
                document.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content")

            thumbnail_url =
                document.selectFirst(
                    "img.videoElementPoster"
                )?.attr("src")

            genre =
                document.select("div.tagsWrapper a")
                    .joinToString(", ") { it.text() }

            status = SAnime.COMPLETED
        }
    }

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                setUrlWithoutDomain(
                    response.request.url.toString()
                )
            },
        )
    }

    override fun episodeListSelector() =
        throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) =
        throw UnsupportedOperationException()

    override fun videoListParse(
        response: Response,
    ): List<Video> {

        val document = response.asJsoup()

        val script =
            document.selectFirst(
                "script:containsData(var flashvars)"
            )?.data()
                ?: return emptyList()

        val flashvarsJson = script
            .substringAfter("var flashvars = ")
            .substringBefore(";")

        val flashvars =
            json.decodeFromString<Phub>(flashvarsJson)

        return flashvars.mediaDefinitions
            ?.filter {
                !it.videoUrl.isNullOrBlank()
            }
            ?.map {
                Video(
                    url = it.videoUrl!!,
                    quality = "${it.quality}",
                    videoUrl = it.videoUrl,
                )
            }
            ?: emptyList()
    }

    override fun videoListSelector() =
        throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) =
        throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) =
        throw UnsupportedOperationException()

    override fun latestUpdatesSelector() =
        throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(
        element: Element,
    ) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() =
        throw UnsupportedOperationException()

    override fun getFilterList() =
        AnimeFilterList()
}

@Serializable
data class Phub(
    val mediaDefinitions: List<PhubVideo>? = null,
)

@Serializable
data class PhubVideo(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: String? = null,
)
