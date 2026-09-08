package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Cookie", "age-allow-cosxplay-com=1; abn_country=BR")
        .add("Referer", "$baseUrl/")

    // ============================== Populares ==============================
    override fun popularAnimeRequest(page: Int): Request = if (page > 1) GET("$baseUrl/page/$page/", headers) else GET(baseUrl, headers)

    override fun popularAnimeSelector(): String = ".video-block"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst("span.title")?.text().orEmpty().trim()
        setUrlWithoutDomain(element.selectFirst("a.thumb")?.attr("href").orEmpty())

        val img = element.selectFirst("img.video-img")
        thumbnail_url = img?.let {
            it.attr("data-src").ifEmpty { it.attr("abs:src") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a.next"

    // =============================== Mais Recentes ==============================
    override fun latestUpdatesRequest(page: Int): Request = if (page > 1) {
        GET("$baseUrl/page/$page/?filter=latest", headers)
    } else {
        GET("$baseUrl/?filter=latest", headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Pesquisa ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (page > 1) {
        GET("$baseUrl/page/$page/?s=$query", headers)
    } else {
        GET("$baseUrl/?s=$query", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Detalhes ===========================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst(".top-h1 h1, h1")?.text().orEmpty().trim()
        description = document.selectFirst(".description, .entry-content")?.text()
        genre = document.select(".tags-list a.label-tag-video, .tags-list a.label-cat-video")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        thumbnail_url = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
    }

    // ============================== Episódios =============================
    override fun episodeListSelector(): String = "html"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = "Vídeo Completo"
        episode_number = 1f
        setUrlWithoutDomain(element.ownerDocument()?.location().orEmpty())
    }

    // ============================ Links de Vídeo ============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val pageUrl = response.request.url.toString()

        // 1. Processa IFrames externos via Extractors da pasta 'lib'
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("abs:src")
            if (iframeUrl.startsWith("http")) {
                videoList.addAll(extractVideosFromIframe(iframeUrl))
            }
        }

        // 2. Stream principal (Herda TODOS os headers principais + Cookies de idade)
        val streamHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Accept", "*/*")
            .build()

        val playlistUtils = PlaylistUtils(client, headers)

        document.select("video.xp-Player-video source, video source, source").forEach { element ->
            val src = element.attr("abs:src").ifEmpty { element.attr("src") }
            val qualityLabel = element.attr("title").ifEmpty { "Servidor Principal (HD)" }.uppercase()

            if (src.isNotEmpty() && src.startsWith("http") && !videoList.any { it.url == src }) {
                if (src.contains(".m3u8")) {
                    runCatching {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                src,
                                referer = pageUrl,
                                videoNameGen = { q -> "Servidor Principal - $q" }
                            )
                        )
                    }
                } else {
                    videoList.add(Video(src, qualityLabel, src, headers = streamHeaders))
                }
            }
        }

        return videoList
    }

    private fun extractVideosFromIframe(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val iframeHeaders = headers.newBuilder().set("Referer", url).build()

        when {
            "filemoon" in url || "moonplayer" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(FilemoonExtractor(client).videosFromUrl(url))
                    }
                }
            }
            "streamwish" in url || "swdyu" in url || "embedwish" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(StreamWishExtractor(client, iframeHeaders).videosFromUrl(url))
                    }
                }
            }
            "voe" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(VoeExtractor(client, iframeHeaders).videosFromUrl(url))
                    }
                }
            }
            "vidhide" in url || "hidev" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(VidHideExtractor(client, iframeHeaders).videosFromUrl(url))
                    }
                }
            }
            "dood" in url || "doodstream" in url -> {
                runCatching {
                    DoodExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
                }
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")
}
