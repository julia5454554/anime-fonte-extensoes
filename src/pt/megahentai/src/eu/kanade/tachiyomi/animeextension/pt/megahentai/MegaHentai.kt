package eu.kanade.tachiyomi.animeextension.pt.megahentai

import eu.kanade.tachiyomi.animeextension.pt.megahentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MegaHentai : AnimeHttpSource() {

    override val name = "Mega Hentai"
    override val baseUrl = "https://megahentai.biz"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        add("Referer", baseUrl)
    }

    // ==================== LISTAGEM ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/hentai/" else "$baseUrl/hentai/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item.tvshows")
        val animes = items.map { element -> parseAnimeFromCard(element) }
        val hasNextPage = document.select("link[rel=next]").first() != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeFromCard(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select(".data h3 a").text().trim()
        val url = element.select(".poster a").attr("href")
        anime.setUrlWithoutDomain(url)
        val img = element.select(".poster img").first()
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return anime
    }

    // ==================== BUSCA ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=${query.replace(" ", "+")}"
        } else {
            "$baseUrl/?s=${query.replace(" ", "+")}&paged=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item.tvshows")
        val animes = items.map { element -> parseAnimeFromCard(element) }
        val hasNextPage = document.select("link[rel=next]").first() != null
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== DETALHES ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.title = document.select("h1.entry-title, h1").text().trim()
        anime.thumbnail_url = document.select("meta[property=og:image]").attr("content")
        anime.description = document.select(".sinopse .texto, .entry-content p").text().trim()
        anime.genre = document.select(".gen_flex a, .mta a").joinToString { it.text() }
        return anime
    }

    // ==================== EPISÓDIOS ====================
    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeElements = document.select("ul.episodios li")
        return episodeElements.mapIndexed { index, element ->
            val link = element.select(".episodiotitle a").first()
            val url = link?.attr("href") ?: return@mapIndexed null
            val title = link.text().trim()
            val episodeNumber = element.select(".numerando").text().trim()
                .substringBefore("-").trim().toFloatOrNull() ?: (index + 1).toFloat()
            SEpisode.create().apply {
                name = title
                episode_number = episodeNumber
                setUrlWithoutDomain(url)
            }
        }.filterNotNull()
    }

    // ==================== EXTRAÇÃO DE VÍDEO ====================
    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // Identifica o iframe ativo do player
        val iframe = document.select(".source-box.on iframe").first()
            ?: document.select("iframe.metaframe").first()
            ?: document.select("iframe").first()

        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                val iframeHeaders = headersBuilder()
                    .add("Referer", response.request.url.toString())
                    .build()
                // Usa UniversalExtractor para extrair os vídeos
                val videos = UniversalExtractor(client).videosFromUrl(
                    origRequestUrl = iframeSrc,
                    origRequestHeader = iframeHeaders,
                    name = "MegaHentai",
                )
                if (videos.isNotEmpty()) {
                    return videos
                }
            }
        }
        // Fallback: se não encontrou iframe ou o extrator falhou, tenta extração direta
        val videoUrl = extractDirectVideoUrl(document)
        if (videoUrl != null) {
            val videoHeaders = headersBuilder()
                .add("Referer", response.request.url.toString())
                .add("Accept", "*/*")
                .build()
            return listOf(Video(videoUrl, "HD", videoUrl, headers = videoHeaders))
        }
        return emptyList()
    }

    private fun extractDirectVideoUrl(document: Document): String? {
        // Tenta tags <video> ou <source> (fallback)
        document.select("video source").first()?.attr("src")?.let { return it }
        document.select("video").first()?.attr("src")?.let { return it }
        // Regex para .mp4/.m3u8
        val html = document.html()
        Regex("(https?://[^\"']+\\.(mp4|m3u8)[^\"']*)").find(html)?.value?.let { return it }
        return null
    }
}
