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
        val elements = document.select("div.card.video-card, div.col-12:has(.video_name)")

        val animeList = elements.mapNotNull { element ->
            val linkElement = element.selectFirst("h6.card-title a, .video_name a") ?: return@mapNotNull null
            val rawUrl = linkElement.attr("href").trim()
            val title = linkElement.text().trim()

            if (rawUrl.isBlank() || title.isBlank()) return@mapNotNull null

            val container = element.selectFirst(".mobVideoContainer")
            val screenshotsAttr = container?.attr("data-screenshots") ?: ""
            val thumbnail = Regex("""https?://[^"'\s\\]+""").find(screenshotsAttr)?.value
                ?: element.selectFirst(".vjs-poster")?.attr("style")?.let { style ->
                    Regex("""url\((?:['"]?)(.*?)(?:['"]?)\)""").find(style)?.groupValues?.get(1)
                }
                ?: element.selectFirst("img")?.attr("src")

            SAnime.create().apply {
                setUrlWithoutDomain(rawUrl)
                this.title = title
                thumbnail_url = thumbnail?.let { fixUrl(it.replace("\\/", "/")) }
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("a.next, a.nextpostslink, .pagination a.next, a[rel='next']") != null
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
        anime.title = document.selectFirst("h6.card-title, h1, .entry-title")?.text()?.trim()
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
            name = "Assistir Vídeo / GIF"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // 1. Extrai URLs do atributo JSON data-videos (.m3u8 / .mp4)
        val dataVideosAttr = document.selectFirst(".mobVideoContainer")?.attr("data-videos") ?: ""
        val urlRegex = Regex("""https?:\\?/\\?/[^"'\s,]+?\.(?:m3u8|mp4|webm)""")

        urlRegex.findAll(dataVideosAttr + document.html()).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&").trim()
            if (cleanUrl.startsWith("http")) {
                val quality = if (cleanUrl.contains(".m3u8")) "HLS Playlist (HD)" else "MP4 Direct"
                videos.add(Video(cleanUrl, quality, cleanUrl, videoHeaders(pageUrl)))
            }
        }

        // 2. Fallback para tags <source> ou <video>
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
