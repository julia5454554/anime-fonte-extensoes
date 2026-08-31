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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document

class HardGif : AnimeHttpSource() {

    override val name = "HardGif"
    override val baseUrl = "https://hardgif.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Cookie", "age_ok=1; age_verified=1")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/popular/page/$page/" else "$baseUrl/popular/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseCards(document)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        if (page > 1) {
            urlBuilder.addPathSegment("page")
            urlBuilder.addPathSegment(page.toString())
            urlBuilder.addPathSegment("")
        }
        urlBuilder.addQueryParameter("s", query.trim())
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())

        anime.title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.selectFirst("h6.video_name")?.text()?.trim()
            ?: "Sem título"

        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("div.vjs-poster")?.attr("style")
                ?.substringAfter("url(\"")?.substringBefore("\")")
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
    private fun parseCards(document: Document): List<SAnime> {
        val animes = mutableListOf<SAnime>()
        val containerSelectors = listOf(
            "div.video-card",
            "div.card",
            "div.thumb",
            "div.item",
            "article",
            "div.post",
        )

        for (selector in containerSelectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                for (element in elements) {
                    val link = element.selectFirst("a[href]") ?: continue
                    val absUrl = link.absUrl("href")
                    
                    if (absUrl.isBlank() || absUrl == baseUrl || absUrl == "$baseUrl/") continue
                    
                    val title = link.attr("title").ifBlank { link.text() }.trim()
                    val img = element.selectFirst("img")
                    val thumbnail = img?.absUrl("data-src")?.ifEmpty { img.absUrl("src") } ?: ""

                    if (title.isNotBlank()) {
                        animes.add(
                            SAnime.create().apply {
                                this.title = title
                                this.setUrlWithoutDomain(absUrl)
                                this.thumbnail_url = thumbnail
                            },
                        )
                    }
                }
                if (animes.isNotEmpty()) break
            }
        }

        if (animes.isEmpty()) {
            document.select("a[href*='/gif/'], a[href*='/video/'], a[href*='/g/']").forEach { link ->
                val absUrl = link.absUrl("href")
                val title = link.attr("title").ifBlank { link.text() }.trim()
                val img = link.selectFirst("img")
                val thumbnail = img?.absUrl("data-src")?.ifEmpty { img.absUrl("src") } ?: ""

                if (title.isNotBlank() && absUrl.isNotBlank()) {
                    animes.add(
                        SAnime.create().apply {
                            this.title = title
                            this.setUrlWithoutDomain(absUrl)
                            this.thumbnail_url = thumbnail
                        },
                    )
                }
            }
        }

        return animes.distinctBy { it.url }
    }

    private fun extractDescription(document: Document): String {
        val selectors = listOf(
            "div.video-description",
            "div.description",
            "div.entry-content",
            "div.card-body p",
            "meta[name='description']",
            "meta[property='og:description']",
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

        val videoContainer = document.selectFirst("[data-videos]")
        if (videoContainer != null) {
            val dataVideos = videoContainer.attr("data-videos")
            if (dataVideos.isNotBlank()) {
                try {
                    val jsonArray = JSONArray(dataVideos)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val url = obj.optString("url").replace("\\/", "/")
                        val format = obj.optString("format", "hls_url")
                        if (url.isNotBlank() && url.startsWith("http")) {
                            val videoHeaders = headers.newBuilder()
                                .set("Referer", pageUrl)
                                .set("Accept", "*/*")
                                .build()
                            val quality = if (format.contains("hls")) "HLS" else "Video"
                            videos.add(Video(url, quality, url, videoHeaders))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (videos.isEmpty()) {
            val hlsRegex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            hlsRegex.findAll(document.html()).forEach { match ->
                val url = match.value.replace("&amp;", "&")
                val videoHeaders = headers.newBuilder()
                    .set("Referer", pageUrl)
                    .set("Accept", "*/*")
                    .build()
                videos.add(Video(url, "HLS", url, videoHeaders))
            }
        }

        if (videos.isEmpty()) {
            document.select("video source[src], video[src]").forEach { element ->
                var src = element.attr("src").trim()
                if (src.startsWith("//")) {
                    src = "https:$src"
                } else if (src.startsWith("/")) {
                    src = "$baseUrl$src"
                }
                if (src.isNotBlank() && !src.startsWith("blob:")) {
                    val videoHeaders = headers.newBuilder()
                        .set("Referer", pageUrl)
                        .set("Accept", "*/*")
                        .build()
                    val quality = if (src.contains(".mp4")) "MP4" else "Video"
                    videos.add(Video(src, quality, src, videoHeaders))
                }
            }
        }

        return videos.distinctBy { it.url }
    }
}
