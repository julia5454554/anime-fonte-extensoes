package eu.kanade.tachiyomi.animeextension.pt.javrider

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class JavRider : AnimeHttpSource() {

    override val name = "JavRider"
    override val baseUrl = "https://javrider.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2"
    private val embedParam = "wp:featuredmedia"

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .add("Referer", "$baseUrl/pt/")
            .build()
    }

    // ==================== HELPERS ====================

    private fun safeBody(response: Response): String =
        try {
            response.body?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }

    private fun safeDocument(response: Response): Document =
        Jsoup.parse(safeBody(response), response.request.url.toString())

    private fun extractSlug(url: String): String {
        val cleaned = url.trim().trimEnd('/').substringBefore("?").substringBefore("#")
        return cleaned.substringAfterLast("/")
    }

    private fun buildUrl(slug: String): String = "$baseUrl/pt/$slug/"

    private fun resolveUrl(rawUrl: String): String {
        val slug = extractSlug(rawUrl)
        return if (slug.isNotEmpty() && !slug.contains(":")) buildUrl(slug) else rawUrl
    }

    private fun responseUrlOrDefault(response: Response): String {
        val fromResponse = response.request.url.toString()
        return if (fromResponse.startsWith("http")) fromResponse else baseUrl
    }

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePosts(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&orderby=date&order=desc" +
            "&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePosts(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/posts?search=$q&per_page=24&page=$page&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePosts(response)

    private fun parsePosts(response: Response): AnimesPage {
        val body = safeBody(response)
        if (body.isBlank()) return AnimesPage(emptyList(), false)
        return try {
            val json = JSONArray(body)
            val list = ArrayList<SAnime>(json.length())
            for (i in 0 until json.length()) {
                val post = json.getJSONObject(i)
                val slug = post.optString("slug", "")
                val realUrl = if (slug.isNotEmpty()) buildUrl(slug) else ""
                val thumb = post.optJSONObject("_embedded")
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url")
                    ?.takeIf { it.startsWith("http") }
                val anime = SAnime.create().apply {
                    title = post.getJSONObject("title").getString("rendered")
                        .replace(Regex("<[^>]+>"), "").trim()
                    url = realUrl
                    thumbnail_url = thumb
                }
                if (realUrl.isNotEmpty()) list.add(anime)
            }
            AnimesPage(list, json.length() == 24)
        } catch (_: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== DETALHES ====================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), apiHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        try {
            val document = safeDocument(response)
            anime.title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            anime.thumbnail_url = document.selectFirst("meta[property=og:image]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            anime.genre = document.select("a.category-item").joinToString(", ") { it.text() }
                .takeIf { it.isNotEmpty() }
            val content = document.selectFirst("div.entry-content")
            if (content != null) {
                content.select("div.code-block, script, style").remove()
                anime.description = content.text().trim().take(1500).takeIf { it.isNotEmpty() }
            }
            anime.author = document.selectFirst("li:contains(Estúdio) a")?.text()
            anime.status = SAnime.COMPLETED
        } catch (_: Exception) {
            // retorna anime vazio mas válido
        }
        if (anime.title.isNullOrBlank()) anime.title = "JavRider"
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), apiHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = responseUrlOrDefault(response)
        val ep = SEpisode.create().apply {
            name = "Vídeo Completo"
            this.url = url
            episode_number = 1f
        }
        return listOf(ep)
    }

    // ==================== VÍDEO ====================

    override fun videoListRequest(episode: SEpisode): Request =
        GET(resolveUrl(episode.url), apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val videos = mutableListOf<Video>()
        val referer = responseUrlOrDefault(response)
        try {
            val document = safeDocument(response)
            val iframe = document.selectFirst("div.player-3rdparty iframe, iframe[src*=javplayers]")
                ?: document.selectFirst("iframe[src]")
            val iframeSrc = iframe?.let {
                it.attr("src").ifEmpty { it.attr("data-lazy-src") }
            }.orEmpty()

            if (iframeSrc.startsWith("http")) {
                videos.addAll(extractFromPlayer(iframeSrc, referer))
            }

            document.select("video, video source").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.startsWith("http") && videos.none { it.url == src }) {
                    videos.add(Video(src, "Direto", src, videoHeadersFor(referer)))
                }
            }
        } catch (_: Exception) {
            // retorna o que já tiver
        }
        return videos
    }

    private fun videoHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .build()

    private fun extractFromPlayer(iframeUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val hash = Regex("""/video/([a-f0-9]{16,})""")
                .find(iframeUrl)?.groupValues?.get(1)
                ?: return videos

            val apiUrl = "https://javplayers.com/player/index.php?data=$hash&do=getVideo"

            val req = Request.Builder()
                .url(apiUrl)
                .headers(apiHeadersFor(iframeUrl))
                .build()

            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) return videos

            val json = JSONObject(body)
            val secured = json.optString("securedLink", "")
                .takeIf { it.startsWith("http") }
            val source = json.optString("videoSource", "")
                .takeIf { it.startsWith("http") }

            if (secured != null) {
                videos.add(Video(secured, "Servidor Principal", secured, videoHeadersFor(iframeUrl)))
            }
            if (source != null) {
                videos.add(Video(source, "Servidor Alternativo", source, videoHeadersFor(iframeUrl)))
            }
        } catch (_: Exception) {
            // silencioso
        }
        return videos
    }

    private fun apiHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .add("X-Requested-With", "XMLHttpRequest")
        .build()
}
