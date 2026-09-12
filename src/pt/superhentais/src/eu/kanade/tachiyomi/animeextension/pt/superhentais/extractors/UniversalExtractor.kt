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
        Log.d(tag, "=== INÍCIO ===")
        Log.d(tag, "Iframe URL: ${iframeUrl.take(120)}...")

        // Passo 1: segue o redirect do t_param.php para descobrir a URL do Blogger
        val bloggerUrl = resolveFinalUrl(iframeUrl, pageUrl)
        Log.d(tag, "URL final (Blogger): ${bloggerUrl?.take(200)}")

        if (bloggerUrl.isNullOrBlank()) {
            Log.e(tag, "Não foi possível resolver o redirect")
            return emptyList()
        }

        // Headers necessários pro BloggerExtractor
        val headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", pageUrl)
            .add("Origin", "https://superhentais.com.br")
            .add("Accept", "*/*")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        // Passo 2: usa BloggerExtractor (função suspend → runBlocking)
        val videos = runBlocking {
            try {
                bloggerExtractor.videosFromUrl(bloggerUrl, headers, "Blogger")
            } catch (e: Exception) {
                Log.e(tag, "Erro no BloggerExtractor: ${e.message}", e)
                emptyList()
            }
        }

        Log.d(tag, "BloggerExtractor retornou ${videos.size} vídeo(s)")

        // Fallback: se o BloggerExtractor falhar, devolve a URL direta
        if (videos.isEmpty()) {
            Log.w(tag, "Fallback: entregando URL direta pro ExoPlayer")
            return listOf(Video(bloggerUrl, "Padrão", bloggerUrl, headers))
        }

        return videos
    }

    /**
     * Segue o redirect 302 do t_param.php e retorna a URL final do googlevideo.com
     */
    private fun resolveFinalUrl(url: String, referer: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", uaDesktop)
            .header("Referer", referer)
            .header("Origin", "https://superhentais.com.br")
            .header("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            Log.d(tag, "Status: ${resp.code}, Content-Type: ${resp.header("Content-Type")}")
            Log.d(tag, "URL final completa: ${finalUrl.take(250)}")

            if (resp.isSuccessful && finalUrl != url) {
                finalUrl
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao seguir redirect: ${e.message}")
        null
    }
}
