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
        Log.e(tag, "Page URL: $pageUrl | Iframe: ${iframeUrl.take(150)}")

        val headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", pageUrl)
            .add("Origin", "https://superhentais.com.br")
            .build()

        // 1. Tenta extrator padrão do Blogger
        runCatching {
            val videos = runBlocking { bloggerExtractor.videosFromUrl(iframeUrl, headers, "Blogger") }
            if (videos.isNotEmpty()) return videos
        }

        // 2. Resolve redirecionamentos
        val finalUrl = resolveRedirect(iframeUrl, pageUrl) ?: iframeUrl

        // 3. Tenta o BloggerExtractor com a URL resolvida
        runCatching {
            val videos = runBlocking { bloggerExtractor.videosFromUrl(finalUrl, headers, "Blogger") }
            if (videos.isNotEmpty()) return videos
        }

        // 4. Raspagem direta no HTML do iframe (fallback para extrair .mp4 diretamente)
        val extractedVideos = extractMp4FromHtml(finalUrl, headers)
        if (extractedVideos.isNotEmpty()) {
            return extractedVideos
        }

        // 5. Só retorna no fallback se for explicitamente um link direto de vídeo
        if (isDirectVideoUrl(finalUrl)) {
            return listOf(Video(finalUrl, "Padrão", finalUrl, headers))
        }

        Log.e(tag, "Nenhum link de vídeo válido encontrado.")
        return emptyList()
    }

    private fun extractMp4FromHtml(url: String, headers: Headers): List<Video> {
        return try {
            val request = Request.Builder().url(url).headers(headers).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            // Regex para buscar streams do Blogger/Google Video no HTML
            val mp4Regex = """https?://[^\s"'<>]+(?:\.mp4|googlevideo\.com/videoplayback)[^\s"'<>]*""".toRegex()
            val matches = mp4Regex.findAll(body).map { it.value }.distinct().toList()

            matches.mapIndexed { index, videoUrl ->
                Video(videoUrl, "Blogger Direct ${index + 1}", videoUrl, headers)
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao extrair HTML: ${e.message}")
            emptyList()
        }
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val cleanUrl = url.lowercase()
        return cleanUrl.contains(".mp4") || 
               cleanUrl.contains(".m3u8") || 
               cleanUrl.contains("googlevideo.com/videoplayback")
    }

    private fun resolveRedirect(url: String, referer: String): String? {
        return try {
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
}
