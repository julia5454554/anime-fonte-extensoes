package eu.kanade.tachiyomi.animeextension.pt.hardgif

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class HardGif : AnimeHttpSource() {

    override val name = "HardGif"
    override val baseUrl = "https://hardgif.com"
    override val lang = "pt"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")
        .add("sec-ch-ua", "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", "?1")
        .add("sec-ch-ua-platform", "\"Android\"")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")
        .add("Cookie", "age_verified=1; age_gate=1; over18=1; age_check=1; wordpress_eligibility=1; is_adult=1")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        checkCloudflareAndAgeGate(response, document.html())

        val elements = document.select(".card, article, .post, .mobVideoContainer, div[class*='col-']")
            .ifEmpty { document.select("a[href*='/gif/'], a[href*='/video/']") }

        val animeList = elements.mapNotNull { element ->
            val link = if (element.tagName() == "a") {
                element
            } else {
                element.selectFirst("a[href*='/gif/'], a[href*='/video/'], h1 a, h2 a, h3 a, .card-title a, a[href]")
                    ?: return@mapNotNull null
            }

            val rawUrl = link.attr("href").trim()
            val absoluteUrl = fixUrl(rawUrl)

            val isInvalidLink = rawUrl.isBlank() ||
                rawUrl == "#" ||
                absoluteUrl == baseUrl ||
                absoluteUrl == "$baseUrl/" ||
                rawUrl.contains("/category/") ||
                rawUrl.contains("/tag/") ||
                rawUrl.contains("/author/") ||
                rawUrl.contains("/page/") ||
                rawUrl.contains("javascript:") ||
                rawUrl.matches(Regex(""".*\.(png|jpg|jpeg|gif|css|js|svg)$""", RegexOption.IGNORE_CASE))

            if (isInvalidLink) return@mapNotNull null

            val title = link.text().trim().ifBlank {
                element.selectFirst("h1, h2, h3, h4, h5, h6, .card-title, .title")?.text()?.trim() ?: ""
            }

            if (title.isBlank() || title.length < 3 || title.equals("Sem título", ignoreCase = true) || title.equals("Vídeo", ignoreCase = true)) {
                return@mapNotNull null
            }

            val container = if (element.hasClass("mobVideoContainer")) element else element.selectFirst(".mobVideoContainer")
            val screenshotsAttr = container?.attr("data-screenshots") ?: ""

            // Extração de capas filtrando atributos ocultos, posters de vídeo e lazy loading
            val thumbnail = Regex("""https?://[^"'\s\\]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                .find(screenshotsAttr)?.value
                ?: element.selectFirst("video")?.attr("poster")?.ifBlank { null }
                ?: element.select("img").mapNotNull { img ->
                    val src = img.attr("data-src")
                        .ifBlank { img.attr("data-lazy-src") }
                        .ifBlank { img.attr("data-original") }
                        .ifBlank { img.attr("src") }

                    if (src.isBlank() || src.contains("avatar") || src.contains("logo") || src.contains("external-preview") || src.contains("icon")) {
                        null
                    } else {
                        src
                    }
                }.firstOrNull()
                ?: Regex("""https?://[^"'\s\\]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                    .find(element.html())?.value

            SAnime.create().apply {
                setUrlWithoutDomain(rawUrl)
                this.title = title
                thumbnail_url = thumbnail?.replace("\\/", "/")?.let { fixUrl(it) }
            }
        }.distinctBy { it.url }

        if (animeList.isEmpty()) {
            throw Exception("Nenhum item encontrado. Abra na WebView para aceitar a verificação de idade e passar o Cloudflare.")
        }

        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        return GET("$baseUrl/?s=$encodedQuery", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        checkCloudflareAndAgeGate(response, document.html())

        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, h2, h6.card-title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Vídeo"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.select("img").mapNotNull { img ->
                val src = img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("data-original") }
                    .ifBlank { img.attr("src") }
                if (src.contains("external-preview") || src.contains("avatar") || src.contains("logo")) null else src
            }.firstOrNull()
        anime.description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".entry-content, .post-content, p")?.text()

        val genres = mutableListOf<String>()
        document.select("a[rel='category tag'], a[rel='tag'], a[href*='/category/']").forEach {
            genres.add(it.text().trim())
        }
        anime.genre = genres.distinct().joinToString(", ")

        return anime
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Assistir Mídia"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        checkCloudflareAndAgeGate(response, document.html())

        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        val iframeSrc = document.selectFirst("iframe[src*='stream'], iframe[src*='player']")?.attr("src")
        val htmlToParse = if (!iframeSrc.isNullOrEmpty()) {
            val iframeUrl = fixUrl(iframeSrc)
            try {
                client.newCall(GET(iframeUrl, videoHeaders(pageUrl))).execute().asJsoup().html()
            } catch (e: Exception) {
                document.html()
            }
        } else {
            document.html()
        }

        val dataVideosAttr = document.selectFirst(".mobVideoContainer")?.attr("data-videos") ?: ""
        val urlRegex = Regex("""https?:\\?/\\?/[^"'\s,]+?\.(?:m3u8|mp4|webm)""")

        urlRegex.findAll(dataVideosAttr + htmlToParse).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&").trim()
            if (cleanUrl.startsWith("http")) {
                val quality = if (cleanUrl.contains(".m3u8")) "HLS Playlist (HD)" else "MP4 Direct"
                videos.add(Video(cleanUrl, quality, cleanUrl, videoHeaders(pageUrl)))
            }
        }

        document.select("video source, video").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank() && !rawSrc.startsWith("blob:")) {
                val src = fixUrl(rawSrc)
                videos.add(Video(src, "Mídia HD", src, videoHeaders(pageUrl)))
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    // ============================= Utilities ==============================
    private fun checkCloudflareAndAgeGate(response: Response, html: String) {
        if (response.code in listOf(403, 503) || html.contains("cf-challenge") || html.contains("Just a moment")) {
            throw Exception("Proteção Cloudflare ativada. Toque em 'Abrir na WebView'.")
        }
        if (html.contains("VERIFICATION REQUIRED") || html.contains("I AM 18+")) {
            throw Exception("Verificação de idade necessária. Toque em 'Abrir na WebView' e clique em 'I AM 18+' para salvar o acesso.")
        }
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    private fun videoHeaders(pageUrl: String): Headers = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")
        .set("sec-ch-ua", "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"")
        .set("sec-ch-ua-mobile", "?1")
        .set("sec-ch-ua-platform", "\"Android\"")
        .set("Referer", pageUrl)
        .set("Origin", baseUrl)
        .set("Accept", "*/*")
        .set("Cookie", "age_verified=1; age_gate=1; over18=1; age_check=1; wordpress_eligibility=1; is_adult=1")
        .build()
}
