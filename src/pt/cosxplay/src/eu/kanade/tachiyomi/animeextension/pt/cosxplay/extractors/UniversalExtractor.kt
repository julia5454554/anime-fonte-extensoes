package eu.kanade.tachiyomi.animeextension.pt.cosxplay.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UniversalExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(embedUrl: String, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val embedHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

        try {
            val response = client.newCall(GET(embedUrl, embedHeaders)).execute()
            val document = response.asJsoup()
            document.select("video source").forEach { source ->
                val src = source.attr("src")
                val quality = source.attr("title").ifBlank { "Vídeo" }
                if (src.isNotEmpty()) {
                    videos.add(Video(src, quality, src, embedHeaders))
                }
            }
        } catch (_: Exception) {}

        return videos
    }
}
