package eu.kanade.tachiyomi.animeextension.pt.porndude

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class PornDude : AnimeHttpSource() {

    override val name = "3D Porn Dude"
    override val baseUrl = "https://3dporndude.com"
    override val lang = "pt"
    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page&_=${System.currentTimeMillis()}"
        }
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
            "$baseUrl/search/?q=$encodedQuery"
        } else {
            "$baseUrl/search/$encodedQuery/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        val hasNextPage = document.selectFirst("a.next") != null ||
            document.select("ul.pagination a[href*='page']").isNotEmpty() ||
            animes.isNotEmpty()
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

        anime.thumbnail_url = document.selectFirst("div.fp-poster img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        anime.description = extractDescription(document)

        val script = document.select("script").firstOrNull { it.html().contains("flashvars") }
        if (script != null) {
            val categories = extractFlashvar(script.html(), "video_categories")
            if (!categories.isNullOrBlank()) {
                anime.genre = categories.split(",").joinToString(", ") { it.trim() }
            }
        }

        anime.status = SAnime.COMPLETED
        return anime
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val videoId = extractVideoId(response.request.url.toString())
        val episodeUrl = if (videoId != null) {
            "$baseUrl/embed/$videoId"
        } else {
            response.request.url.toString()
        }

        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(episodeUrl)
        episode.name = "Vídeo"
        episode.episode_number = 1f
        episode.date_upload = 0L
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = extractVideosFromDocument(document, response.request.url.toString())
        if (videos.isNotEmpty()) return videos

        val videoId = extractVideoId(response.request.url.toString())
        if (videoId != null) {
            val normalUrl = "$baseUrl/video/$videoId/"
            val normalDoc = try {
                client.newCall(GET(normalUrl, headers)).execute().use { resp ->
                    if (resp.isSuccessful) resp.asJsoup() else null
                }
            } catch (e: Exception) {
                null
            }
            if (normalDoc != null) {
                val normalVideos = extractVideosFromDocument(normalDoc, normalUrl)
                if (normalVideos.isNotEmpty()) return normalVideos
            }
        }

        return emptyList()
    }

    // ============================= Utilities ==============================
    private fun parseVideoCards(document: Document): List<SAnime> {
        return document.select("div.thumb-itm").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/video/']") ?: return@mapNotNull null
            val title = link.attr("title").trim()
            val href = link.attr("href")
            val thumbnail = element.selectFirst("img")?.attr("data-webp")
                ?: element.selectFirst("img")?.attr("src")

            SAnime.create().apply {
                this.title = title
                // Correção principal: extrai e define apenas o caminho relativo
                this.setUrlWithoutDomain(href)
                this.thumbnail_url = thumbnail?.let { if (it.startsWith("http")) it else baseUrl + it }
            }
        }
    }

    private fun extractDescription(document: Document): String {
        val selectors = listOf(
            "div.video-description",
            "div.description",
            "div.wp-content",
            "div.entry-content",
            "div.post-content",
            "div.video-info",
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
        val script = document.select("script").firstOrNull { it.html().contains("flashvars") }
        if (script != null) {
            val scriptContent = script.html()
            val videos = mutableListOf<Video>()

            val qualityMap = mapOf(
                "video_url" to "video_url_text",
                "video_alt_url" to "video_alt_url_text",
                "video_alt_url2" to "video_alt_url2_text",
                "video_alt_url3" to "video_alt_url3_text",
            )

            for ((urlKey, qualityKey) in qualityMap) {
                val rawUrl = extractFlashvar(scriptContent, urlKey) ?: continue
                val quality = extractFlashvar(scriptContent, qualityKey) ?: "HD"
                val videoUrl = rawUrl.replace("&amp;", "&")

                videos.add(
                    Video(
                        videoUrl,
                        quality,
                        videoUrl,
                        headers = headers.newBuilder()
                            .add("Referer", pageUrl)
                            .build(),
                    ),
                )
            }

            if (videos.isNotEmpty()) {
                return videos.sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
            }
        }

        val videoTags = document.select("video, video source")
        for (tag in videoTags) {
            val src = tag.attr("src")
            if (src.isNotBlank()) {
                return listOf(
                    Video(
                        src,
                        "Video",
                        src,
                        headers = headers.newBuilder()
                            .add("Referer", pageUrl)
                            .build(),
                    ),
                )
            }
        }

        return emptyList()
    }

    // Atualizado para reconhecer IDs tanto de /video/ quanto de /embed/
    private fun extractVideoId(url: String): String? = Regex("""/(?:video|embed)/(\d+)""").find(url)?.groupValues?.get(1)

    private fun extractFlashvar(script: String, key: String): String? {
        val regexSingle = Regex("""$key\s*:\s*'([^']*)'""", RegexOption.DOT_MATCHES_ALL)
        regexSingle.find(script)?.let { return it.groupValues[1].trim() }

        val regexDouble = Regex("""$key\s*:\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
        regexDouble.find(script)?.let { return it.groupValues[1].trim() }

        return null
    }
}
