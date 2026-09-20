package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VideoHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"
    override val baseUrl = "https://pornhub.com"
    override val lang = "en"
    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/videos?page=$page", headers)

    override fun popularAnimeSelector(): String =
        "li[data-video-id]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a.video-link")

            title = link?.text()?.trim() ?: "Untitled"

            thumbnail_url =
                element.selectFirst("img.thumbnail")
                    ?.attr("src")

            setUrlWithoutDomain(
                link?.attr("href") ?: "",
            )
        }
    }

    override fun popularAnimeNextPageSelector(): String =
        "a.next-page"

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request =
        GET(
            "$baseUrl/search?q=$query&page=$page",
            headers,
        )

    override fun searchAnimeSelector(): String =
        popularAnimeSelector()

    override fun searchAnimeFromElement(
        element: Element,
    ): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String =
        popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title =
                document.selectFirst("h1.video-title")
                    ?.text()
                    .orEmpty()

            description =
                document.selectFirst("div.description")
                    ?.text()

            thumbnail_url =
                document.selectFirst("meta[property=og:image]")
                    ?.attr("content")

            genre =
                document.select("a.tag")
                    .joinToString(", ") { it.text() }

            author =
                document.selectFirst("a.channel-name")
                    ?.text()

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
                        .removePrefix(baseUrl),
                )
            },
        )
    }

    override fun episodeListSelector(): String =
        throw UnsupportedOperationException()

    override fun episodeFromElement(
        element: Element,
    ): SEpisode =
        throw UnsupportedOperationException()

    override fun videoListParse(
        response: Response,
    ): List<Video> {

        val document = response.asJsoup()

        val mp4Url =
            document.selectFirst("video source")
                ?.attr("src")
                ?: return emptyList()

        return listOf(
            Video(
                url = mp4Url,
                quality = "720p",
                videoUrl = mp4Url,
            ),
        )
    }

    override fun videoListSelector(): String =
        throw UnsupportedOperationException()

    override fun videoUrlParse(
        document: Document,
    ): String =
        throw UnsupportedOperationException()

    override fun videoFromElement(
        element: Element,
    ): Video =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(
        page: Int,
    ): Request =
        throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(
        element: Element,
    ): SAnime =
        throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList()
}
