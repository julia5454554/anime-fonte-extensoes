package eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "SuperHentais-Extractor"

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.e(tag, "=== INÍCIO ===")
        Log.e(tag, "Page URL: $pageUrl")
        Log.e(tag, "Iframe URL: ${iframeUrl.take(200)}")

        val headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", pageUrl)
            .add("Origin", "https://superhentais.com.br")
            .add("Accept", "*/*")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        // Estratégia 1: passar a URL do iframe DIRETO pro BloggerExtractor
        // (o BloggerExtractor sabe lidar com redirects internamente)
        Log.e(tag, "Estratégia 1: passar iframeUrl direto pro BloggerExtractor")
        val videos1 = runBlocking {
            try {
                bloggerExtractor.videosFromUrl(iframeUrl, headers, "Blogger")
            } catch (e: Exception) {
                Log.e(tag, "Estratégia 1 falhou: ${e.message}")
                emptyList()
            }
        }
        Log.e(tag, "Estratégia 1 retornou ${videos1.size} vídeo(s)")
        if (videos1.isNotEmpty()) {
            videos1.forEach { Log.e(tag, "  → ${it.quality} | ${it.url.take(120)}") }
            return videos1
        }

        // Estratégia 2: resolver o redirect manualmente e passar pro BloggerExtractor
        Log.e(tag, "Estratégia 2: resolver redirect manualmente")
        val finalUrl = resolveRedirect(iframeUrl, pageUrl)
        Log.e(tag, "URL final após redirect: ${finalUrl?.take(200)}")

        if (!finalUrl.isNullOrBlank()) {
            val videos2 = runBlocking {
                try {
                    bloggerExtractor.videosFromUrl(finalUrl, headers, "Blogger")
                } catch (e: Exception) {
                    Log.e(tag, "Estratégia 2 falhou: ${e.message}")
                    emptyList()
                }
            }
            Log.e(tag, "Estratégia 2 retornou ${videos2.size} vídeo(s)")
            if (videos2.isNotEmpty()) {
                videos2.forEach { Log.e(tag, "  → ${it.quality} | ${it.url.take(120)}") }
                return videos2
            }

            // Fallback final: entregar a URL final direto pro ExoPlayer
            Log.e(tag, "Fallback: entregar URL final direto")
            return listOf(Video(finalUrl, "Padrão", finalUrl, headers))
        }

        Log.e(tag, "=== FIM: NENHUM VÍDEO ENCONTRADO ===")
        return emptyList()
    }

    /**
     * Segue redirects manualmente lendo o header Location em cada passo.
     * Retorna a URL final (do googlevideo).
     */
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
                Log.e(tag, "  Hop $hops: ${currentUrl.take(150)}")

                val request = okhttp3.Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", uaDesktop)
                    .header("Referer", referer)
                    .header("Origin", "https://superhentais.com.br")
                    .header("Accept", "*/*")
                    .build()

                val resp = noRedirect.newCall(request).execute()
                try {
                    Log.e(tag, "  → status=${resp.code} ct=${resp.header("Content-Type")}")

                    if (resp.isRedirect) {
                        val location = resp.header("Location")
                        Log.e(tag, "  → Location: ${location?.take(200)}")
                        if (location.isNullOrBlank()) return currentUrl

                        // Resolve URL relativa
                        currentUrl = if (location.startsWith("http")) {
                            location
                        } else {
                            okhttp3.HttpUrl.parse(currentUrl)?.resolve(location)?.toString()
                                ?: return currentUrl
                        }
                        continue
                    }

                    // Não é redirect: verifica se é vídeo
                    val ct = resp.header("Content-Type") ?: ""
                    if (ct.contains("video") || ct.contains("octet-stream")) {
                        Log.e(tag, "  → Content-Type de vídeo, retornando URL atual")
                        return currentUrl
                    }

                    // Retorna URL atual (pode ser HTML com player)
                    return currentUrl
                } finally {
                    resp.close()
                }
            }

            currentUrl
        } catch (e: Exception) {
            Log.e(tag, "  Exceção ao resolver redirect: ${e.message}")
            null
        }
    }
}
