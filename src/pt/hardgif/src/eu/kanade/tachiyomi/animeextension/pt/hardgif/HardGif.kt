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
        val elements = document.select("div.card.video-card")
        val animeList = elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a[href*='/gif/']") ?: return@mapNotNull null
            val url = linkElement.attr("href")
            if (url.isBlank()) return@mapNotNull null
            val title = linkElement.text().trim().ifBlank {
                element.selectFirst("h6.card-title.video_name")?.text()?.trim() ?: ""
            }
            if (title.isBlank()) return@mapNotNull null

            val thumbnail = element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("video[poster]")?.attr("poster")

            SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = thumbnail?.let { fixUrl(it) }
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("a.next, a.nextpostslink, .pagination a.next") != null
        return AnimesPage(animeList, hasNextPage)
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
            "$baseUrl/page/$page/?search=$encodedQuery"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
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
            name = "Vídeo / GIF"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // 1. Tags <video> e <source>
        document.select("video, video source, source").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank()) {
                val src = fixUrl(rawSrc)
                val quality = element.attr("label").ifBlank { "HD" }
                videos.add(Video(src, quality, src, videoHeaders(pageUrl)))
            }
        }

        // 2. Links diretos no HTML (.mp4, .webm, .m3u8, .gifv)
        val mediaRegex = Regex("""["']([^"']+\.(?:mp4|webm|m3u8|gifv|gif)[^"']*)["']""")
        mediaRegex.findAll(document.html()).forEach { match ->
            var url = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&").trim()
            if (!url.endsWith(".png") && !url.endsWith(".jpg")) {
                url = fixUrl(url)
                if (url.startsWith("http") && videos.none { it.videoUrl == url }) {
                    // Se for m3u8, gerar variações de qualidade
                    if (url.contains(".m3u8")) {
                        val base = url.substringBeforeLast(".m3u8")
                        val resolutions = listOf("360", "480", "720", "1080")
                        resolutions.forEach { res ->
                            val newUrl = base.replace(Regex("""_(\d+)$"""), "_$res") + ".m3u8"
                            videos.add(Video(newUrl, "${res}p", newUrl, videoHeaders(pageUrl)))
                        }
                        videos.add(Video(url, "Original", url, videoHeaders(pageUrl)))
                    } else {
                        videos.add(Video(url, "Direto", url, videoHeaders(pageUrl)))
                    }
                }
            }
        }

        // 3. Embeds/Iframes
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.isNotBlank() && iframeUrl.startsWith("http")) {
                try {
                    client.newCall(GET(iframeUrl, headers)).execute().use { embedResp ->
                        if (embedResp.isSuccessful) {
                            val embedDoc = embedResp.asJsoup()
                            val embedMediaRegex = Regex("""["']([^"']+\.(?:mp4|m3u8|webm)[^"']*)["']""")
                            embedMediaRegex.findAll(embedDoc.html()).forEach { match ->
                                var url = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&").trim()
                                url = fixUrl(url)
                                if (url.startsWith("http")) {
                                    if (url.contains(".m3u8")) {
                                        val base = url.substringBeforeLast(".m3u8")
                                        val resolutions = listOf("360", "480", "720", "1080")
                                        resolutions.forEach { res ->
                                            val newUrl = base.replace(Regex("""_(\d+)$"""), "_$res") + ".m3u8"
                                            videos.add(Video(newUrl, "${res}p (Embed)", newUrl, videoHeaders(iframeUrl)))
                                        }
                                    } else {
                                        videos.add(Video(url, "Embed HD", url, videoHeaders(iframeUrl)))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
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
