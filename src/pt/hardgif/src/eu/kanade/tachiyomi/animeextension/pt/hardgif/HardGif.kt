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
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Seletores baseados no flex-images (.flex-images .item ou div.item)
        val elements = document.select(".flex-images .item, div.item, a.item, article")

        val animeList = elements.mapNotNull { element ->
            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href]")
            val rawUrl = linkElement?.attr("href")?.trim() ?: element.attr("data-url").trim()

            if (rawUrl.isBlank() || rawUrl == baseUrl || rawUrl == "$baseUrl/" ||
                rawUrl.contains("/category/") || rawUrl.contains("/tag/") || rawUrl.contains("/page/")
            ) {
                return@mapNotNull null
            }

            val imgElement = element.selectFirst("img, video")
            val title = imgElement?.attr("alt")?.trim()
                ?: linkElement?.attr("title")?.trim()
                ?: element.selectFirst(".title, h2, h3")?.text()?.trim()
                ?: linkElement?.text()?.trim()

            if (title.isNullOrBlank()) return@mapNotNull null

            val thumbnail = imgElement?.attr("data-src")
                ?: imgElement?.attr("data-lazy-src")
                ?: imgElement?.attr("src")
                ?: imgElement?.attr("poster")

            SAnime.create().apply {
                setUrlWithoutDomain(rawUrl)
                this.title = title
                thumbnail_url = thumbnail?.let { fixUrl(it) }
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".pagination a.next, a.next, a:contains(Next), a:contains(Próxima)") != null
        return AnimesPage(animeList, hasNextPage)
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
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img")?.attr("src")
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
            name = "Assistir GIF / Vídeo"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // 1. Video.js e tags HTML5
        document.select("video.video-js, video, video source, source").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank()) {
                val src = fixUrl(rawSrc)
                videos.add(Video(src, "Vídeo HD", src, videoHeaders(pageUrl)))
            }
        }

        // 2. Mídia v.redd.it / mp4 / m3u8 extraída via Regex
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

    // ============================= Utilities ==============================
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
