package eu.kanade.tachiyomi.animeextension.pt.javrider

import android.util.Log
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

    // Categoria "subtitle-pt" no WordPress (visto no HTML: data-cat-id="cat_135")
    private val ptCategoryId = "135"

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

    private fun safeBody(response: Response): String = try {
        response.body?.string().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun safeDocument(response: Response): Document = Jsoup.parse(safeBody(response), response.request.url.toString())

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
        val url = "$apiUrl/posts?categories=$ptCategoryId&per_page=24&page=$page&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePosts(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/posts?categories=$ptCategoryId&per_page=24&page=$page" +
            "&orderby=date&order=desc&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePosts(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/posts?search=$q&categories=$ptCategoryId&per_page=24&page=$page" +
            "&_embed=$embedParam"
        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePosts(response)

    private fun parsePosts(response: Response): AnimesPage {
        val body = safeBody(response)
        if (body.isBlank()) return AnimesPage(emptyList(), false)
        Log.e("JavRider", "LIST url=${response.request.url} len=${body.length}")
        return try {
            val json = JSONArray(body)
            val list = ArrayList<SAnime>(json.length())
            for (i in 0 until json.length()) {
                val post = json.getJSONObject(i)
                val title = post.getJSONObject("title").getString("rendered")
                    .replace(Regex("<[^>]+>"), "").trim()
                val slug = post.optString("slug", "")
                val realUrl = if (slug.isNotEmpty()) buildUrl(slug) else ""
                val thumb = post.optJSONObject("_embedded")
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url")
                    ?.takeIf { it.startsWith("http") }

                val anime = SAnime.create().apply {
                    this.title = title
                    url = realUrl
                    thumbnail_url = thumb
                }
                if (realUrl.isNotEmpty()) list.add(anime)
            }
            Log.e("JavRider", "LIST parsed=${list.size} hasNext=${json.length() == 24}")
            AnimesPage(list, json.length() == 24)
        } catch (e: Exception) {
            Log.e("JavRider", "LIST erro: ${e.message}")
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

    override fun videoListRequest(episode: SEpisode): Request = GET(resolveUrl(episode.url), apiHeaders)

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
        } catch (e: Exception) {
            Log.e("JavRider", "VIDEO erro: ${e.message}", e)
        }
        Log.e("JavRider", "VIDEO total=${videos.size}")
        return videos
    }

    private fun videoHeadersFor(): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Referer", "https://javplayers.com/")
        .add("Origin", "https://javplayers.com")
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .build()

    private fun extractFromPlayer(iframeUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        val hash = Regex("""/video/([a-f0-9]{16,})""")
            .find(iframeUrl)?.groupValues?.get(1)
            ?: return videos

        // Passo 1: visitar iframe (cookies)
        try {
            val pageReq = Request.Builder()
                .url(iframeUrl)
                .headers(playerPageHeaders(referer))
                .build()
            client.newCall(pageReq).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            // segue
        }

        // Passo 2: API getVideo
        val apiUrl = "https://javplayers.com/player/index.php?data=$hash&do=getVideo"
        val body = tryApiGet(apiUrl, iframeUrl)
        if (body.isBlank()) return videos

        // Passo 3: extrai securedLink
        val normalized = body.replace("\\/", "/").replace("\\u002F", "/")
        var secured: String? = null
        try {
            val json = JSONObject(normalized)
            secured = json.optString("securedLink", "").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            // não é JSON puro
        }
        if (secured == null) {
            secured = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(normalized)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
        }
        if (secured == null) {
            secured = Regex("""(https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+)""")
                .find(normalized)?.groupValues?.get(1)
        }
        if (secured == null) return videos

        Log.e("JavRider", "secured=$secured")

        // Passo 4: parseia o m3u8 master pra listar resoluções
        val masterBody = tryApiGet(secured, "https://javplayers.com/")
        Log.e("JavRider", "master[${masterBody.length}]=${masterBody.take(1200)}")

        val variants = parseM3u8Master(masterBody, secured)
        if (variants.isNotEmpty()) {
            variants.forEach { (label, url) ->
                videos.add(Video(url, label, url, videoHeadersFor()))
            }
        } else {
            // fallback: master direto (player escolhe)
            videos.add(Video(secured, "Auto", secured, videoHeadersFor()))
        }
        return videos
    }

    private fun parseM3u8Master(body: String, masterUrl: String): List<Pair<String, String>> {
        val result = mutableListOf<Triple<Int, String, String>>()
        val lines = body.lines()
        var infoLine: String? = null
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                infoLine = line
            } else if (infoLine != null && line.isNotEmpty() && !line.startsWith("#")) {
                val height = Regex("""RESOLUTION=\d+x(\d+)""")
                    .find(infoLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val label = when {
                    height >= 1080 -> "1080p"
                    height >= 720 -> "720p"
                    height >= 480 -> "480p"
                    height >= 360 -> "360p"
                    height > 0 -> "${height}p"
                    else -> "Auto"
                }
                val url = if (line.startsWith("http")) {
                    line
                } else if (line.startsWith("/")) {
                    val host = masterUrl.substringAfter("://").substringBefore("/")
                    "https://$host$line"
                } else {
                    "${masterUrl.substringBeforeLast("/")}/$line"
                }
                result.add(Triple(height, label, url))
                infoLine = null
            }
        }
        return result.sortedByDescending { it.first }.map { it.second to it.third }
    }

    private fun tryApiGet(url: String, referer: String): String = try {
        val req = Request.Builder().url(url).headers(apiHeadersFor(referer)).build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    } catch (e: Exception) {
        Log.e("JavRider", "GET erro: ${e.message}")
        ""
    }

    private fun playerPageHeaders(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .build()

    private fun apiHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()
}
