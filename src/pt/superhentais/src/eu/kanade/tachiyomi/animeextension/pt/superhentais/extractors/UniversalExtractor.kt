package eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag by lazy { javaClass.simpleName }

    private val uaMobile = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Extrai os vídeos de uma URL de iframe t_param.php do SuperHentais.
     *
     * O servidor responde com um redirect 302 para googlevideo.com (Blogger).
     * Nós seguimos o redirect nós mesmos, capturamos a URL FINAL assinada,
     * e entregamos essa URL ao ExoPlayer SEM o Referer original
     * (o Google Video rejeita com "unrecognized file format" se vier com Referer errado).
     */
    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.d(tag, "Extraindo de iframe: $iframeUrl")

        val attempts = listOf(
            "360p" to uaMobile,
            "720p" to uaDesktop,
        )

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        for ((fallbackLabel, ua) in attempts) {
            val finalUrl = followRedirect(iframeUrl, pageUrl, ua) ?: continue
            if (!seen.add(finalUrl)) continue

            val realQuality = detectQuality(finalUrl) ?: fallbackLabel

            // ⚠️ CRÍTICO: sem Referer de superhentais aqui!
            // O Google Video (Blogger) rejeita se o Referer não for blogger.com
            val videoHeaders = Headers.Builder()
                .add("User-Agent", ua)
                .add("Accept", "*/*")
                .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            videos.add(Video(finalUrl, realQuality, finalUrl, videoHeaders))
            Log.d(tag, "Adicionado vídeo: $realQuality → $finalUrl")
        }

        if (videos.isEmpty()) {
            Log.e(tag, "Nenhum vídeo pôde ser extraído de $iframeUrl")
        }

        return videos
    }

    /**
     * Segue o redirect 302 do t_param.php até a URL final (googlevideo).
     */
    private fun followRedirect(url: String, referer: String, ua: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Origin", "https://superhentais.com.br")
            .header("Accept", "*/*")
            .build()

        // O client padrão já segue redirects automaticamente
        client.newCall(request).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            Log.d(tag, "URL final após redirect: $finalUrl (status ${resp.code})")

            val isVideoUrl = finalUrl.contains("googlevideo.com") ||
                finalUrl.contains("videoplayback") ||
                finalUrl.contains(".mp4") ||
                finalUrl.contains(".m3u8")

            if (resp.isSuccessful && finalUrl != url && isVideoUrl) {
                finalUrl
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao seguir redirect: ${e.message}")
        null
    }

    /**
     * Detecta a qualidade real baseada no parâmetro itag do Google Video.
     */
    private fun detectQuality(url: String): String? = when {
        url.contains("itag=37") -> "1080p"
        url.contains("itag=22") -> "720p"
        url.contains("itag=59") -> "480p"
        url.contains("itag=18") -> "360p"
        url.contains("itag=17") -> "144p"
        else -> null
    }
}
