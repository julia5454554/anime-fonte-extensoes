package eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "SuperHentais-Extractor"
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.e(tag, "Page: $pageUrl | Iframe: ${iframeUrl.take(150)}")

        val headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", pageUrl)
            .build()

        // 1. PRIMEIRO: Resolve redirect + busca HTML direto
        val resolvedUrl = resolveRedirect(iframeUrl, pageUrl) ?: iframeUrl
        val htmlContent = fetchHtml(resolvedUrl, headers)

        if (htmlContent != null) {
            // Tenta extrair iframe interno se existir
            val innerIframeRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
            val innerIframe = innerIframeRegex.find(htmlContent)?.groupValues?.get(1)

            if (!innerIframe.isNullOrBlank()) {
                val fullInnerUrl = if (innerIframe.startsWith("http")) {
                    innerIframe
                } else {
                    "https:" + innerIframe.removePrefix("https:").removePrefix("http:")
                }
                runCatching {
                    val videos = runBlocking { bloggerExtractor.videosFromUrl(fullInnerUrl, headers, "Blogger") }
                    if (videos.isNotEmpty()) {
                        Log.e(tag, "✅ ${videos.size} vídeo(s) extraído(s) via BloggerExtractor interno")
                        return videos
                    }
                }
            }

            // Tenta extração direta via regex no HTML (mais rápido)
            val directVideos = extractMp4FromHtml(htmlContent)
            if (directVideos.isNotEmpty()) {
                Log.e(tag, "✅ ${directVideos.size} vídeo(s) extraído(s) via HTML regex")
                return directVideos
            }
        }

        // 2. DEPOIS: Fallback usando BloggerExtractor na URL original
        runCatching {
            val videos = runBlocking { bloggerExtractor.videosFromUrl(iframeUrl, headers, "Blogger") }
            if (videos.isNotEmpty()) {
                Log.e(tag, "✅ ${videos.size} vídeo(s) extraído(s) via BloggerExtractor fallback")
                return videos
            }
        }

        Log.e(tag, "❌ Nenhum vídeo encontrado")
        return emptyList()
    }

    private fun fetchHtml(url: String, headers: Headers): String? = try {
        val request = Request.Builder().url(url).headers(headers).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao buscar HTML: ${e.message}")
        null
    }

    private fun extractMp4FromHtml(html: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val regex = """https?://[^\s"'<>]+?(?:googlevideo\.com/videoplayback|\.mp4)[^\s"'<>]*""".toRegex()
        val matches = regex.findAll(html).map { it.value }.distinct().toList()

        matches.forEach { rawUrl ->
            val cleanUrl = rawUrl
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .trimEnd('\\', '"', '\'', ';')

            if (cleanUrl.contains("googlevideo.com") || cleanUrl.contains(".mp4")) {
                // Header exclusivo do Blogger para autorização no Google Video
                val videoHeaders = Headers.Builder()
                    .add("User-Agent", uaDesktop)
                    .add("Referer", "https://www.blogger.com/")
                    .build()

                val quality = detectQuality(cleanUrl)
                videoList.add(Video(cleanUrl, quality, cleanUrl, videoHeaders))
            }
        }

        return videoList
    }

    private fun detectQuality(url: String): String = when {
        url.contains("itag=37") -> "1080p"
        url.contains("itag=22") -> "720p"
        url.contains("itag=59") || url.contains("itag=45") || url.contains("itag=35") -> "480p"
        url.contains("itag=18") || url.contains("itag=43") -> "360p"
        url.contains("itag=17") -> "144p"
        else -> "Blogger (HD)"
    }

    private fun resolveRedirect(url: String, referer: String): String? = try {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = url
        var hops = 0

        while (hops < 5) {
            hops++
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", uaDesktop)
                .header("Referer", referer)
                .build()

            val resp = noRedirect.newCall(request).execute()
            resp.use {
                if (it.isRedirect) {
                    val location = it.header("Location") ?: return currentUrl
                    currentUrl = if (location.startsWith("http")) location else java.net.URI(currentUrl).resolve(location).toString()
                    continue
                }
                return currentUrl
            }
        }
        currentUrl
    } catch (e: Exception) {
        null
    }
}
