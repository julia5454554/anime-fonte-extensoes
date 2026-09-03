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
import okhttp3.Request
import okhttp3.Response

class HardGif : AnimeHttpSource() {

    override val name = "HardGif"
    override val baseUrl = "https://hardgif.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article, div.card, div.post, div.video-card, div.item, .grid-item, div.entry")

        val animeList = elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a[href]") ?: return@mapNotNull null
            val rawUrl = linkElement.attr("href")

            if (rawUrl.isBlank() || rawUrl == baseUrl || rawUrl == "$baseUrl/" ||
                rawUrl.contains("/category/") || rawUrl.contains("/tag/") || rawUrl.contains("/page/")
            ) {
                return@mapNotNull null
            }

            val title = element.selectFirst("h1, h2, h3, h4, h5, h6, .card-title, .title, .entry-title")?.text()?.trim()
                ?: linkElement.attr("title").ifBlank { linkElement.text() }.trim()

            if (title.isBlank()) return@mapNotNull null

            val imgElement = element.selectFirst("img, video")
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

        val hasNextPage = document.selectFirst("a.next, a.nextpostslink, .pagination a.next, a:contains(Next), a:contains(Próxima), .nav-previous a") != null
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
        anime.title = document.selectFirst("h1, h2.entry-title, .post-title, .card-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img")?.attr("src")
        anime.description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("div.entry-content, div.post-content, p")?.text()

        val genres = mutableListOf<String>()
        document.select("a[rel='category tag'], a[rel='tag'], a[href*='/category/'], a[href*='/tag/']").forEach {
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

        // 1. Tags de mídia do HTML
        document.select("video, video source, source").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank()) {
                val src = fixUrl(rawSrc)
                val quality = element.attr("label").ifBlank { "Vídeo HD" }
                videos.add(Video(src, quality, src, videoHeaders(pageUrl)))
            }
        }

        // 2. Links diretos via Regex no HTML
        val allMediaRegex = Regex("""(?:https?:)?//[^"'\s]+\.(?:m3u8|mp4|webm|gifv)[^"'\s]*""")
        allMediaRegex.findAll(document.html()).forEach { match ->
            var url = match.value.replace("\\/", "/").replace("&amp;", "&").trim()
            url = fixUrl(url)
            if (url.startsWith("http") && !url.endsWith(".png") && !url.endsWith(".jpg")) {
                videos.add(Video(url, "Mídia Direta", url, videoHeaders(pageUrl)))
            }
        }

        // 3. Iframe Embeds
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.isNotBlank() && iframeUrl.startsWith("http")) {
                try {
                    client.newCall(GET(iframeUrl, headers)).execute().use { embedResp ->
                        if (embedResp.isSuccessful) {
                            val embedDoc = embedResp.asJsoup()
                            embedDoc.select("video source, video").forEach { element ->
                                val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
                                if (rawSrc.isNotBlank()) {
                                    val src = fixUrl(rawSrc)
                                    videos.add(Video(src, "Embed HD", src, videoHeaders(iframeUrl)))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
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
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", pageUrl)
        .set("Origin", baseUrl)
        .set("Accept", "*/*")
        .build()
}
