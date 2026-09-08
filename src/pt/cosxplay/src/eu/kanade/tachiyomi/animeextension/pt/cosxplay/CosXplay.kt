package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt"
    override val supportsLatest = true

    override val headers: Headers = super.headers.newBuilder()
        .add("Cookie", "age-allow-cosxplay-com=1; abn_country=BR")
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")
        .build()

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .headers(headers)
                .build()
            chain.proceed(request)
        }
        .build()

    // ============================== Populares ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return if (page > 1) GET("$baseUrl/page/$page/", headers) else GET(baseUrl, headers)
    }

    override fun popularAnimeSelector(): String = ".video-block"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("span.title")?.text().orEmpty().trim()
            setUrlWithoutDomain(element.selectFirst("a.thumb")?.attr("href").orEmpty())

            val img = element.selectFirst("img.video-img")
            thumbnail_url = img?.let {
                it.attr("data-src").ifEmpty { it.attr("abs:src") }
            }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a.next"

    // =============================== Mais Recentes ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        return if (page > 1) {
            GET("$baseUrl/page/$page/?filter=latest", headers)
        } else {
            GET("$baseUrl/?filter=latest", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Pesquisa ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (page > 1) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            GET("$baseUrl/?s=$query", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Detalhes ===========================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst(".top-h1 h1, h1")?.text().orEmpty().trim()
            description = document.selectFirst(".description, .entry-content")?.text()
            genre = document.select(".tags-list a.label-tag-video, .tags-list a.label-cat-video")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            thumbnail_url = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        }
    }

    // ============================== Episódios =============================
    override fun episodeListSelector(): String = "html"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = "Vídeo Completo"
            episode_number = 1f
            setUrlWithoutDomain(element.ownerDocument()?.location().orEmpty())
        }
    }

    // ============================ Links de Vídeo ============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("video.xp-Player-video source, video source").forEach { element ->
            val src = element.attr("abs:src").ifEmpty { element.attr("src") }
            val qualityLabel = element.attr("title").ifEmpty { "HD" }.uppercase()

            if (src.isNotEmpty()) {
                videoList.add(Video(src, qualityLabel, src))
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
