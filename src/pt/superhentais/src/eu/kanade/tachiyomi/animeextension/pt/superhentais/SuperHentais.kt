package eu.kanade.tachiyomi.animeextension.pt.superhentais

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class SuperHentais : AnimeHttpSource() {

    override val name = "SuperHentais"
    override val baseUrl = "https://superhentais.com.br"
    override val lang = "pt"
    override val supportsLatest = true

    // ==================== USER-AGENTS ====================

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val uaMobile = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/lista-de-hentais"
        } else {
            "$baseUrl/lista-de-hentais/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article.box_view.list").mapNotNull { element ->
            animeFromElement(element)
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun animeFromElement(element: Element): SAnime? {
        val linkElement = element.selectFirst("a[itemprop=url]") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        val title = element.selectFirst("h2.grid_title a")?.text()?.trim()
            ?: linkElement.attr("title").trim()

        val imageUrl = element.selectFirst("img")?.attr("abs:src") ?: ""

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            this.thumbnail_url = imageUrl
        }
    }

    // ==================== DETALHES ====================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?.removeSuffix(" Hentai Online")
            ?.removeSuffix(" Online")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""

        anime.thumbnail_url = document.selectFirst("img[itemprop=image]")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""

        val sinopse = document.selectFirst(".sinop .box_content p:not(:empty)")?.text()?.trim()
        anime.description = sinopse
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        anime.genre = document.select("span[itemprop=genre] a.genero_btn")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select("div.epsBox.epcontent").forEachIndexed { index, element ->
            val linkElement = element.selectFirst("a[itemprop=url]") ?: return@forEachIndexed
            val href = linkElement.attr("href")
            if (href.isBlank()) return@forEachIndexed

            val epName = element.selectFirst("span[itemprop=name]")?.text()?.trim()
                ?: linkElement.attr("title").trim()
                ?: "Episódio ${index + 1}"

            val epNumber = element.attr("data-cap").toFloatOrNull()
                ?: element.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toFloatOrNull()
                ?: (index + 1).toFloat()

            val epThumb = element.selectFirst("img")?.attr("abs:src") ?: ""

            val episode = SEpisode.create().apply {
                name = epName
                setUrlWithoutDomain(href)
                episode_number = epNumber
                thumbnail_url = epThumb
                date_upload = 0L
            }
            episodes.add(episode)
        }

        if (episodes.isEmpty()) {
            val episode = SEpisode.create().apply {
                name = document.selectFirst("h1")?.text()?.trim() ?: "Assistir"
                setUrlWithoutDomain(response.request.url.toString())
                episode_number = 1f
            }
            episodes.add(episode)
        }

        return episodes
    }

    // ==================== VÍDEO ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val pageUrl = response.request.url.toString()

        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        // -------- ESTRATÉGIA PRINCIPAL: iframe t_param.php do SuperHentais --------
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isBlank()) return@forEach

            if (src.contains("t_param.php") || src.contains("video-play.mp4")) {
                val attempts = listOf(
                    QualityAttempt("360p", uaMobile),
                    QualityAttempt("720p", uaDesktop),
                )
                val seenUrls = mutableSetOf<String>()

                attempts.forEach { attempt ->
                    val directUrl = resolveRedirect(
                        noRedirectClient = noRedirectClient,
                        url = src,
                        referer = pageUrl,
                        userAgent = attempt.userAgent,
                    )
                    if (!directUrl.isNullOrBlank() && seenUrls.add(directUrl)) {
                        val vHeaders = headersBuilder()
                            .add("User-Agent", attempt.userAgent)
                            .add("Referer", pageUrl)
                            .add("Origin", baseUrl)
                            .add("Accept", "*/*")
                            .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                            .build()
                        videoList.add(Video(directUrl, attempt.label, directUrl, vHeaders))
                    }
                }

                if (videoList.isEmpty()) {
                    val vHeaders = headersBuilder()
                        .add("Referer", pageUrl)
                        .add("Origin", baseUrl)
                        .add("Accept", "*/*")
                        .build()
                    videoList.add(Video(src, "Padrão", src, vHeaders))
                }
            } else if (src.contains("embed") || src.contains("player") || src.contains("/e/")) {
                try {
                    val iframeResponse = client.newCall(GET(src, headers)).execute()
                    val iframeDoc = iframeResponse.asJsoup()
                    iframeDoc.select("video source, video[src]").forEach { el ->
                        val url = el.attr("abs:src").ifBlank { el.attr("abs:data-src") }
                        if (url.isNotBlank() && videoList.none { it.url == url }) {
                            videoList.add(createVideo(url, "Embed", pageUrl))
                        }
                    }
                    val iframeHtml = iframeDoc.html()
                    val regex = """(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""".toRegex()
                    regex.findAll(iframeHtml).forEach { match ->
                        val url = match.value
                        if (videoList.none { it.url == url }) {
                            videoList.add(createVideo(url, "Embed", pageUrl))
                        }
                    }
                } catch (_: Exception) {
                    // ignora
                }
            }
        }

        // -------- Fallback 1: tags <video>/<source> soltas --------
        document.select("video source, video[src]").forEach { element ->
            val url = element.attr("abs:src").ifBlank { element.attr("abs:data-src") }
            if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                if (videoList.none { it.url == url }) {
                    videoList.add(createVideo(url, "Vídeo", pageUrl))
                }
            }
        }

        // -------- Fallback 2: regex em scripts --------
        val scriptContent = document.select("script").html()
        val urlRegex = """(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""".toRegex()
        urlRegex.findAll(scriptContent).forEach { match ->
            val url = match.value
            if (videoList.none { it.url == url }) {
                videoList.add(createVideo(url, "Vídeo", pageUrl))
            }
        }

        return videoList
    }

    // ==================== BUSCA ====================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/$page/?s=$encodedQuery"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==================== HELPERS ====================

    private data class QualityAttempt(val label: String, val userAgent: String)

    private fun resolveRedirect(
        noRedirectClient: OkHttpClient,
        url: String,
        referer: String,
        userAgent: String,
    ): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Origin", baseUrl)
            .header("Accept", "*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        noRedirectClient.newCall(request).execute().use { resp ->
            if (resp.isRedirect) {
                resp.header("Location")
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun createVideo(url: String, quality: String, pageUrl: String): Video {
        val videoHeaders = headersBuilder()
            .add("Referer", pageUrl)
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .build()
        return Video(url, quality, url, videoHeaders)
    }

    private fun parseDate(dateStr: String): Long = try {
        val format = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
        format.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
