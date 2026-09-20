package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub : ParsedAnimeHttpSource() {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    // ============================== POPULAR ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/video?o=mv&page=$page")
    }

    override fun popularAnimeSelector(): String = "ul.videos search-video-thumbs li, ul.videos li.pcVideoListItem, div.ph-thumbnail-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        val rawUrl = element.select("a[href]").firstOrNull()?.attr("href")
            ?: element.select("a").attr("href").takeIf { it.isNotBlank() }
            ?: element.attr("data-href").takeIf { it.isNotBlank() }
            ?: ""

        if (rawUrl.isNotBlank()) {
            anime.setUrlWithoutDomain(rawUrl)
        } else {
            anime.url = ""
        }

        val titleText = element.select("span.title, a.title, .videoTitle").text().ifBlank {
            element.select("img").attr("alt")
        }
        anime.title = titleText.ifBlank { "Sem título" }

        val thumbUrl = element.select("img").attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: element.select("img").attr("data-thumb_url").takeIf { it.isNotBlank() }
            ?: element.select("img").attr("data-mediumproxy").takeIf { it.isNotBlank() }
            ?: ""
        anime.thumbnail_url = thumbUrl

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a, a.relational[rel=next]"

    // =============================== LATEST ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/video?o=mr&page=$page")
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== SEARCH ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/video/search?search=$encodedQuery&page=$page")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== DETAILS / EPISODES ===========================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        
        anime.title = document.select("h1.inlineFree, .video-wrapper h1").text().ifBlank { "Vídeo" }
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

    // Implementação dos membros abstratos obrigatórios de ParsedAnimeHttpSource
    override fun videoListSelector(): String = "html"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Não utilizado; extração feita via videoListParse")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Extrai os scripts da página com segurança
        val scripts = document.select("script")
        var scriptData = ""
        for (script in scripts) {
            val data = script.data()
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
