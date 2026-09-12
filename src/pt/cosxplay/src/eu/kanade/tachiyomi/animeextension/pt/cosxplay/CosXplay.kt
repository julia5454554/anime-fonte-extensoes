package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt"
    override val supportsLatest = true

    // ==================== Populares ====================

    override fun popularAnimeSelector(): String = "article"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val link = element.selectFirst("h2 a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: ""
        val title = element.selectFirst("h2 a")?.text() ?: element.selectFirst("h2")?.text() ?: ""
        val img = element.selectFirst("img")
        val thumb = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return SAnime.create().apply {
            setUrlWithoutDomain(link)
            this.title = title
            thumbnail_url = thumb
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

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
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        genre = document.select("a[rel=tag]").joinToString { it.text() }
    }

    // ==================== Episódios ====================
    // 1 episódio por página. Extrai a URL atual do og:url e converte para o embed.

    override fun episodeListSelector(): String = "meta[property=og:url]"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val pageUrl = element.attr("content")
        val id = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('-')
        setUrlWithoutDomain("/embed/$id/")
        name = "Vídeo"
        episode_number = 1f
    }

    // ==================== Vídeos ====================
    // O embed tem <video><source src="..." title="high|low" type="video/mp4">

    override fun videoListSelector(): String = "video source"

    override fun videoFromElement(element: Element): Video {
        val src = element.attr("src")
        val quality = element.attr("title").ifBlank { "Vídeo" }
        return Video(src, quality, src)
    }

    override fun videoUrlParse(document: Document): String = document.selectFirst("video source")?.attr("src") ?: ""
}
