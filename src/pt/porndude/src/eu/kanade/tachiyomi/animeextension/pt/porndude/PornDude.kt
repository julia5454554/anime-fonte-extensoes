package eu.kanade.tachiyomi.animeextension.pt.porndude

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class PornDude : AnimeHttpSource() {

    override val name = "3D Porn Dude"

    override val baseUrl = "https://3dporndude.com"

    override val lang = "pt"

    override val supportsLatest = true

    // ============================== Headers ==============================

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page"
        }

        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)

        val hasNextPage = document.selectFirst("a.next") != null ||
            document.select("a[href*='page']").isNotEmpty()

        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return popularAnimeRequest(page)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val encodedQuery = query.trim().replace(" ", "+")

        val url = if (page == 1) {
            "$baseUrl/search/?q=$encodedQuery"
        } else {
            "$baseUrl/search/$encodedQuery/page/$page/"
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)

        val hasNextPage = document.selectFirst("a.next") != null ||
            document.select("ul.pagination a[href*='page']").isNotEmpty() ||
            document.select("a[href*='page/']").isNotEmpty()

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            setUrlWithoutDomain(response.request.url.toString())

            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")
                    ?.attr("content")
                    ?.trim()
                ?: "Sem título"

            thumbnail_url = document.selectFirst("div.fp-poster img")
                ?.attr("abs:src")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property='og:image']")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }

            description = document.selectFirst("div.video-description")
                ?.text()
                ?.trim()
                ?: document.selectFirst("div.description")
                    ?.text()
                    ?.trim()
                ?: document.selectFirst("meta[name='description']")
                    ?.attr("content")
                    ?.trim()
                ?: document.selectFirst("meta[property='og:description']")
                    ?.attr("content")
                    ?.trim()
                ?: ""

            val script = document.select("script")
                .firstOrNull { it.html().contains("flashvars") }

            if (script != null) {
                val categories = extractFlashvar(
                    script.html(),
                    "video_categories",
                )

                if (!categories.isNullOrBlank()) {
                    genre = categories
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
            }

            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Vídeo"
                episode_number = 1f
                date_upload = 0L
            },
        )
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()

        val videos = extractVideosFromDocument(
            document,
            pageUrl,
        )

        if (videos.isNotEmpty()) {
            return videos
        }

        val videoId = extractVideoId(pageUrl)

        if (videoId != null) {
            val embedUrl = "$baseUrl/embed/$videoId"

            val embedDocument = runCatching {
                client.newCall(
                    GET(embedUrl, headers),
                ).execute().use { embedResponse ->
                    if (embedResponse.isSuccessful) {
                        embedResponse.asJsoup()
                    } else {
                        null
                    }
                }
            }.getOrNull()

            if (embedDocument != null) {
                return extractVideosFromDocument(
                    embedDocument,
                    embedUrl,
                )
            }
        }

        return emptyList()
    }

    // ============================= Utilities ==============================

    private fun parseVideoCards(document: Document): List<SAnime> {
        return document.select("div.thumb-itm").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/video/']")
                ?: return@mapNotNull null

            val href = link.attr("href").trim()

            if (href.isBlank()) {
                return@mapNotNull null
            }

            val url = toRelativeUrl(href)

            val title = link.attr("title").trim()
                .ifBlank {
                    link.text().trim()
                }

            if (title.isBlank()) {
                return@mapNotNull null
            }

            val thumbnail = element.selectFirst("img")?.let { image ->
                image.attr("abs:src").takeIf { it.isNotBlank() }
                    ?: image.attr("abs:data-webp").takeIf { it.isNotBlank() }
                    ?: image.attr("data-webp").takeIf { it.isNotBlank() }
            }

            SAnime.create().apply {
                this.url = url
                this.title = title
                this.thumbnail_url = thumbnail
            }
        }
    }

    private fun extractVideosFromDocument(
        document: Document,
        pageUrl: String,
    ): List<Video> {
        val videos = mutableListOf<Video>()

        // ========================== Flashvars ==========================

        val script = document.select("script")
            .firstOrNull { it.html().contains("flashvars") }

        if (script != null) {
            val scriptContent = script.html()

            val qualityMap = linkedMapOf(
                "video_url" to "video_url_text",
                "video_alt_url" to "video_alt_url_text",
                "video_alt_url2" to "video_alt_url2_text",
                "video_alt_url3" to "video_alt_url3_text",
            )

            for ((urlKey, qualityKey) in qualityMap) {
                val rawUrl = extractFlashvar(
                    scriptContent,
                    urlKey,
                ) ?: continue

                val videoUrl = rawUrl
                    .replace("&amp;", "&")
                    .trim()

                if (videoUrl.isBlank()) {
                    continue
                }

                if (!videoUrl.startsWith("http://") &&
                    !videoUrl.startsWith("https://")
                ) {
                    continue
                }

                val quality = extractFlashvar(
                    scriptContent,
                    qualityKey,
                )?.trim()
                    ?.ifBlank { null }
                    ?: "HD"

                videos.add(
                    Video(
                        videoUrl,
                        quality,
                        videoUrl,
                        headers = headers.newBuilder()
                            .set("Referer", pageUrl)
                            .build(),
                    ),
                )
            }
        }

        if (videos.isNotEmpty()) {
            return videos
                .distinctBy { it.videoUrl }
                .sortedByDescending {
                    extractQualityNumber(it.quality)
                }
        }

        // ============================ Video ============================

        val videoTag = document.selectFirst("video")

        if (videoTag != null) {
            val source = videoTag.selectFirst("source")

            val src = source?.attr("abs:src")
                ?.takeIf { it.isNotBlank() }
                ?: videoTag.attr("abs:src")
                    .takeIf { it.isNotBlank() }

            if (!src.isNullOrBlank()) {
                return listOf(
                    Video(
                        src,
                        "Video",
                        src,
                        headers = headers.newBuilder()
                            .set("Referer", pageUrl)
                            .build(),
                    ),
                )
            }
        }

        return emptyList()
    }

    private fun extractVideoId(url: String): String? {
        return Regex(
            """\/video\/(\d+)\/?""",
        ).find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractFlashvar(
        script: String,
        key: String,
    ): String? {
        val regexSingle = Regex(
            """$key\s*:\s*'([^']*)'""",
            RegexOption.DOT_MATCHES_ALL,
        )

        regexSingle.find(script)?.let {
            return it.groupValues[1]
                .trim()
        }

        val regexDouble = Regex(
            """$key\s*:\s*"([^"]*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )

        regexDouble.find(script)?.let {
            return it.groupValues[1]
                .trim()
        }

        return null
    }

    private fun toRelativeUrl(url: String): String {
        val cleaned = url
            .trim()
            .substringBefore("#")

        return when {
            cleaned.startsWith(baseUrl) -> {
                cleaned.removePrefix(baseUrl)
                    .ifBlank { "/" }
            }

            cleaned.startsWith("https://") ||
                cleaned.startsWith("http://") -> {
                runCatching {
                    okhttp3.HttpUrl.get(cleaned)
                        .encodedPath
                        .let { path ->
                            if (path.isBlank()) "/" else path
                        }
                }.getOrDefault(cleaned)
            }

            cleaned.startsWith("/") -> cleaned

            else -> "/$cleaned"
        }
    }

    private fun extractQualityNumber(quality: String): Int {
        return Regex("""(\d{3,4})\s*p?""")
            .find(quality.lowercase())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }
}
