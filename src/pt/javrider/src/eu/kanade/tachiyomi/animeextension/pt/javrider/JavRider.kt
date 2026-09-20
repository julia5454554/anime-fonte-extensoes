package eu.kanade.tachiyomi.animeextension.pt.javrider

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val ptCategoryId = "135"

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 13; 23049PCD8G) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

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

    private fun videoHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .build()

    private fun extractFromPlayer(iframeUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        val hash = Regex("""/video/([a-f0-9]{16,})""")
            .find(iframeUrl)?.groupValues?.get(1)
            ?: return videos

        try {
            val pageReq = Request.Builder()
                .url(iframeUrl)
                .headers(fullBrowserHeaders(referer, desktopUa))
                .build()
            client.newCall(pageReq).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            // segue
        }

        val apiBase = "https://javplayers.com/player/index.php"
        val postBody = "data=$hash&do=getVideo"
            .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())

        val attempts = listOf(
            runAttempt("$apiBase?data=$hash&do=getVideo", "GET-full-desktop", iframeUrl, desktopUa),
            runPostAttempt(apiBase, "POST-desktop", iframeUrl, desktopUa, postBody),
            runAttempt("$apiBase?data=$hash&do=getVideo", "GET-mobile", iframeUrl, mobileUa),
            runPostAttempt(apiBase, "POST-mobile", iframeUrl, mobileUa, postBody),
        )

        var bestBody = ""
        var bestLabel = ""
        for ((label, body) in attempts) {
            if (body.contains("securedLink", true) || body.contains("videoSource", true)) {
                bestBody = body
                bestLabel = label
                break
            }
            if (body.isNotBlank() && !body.trimStart().startsWith("<") && body.length > bestBody.length) {
                bestBody = body
                bestLabel = label
            }
        }
        Log.e("JavRider", "PLAYER best=$bestLabel len=${bestBody.length}")

        if (bestBody.isBlank()) return videos

        val normalized = bestBody.replace("\\/", "/").replace("\\u002F", "/")
        var secured: String? = null
        var source: String? = null

        try {
            val json = JSONObject(normalized)
            secured = json.optString("securedLink", "").takeIf { it.startsWith("http") }
            source = json.optString("videoSource", "").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            // não é JSON puro
        }
        if (secured == null) {
            secured = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(normalized)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        }
        if (source == null) {
            source = Regex(""""videoSource"\s*:\s*"([^"]+)"""")
                .find(normalized)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        }
        if (secured == null) {
            secured = Regex("""(https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+)""")
                .find(normalized)?.groupValues?.get(1)
        }
        if (secured == null) {
            secured = Regex("""["']([^"']*master\.m3u8[^"']*)["']""")
                .find(normalized)?.groupValues?.get(1)?.let {
                    if (it.startsWith("http")) it else "https://javplayers.com$it"
                }
        }

        Log.e("JavRider", "PLAYER secured=$secured source=$source")

        val usedUrl = secured ?: source
        if (usedUrl == null) {
            val htmlFull = tryFullIframeHtml(iframeUrl, referer)
            val m3 = Regex("""(https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+)""")
                .find(htmlFull)?.groupValues?.get(1)
            Log.e("JavRider", "PLAYER fallback m3 do HTML=$m3")
            if (m3 == null) return videos
            videos.add(Video(m3, "Auto", m3, videoHeadersFor(iframeUrl)))
            return videos
        }

        val subtitles = extractSubtitles(normalized)
        val masterBody = tryApiGet(usedUrl, "https://javplayers.com/")
        Log.e("JavRider", "PLAYER master[${masterBody.length}] first400=${masterBody.take(400)}")

        val variants = parseM3u8Master(masterBody, usedUrl)
        Log.e("JavRider", "PLAYER variants=${variants.size}")

        if (variants.isNotEmpty()) {
            variants.forEach { (label, url) ->
                videos.add(Video(url, label, url, videoHeadersFor(iframeUrl), subtitleTracks = subtitles))
            }
        } else {
            videos.add(Video(usedUrl, "Auto", usedUrl, videoHeadersFor(iframeUrl), subtitleTracks = subtitles))
        }
        return videos
    }

    private fun tryFullIframeHtml(iframeUrl: String, referer: String): String = try {
        val req = Request.Builder()
            .url(iframeUrl)
            .headers(fullBrowserHeaders(referer, desktopUa))
            .build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    } catch (_: Exception) {
        ""
    }

    private fun runAttempt(url: String, label: String, referer: String, ua: String): Pair<String, String> = try {
        val req = Request.Builder()
            .url(url)
            .headers(fullBrowserHeaders(referer, ua))
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string().orEmpty()
        Log.e("JavRider", "ATTEMPT[$label] code=${resp.code} len=${body.length} first80=${body.take(80).replace("\n", " ")}")
        label to body
    } catch (e: Exception) {
        Log.e("JavRider", "ATTEMPT[$label] erro=${e.message}")
        label to ""
    }

    private fun runPostAttempt(url: String, label: String, referer: String, ua: String, body: RequestBody): Pair<String, String> = try {
        val req = Request.Builder()
            .url(url)
            .headers(fullBrowserHeaders(referer, ua))
            .post(body)
            .build()
        val resp = client.newCall(req).execute()
        val respBody = resp.body?.string().orEmpty()
        Log.e("JavRider", "ATTEMPT[$label] code=${resp.code} len=${respBody.length} first80=${respBody.take(80).replace("\n", " ")}")
        label to respBody
    } catch (e: Exception) {
        Log.e("JavRider", "ATTEMPT[$label] erro=${e.message}")
        label to ""
    }

    private fun extractSubtitles(body: String): List<Track> {
        val tracks = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        Regex("""https?://[^"'\s]+\.srt""")
            .findAll(body).forEach { m ->
                if (seen.add(m.value)) tracks.add(Track(m.value, "Português"))
            }
        return tracks
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
        val req = Request.Builder()
            .url(url)
            .headers(fullBrowserHeaders(referer, desktopUa))
            .build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    } catch (e: Exception) {
        Log.e("JavRider", "GET erro: ${e.message}")
        ""
    }

    private fun fullBrowserHeaders(referer: String, ua: String): Headers = Headers.Builder()
        .add("User-Agent", ua)
        .add("Accept", "*/*")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .add("sec-ch-ua", "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", if (ua.contains("Mobile")) "?1" else "?0")
        .add("sec-ch-ua-platform", if (ua.contains("Windows")) "\"Windows\"" else "\"Android\"")
        .build()
}
