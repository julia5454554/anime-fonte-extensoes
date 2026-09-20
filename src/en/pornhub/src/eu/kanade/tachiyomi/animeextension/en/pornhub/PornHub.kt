Package eu.kanade.tachiyomi.animeextension.en.pornhub

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class PornHub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("Cookie", "hasVisited=1; accessAgeDisclaimerPH=1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
                .build()
            chain.proceed(newRequest)
        }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularAnimeSelector(): String = "div.gridWrapper li.pcVideoListItem"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/video?page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a")
        val img = element.selectFirst("img")

        anime.setUrlWithoutDomain(link?.attr("href") ?: "")
        anime.title = img?.attr("alt") ?: "Video"
        anime.thumbnail_url = img?.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categoryFilter = filters.find { it is CategoryFilter } as? CategoryFilter
        val categoryUrl = categoryFilter?.toUrl()

        return when {
            query.isNotBlank() -> GET("$baseUrl/video/search?search=$query&page=$page", headers)
            categoryUrl != null -> {
                val connector = if (categoryUrl.contains("?")) "&" else "?"
                GET("$baseUrl$categoryUrl${connector}page=$page", headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text()?.trim() ?: ""

        val noscriptTag = document.selectFirst("noscript:has(img.videoElementPoster)")
        val poster = if (noscriptTag != null) {
            Jsoup.parse(noscriptTag.html()).selectFirst("img")?.attr("src")
        } else {
            document.selectFirst("img.videoElementPoster")?.attr("src")
        }
        anime.thumbnail_url = poster

        anime.description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        anime.genre = document.select("div.tagsWrapper a").joinToString { it.text() }
        anime.author = document.select("a.pstar-list-btn").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Vídeo Completo"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw Exception("Not used")
    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(var flashvars)")?.data() ?: return emptyList()
        val jsonString = scriptData.substringAfter("var flashvars_").substringAfter(" = ").substringBefore(";\n")

        val videoList = mutableListOf<Video>()

        try {
            val parsedData = json.decodeFromString<PhubJson>(jsonString)
            parsedData.mediaDefinitions?.forEach { media ->
                val videoUrl = media.videoUrl ?: return@forEach
                val qualityName = media.quality?.toString() ?: "Default"
                val format = media.format ?: ""

                videoList.add(
                    Video(
                        url = videoUrl,
                        quality = "PornHub - $qualityName ($format)",
                        videoUrl = videoUrl,
                        headers = headers,
                    ),
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videoList
    }

    override fun videoListSelector() = throw Exception("Not used")
    override fun videoUrlParse(document: Document) = throw Exception("Not used")
    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString("preferred_quality", "720") ?: "720"
        return this.sortedByDescending { it.quality.contains(preferred) }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("A busca por texto ignora os filtros de categoria"),
        CategoryFilter(),
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Categoria", categories.map { it.first }.toTypedArray()) {
        fun toUrl() = categories[state].second

        companion object {
            private val categories = arrayOf(
                "Todos" to "/video",
                "18-25" to "/categories/teen",
                "60FPS" to "/video?c=105",
                "Amateur" to "/video?c=3",
                "Anal" to "/video?c=35",
                "Arab" to "/video?c=98",
                "Asian" to "/video?c=1",
                "Babe" to "/categories/babe",
                "Big Ass" to "/video?c=4",
                "Blonde" to "/video?c=9",
                "Brunette" to "/video?c=11",
                "Cosplay" to "/video?c=241",
                "Ebony" to "/video?c=17",
                "HD Porn" to "/hd",
                "MILF" to "/video?c=29",
            )
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualidade Preferencial"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")
    override fun latestUpdatesRequest(page: Int) = throw Exception("Not used")
    override fun latestUpdatesSelector() = throw Exception("Not used")
}

@Serializable
data class PhubJson(
    val mediaDefinitions: List<PhubVideoJson>? = null,
)

@Serializable
data class PhubVideoJson(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
)
