package eu.kanade.tachiyomi.animeextension.pt.javrider

import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import java.net.URLEncoder

class JavRider : AnimeHttpSource() {

    override val name = "JavRider"
    override val baseUrl = "https://javrider.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2"
    private val fields = "id,title,link,_embedded"
    private val embedParam = "wp:featuredmedia"

    // headers customizados derivados do headers base (que é final na base)
    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .add("Referer", "$baseUrl/pt/")
            .build()
    }

    private val playerHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .add("Referer", "https://javplayers.com/")
            .build()
    }

    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Referer", "https://javplayers.com/")
            .add("Origin", "https://javplayers.com")
            .add("Accept", "*/*")
            .add("Accept-Encoding", "identity")
            .build()
    }

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&_embed=$embedParam&_fields=$fields"
        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePosts(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&orderby=date&order=desc" +
            "&_embed=$embedParam&_fields=$fields"
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePosts(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/posts?search=$q&per_page=24&page=$page" +
            "&_embed=$embedParam&_fields=$fields"
        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePosts(response)

    private fun parsePosts(response: Response): AnimesPage {
        val body = response.body!!.string()
        return try {
            val json = JSONArray(body)
            val list = ArrayList<SAnime>(json.length())
            for (i in 0 until json.length()) {
                val post = json.getJSONObject(i)
                val anime = SAnime.create().apply {
                    title = post.getJSONObject("title").getString("rendered")
                        .replace(Regex("<[^>]+>"), "").trim()
                    url = post.getString("link")
                    thumbnail_url = post.optJSONObject("_embedded")
                        ?.optJSONArray("wp:featuredmedia")
                        ?.optJSONObject(0)
                        ?.optString("source_url")
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                }
                list.add(anime)
            }
            AnimesPage(list, json.length() == 24)
        } catch (_: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== DETALHES ====================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        anime.thumbnail_url = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf { it.isNotEmpty() }
        anime.genre = document.select("a.category-item").joinToString(", ") { it.text() }
            .takeIf { it.isNotEmpty() }

        val content = document.selectFirst("div.entry-content")
        if (content != null) {
            content.select("div.code-block, script, style").remove()
            anime.description = content.text().trim().take(1500).takeIf { it.isNotEmpty() }
        }

        anime.author = document.selectFirst("li:contains(Estúdio) a")?.text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val ep = SEpisode.create().apply {
            name = "Vídeo Completo"
            url = response.request.url.toString()
            episode_number = 1f
        }
        return listOf(ep)
    }

    // ==================== VÍDEO ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val videos = mutableListOf<Video>()

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
                videos.add(Video(src, "Direto", src, videoHeaders))
            }
        }

        return videos
    }

    private fun extractFromPlayer(iframeUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val reqHeaders = playerHeaders.newBuilder()
                .add("Referer", referer)
                .build()
            val req = Request.Builder().url(iframeUrl).headers(reqHeaders).build()
            val html = client.newCall(req).execute().use { it.body!!.string() }

            val m3Regex = Regex("""https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+""")
            m3Regex.findAll(html).map { it.value }.distinct().forEachIndexed { i, url ->
                videos.add(Video(url, "Servidor ${i + 1}", url, videoHeaders))
            }

            if (videos.isEmpty()) {
                val generic = Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
                generic.findAll(html).map { it.value }.distinct().forEachIndexed { i, url ->
                    videos.add(Video(url, "Servidor ${i + 1}", url, videoHeaders))
                }
            }
        } catch (_: Exception) {
            // silencioso
        }
        return videos
    }
}
