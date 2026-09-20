package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder().build()

    // Headers necessários para simular um navegador e evitar ser bloqueado ou redirecionado
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Cookie", "age_verified=1; platform=pc")
    }

    // ============================== POPULAR ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/video?o=mv&page=$page", headers)
    }

    // Seletores abrangentes para cobrir diferentes estruturas de HTML do site
    override fun popularAnimeSelector(): String = "ul.videos li, ul.search-video-thumbs li, div.ph-thumbnail-card, li.pcVideoListItem"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        // Tenta capturar a URL por diferentes seletores e atributos
        val rawUrl = element.select("a[href*=/view_video.php]").firstOrNull()?.attr("href")
            ?: element.select("a").firstOrNull()?.attr("href")
            ?: element.attr("data-href")
            ?: ""

        if (rawUrl.isNotBlank()) {
            anime.setUrlWithoutDomain(rawUrl)
        } else {
            anime.url = ""
        }

        // Título
        val titleText = element.select("span.title, a.title, .videoTitle, img").attr("alt").ifBlank {
            element.select("span.title, a.title, .videoTitle").text()
        }
        anime.title = titleText.ifBlank { "Sem título" }

        // Thumbnail / Capa
        val thumbUrl = element.select("img").attr("data-thumb_url").takeIf { it.isNotBlank() }
            ?: element.select("img").attr("data-mediumproxy").takeIf { it.isNotBlank() }
            ?: element.select("img").attr("data-src").takeIf { it.isNotBlank() }
            ?: element.select("img").attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: ""
        anime.thumbnail_url = thumbUrl

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a, a.relational[rel=next], a[class*=next]"

    // =============================== LATEST ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/video?o=mr&page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== SEARCH ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/video/search?search=$encodedQuery&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== DETAILS / EPISODES ===========================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        
        anime.title = document.select("h1.inlineFree, .video-wrapper h1, h1").text().ifBlank { "Vídeo" }
        anime.author = document.select(".userInfo .usernameWrap a, .video-uploader-name").text()
        anime.description = document.select(".video-description, .descriptionContainer").text()
        anime.genre = document.select(".categoriesWrapper a, .tagsWrapper a").joinToString { it.text() }
        
        val thumb = document.select("meta[property=og:image]").attr("content")
        if (thumb.isNotBlank()) {
            anime.thumbnail_url = thumb
        }

        return anime
    }

    override fun episodeListSelector(): String = "html"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.name = "Assistir Vídeo"
        episode.episode_number = 1f
        episode.setUrlWithoutDomain(element.ownerDocument()?.location() ?: "")
        return episode
    }

    // =============================== VIDEOS ===============================

    override fun videoListSelector(): String = "html"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Não utilizado")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        val videoList = mutableListOf<Video>()

        val scripts = document.select("script").toList()
        var scriptData = ""
        
        for (i in scripts.indices) {
            val data = scripts[i].data()
            if (data.contains("flashvars")) {
                scriptData = data
                break
            }
        }

        val hlsRegex = """"videoUrl"\s*:\s*"([^"]+)"""".toRegex()
        val matches = hlsRegex.findAll(scriptData)

        var count = 1
        for (match in matches) {
            val url = match.groupValues[1].replace("\\/", "/")
            if (url.isNotBlank() && url.contains(".m3u8")) {
                videoList.add(Video(url, "Qualidade $count", url))
                count++
            }
        }

        return videoList
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Não utilizado")
    }
}
