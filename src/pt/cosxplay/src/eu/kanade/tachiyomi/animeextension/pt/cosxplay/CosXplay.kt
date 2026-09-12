package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ==================== Populares ====================

    override fun popularAnimeSelector(): String = "div.video-block"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = parseCard(element)

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item:last-child a.page-link"

    // ==================== Recentes ====================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ==================== Busca ====================

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) "$baseUrl/?s=$query" else "$baseUrl/page/$page/?s=$query"
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ==================== Detalhes ====================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
    }

    // ==================== Episódios ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageUrl = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(pageUrl.removePrefix(baseUrl))
                name = "Vídeo"
                episode_number = 1f
            },
        )
    }

    override fun episodeListSelector(): String = "article.post"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create()

    // ==================== Vídeos ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        return document.select("video source").mapNotNull { source ->
            val src = source.attr("src")
            if (src.isEmpty()) return@mapNotNull null
            val quality = source.attr("title").ifBlank { "Vídeo" }
            val workingHeaders = pickBestHeaders(src, pageUrl)
            Video(src, quality, src, workingHeaders)
        }
    }

    override fun videoListSelector(): String = "video source"

    override fun videoFromElement(element: Element): Video = Video("", "", "")

    override fun videoUrlParse(document: Document): String = ""

    // ==================== Helpers ====================

    private fun pickBestHeaders(url: String, refererUrl: String): Headers {
        val variants = listOf(
            headers.newBuilder()
                .set("User-Agent", chromeUa)
                .set("Referer", refererUrl)
                .set("Origin", baseUrl)
                .set("Accept", "*/*")
                .set("Range", "bytes=0-")
                .build(),
            headers.newBuilder()
                .set("User-Agent", chromeUa)
                .set("Referer", "$baseUrl/")
                .set("Origin", baseUrl)
                .set("Range", "bytes=0-")
                .build(),
            headers.newBuilder()
                .set("User-Agent", chromeUa)
                .set("Referer", refererUrl)
                .build(),
            headers.newBuilder()
                .set("User-Agent", chromeUa)
                .set("Referer", "$baseUrl/")
                .build(),
            headers.newBuilder()
                .set("User-Agent", chromeUa)
                .build(),
        )
        for (h in variants) {
            try {
                val probe = Request.Builder()
                    .url(url)
                    .headers(h)
                    .get()
                    .header("Range", "bytes=0-1")
                    .build()
                val resp = client.newCall(probe).execute()
                val code = resp.code
                resp.close()
                if (code in 200..299) return h
            } catch (_: Exception) {
                // tenta a próxima variante
            }
        }
        return variants.first()
    }

    private fun parseCard(element: Element): SAnime {
        val link = element.selectFirst("a.thumb")?.attr("href") ?: ""
        val img = element.selectFirst("img.video-img")
        val thumb = img?.attr("data-src") ?: img?.attr("src") ?: ""
        val slug = link.trimEnd('/').substringAfterLast('/')
        val title = slug.substringAfter('-', slug).replace("-", " ")
        return SAnime.create().apply {
            setUrlWithoutDomain(link)
            this.title = title
            thumbnail_url = thumb
        }
    }
}
