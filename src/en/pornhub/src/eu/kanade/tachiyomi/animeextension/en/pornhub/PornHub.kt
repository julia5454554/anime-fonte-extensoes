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

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // Servidor Proxy Local (NanoHTTPD) inicializado sob demanda
    private val proxyServer by lazy {
        PornHubProxyServer(client, baseUrl).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    // Força o cabeçalho 'Accept-Encoding: gzip' para evitar erros de decodificação Brotli no Anikku
    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("Accept-Encoding", "gzip")
                .header("Cookie", "hasVisited=1; accessAgeDisclaimerPH=1; platform=pc")
                .header("User-Agent", PornHubProxyServer.UA)
                .build()
            chain.proceed(newRequest)
        }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== POPULAR ==============================

    override fun popularAnimeSelector(): String = "div.gridWrapper li.pcVideoListItem, ul.videos li"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/video?page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/view_video.php]") ?: element.selectFirst("a")
        val img = element.selectFirst("img")

        anime.setUrlWithoutDomain(link?.attr("href") ?: "")
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("span.title")?.text()
            ?: "Video"

        val thumb = img?.attr("data-mediumproxy")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-thumb_url")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")
        anime.thumbnail_url = thumb
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a"

    // =============================== SEARCH ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categoryFilter = filters.find { it is CategoryFilter } as? CategoryFilter
        val categoryUrl = categoryFilter?.toUrl()

        return when {
            query.isNotBlank() -> GET("$baseUrl/video/search?search=$query&page=$page", headers)
            categoryUrl != null -> {
                val connector = if (categoryUrl.contains("?")) "&" else "?"
                GET("$baseUrl$categoryUrl${connector}page=$page", headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // =========================== DETAILS / EPISODES ===========================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.inlineFree, h1")?.text()?.trim() ?: ""

        val noscriptTag = document.selectFirst("noscript:has(img.videoElementPoster)")
        val poster = if (noscriptTag != null) {
            Jsoup.parse(noscriptTag.html()).selectFirst("img")?.attr("src")
        } else {
            document.selectFirst("img.videoElementPoster")?.attr("src")
        }
        anime.thumbnail_url = poster

        anime.description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        anime.genre = document.select("div.tagsWrapper a, div.categoriesWrapper a").joinToString { it.text() }
        anime.author = document.select("a.pstar-list-btn, .userInfo .usernameWrap a").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Vídeo Completo"
            setUrlWithoutDomain(response.request.url.toString().removePrefix(baseUrl))
            date_upload = System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw Exception("Not used")
    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // =============================== VIDEOS ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(var flashvars)")?.data() ?: return emptyList()
        val jsonString = scriptData.substringAfter("var flashvars_").substringAfter(" = ").substringBefore(";\n")

        val videoList = mutableListOf<Video>()
        val port = proxyServer.listeningPort
        val pageUrl = response.request.url.toString()

        try {
            val parsedData = json.decodeFromString<PhubJson>(jsonString)
            parsedData.mediaDefinitions?.forEach { media ->
                val rawUrl = media.videoUrl ?: return@forEach
                val cleanUrl = rawUrl.replace("""\/""", "/")

                if (cleanUrl.isBlank()) return@forEach

                val rawQuality = when {
                    media.quality?.jsonPrimitive?.isString == true -> media.quality.jsonPrimitive.content
                    else -> "Default"
                }
                val qualityName = if (rawQuality.endsWith("p") || rawQuality == "Default") rawQuality else "${rawQuality}p"

                // Codifica os parâmetros para passar ao Proxy Local
                val encodedUrl = URLEncoder.encode(cleanUrl, "UTF-8")
                val encodedReferer = URLEncoder.encode(pageUrl, "UTF-8")
                val localProxyUrl = "http://127.0.0.1:$port/proxy?url=$encodedUrl&referer=$encodedReferer"

                videoList.add(
                    Video(
                        url = localProxyUrl,
                        quality = "PornHub - $qualityName",
                        videoUrl = localProxyUrl,
                    ),
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videoList
    }

    override fun videoListSelector(): String = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String = throw Exception("Not used")
    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString("preferred_quality", "720") ?: "720"
        return this.sortedByDescending { it.quality.contains(preferred) }
    }

    // ============================== FILTERS & PREFS ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("A busca por texto ignora os filtros de categoria"),
        CategoryFilter(),
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Categoria", categories.map { it.first }.toTypedArray()) {
        fun toUrl() = categories[state].second

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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualidade Preferencial"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesSelector(): String = throw Exception("Not used")
}

// ============================== PROXY SERVER ==============================

class PornHubProxyServer(
    private val client: OkHttpClient,
    private val baseUrl: String,
) : NanoHTTPD("127.0.0.1", 0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/proxy") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        // O NanoHTTPD decodifica automaticamente os query params
        val targetUrl = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing url")

        val pageReferer = session.parameters["referer"]?.firstOrNull() ?: "$baseUrl/"

        val reqHeaders = Headers.Builder()
            .set("User-Agent", UA)
            .set("Referer", pageReferer)
            .set("Origin", baseUrl)
            .set("Accept", "*/*")
            .build()

        val reqBuilder = Request.Builder().url(targetUrl).headers(reqHeaders).get()
        session.headers["range"]?.let { reqBuilder.header("Range", it) }

        Log.d(TAG, "→ range=${session.headers["range"]} url=$targetUrl")

        val upstream = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Upstream exception", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "err")
        }

        Log.d(TAG, "← ${upstream.code} len=${upstream.header("Content-Length")} range=${upstream.header("Content-Range")}")

        if (upstream.code >= 400) {
            val code = upstream.code
            upstream.close()
            val status = Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR
            return newFixedLengthResponse(status, "text/plain", "upstream $code")
        }

        val body = upstream.body
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "no body")

        val mime = upstream.header("Content-Type") ?: "video/mp4"
        val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val length = body.contentLength()

        val nanoResponse = if (length >= 0) {
            newFixedLengthResponse(status, mime, body.byteStream(), length)
        } else {
            newChunkedResponse(status, mime, body.byteStream())
        }

        upstream.header("Content-Range")?.let { nanoResponse.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { nanoResponse.addHeader("Accept-Ranges", it) }
        return nanoResponse
    }

    companion object {
        private const val TAG = "PornHubProxy"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

// ============================== JSON MODELS ==============================

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
