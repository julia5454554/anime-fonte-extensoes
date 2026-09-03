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
import org.jsoup.nodes.Document

class HardGif : AnimeHttpSource() {

    override val name = "HardGif"
    override val baseUrl = "https://hardgif.com"
    override val lang = "pt"
    override val supportsLatest = true

    // Habilita a proteção e o controle de cookies do Cloudflare
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        checkCloudflare(response)
        val document = response.asJsoup()

        // Tenta capturar por contêineres e fallback por links
        val elements = document.select("article, div.post, div.card, div.item, div.grid-item, div.video-card")
        
        val animeList = if (elements.isNotEmpty()) {
            elements.mapNotNull { element -> parseAnimeFromElement(element) }
        } else {
            document.select("a[href]:has(img), a[href]:has(video)").mapNotNull { link ->
                parseAnimeFromLink(link, document)
            }
        }

        val cleanList = animeList.distinctBy { it.url }
        val hasNextPage = document.selectFirst("a.next, a.nextpostslink, .pagination a.next, a[rel='next']") != null

        return AnimesPage(cleanList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/$page/?s=$encodedQuery"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        checkCloudflare(response)
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, h2.entry-title, .post-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img")?.attr("src")
        anime.description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("div.entry-content, div.post-content, p")?.text()

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
            name = "Assistir Vídeo / GIF"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        checkCloudflare(response)
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // 1. Tags de vídeo e mídia
        document.select("video, video source, source").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank()) {
                val src = fixUrl(rawSrc)
                videos.add(Video(src, "Vídeo HD", src, videoHeaders(pageUrl)))
            }
        }

        // 2. Extração via Regex no HTML
        val mediaRegex = Regex("""(?:https?:)?//[^"'\s]+\.(?:mp4|webm|m3u8|gifv)[^"'\s]*""")
        mediaRegex.findAll(document.html()).forEach { match ->
            var url = match.value.replace("\\/", "/").replace("&amp;", "&").trim()
            url = fixUrl(url)
            if (url.startsWith("http") && !url.endsWith(".png") && !url.endsWith(".jpg")) {
                if (videos.none { it.videoUrl == url }) {
                    videos.add(Video(url, "Mídia Direta", url, videoHeaders(pageUrl)))
                }
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    // ============================= Helpers ================================
    private fun parseAnimeFromElement(element: org.jsoup.nodes.Element): SAnime? {
        val linkElement = element.selectFirst("a[href]") ?: return null
        val rawUrl = linkElement.attr("href").trim()

        if (!isValidUrl(rawUrl)) return null

        val imgElement = element.selectFirst("img, video")
        val title = element.selectFirst("h1, h2, h3, h4, .title, .entry-title")?.text()?.trim()
            ?: imgElement?.attr("alt")
            ?: linkElement.attr("title").ifBlank { linkElement.text() }.trim()

        if (title.isBlank()) return null

        val thumbnail = imgElement?.attr("data-src")
            ?: imgElement?.attr("data-lazy-src")
            ?: imgElement?.attr("src")
            ?: imgElement?.attr("poster")

        return SAnime.create().apply {
            setUrlWithoutDomain(rawUrl)
            this.title = title
            thumbnail_url = thumbnail?.let { fixUrl(it) }
        }
    }

    private fun parseAnimeFromLink(link: org.jsoup.nodes.Element, doc: Document): SAnime? {
        val rawUrl = link.attr("href").trim()
        if (!isValidUrl(rawUrl)) return null

        val imgElement = link.selectFirst("img, video")
        val title = imgElement?.attr("alt")
            ?: link.attr("title").ifBlank { link.text() }.trim()

        if (title.isBlank()) return null

        val thumbnail = imgElement?.attr("data-src")
            ?: imgElement?.attr("src")

        return SAnime.create().apply {
            setUrlWithoutDomain(rawUrl)
            this.title = title
            thumbnail_url = thumbnail?.let { fixUrl(it) }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        if (url.isBlank() || url == baseUrl || url == "$baseUrl/") return false
        val blacklistedKeywords = listOf(
            "/category/", "/tag/", "/page/", "/dmca", "/privacy",
            "/terms", "/contact", "/search", "#"
        )
        return blacklistedKeywords.none { url.contains(it) }
    }

    private fun checkCloudflare(response: Response) {
        if (response.code == 403 || response.code == 503) {
            val html = response.peekBody(Long.MAX_VALUE).string()
            if (html.contains("cf-challenge") || html.contains("Just a moment")) {
                throw Exception("Abra o site na WebView para resolver o Cloudflare e tente novamente.")
            }
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
