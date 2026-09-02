package eu.kanade.tachiyomi.animeextension.pt.hentai3d

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class Hentai3D : AnimeHttpSource() {

    override val name = "Hentai3D"
    override val baseUrl = "https://hentais3d.net"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headersBuilder().build())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.item.min-w-0")
        val animeList = elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a.image") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifBlank {
                element.selectFirst("a.title")?.text()?.trim() ?: ""
            }
            val url = linkElement.attr("href")
            val thumbnail = element.selectFirst("img.thumb")?.attr("src")
            SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = thumbnail
            }
        }
        val hasNextPage = elements.isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "%20")
        val url = if (page == 1) {
            "$baseUrl/search?q=$encodedQuery"
        } else {
            "$baseUrl/search?q=$encodedQuery&page=$page"
        }
        return GET(url, headersBuilder().build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, h2.title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img.thumb")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")
        anime.description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("div.description, div.entry-content, div.video-description, p")?.text()
        val genres = mutableListOf<String>()
        document.select("a[href*='/category/']").forEach { genres.add(it.text().trim()) }
        document.select("a.taxonomy-tag, a[href*='/tags/']").forEach { genres.add(it.text().trim()) }
        document.select("a[rel='tag']").forEach { genres.add(it.text().trim()) }
        anime.genre = genres.distinct().joinToString(", ")
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(response.request.url.toString())
        episode.name = "Vídeo"
        episode.episode_number = 1f
        return listOf(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        document.select("video.art-video").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                videos.add(Video(src, "HD", src, headers = videoHeaders(pageUrl)))
            }
        }

        document.select("video").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank() && !src.contains("preview.mp4") && (src.contains(".mp4") || src.contains(".m3u8"))) {
                val quality = element.attr("label").ifBlank { "Default" }
                if (videos.none { it.videoUrl == src }) {
                    videos.add(Video(src, quality, src, headers = videoHeaders(pageUrl)))
                }
            }
        }

        document.select("video source").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank() && !src.contains("preview.mp4") && (src.contains(".mp4") || src.contains(".m3u8"))) {
                val quality = element.attr("label").ifBlank { "Default" }
                if (videos.none { it.videoUrl == src }) {
                    videos.add(Video(src, quality, src, headers = videoHeaders(pageUrl)))
                }
            }
        }

        val html = document.html()
        val genericRegex = Regex("""(https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)""")
        genericRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (!url.contains("preview.mp4") && videos.none { it.videoUrl == url }) {
                videos.add(Video(url, "Direto", url, headers = videoHeaders(pageUrl)))
            }
        }

        val flashVarsRegex = Regex("""(?:video_url|video_alt_url\d*)\s*:\s*["']([^"']+)["']""")
        val qualityRegex = Regex("""(?:video_quality|quality)\s*:\s*["']([^"']+)["']""")
        val matches = flashVarsRegex.findAll(html).toList()
        val qualities = qualityRegex.findAll(html).map { it.groupValues[1] }.toList()
        matches.forEachIndexed { index, match ->
            val url = match.groupValues[1].replace("\\/", "/")
            val quality = qualities.getOrNull(index) ?: "HD"
            if (videos.none { it.videoUrl == url }) {
                videos.add(Video(url, "$quality (Flashvars)", url, headers = videoHeaders(pageUrl)))
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    private fun videoHeaders(pageUrl: String): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", pageUrl)
        .add("Origin", baseUrl)
        .add("Accept", "*/*")
        .build()
}
