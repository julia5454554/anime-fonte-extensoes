package eu.kanade.tachiyomi.animeextension.en.pornhub

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fi.iki.elonen.NanoHTTPD
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class PornHub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val videoSelector =
        "div.gridWrapper li.pcVideoListItem, " +
            "ul.videos li, " +
            "li.pcVideoListItem, " +
            "li.videoBox, " +
            "div.videoBox"

    private val nextPageSelector =
        "li.page_next a, " +
            "a.page_next, " +
            "a[rel=next], " +
            "a.pagination-next"

    private val proxyServer by lazy {
        PornHubProxyServer(client, baseUrl).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    override val client: OkHttpClient =
        super.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("Accept-Encoding", "gzip")
                    .header(
                        "Cookie",
                        "hasVisited=1; " +
                            "accessAgeDisclaimerPH=1; " +
                            "platform=pc",
                    )
                    .header("User-Agent", PornHubProxyServer.UA)
                    .build()

                chain.proceed(request)
            }
            .build()

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .add("Referer", "$baseUrl/")

    // ============================== POPULAR ==============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/video?page=$page", headers)

    override fun popularAnimeSelector(): String =
        videoSelector

    override fun popularAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun popularAnimeNextPageSelector(): String =
        nextPageSelector

    // =============================== LATEST ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/video?o=mr&page=$page", headers)

    override fun latestUpdatesSelector(): String =
        videoSelector

    override fun latestUpdatesFromElement(element: Element): SAnime =
        element.toAnime()

    override fun latestUpdatesNextPageSelector(): String =
        nextPageSelector

    // =============================== SEARCH ===============================

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header(
                "A busca por texto ignora os filtros de categoria",
            ),
            CategoryFilter(),
        )

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val categoryFilter = filters
            .filterIsInstance<CategoryFilter>()
            .firstOrNull()

        val categoryUrl = categoryFilter?.toUrl()

        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")

            return GET(
                "$baseUrl/video/search" +
                    "?search=$encodedQuery&page=$page",
                headers,
            )
        }

        if (categoryUrl != null) {
            val separator = if (categoryUrl.contains("?")) {
                "&"
            } else {
                "?"
            }

            return GET(
                "$baseUrl$categoryUrl" +
                    "${separator}page=$page",
                headers,
            )
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeSelector(): String =
        videoSelector

    override fun searchAnimeFromElement(element: Element): SAnime =
        element.toAnime()

    override fun searchAnimeNextPageSelector(): String =
        nextPageSelector

    // ============================== DETAILS ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document
                .selectFirst("h1.inlineFree, h1")
                ?.text()
                ?.trim()
                .orEmpty()

            thumbnail_url = getPoster(document)

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
                        "div.categoriesWrapper a",
                )
                .eachText()
                .distinct()
                .joinToString()

            author = document
                .select(
                    "a.pstar-list-btn, " +
                        ".userInfo .usernameWrap a",
                )
                .eachText()
                .distinct()
                .joinToString()

            status = SAnime.COMPLETED
        }
    }

    private fun getPoster(document: Document): String? {
        val noscript = document.selectFirst(
            "noscript:has(img.videoElementPoster)",
        )

        if (noscript != null) {
            val poster = Jsoup
                .parse(noscript.html())
                .selectFirst("img")
                ?.attr("src")
                ?.trim()

            if (!poster.isNullOrBlank()) {
                return poster
            }
        }

        val image = document.selectFirst(
            "img.videoElementPoster, " +
                "meta[property=og:image]",
        ) ?: return null

        val url = if (image.tagName() == "meta") {
            image.attr("content")
        } else {
            image.attr("src")
                .ifBlank { image.attr("data-src") }
        }

        return url.takeIf { it.isNotBlank() }
    }

    // ============================== EPISODES ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Vídeo completo"
                setUrlWithoutDomain(
                    response.request.url
                        .toString()
                        .removePrefix(baseUrl),
                )
                date_upload = System.currentTimeMillis()
                episode_number = 1F
            },
        )
    }

    override fun episodeListSelector(): String =
        throw UnsupportedOperationException("Not used")

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException("Not used")

    // =============================== VIDEOS ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val scriptData = document
            .select("script")
            .firstOrNull { script ->
                val content = script.data().ifBlank { script.html() }

                content.contains("flashvars") ||
                    content.contains("mediaDefinitions") ||
                    content.contains("videoUrl")
            }
            ?.data()
            ?.ifBlank {
                document
                    .select("script")
                    .firstOrNull { script ->
                        script.html().contains("flashvars")
                    }
                    ?.html()
                    .orEmpty()
            }
            ?: return emptyList()

        val jsonString = extractJson(scriptData)
            ?: return emptyList()

        val parsedData = runCatching {
            json.decodeFromString<PhubJson>(jsonString)
        }.getOrElse {
            Log.e(TAG, "Erro ao interpretar JSON do player", it)
            return emptyList()
        }

        val port = proxyServer.listeningPort
        val pageUrl = response.request.url.toString()

        return parsedData.mediaDefinitions
            .orEmpty()
            .mapNotNull { media ->
                val rawUrl = media.videoUrl
                    ?.trim()
                    ?.replace("\\/", "/")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val quality = getQuality(media)

                val encodedUrl = URLEncoder.encode(
                    rawUrl,
                    "UTF-8",
                )

                val encodedReferer = URLEncoder.encode(
                    pageUrl,
                    "UTF-8",
                )

                val proxyUrl =
                    "http://127.0.0.1:$port/proxy" +
                        "?url=$encodedUrl" +
                        "&referer=$encodedReferer"

                Video(
                    url = proxyUrl,
                    quality = "PornHub - $quality",
                    videoUrl = proxyUrl,
                )
            }
            .distinctBy { it.videoUrl }
    }

    private fun getQuality(media: PhubVideoJson): String {
        val rawQuality = media.quality
            ?.let { quality ->
                runCatching {
                    if (quality.jsonPrimitive.isString) {
                        quality.jsonPrimitive.content
                    } else {
                        quality.toString()
                    }
                }.getOrNull()
            }
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?: media.format
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "Default"

        return if (
            rawQuality.endsWith("p") ||
            rawQuality == "Default"
        ) {
            rawQuality
        } else {
            "${rawQuality}p"
        }
    }

    private fun extractJson(script: String): String? {
        val markers = listOf(
            "var flashvars_",
            "var flashvars",
            "flashvars =",
            "mediaDefinitions",
        )

        val markerIndex = markers
            .map { marker -> script.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null

        val start = script.indexOf(
            '{',
            markerIndex,
        ).takeIf { it >= 0 } ?: return null

        var depth = 0
        var insideString = false
        var escaped = false

        for (index in start until script.length) {
            val char = script[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\' && insideString) {
                escaped = true
                continue
            }

            if (char == '"') {
                insideString = !insideString
                continue
            }

            if (insideString) continue

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
        throw UnsupportedOperationException("Not used")

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used")

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences
            .getString("preferred_quality", "720")
            ?: "720"

        return sortedByDescending {
            it.quality.contains(preferred)
        }
    }

    // ============================== UTILITIES ==============================

    private fun Element.toAnime(): SAnime {
        val anime = SAnime.create()

        val link = selectFirst(
            "a[href*=/view_video.php], " +
                "a[href*=/video/], " +
                "a[href*=/view_video]",
        ) ?: selectFirst("a")
            ?: throw IllegalArgumentException(
                "Link do vídeo não encontrado",
            )

        val href = link
            .absUrl("href")
            .takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "URL do vídeo não encontrada",
            )

        val image = selectFirst("img")

        val title = image
            ?.attr("alt")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("span.title, .title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: link.attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: link.text()
                .trim()
                .takeIf { it.isNotBlank() }
            ?: "Vídeo"

        val thumbnail = image?.let {
            when {
                it.attr("data-mediumproxy").isNotBlank() ->
                    it.attr("data-mediumproxy")

                it.attr("data-thumb_url").isNotBlank() ->
                    it.attr("data-thumb_url")

                it.attr("data-src").isNotBlank() ->
                    it.attr("data-src")

                it.attr("data-thumb").isNotBlank() ->
                    it.attr("data-thumb")

                else ->
                    it.attr("src")
            }
        }?.takeIf { it.isNotBlank() }

        anime.setUrlWithoutDomain(href)
        anime.title = title
        anime.thumbnail_url = thumbnail

        return anime
    }

    // ============================== FILTERS ==============================

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Categoria",
        categories.map { it.first }.toTypedArray(),
    ) {
        fun toUrl(): String =
            categories[state].second

        companion object {
            private val categories = arrayOf(
                "Todos" to "/video",
                "18-25" to "/categories/teen",
                "60FPS" to "/video?c=105",
                "Amateur" to "/video?c=3",
                "Anal" to "/video?c=35",
                "Arab" to "/video?c=98",
                "Asian" to "/video?c=1",
                "Babe" to "/categories/babe",
                "Big Ass" to "/video?c=4",
                "Blonde" to "/video?c=9",
                "Brunette" to "/video?c=11",
                "Cosplay" to "/video?c=241",
                "Ebony" to "/video?c=17",
                "HD Porn" to "/hd",
                "MILF" to "/video?c=29",
            )
        }
    }

    // ============================== PREFERENCES ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPreference = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualidade preferencial"

            entries = arrayOf(
                "1080p",
                "720p",
                "480p",
                "360p",
            )

            entryValues = arrayOf(
                "1080",
                "720",
                "480",
                "360",
            )

            setDefaultValue("720")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as String

                preferences
                    .edit()
                    .putString("preferred_quality", value)
                    .apply()

                true
            }
        }

        screen.addPreference(qualityPreference)
    }

    companion object {
        private const val TAG = "PornHubSource"
    }
}

// ============================== PROXY ==============================

class PornHubProxyServer(
    private val client: OkHttpClient,
    private val baseUrl: String,
) : NanoHTTPD("127.0.0.1", 0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/proxy") {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "not found",
            )
        }

        val targetUrl = session
            .parameters["url"]
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "missing url",
            )

        val referer = session
            .parameters["referer"]
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "$baseUrl/"

        val requestHeaders = Headers.Builder()
            .set("User-Agent", UA)
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .set("Accept", "*/*")
            .build()

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .headers(requestHeaders)
            .get()

        session.headers["range"]?.let { range ->
            requestBuilder.header("Range", range)
        }

        Log.d(
            TAG,
            "Requisição: range=${session.headers["range"]}, " +
                "url=$targetUrl",
        )

        val upstream = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (error: Exception) {
            Log.e(TAG, "Erro na requisição do vídeo", error)

            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                error.message ?: "request error",
            )
        }

        if (upstream.code >= 400) {
            val code = upstream.code
            upstream.close()

            val status = Response.Status.lookup(code)
                ?: Response.Status.INTERNAL_ERROR

            return newFixedLengthResponse(
                status,
                "text/plain",
                "upstream error: $code",
            )
        }

        val body = upstream.body
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "empty body",
            )

        val mimeType = upstream.header(
            "Content-Type",
        ) ?: "video/mp4"

        val status = if (upstream.code == 206) {
            Response.Status.PARTIAL_CONTENT
        } else {
            Response.Status.OK
        }

        val response = if (body.contentLength() >= 0) {
            newFixedLengthResponse(
                status,
                mimeType,
                body.byteStream(),
                body.contentLength(),
            )
        } else {
            newChunkedResponse(
                status,
                mimeType,
                body.byteStream(),
            )
        }

        upstream.header("Content-Range")?.let {
            response.addHeader("Content-Range", it)
        }

        upstream.header("Accept-Ranges")?.let {
            response.addHeader("Accept-Ranges", it)
        }

        upstream.header("Content-Length")?.let {
            response.addHeader("Content-Length", it)
        }

        return response
    }

    companion object {
        private const val TAG = "PornHubProxy"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}

// ============================== JSON ==============================

@Serializable
data class PhubJson(
    val mediaDefinitions: List<PhubVideoJson>? = null,
)

@Serializable
data class PhubVideoJson(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
)
