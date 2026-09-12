package eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient
import okhttp3.Request

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "SuperHentais-Extractor"

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.d(tag, "=== INÍCIO ===")
        Log.d(tag, "Iframe URL: $iframeUrl")

        // Passo 1: segue o redirect do t_param.php para descobrir a URL do Blogger
        val bloggerUrl = resolveFinalUrl(iframeUrl, pageUrl)
        Log.d(tag, "URL final (Blogger): $bloggerUrl")

        if (bloggerUrl.isNullOrBlank()) {
            Log.e(tag, "Não foi possível resolver o redirect")
            return emptyList()
        }

        // Passo 2: usa BloggerExtractor para extrair os vídeos
        val videos = try {
            bloggerExtractor.videosFromUrl(bloggerUrl, pageUrl)
        } catch (e: Exception) {
            Log.e(tag, "Erro no BloggerExtractor: ${e.message}")
            emptyList()
        }

        Log.d(tag, "BloggerExtractor retornou ${videos.size} vídeo(s)")

        // Fallback: se o BloggerExtractor falhar, devolve a URL direta
        if (videos.isEmpty()) {
            Log.w(tag, "Fallback: entregando URL direta pro ExoPlayer")
            return listOf(Video(bloggerUrl, "Padrão", bloggerUrl))
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
            Log.d(tag, "URL final: ${finalUrl.take(200)}")

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
