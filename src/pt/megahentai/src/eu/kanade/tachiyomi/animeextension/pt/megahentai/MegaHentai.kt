package eu.kanade.tachiyomi.animeextension.pt.megahentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.megahentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MegaHentai :
    DooPlay(
        "pt-BR",
        "Mega Hentai",
        "https://megahentai.biz",
    ) {

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val episodeNumberRegex = "(?:Epis[oó]dio\\s+)([0-9]+(?:\\.[0-9]+)?)".toRegex(RegexOption.IGNORE_CASE)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item, div.item")
        val animes = items.map { parseAnimeFromCard(it) }

        val hasNextPage = document.select(".pagination span.current + a, .pagination a:contains(arrow_pag)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination span.current + a.inactive"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/assistir-hentai-online/"
        } else {
            "$baseUrl/assistir-hentai-online/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.epi article, article.item, div.item")

        val animes = items.map { parseAnimeFromCard(it, isEpisodeCard = true) }
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeFromCard(element: Element, isEpisodeCard: Boolean = false): SAnime {
        val anime = SAnime.create()

        val titleSelector = if (isEpisodeCard) ".data h3 a, h2 a" else ".data h3 a"
        anime.title = element.select(titleSelector).text().trim()

        val url = element.select(".poster a, a.w-full").attr("href")
        anime.setUrlWithoutDomain(url)

        val img = element.select(".poster img, img.w-full").first()
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return anime
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)

        val sheader = doc.selectFirst("div.sheader")

        if (sheader == null) {
            return SAnime.create().apply {
                setUrlWithoutDomain(doc.location())
                title = doc.selectFirst("h1")?.text()?.trim()
                    ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty() }
                    ?: ""
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
                description = doc.selectFirst("div.wp-content, meta[name='description']")?.text()?.trim()
                status = SAnime.UNKNOWN
            }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())

            val posterImg = sheader.selectFirst("div.poster > img")
            title = posterImg?.attr("alt")?.trim() ?: sheader.selectFirst("div.data > h1")?.text()?.trim() ?: ""
            thumbnail_url = posterImg?.attr("abs:src") ?: posterImg?.attr("src")

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            val synopsis = doc.selectFirst("div#info div.wp-content p")?.text()?.trim()
            val altTitle = sheader.selectFirst("div.data > span.extra-title")?.text()?.trim()

            description = buildString {
                if (!altTitle.isNullOrBlank()) append("Título Alternativo: $altTitle\n\n")
                if (!synopsis.isNullOrBlank()) append(synopsis)
            }

            status = SAnime.UNKNOWN
        }
    }

    // ============================ Episodes List ============================
    override fun episodeListSelector() = "ul.episodios li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")
        val url = link?.attr("href") ?: ""
        episode.setUrlWithoutDomain(url)

        val name = link?.text()?.trim() ?: ""
        episode.name = name

        val number = element.selectFirst(".numerando")?.text()?.trim()
            ?.substringBefore("-")?.trim()?.toFloatOrNull()
            ?: episodeNumberRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 0f

        episode.episode_number = number
        return episode
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
            val apiRequest = GET(apiUrl, headers.newBuilder().add("Referer", document.location()).build())

            runCatching {
                client.newCall(apiRequest).execute().use { apiResponse ->
                    if (!apiResponse.isSuccessful) return@runCatching

                    val responseBody = apiResponse.body.string()
                    val embedUrl = responseBody
                        .substringAfter("\"embed_url\":\"", "")
                        .substringBefore("\"", "")
                        .replace("\\/", "/")

                    if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                        videoList.addAll(extractVideosFromEmbed(embedUrl, playerListName))
                    }
                }
            }
        }

        if (videoList.isEmpty()) {
            document.select("div.source-box iframe, div.embed-holder iframe").firstOrNull()?.let { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (src.isNotBlank() && src.startsWith("http")) {
                    videoList.addAll(extractVideosFromEmbed(src, "Iframe Fallback"))
                }
            }
        }

        return videoList
    }

    private fun extractVideosFromEmbed(embedUrl: String, playerName: String): List<Video> = when {
        "blogger.com" in embedUrl || "blogspot.com" in embedUrl -> {
            bloggerExtractor.videosFromUrl(embedUrl, headers)
        }
        else -> {
            universalExtractor.videosFromUrl(embedUrl, headers, playerName)
        }
    }
}
