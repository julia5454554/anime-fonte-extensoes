package eu.kanade.tachiyomi.animeextension.pt.megahentai

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MegaHentai :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Mega Hentai"

    override val baseUrl = "https://megahentai.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.result-item article, div.items article"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst("div.data h3 a, div.title a")
        title = titleElement?.text() ?: ""
        setUrlWithoutBaseUrl(titleElement?.attr("abs:href") ?: element.selectFirst("a")?.attr("abs:href") ?: "")
        thumbnail_url = element.selectFirst("div.poster img, img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next, a.arrow_pag"

    // =============================== Episodes ===============================
    override fun episodeListSelector(): String = "ul.episodios li, div.episodios ul li"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("a")
        name = element.selectFirst("div.episodiotitle a, a")?.text() ?: "Episódio"
        setUrlWithoutBaseUrl(link?.attr("abs:href") ?: "")
        episode_number = element.selectFirst("div.numerando")?.text()?.filter { it.isDigit() }?.toFloatOrNull() ?: 1f
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val playerOptions = document.select("ul#playeroptionsul li")

        for (player in playerOptions) {
            val playerListName = player.select("span.title").text().trim()

            val post = player.attr("data-post")
            val nume = player.attr("data-nume")
            val type = player.attr("data-type")

            if (post.isEmpty() || nume.isEmpty() || type.isEmpty()) continue

            val apiUrl = "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume"
            val apiRequest = GET(apiUrl, headers.newBuilder().add("Referer", document.location().toString()).build())

            runCatching {
                client.newCall(apiRequest).execute().use { apiResponse ->
                    if (!apiResponse.isSuccessful) return@runCatching

                    val responseBody = apiResponse.body.string()
                    val embedUrl = responseBody
                        .substringAfter("\"embed_url\":\"", "")
                        .substringBefore("\"", "")
                        .replace("\\/", "/")

                    if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                        extractVideosFromEmbed(embedUrl, playerListName).let { videoList.addAll(it) }
                    }
                }
            }
        }

        if (videoList.isEmpty()) {
            document.select("div.source-box iframe, div.embed-holder iframe").firstOrNull()?.let { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (src.isNotBlank() && src.startsWith("http")) {
                    extractVideosFromEmbed(src, "Iframe Fallback").let { videoList.addAll(it) }
                }
            }
        }

        return videoList
    }

    private fun extractVideosFromEmbed(embedUrl: String, playerName: String): List<Video> {
        return if ("blogger.com" in embedUrl || "blogspot.com" in embedUrl) {
            listOf(Video(embedUrl, playerName, embedUrl))
        } else {
            emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1, div.data h1")?.text() ?: ""
        genre = document.select("div.sgeneros a, div.genre a").joinToString { it.text() }
        description = document.selectFirst("div.wp-content p, div.entry-content p")?.text()
        thumbnail_url = document.selectFirst("div.poster img, img")?.attr("abs:src")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())
}
