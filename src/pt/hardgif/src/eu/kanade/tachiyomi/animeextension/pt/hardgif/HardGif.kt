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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        checkCloudflare(response, document.html())

        val animeList = document.select("a[href]").mapNotNull { link ->
            val rawUrl = link.attr("href").trim()
            val absoluteUrl = fixUrl(rawUrl)

            // Ignora a Home, links de navegação, imagens diretas, avatares e estáticos
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

            val parent = link.parents().firstOrNull {
                it.hasClass("card") || it.hasClass("post") || it.hasClass("mobVideoContainer") || it.tagName() == "article"
            } ?: link.parent()

            val title = link.text().trim().ifBlank {
                parent?.selectFirst("h1, h2, h3, h4, h5, h6, .card-title, .title")?.text()?.trim() ?: ""
            }

            if (title.isBlank() || title.length < 3 || title.equals("Sem título", ignoreCase = true)) {
                return@mapNotNull null
            }

            // Captura a capa do post e descarta imagens do tipo avatar/logo
            val container = parent?.selectFirst(".mobVideoContainer")
            val screenshotsAttr = container?.attr("data-screenshots") ?: ""
            val thumbnail = Regex("""https?://[^"'\s\\]+""").find(screenshotsAttr)?.value
                ?: parent?.selectFirst("video")?.attr("poster")
                ?: parent?.selectFirst("img:not([src*='logo']):not([src*='avatar'])")?.attr("src")
                ?: parent?.selectFirst("img:not([src*='logo']):not([src*='avatar'])")?.attr("data-src")

            SAnime.create().apply {
                setUrlWithoutDomain(rawUrl)
                this.title = title
                thumbnail_url = thumbnail?.let { fixUrl(it.replace("\\/", "/")) }
            }
        }.distinctBy { it.url }

        if (animeList.isEmpty()) {
            throw Exception("Nenhum item encontrado. Abra na WebView para concluir a verificação do Cloudflare.")
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
        checkCloudflare(response, document.html())

        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, h2, h6.card-title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Vídeo"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img:not([src*='logo']):not([src*='avatar'])")?.attr("src")
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
        checkCloudflare(response, document.html())

        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        val dataVideosAttr = document.selectFirst(".mobVideoContainer")?.attr("data-videos") ?: ""
        val urlRegex = Regex("""https?:\\?/\\?/[^"'\s,]+?\.(?:m3u8|mp4|webm)""")

        urlRegex.findAll(dataVideosAttr + document.html()).forEach { match ->
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
    private fun checkCloudflare(response: Response, html: String) {
        if (response.code in listOf(403, 503) || html.contains("cf-challenge") || html.contains("Just a moment")) {
            throw Exception("Proteção Cloudflare ativada. Toque em 'Abrir na WebView'.")
        }
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    private fun videoHeaders(pageUrl: String): Headers = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .set("Referer", pageUrl)
        .set("Origin", baseUrl)
        .set("Accept", "*/*")
        .build()
}
