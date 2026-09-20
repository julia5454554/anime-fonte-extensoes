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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "en"

    override val supportsLatest = true

    private val videoCardSelector =
        "li.pcVideoListItem, li.videoBox, div.videoBox, article.video-item"

    private val nextPageSelector =
        "a.page_next, a.pagination-next, a[rel=next], " +
            "a[href*=\"page=\"][class*=\"next\"]"

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET(
            baseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("video")
                .addQueryParameter("page", page.toString())
                .build(),
        )
    }

    override fun popularAnimeSelector(): String =
        videoCardSelector

    override fun popularAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun popularAnimeNextPageSelector(): String =
        nextPageSelector

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(
            baseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("video")
                .addQueryParameter("o", "mr")
                .addQueryParameter("page", page.toString())
                .build(),
        )
    }

    override fun latestUpdatesSelector(): String =
        videoCardSelector

    override fun latestUpdatesFromElement(element: Element): SAnime =
        element.toAnime()

    override fun latestUpdatesNextPageSelector(): String =
        nextPageSelector

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegments("video/search")
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
            .let(::GET)
    }

    override fun searchAnimeSelector(): String =
        videoCardSelector

    override fun searchAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun searchAnimeNextPageSelector(): String =
        nextPageSelector

    // ============================== Details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document
                .selectFirst("h1, h1.title, h1.video-title")
                ?.text()
                ?.trim()
                .orEmpty()

            thumbnail_url = getPosterUrl(document)

            description = document
                .selectFirst(
                    "meta[property=og:description], " +
                        "meta[name=description]",
                )
                ?.attr("content")
                ?.trim()

            genre = document
                .select(
                    "div.tagsWrapper a, " +
                        ".video-tags a, " +
                        "a[href*=\"/video/search?search=\"]",
                )
                .eachText()
                .distinct()
                .joinToString()

            status = SAnime.COMPLETED
        }
    }

    private fun getPosterUrl(document: Document): String? {
        val image = document.selectFirst(
            "img.videoElementPoster, " +
                "video[poster], " +
                "meta[property=og:image]",
        )

        if (image != null) {
            val url = when {
                image.tagName() == "meta" -> image.attr("content")

                image.hasAttr("data-src") -> image.attr("data-src")

                image.hasAttr("data-mediumthumb") ->
                    image.attr("data-mediumthumb")

                image.hasAttr("data-thumb") ->
                    image.attr("data-thumb")

                else -> image.attr("src")
            }

            if (url.isNotBlank()) {
                return image.absUrlFromValue(url)
            }
        }

        val noscript = document.selectFirst(
            "noscript:has(img.videoElementPoster)",
        ) ?: return null

        return Jsoup
            .parse(noscript.html())
            .selectFirst("img")
            ?.let { imageElement ->
                val url = imageElement.attr("src")
                    .ifBlank { imageElement.attr("data-src") }

                imageElement.absUrlFromValue(url)
            }
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

        val script = document.select("script").firstOrNull {
            val content = it.data().ifBlank { it.html() }

            content.contains("mediaDefinitions") ||
                content.contains("flashvars") ||
                content.contains("videoUrl")
        } ?: return emptyList()

        val scriptContent = script.data().ifBlank { script.html() }

        val rawJson = extractPlayerJson(scriptContent)
            ?: return emptyList()

        val playerData = runCatching {
            rawJson.parseAs<PhubPlayer>()
        }.getOrNull() ?: return emptyList()

        return playerData.mediaDefinitions
            .orEmpty()
            .mapNotNull { media ->
                val url = media.videoUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val quality = media.quality
                    ?.toString()
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }
                    ?: media.format
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: "Unknown"

                Video(
                    url = url,
                    quality = quality,
                    videoUrl = url,
                )
            }
            .distinctBy { it.videoUrl }
            .sortedWith(
                compareByDescending<Video> {
                    it.quality
                        .filter { char -> char.isDigit() }
                        .toIntOrNull() ?: 0
                },
            )
    }

    private fun extractPlayerJson(script: String): String? {
        val markers = listOf(
            "flashvars =",
            "flashvars=",
            "mediaDefinitions:",
            "\"mediaDefinitions\":",
        )

        val markerIndex = markers
            .map { marker -> script.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null

        val start = script.indexOf('{', markerIndex)
            .takeIf { it >= 0 }
            ?: return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until script.length) {
            val char = script[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\' && inString) {
                escaped = true
                continue
            }

            if (char == '"') {
                inString = !inString
                continue
            }

            if (inString) continue

            when (char) {
                '{' -> depth++
                '}' -> {
                    depth--

                    if (depth == 0) {
                        return script.substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }

    override fun videoListSelector(): String =
        throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    // ============================== Utilities ==============================

    private fun Element.toAnime(): SAnime {
        val link = selectFirst(
            "a[href*=\"/view_video.php\"], " +
                "a[href*=\"/video/\"], " +
                "a[href*=\"/view_video\"]",
        ) ?: selectFirst("a")
            ?: error("Card sem link")

        val href = link.absUrl("href")
            .takeIf { it.isNotBlank() }
            ?: error("Card sem URL")

        val image = selectFirst("img")

        val title = getAttributeValue(
            image,
            "alt",
            "data-title",
            "title",
        )
            .trim()
            .takeIf { it.isNotBlank() }
            ?: link.attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: link.text()
                .trim()
                .takeIf { it.isNotBlank() }
            ?: "Sem título"

        val thumbnail = image?.let {
            val imageUrl = getAttributeValue(
                it,
                "data-src",
                "data-mediumthumb",
                "data-thumb",
                "src",
            )

            it.absUrlFromValue(imageUrl)
                .takeIf { url -> url.isNotBlank() }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun getAttributeValue(
        element: Element?,
        vararg attributes: String,
    ): String {
        if (element == null) return ""

        return attributes
            .asSequence()
            .map { element.attr(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun Element.absUrlFromValue(value: String): String {
        if (value.isBlank()) return ""

        return when {
            value.startsWith("http://") ||
                value.startsWith("https://") -> value

            value.startsWith("//") -> "https:$value"

            else -> {
                val base = ownerDocument()
                    ?.baseUri()
                    .orEmpty()

                if (base.isNotBlank()) {
                    org.jsoup.parser.Parser
                        .htmlParser()
                        .let {
                            absUrl("src")
                                .takeIf { url -> url.isNotBlank() }
                                ?: value
                        }
                } else {
                    value
                }
            }
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
