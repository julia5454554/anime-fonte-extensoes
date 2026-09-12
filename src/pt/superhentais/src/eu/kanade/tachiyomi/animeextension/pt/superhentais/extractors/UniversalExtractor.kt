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
            .build()

        // 1. Tenta extrator padrão do Blogger com a URL do iframe
        runCatching {
            val videos = runBlocking { bloggerExtractor.videosFromUrl(iframeUrl, headers, "Blogger") }
            if (videos.isNotEmpty()) return videos
        }

        // 2. Resolve redirects da URL do iframe
        val resolvedUrl = resolveRedirect(iframeUrl, pageUrl) ?: iframeUrl

        runCatching {
            val videos = runBlocking { bloggerExtractor.videosFromUrl(resolvedUrl, headers, "Blogger") }
            if (videos.isNotEmpty()) return videos
        }

        // 3. Baixa o HTML para buscar iframes internos ou links diretos
        val htmlContent = fetchHtml(resolvedUrl, headers) ?: return emptyList()

        // Procura por um iframe interno do blogger na página
        val innerIframeRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val innerIframeMatch = innerIframeRegex.find(htmlContent)?.groupValues?.get(1)

        if (!innerIframeMatch.isNullOrBlank()) {
            val fullInnerUrl = if (innerIframeMatch.startsWith("http")) {
                innerIframeMatch
            } else {
                "https:" + innerIframeMatch.removePrefix("https:").removePrefix("http:")
            }
            runCatching {
                val videos = runBlocking { bloggerExtractor.videosFromUrl(fullInnerUrl, headers, "Blogger") }
                if (videos.isNotEmpty()) return videos
            }
        }

        // 4. Extração manual decodificando e tratando as URLs do Google Video
        val extractedVideos = extractMp4FromHtml(htmlContent)
        if (extractedVideos.isNotEmpty()) {
            return extractedVideos
        }

        Log.e(tag, "Nenhum vídeo válido pôde ser extraído.")
        return emptyList()
    }

    private fun fetchHtml(url: String, headers: Headers): String? = try {
        val request = Request.Builder().url(url).headers(headers).build()
        client.newCall(request).execute().use { response ->
            response.body?.string()
        }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao buscar HTML: ${e.message}")
        null
    }

    private fun extractMp4FromHtml(html: String): List<Video> {
        val videoList = mutableListOf<Video>()

        // Busca por URLs do googlevideo ou arquivos mp4 dentro do HTML/JS
        val regex = """https?://[^\s"'<>]+?(?:googlevideo\.com/videoplayback|\.mp4)[^\s"'<>]*""".toRegex()
        val matches = regex.findAll(html).map { it.value }.distinct().toList()

        matches.forEachIndexed { index, rawUrl ->
            // Limpa as URLs corrigindo os escapes do JSON (\u0026) e HTML (&amp;)
            val cleanUrl = rawUrl
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .trimEnd('\\', '"', '\'', ';')

            if (cleanUrl.contains("googlevideo.com") || cleanUrl.contains(".mp4")) {
                // Headers necessários para o ExoPlayer não receber HTTP 403 do Google
                val videoHeaders = Headers.Builder()
                    .add("User-Agent", uaDesktop)
                    .add("Referer", "https://www.blogger.com/")
                    .build()

                videoList.add(Video(cleanUrl, "Blogger SD/HD ${index + 1}", cleanUrl, videoHeaders))
            }
        }

        return videoList
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
