package eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "SuperHentais-Extractor"

    private val uaMobile = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.d(tag, "=== INÍCIO DA EXTRAÇÃO ===")
        Log.d(tag, "Page URL: $pageUrl")
        Log.d(tag, "Iframe URL: $iframeUrl")

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        // Tenta com 2 UAs diferentes
        val attempts = listOf(
            "360p" to uaMobile,
            "720p" to uaDesktop,
        )

        for ((fallbackLabel, ua) in attempts) {
            Log.d(tag, "--- Tentativa com UA=$fallbackLabel ---")
            val finalUrl = tryFetchVideoUrl(iframeUrl, pageUrl, ua)
            if (finalUrl.isNullOrBlank()) {
                Log.w(tag, "  → Nada retornado")
                continue
            }
            if (!seen.add(finalUrl)) {
                Log.d(tag, "  → URL duplicada, pulando")
                continue
            }

            val realQuality = detectQuality(finalUrl) ?: fallbackLabel
            val videoHeaders = Headers.Builder()
                .add("User-Agent", ua)
                .add("Accept", "*/*")
                .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            videos.add(Video(finalUrl, realQuality, finalUrl, videoHeaders))
            Log.d(tag, "  ✅ ADICIONADO: $realQuality → ${finalUrl.take(120)}...")
        }

        // FALLBACK FINAL: se nada funcionou, entrega o iframe direto pro ExoPlayer
        if (videos.isEmpty()) {
            Log.w(tag, "⚠️ Nenhum redirect funcionou, usando iframe URL como fallback")
            val videoHeaders = Headers.Builder()
                .add("User-Agent", uaDesktop)
                .add("Referer", pageUrl)
                .add("Origin", "https://superhentais.com.br")
                .add("Accept", "*/*")
                .build()
            videos.add(Video(iframeUrl, "Padrão", iframeUrl, videoHeaders))
        }

        Log.d(tag, "=== FIM: ${videos.size} vídeo(s) extraído(s) ===")
        return videos
    }

    /**
     * Tenta múltiplas estratégias para obter a URL final do vídeo:
     *   1. Seguindo redirect com Referer da página
     *   2. Seguindo redirect sem Referer (o Blogger pode esperar isso)
     *   3. Lendo o HTML da resposta (caso o t_param.php retorne HTML com o player)
     *   4. Lendo header Location manualmente (caso followRedirects falhe)
     */
    private fun tryFetchVideoUrl(url: String, referer: String, ua: String): String? {
        // Estratégia 1: com Referer
        Log.d(tag, "Estratégia 1: redirect com Referer")
        tryFetchWithHeaders(url, referer, ua, withReferer = true)?.let {
            Log.d(tag, "  ✅ Estratégia 1 funcionou")
            return it
        }

        // Estratégia 2: sem Referer
        Log.d(tag, "Estratégia 2: redirect sem Referer")
        tryFetchWithHeaders(url, referer, ua, withReferer = false)?.let {
            Log.d(tag, "  ✅ Estratégia 2 funcionou")
            return it
        }

        // Estratégia 3: manual, sem seguir redirects
        Log.d(tag, "Estratégia 3: ler Location manualmente")
        readLocationManually(url, referer, ua)?.let {
            Log.d(tag, "  ✅ Estratégia 3 funcionou")
            return it
        }

        return null
    }

    private fun tryFetchWithHeaders(
        url: String,
        referer: String,
        ua: String,
        withReferer: Boolean,
    ): String? = try {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

        if (withReferer) {
            builder.header("Referer", referer)
            builder.header("Origin", "https://superhentais.com.br")
        }

        client.newCall(builder.build()).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val contentType = resp.header("Content-Type") ?: "?"
            Log.d(tag, "  status=${resp.code} ct=$contentType")
            Log.d(tag, "  finalUrl=${finalUrl.take(150)}")

            // Se houve redirect para uma URL de vídeo, retorna
            if (finalUrl != url && isVideoUrl(finalUrl)) {
                return finalUrl
            }

            // Se a resposta é diretamente um vídeo (200 com content-type de vídeo)
            if (resp.isSuccessful && (contentType.contains("video") || contentType.contains("octet-stream"))) {
                return finalUrl
            }

            // Se o t_param.php retornou HTML, tenta extrair URL de vídeo de dentro
            if (resp.isSuccessful && contentType.contains("html")) {
                val html = resp.body.string()
                extractVideoUrlFromHtml(html)?.let {
                    Log.d(tag, "  URL extraída do HTML: ${it.take(150)}")
                    return it
                }
            }

            null
        }
    } catch (e: Exception) {
        Log.e(tag, "  Exceção: ${e.message}")
        null
    }

    private fun readLocationManually(url: String, referer: String, ua: String): String? = try {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Origin", "https://superhentais.com.br")
            .header("Accept", "*/*")
            .build()

        noRedirect.newCall(request).execute().use { resp ->
            Log.d(tag, "  status=${resp.code}")
            if (resp.isRedirect) {
                val location = resp.header("Location")
                Log.d(tag, "  Location=${location?.take(150)}")
                location
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "  Exceção: ${e.message}")
        null
    }

    private fun extractVideoUrlFromHtml(html: String): String? {
        // Regex para pegar URLs diretas
        val patterns = listOf(
            """(https?://[^\s"'<>]+googlevideo\.com[^\s"'<>]*)""".toRegex(),
            """(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""".toRegex(),
            """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""".toRegex(),
            """"file"\s*:\s*"([^"]+)"""".toRegex(),
            """src\s*:\s*["']([^"']+)["']""".toRegex(),
            """<source[^>]+src=["']([^"']+)["']""".toRegex(),
        )

        for (regex in patterns) {
            val match = regex.find(html) ?: continue
            val raw = match.groupValues.getOrNull(1) ?: continue
            val clean = raw.replace("\\/", "/").trim()
            if (clean.startsWith("http") && isVideoUrl(clean)) {
                return clean
            }
        }
        return null
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com") ||
            url.contains("videoplayback") ||
            url.contains(".mp4") ||
            url.contains(".m3u8") ||
            url.contains(".mpd")
    }

    private fun detectQuality(url: String): String? = when {
        url.contains("itag=37") -> "1080p"
        url.contains("itag=22") -> "720p"
        url.contains("itag=59") -> "480p"
        url.contains("itag=18") -> "360p"
        url.contains("itag=17") -> "144p"
        url.contains("googlevideo") -> "Blogger"
        else -> null
    }
}
