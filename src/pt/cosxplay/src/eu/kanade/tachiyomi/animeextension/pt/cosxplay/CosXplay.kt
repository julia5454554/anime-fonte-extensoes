package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
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
        .add("Origin", baseUrl)

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

        // Headers reutilizados para todas as requisições de vídeo
        val videoHeaders = Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            )
            .add("Referer", "https://cosxplay.com/")
            .add("Origin", "https://cosxplay.com")
            .add("Accept", "*/*")
            .add("Accept-Encoding", "identity")
            .add("Range", "bytes=0-")
            .build()

        // 1. Processa IFrames externos via Extractors da pasta 'lib'
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("abs:src")
            if (iframeUrl.startsWith("http")) {
                videoList.addAll(extractVideosFromIframe(iframeUrl))
            }
        }

        // 2. Stream principal direto no atributo src do <video>
        document.select("video[src]").forEach { video ->
            val src = video.attr("abs:src").ifEmpty { video.attr("src") }

            if (src.isNotBlank() && src.startsWith("http") && !videoList.any { it.url == src }) {
                videoList.add(
                    Video(
                        url = src,
                        quality = "NOSOFILES - VIDEO",
                        videoUrl = src,
                        headers = videoHeaders
                    )
                )
            }
        }

        // 3. Stream principal via tags <source> dentro de <video>
        document.select("video.xp-Player-video source, video source, source[src]").forEach { element ->
            val src = element.attr("abs:src").ifEmpty { element.attr("src") }

            if (src.isBlank() || !src.startsWith("http")) return@forEach

            val quality = element.attr("title")
                .ifEmpty { element.attr("res") }
                .ifEmpty { "HD" }

            videoList.add(
                Video(
                    url = src,
                    quality = "NOSOFILES - $quality",
                    videoUrl = src,
                    headers = videoHeaders
                )
            )
        }

        return videoList.distinctBy { it.url }
    }

    private fun extractVideosFromIframe(url: String): List<Video> {
        val videoList = mutableListOf<Video>()

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
                        videoList.addAll(StreamWishExtractor(client, headers).videosFromUrl(url))
                    }
                }
            }
            "voe" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(VoeExtractor(client, headers).videosFromUrl(url))
                    }
                }
            }
            "vidhide" in url || "hidev" in url -> {
                runCatching {
                    runBlocking {
                        videoList.addAll(VidHideExtractor(client, headers).videosFromUrl(url))
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
