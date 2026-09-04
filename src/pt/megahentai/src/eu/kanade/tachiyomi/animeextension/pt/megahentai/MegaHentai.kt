package eu.kanade.tachiyomi.animeextension.pt.megahentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.megahentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/hentai/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item, div.item").filterNot { element ->
            element.parents().any { it.id() == "genre_assistir-hentai" }
        }
        val animes = items.map { parseAnimeFromCard(it) }
        val hasNextPage = document.select("link[rel=next]").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "link[rel=next]"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/episodio/" else "$baseUrl/episodio/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item, div.item").filterNot { element ->
            element.parents().any { it.id() == "genre_assistir-hentai" }
        }
        val animes = items.map { parseAnimeFromCard(it) }
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeFromCard(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select(".data h3 a").text().trim()
        val url = element.select(".poster a").attr("href")
        anime.setUrlWithoutDomain(url)
        val img = element.select(".poster img").first()
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return anime
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=${query.replace(" ", "+")}"
        } else {
            "$baseUrl/?s=${query.replace(" ", "+")}&paged=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item, div.item")
        val animes = items.map { parseAnimeFromCard(it) }
        val hasNextPage = document.select("link[rel=next], div.pagination a.arrow_pag").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .firstOrNull { !it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .firstOrNull { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        if (sheader == null) {
            return SAnime.create().apply {
                setUrlWithoutDomain(doc.location())
                title = doc.selectFirst("h1")?.text()?.trim()
                    ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty() }
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
                description = doc.selectFirst("meta[name='description']")?.attr("content")?.trim().orEmpty()
            }
        }
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }.trim()
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    append(doc.getAlternativeTitle())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    override fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        val explicitUrl = menu?.parent()?.takeIf { !it.hasClass("nonex") }?.attr("href")
            ?.takeIf { it.contains("/hentai/") }
        val animeUrl = explicitUrl ?: document.select("div.pag_episodes a[href*='/episodio/']")
            .map { it.attr("href") }
            .firstOrNull { it.substringAfterLast('/').substringBeforeLast("-episodio-") == document.location().substringAfterLast('/').substringBeforeLast("-episodio-") }
            ?.let { episodeUrl ->
                val slug = episodeUrl.substringAfterLast('/').substringBeforeLast("-episodio-")
                "${baseUrl.trimEnd('/')}/hentai/$slug"
            }

        if (animeUrl == null) {
            return document
        }

        return runCatching {
            client.newCall(GET(animeUrl, headers)).execute().use { response ->
                if (response.isSuccessful) response.asJsoup() else document
            }
        }.getOrElse { document }
    }

    // ============================ Episodes List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val initial = response.asJsoup()
        val doc = getRealAnimeDoc(initial)

        val seasons = doc.select("div#seasons > div.se-c")

        if (seasons.isEmpty()) {
            return parseOrphanEpisodes(initial)
        }

        val episodes = seasons.flatMap { season ->
            val seasonNumber = season.selectFirst("span.se-t")
                ?.text()
                ?.trim()
                .orEmpty()

            season.select("ul.episodios li").mapNotNull { element ->
                val link = element.selectFirst(".episodiotitle a[href]")
                    ?: return@mapNotNull null
                val href = link.attr("href").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val name = link.text().trim()
                val number = element.selectFirst(".numerando")?.text()?.trim()
                    ?.substringBefore("-")?.trim()?.toFloatOrNull()
                    ?: EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = number
                    this.name = if (seasonNumber.isBlank()) name else "Temporada $seasonNumber - $name"
                }
            }
        }.reversed()

        return episodes
    }

    private fun parseOrphanEpisodes(initial: Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var document = initial
        var url = initial.location()
        var step = 0
        val visited = mutableSetOf<String>()

        while (step < 50 && visited.add(url)) {
            val name = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
            val number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            if (number != null) {
                episodes += SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    episode_number = number
                    this.name = name
                }
            }

            val next = document.select("div.pag_episodes a[href]")
                .firstOrNull { it.text().contains("próximo", ignoreCase = true) || it.attr("title").contains("próximo", ignoreCase = true) }
                ?: break

            val nextUrl = next.attr("href")
            if (nextUrl.isBlank() || nextUrl == url) break

            val nextDoc = runCatching {
                client.newCall(GET(nextUrl, headers)).execute().use { response ->
                    if (response.isSuccessful) response.asJsoup() else null
                }
            }.getOrNull() ?: break

            url = nextUrl
            document = nextDoc
            step++
        }

        return episodes
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()

        // 1. Prioriza o iframe ativo diretamente
        val activeIframe = document.select("div.source-box.on iframe").firstOrNull()
            ?: document.select("div.source-box iframe").firstOrNull()

        if (activeIframe != null) {
            val iframeSrc = activeIframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                val iframeHeaders = headersBuilder()
                    .add("Referer", response.request.url.toString())
                    .build()
                return runBlocking { universalExtractor.videosFromUrl(iframeSrc, iframeHeaders, name) }
            }
        }

        // 2. Fallback: tenta usar a API do player
        val players = document.select("ul#playeroptionsul li")
        if (players.isNotEmpty()) {
            val activePlayer = document.select("ul#playeroptionsul li.on").firstOrNull()
                ?: players.firstOrNull()
            if (activePlayer != null) {
                return runBlocking { getPlayerVideos(activePlayer, response.request.url.toString()) }
            }
        }

        return emptyList()
    }

    private suspend fun getPlayerVideos(player: Element, episodeUrl: String): List<Video> {
        val name = player.selectFirst("span")?.text()?.trim() ?: "Player"

        val url = getPlayerUrl(player, episodeUrl)

        val videos = when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)

            "jwplayer?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                return listOf(Video(videoUrl, name, videoUrl, videoHeaders))
            }

            else -> emptyList()
        }

        return if (videos.isEmpty()) {
            universalExtractor.videosFromUrl(url, headers, name)
        } else {
            videos
        }
    }

    private suspend fun getPlayerUrl(player: Element, episodeUrl: String): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        val requestHeaders = headersBuilder()
            .add("Referer", episodeUrl)
            .build()
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num", requestHeaders))
            .awaitSuccess()
            .bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // ============================= Utilities ==============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    companion object {
        private val EPISODE_NUMBER_REGEX = "(?:Epis[oó]dio\\s+)([0-9]+(?:\\.[0-9]+)?)".toRegex(RegexOption.IGNORE_CASE)
    }
}
