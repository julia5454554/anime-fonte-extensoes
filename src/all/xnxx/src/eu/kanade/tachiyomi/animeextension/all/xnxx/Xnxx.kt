package eu.kanade.tachiyomi.animeextension.all.xnxx

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Xnxx :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Xnxx"

    override val baseUrl = "https://www.xnxx.com"

    override val lang = "all"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    override fun popularAnimeSelector(): String = "div.mozaique > div.thumb-block:not(.thumb-cat)"

    override fun popularAnimeRequest(page: Int): Request {
        val pagePath = if (page > 1) "/${page - 1}" else ""
        return GET("$baseUrl/best$pagePath", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("div.thumb-under > p > a, div.thumb > a")

        anime.setUrlWithoutDomain(linkElement?.attr("href") ?: "")
        anime.title = linkElement?.attr("title")?.ifEmpty { linkElement.text() } ?: element.select("p.title").text()

        val img = element.selectFirst("div.thumb img")
        anime.thumbnail_url = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul li a.next, #content-thumbs div.pagination ul li a.next"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val sourcesJson = document.select("script:containsData(html5player.setVideoUrl)").toString()
        val lowQuality = sourcesJson.substringAfter("VideoUrlLow('").substringBefore("')")
        val hlsQuality = sourcesJson.substringAfter("setVideoHLS('").substringBefore("')")
        val highQuality = sourcesJson.substringAfter("VideoUrlHigh('").substringBefore("')")

        val videos = mutableListOf<Video>()
        if (highQuality.isNotBlank() && highQuality.startsWith("http")) {
            videos.add(Video(highQuality, "High", highQuality))
        }
        if (hlsQuality.isNotBlank() && hlsQuality.startsWith("http")) {
            videos.add(Video(hlsQuality, "HLS", hlsQuality))
        }
        if (lowQuality.isNotBlank() && lowQuality.startsWith("http")) {
            videos.add(Video(lowQuality, "Low", lowQuality))
        }
        return videos
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "HLS")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val tagFilter = filters.find { it is Tags } as? Tags
        val calcPage = page - 1
        return when {
            query.isNotBlank() -> GET("$baseUrl/search/hits/$query/$calcPage", headers)
            tagFilter != null && tagFilter.state.isNotBlank() -> GET("$baseUrl/search/hits/${tagFilter.state}/$calcPage", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("#video-content-metadata > div.clear-infobar strong").text()
        anime.author = document.select("#video-content-metadata > div.clear-infobar span a").text()
        anime.description = document.select("#video-content-metadata > p").text().replace("\n", "")
        anime.genre = document.select("#video-content-metadata > div.metadata-row.video-tags > a").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search by text does not affect the filter"),
        Tags("Tag"),
    )

    internal class Tags(name: String) : AnimeFilter.Text(name)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("High", "Low", "HLS")
            entryValues = arrayOf("High", "Low", "HLS")
            setDefaultValue("HLS")
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
}
