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
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject

class HardGif : AnimeHttpSource() {

    override val name = "HardGif"
    override val baseUrl = "https://hardgif.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val lastPageUrls = HashSet<String>()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Cookie", "age_ok=1")  // cookie para pular verificação de idade

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // A paginação não foi confirmada; tentando um padrão comum
        val url = if (page == 1) baseUrl else "$baseUrl?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseCards(document)
        val hasNextPage = animes.isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/?search=$encodedQuery"
        } else {
            "$baseUrl/?search=$encodedQuery&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseCards(document)
        return AnimesPage(animes, animes.isNotEmpty())
    }

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
        return document.select("div.video-card").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/gif/']") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text().trim() }
            val href = link.attr("href")
            val thumbnail = element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")

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
            "div.card-body p",
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

        // 1) Tentar extrair do atributo data-videos (JSON)
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

        // 2) Fallback: procurar por URLs .m3u8 no HTML (se não encontrou acima)
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

        // 3) Fallback: tags <video> ou <source>
        if (videos.isEmpty()) {
            document.select("video source[src], video[src]").forEach { element ->
                var src = element.attr("src").trim()
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = "$baseUrl$src"
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
