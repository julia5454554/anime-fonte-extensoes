package eu.kanade.tachiyomi.animeextension.en.pornhub

import android.content.SharedPreferences
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
import keiyoushi.utils.getPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class PornHub : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "PornHub"
    override val baseUrl = "https://www.pornhub.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy { getPreferences() }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Cookie", "hasVisited=1; accessAgeDisclaimerPH=1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
                .build()
            chain.proceed(request)
        }.build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ============================
    override fun popularAnimeSelector(): String = "div.gridWrapper li.pcVideoListItem"

    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/video?page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        anime.title = element.selectFirst("img")?.attr("alt") ?: "Video"
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a"

    // =========================== Search =============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val categoryUrl = categoryFilter?.toUrl()

        return if (query.isNotBlank()) {
            GET("$baseUrl/video/search?search=$query&page=$page", headers)
        } else if (categoryUrl != null) {
            val connector = if (categoryUrl.contains("?")) "&" else "?"
            GET("$baseUrl$categoryUrl${connector}page=$page", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // =========================== Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text()?.trim() ?: ""

        val noscriptTag = document.selectFirst("noscript:has(img.videoElementPoster)")
        anime.thumbnail_url = if (noscriptTag != null) {
            Jsoup.parse(noscriptTag.html()).selectFirst("img")?.attr("src")
        } else {
            document.selectFirst("img.videoElementPoster")?.attr("src")
        }

        anime.description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        anime.genre = document.select("div.tagsWrapper a").joinToString { it.text() }
        anime.author = document.select("a.pstar-list-btn").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // =========================== Episodes ===========================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Vídeo Completo"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw Exception("Not used")
    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(var flashvars)")?.data() ?: return emptyList()

        val jsonString = scriptData.substringAfter("var flashvars_")
            .substringAfter(" = ")
            .substringBefore(";\n")
        
        val videoList = mutableListOf<Video>()

        try {
            val parsedData = json.decodeFromString<PhubJson>(jsonString)
            parsedData.mediaDefinitions?.forEach { videoJson ->
                val videoUrl = videoJson.videoUrl ?: return@forEach
                val quality = videoJson.quality?.toString() ?: "Default"
                val format = videoJson.format ?: ""
                videoList.add(Video(videoUrl, "PornHub - $quality ($format)", videoUrl, headers = headers))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return sort(videoList)
    }

    private fun sort(list: List<Video>): List<Video> {
        val preferred = preferences.getString("preferred_quality", "720") ?: "720"
        return list.sortedByDescending { it.quality.contains(preferred) }
    }

    override fun videoListSelector(): String = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String = throw Exception("Not used")
    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    // =========================== Filters ============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A busca por texto ignora os filtros de categoria"),
        CategoryFilter()
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Categoria", categories.map { it.first }.toTypedArray()) {
        fun toUrl() = categories[state].second

        companion object {
            val categories = arrayOf(
                Pair("Todos", "/video"),
                Pair("18-25", "/categories/teen"),
                Pair("60FPS", "/video?c=105"),
                Pair("Amateur", "/video?c=3"),
                Pair("Anal", "/video?c=35"),
                Pair("Arab", "/video?c=98"),
                Pair("Asian", "/video?c=1"),
                Pair("Babe", "/categories/babe"),
                Pair("Big Ass", "/video?c=4"),
                Pair("Blonde", "/video?c=9"),
                Pair("Brunette", "/video?c=11"),
                Pair("Cosplay", "/video?c=241"),
                Pair("Ebony", "/video?c=17"),
                Pair("HD Porn", "/hd"),
                Pair("MILF", "/video?c=29")
            )
        }
    }

    // ========================== Settings ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val listPreference = ListPreference(screen.context).apply {
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
                true
            }
        }
        screen.addPreference(listPreference)
    }

    // ======================= Latest (Unused) ========================
    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesSelector(): String = throw Exception("Not used")
}
