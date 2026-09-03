package eu.kanade.tachiyomi.animeextension.pt.genericanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenericAnimeSource : ParsedAnimeHttpSource() {

    override val name = "Nome do Site"
    override val baseUrl = "https://exemplo.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular/page/$page/")

    override fun popularAnimeSelector(): String = "article.item, div.anime-card, div.poster"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a")
            title = element.selectFirst(".title, h2, h3")?.text().orEmpty()
            setUrlWithoutDomain(link?.attr("href") ?: "")
            thumbnail_url = extractImageUrl(element)
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.next, .pagination .next"

    // ============================== Recentes ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/lancamentos/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Busca ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query&page=$page")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Detalhes ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title, h1.title")?.text().orEmpty()
            description = document.selectFirst(".sinopse, .description, .entry-content p")?.text()
            genre = document.select(".genres a, .generos a").joinToString { it.text() }
            
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractImageUrl(document.body())
        }
    }

    // ============================== Episódios ==============================
    override fun episodeListSelector(): String = "ul.episodios > li, div.episode-item"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst("a")
            name = element.selectFirst(".title, .epl-title")?.text() ?: "Episódio"
            setUrlWithoutDomain(link?.attr("href") ?: "")
            
            val epNum = name.replace(Regex("[^0-9.]"), "")
            episode_number = epNum.toFloatOrNull() ?: 1f
        }
    }

    // ============================== Players ==============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val url = fixUrl(src)
                videos.add(Video(url, "Player Padrão", url))
            }
        }
        return videos
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Não utilizado")

    override fun videoUrlParse(document: Document): String = throw Exception("Não utilizado")

    override fun videoListSelector(): String = throw Exception("Não utilizado")

    // ============================== Helpers & Lazy Load Fix ==============================
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }
    }

    private fun cleanAndFixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cleaned = url.replace("&amp;", "&").trim()
        
        if (cleaned.startsWith("data:image") || cleaned.length < 5) return null
        return fixUrl(cleaned)
    }

    private fun extractImageUrl(element: Element): String? {
        // 1. Tags dentro de <noscript>
        element.select("noscript").forEach { noscript ->
            val doc = Jsoup.parseBodyFragment(noscript.html())
            doc.select("img").forEach { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                cleanAndFixUrl(src)?.let { return it }
            }
        }

        // 2. Imagens com atributos de lazy-loading
        element.select("img").forEach { img ->
            val attrs = listOf(
                "data-lazy-src", "data-src", "data-original", 
                "data-cfsrc", "data-old-src", "data-lazy", 
                "data-url", "data-img", "src"
            )
            for (attr in attrs) {
                val value = img.attr(attr)
                cleanAndFixUrl(value)?.let { return it }
            }

            val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                val candidate = srcset.split(",")
                    .map { it.trim().substringBefore(" ") }
                    .firstOrNull { cleanAndFixUrl(it) != null }
                cleanAndFixUrl(candidate)?.let { return it }
            }
        }

        // 3. Estilos CSS com background-image
        element.select("[style*='background']").forEach { el ->
            val style = el.attr("style")
            val match = Regex("""url\(['"]?(.*?)['"]?\)"", RegexOption.IGNORE_CASE).find(style)
            match?.groupValues?.get(1)?.let { url ->
                cleanAndFixUrl(url)?.let { return it }
            }
        }

        // 4. Fallback por expressão regular no HTML bruto
        val htmlRegex = Regex("""https?://[^"'\s<>]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
        htmlRegex.find(element.html())?.value?.let { rawUrl ->
            cleanAndFixUrl(rawUrl)?.let { return it }
        }

        return null
    }
}
