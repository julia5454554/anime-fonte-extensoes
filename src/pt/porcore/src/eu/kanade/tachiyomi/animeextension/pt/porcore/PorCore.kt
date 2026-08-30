package eu.kanade.tachiyomi.animeextension.pt.porcore

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class Porcore : AnimeHttpSource() {

    override val name = "Porcore"
    override val baseUrl = "https://porcore.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        val hasNextPage = document.selectFirst("a.next") != null || animes.isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/search?q=$encodedQuery"
        } else {
            "$baseUrl/search?q=$encodedQuery&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        val hasNextPage = document.selectFirst("a.next") != null || animes.isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())

        anime.title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Sem título"

        anime.thumbnail_url = document.selectFirst("div.video-player img")?.attr("src")
            ?: document.selectFirst("div.video-player")?.attr("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        anime.description = extractDescription(document)
        anime.status = SAnime.COMPLETED
        return anime
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Vídeo"
            episode_number = 1f
            date_upload = 0L
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        return extractVideosFromDocument(document, pageUrl)
    }

    // ============================= Utilities ==============================
    private fun parseVideoCards(document: Document): List<SAnime> {
        return document.select("div.onevideothumb").mapNotNull { element ->
            val link = element.selectFirst("a.clip-link") ?: return@mapNotNull null
            val title = link.attr("title").trim()
            val href = link.attr("href")
            val thumbnail = element.selectFirst("img")?.attr("src")

            SAnime.create().apply {
                this.title = title
                this.setUrlWithoutDomain(href)
                this.thumbnail_url = thumbnail?.let { if (it.startsWith("http")) it else baseUrl + it }
            }
        }
    }

    private fun extractDescription(document: Document): String {
        val selectors = listOf(
            "div.video-description",
            "div.description",
            "div.entry-content",
            "div.post-content",
            "div.well",
            "meta[name='description']",
            "meta[property='og:description']"
        )
        for (selector in selectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                if (element.tagName() == "meta") {
                    val content = element.attr("content").trim()
                    if (content.isNotBlank()) return content
                } else {
                    val text = element.text().trim()
                    if (text.isNotBlank()) return text
                }
            }
        }
        return ""
    }

    private fun extractVideosFromDocument(document: Document, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        // 1) Procurar tag <source> dentro do player Video.js
        val source = document.selectFirst("video#currentvideo_html5_api source[src]")
            ?: document.selectFirst("div.video-player source[src]")
            ?: document.selectFirst("video source[src]")

        if (source != null) {
            var src = source.attr("src").trim()
            if (src.startsWith("//")) src = "https:$src"
            else if (src.startsWith("/")) src = "$baseUrl$src"

            val videoHeaders = headers.newBuilder()
                .set("Referer", pageUrl)
                .set("Accept", "*/*")
                .build()

            val quality = if (src.contains(".mp4", ignoreCase = true)) "MP4" else "HLS"
            videos.add(Video(src, quality, src, videoHeaders))
        }

        // 2) Fallback: procurar <video> com src direto
        if (videos.isEmpty()) {
            document.select("video[src]").forEach { element ->
                var src = element.attr("src").trim()
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = "$baseUrl$src"

                val videoHeaders = headers.newBuilder()
                    .set("Referer", pageUrl)
                    .set("Accept", "*/*")
                    .build()

                val quality = if (src.contains(".mp4", ignoreCase = true)) "MP4" else "Video"
                videos.add(Video(src, quality, src, videoHeaders))
            }
        }

        // 3) Fallback: regex para .m3u8 ou .mp4
        if (videos.isEmpty()) {
            val mediaRegex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
            mediaRegex.findAll(document.html()).forEach { match ->
                val url = match.value.replace("&amp;", "&")
                val quality = if (url.contains(".mp4", ignoreCase = true)) "MP4" else "HLS"
                val videoHeaders = headers.newBuilder()
                    .set("Referer", pageUrl)
                    .set("Accept", "*/*")
                    .build()
                videos.add(Video(url, quality, url, videoHeaders))
            }
        }

        return videos.distinctBy { it.url }
    }
}
