package eu.kanade.tachiyomi.animeextension.pt.javrider

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
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
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

    // ==================== HELPERS DE URL ====================

    private fun extractSlug(url: String): String {
        val cleaned = url.trim().trimEnd('/').substringBefore("?").substringBefore("#")
        return cleaned.substringAfterLast("/")
    }

    private fun buildUrl(slug: String): String = "$baseUrl/pt/$slug/"

    private fun resolveUrl(rawUrl: String): String {
        val slug = extractSlug(rawUrl)
        return if (slug.isNotEmpty()) buildUrl(slug) else rawUrl
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
        val body = response.body!!.string()
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
        val document = response.asJsoup()
        val anime = SAnime.create()
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
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), apiHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val ep = SEpisode.create().apply {
            name = "Vídeo Completo"
            url = resolveUrl(response.request.url.toString())
            episode_number = 1f
        }
        return listOf(ep)
    }

    // ==================== VÍDEO ====================

    override fun videoListRequest(episode: SEpisode): Request = GET(resolveUrl(episode.url), apiHeaders)

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
                videos.add(Video(src, "Direto", src, videoHeadersFor(referer)))
            }
        }

        return videos
    }

    private fun videoHeadersFor(referer: String): Headers = headers.newBuilder()
        .add("User-Agent", desktopUa)
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .build()

    private fun extractFromPlayer(iframeUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val reqHeaders = Headers.Builder()
                .add("User-Agent", desktopUa)
                .add(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,*/*;q=0.8",
                )
                .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .add("Referer", referer)
                .add("Sec-Fetch-Dest", "iframe")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "cross-site")
                .add("Upgrade-Insecure-Requests", "1")
                .build()

            val req = Request.Builder().url(iframeUrl).headers(reqHeaders).build()
            val rawHtml = client.newCall(req).execute().use { it.body!!.string() }

            val html = rawHtml
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            Regex("""https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+""")
                .findAll(html).map { it.value }.distinct()
                .forEachIndexed { i, url ->
                    videos.add(Video(url, "Servidor ${i + 1}", url, videoHeadersFor(iframeUrl)))
                }

            if (videos.isEmpty()) {
                Regex("""https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*""")
                    .findAll(html).map { it.value }.distinct()
                    .forEachIndexed { i, url ->
                        videos.add(
                            Video(url, "Servidor ${i + 1}", url, videoHeadersFor(iframeUrl)),
                        )
                    }
            }

            if (videos.isEmpty()) {
                Regex("""[A-Za-z0-9+/]{60,}={0,2}""")
                    .findAll(html).take(30).forEach { match ->
                        try {
                            val decoded = match.value.decodeBase64()?.utf8() ?: return@forEach
                            Regex("""https?://[^"'\s\\<>]+""")
                                .find(decoded)?.value?.let { url ->
                                    if (videos.none { it.url == url }) {
                                        videos.add(
                                            Video(
                                                url,
                                                "Servidor B64",
                                                url,
                                                videoHeadersFor(iframeUrl),
                                            ),
                                        )
                                    }
                                }
                        } catch (_: Exception) {
                            // ignora base64 inválido
                        }
                    }
            }
        } catch (_: Exception) {
            // silencioso
        }
        return videos
    }
}
