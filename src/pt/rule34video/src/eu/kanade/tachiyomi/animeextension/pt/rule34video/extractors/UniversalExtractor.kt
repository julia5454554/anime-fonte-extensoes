package eu.kanade.tachiyomi.animeextension.pt.rule34video.extractors

import android.util.Base64
import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "Rule34Video-Extractor"

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // const obfuscated = "...";
    private val obfuscatedRegex = """obfuscated\s*=\s*"([^"]+)"""".toRegex()

    // <meta property="og:video" content="...">
    private val ogVideoRegex = Regex(
        """<meta[^>]+property=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    // <iframe src="...">
    private val iframeRegex = Regex(
        """<iframe[^>]+src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    // .mp4 solto no HTML
    private val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")

    fun videosFromHtml(html: String, pageUrl: String, baseHeaders: Headers): List<Video> {
        val videos = mutableListOf<Video>()
        Log.e(tag, "=== Iniciando extração | pageUrl=$pageUrl ===")

        // -------- Estratégia 1: obfuscated JS (principal) --------
        obfuscatedRegex.find(html)?.let { match ->
            deobfuscate(match.groupValues[1])?.let { url ->
                Log.e(tag, "[1] URL via obfuscated: $url")
                videos.add(buildVideo(url, pageUrl, baseHeaders))
            } ?: Log.e(tag, "[1] Falhou ao desofuscar.")
        } ?: Log.e(tag, "[1] Bloco 'obfuscated' não encontrado.")

        // -------- Estratégia 2: meta og:video --------
        if (videos.isEmpty()) {
            ogVideoRegex.find(html)?.groupValues?.getOrNull(1)?.let { url ->
                if (url.isNotBlank() && url.startsWith("http")) {
                    Log.e(tag, "[2] URL via og:video: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            } ?: Log.e(tag, "[2] Nenhum og:video encontrado.")
        }

        // -------- Estratégia 3: iframes (Blogger, Streamtape, etc.) --------
        if (videos.isEmpty()) {
            val iframes = iframeRegex.findAll(html).map { it.groupValues[1] }.toList()
            Log.e(tag, "[3] Encontrados ${iframes.size} iframes.")
            for (raw in iframes) {
                if (raw.contains("shockedguard") || raw.contains("ads") || raw.contains("doubleclick")) {
                    continue
                }
                val fullUrl = normalizeIframeUrl(raw, pageUrl)
                Log.e(tag, "[3] Testando iframe: $fullUrl")

                // 3a. Blogger/GoogleVideo direto
                runCatching {
                    val bloggerVideos = runBlocking {
                        bloggerExtractor.videosFromUrl(fullUrl, baseHeaders, "Blogger")
                    }
                    if (bloggerVideos.isNotEmpty()) {
                        Log.e(tag, "[3a] Blogger retornou ${bloggerVideos.size} vídeo(s).")
                        videos.addAll(bloggerVideos)
                        return@runCatching
                    }
                }.onFailure { Log.e(tag, "[3a] Erro Blogger: ${it.message}") }

                // 3b. Baixa o HTML do iframe e tenta achar mp4/obfuscated dentro
                if (videos.isEmpty()) {
                    val innerHtml = fetchHtml(fullUrl, pageUrl) ?: continue
                    obfuscatedRegex.find(innerHtml)?.let { m ->
                        deobfuscate(m.groupValues[1])?.let { url ->
                            Log.e(tag, "[3b] URL via obfuscated no iframe: $url")
                            videos.add(buildVideo(url, pageUrl, baseHeaders))
                        }
                    }
                    if (videos.isEmpty()) {
                        mp4Regex.findAll(innerHtml).forEach { m ->
                            val url = sanitize(m.value)
                            if (videos.none { it.url == url }) {
                                Log.e(tag, "[3b] mp4 achado no iframe: $url")
                                videos.add(buildVideo(url, pageUrl, baseHeaders))
                            }
                        }
                    }
                }
                if (videos.isNotEmpty()) break
            }
        }

        // -------- Estratégia 4: mp4 direto no HTML --------
        if (videos.isEmpty()) {
            mp4Regex.findAll(html).forEach { m ->
                val url = sanitize(m.value)
                if (videos.none { it.url == url }) {
                    Log.e(tag, "[4] mp4 direto no HTML: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            }
        }

        // -------- Estratégia 5: <video><source src="..."> --------
        if (videos.isEmpty()) {
            val sourceRegex = Regex(
                """<source[^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
            sourceRegex.findAll(html).forEach { m ->
                val raw = m.groupValues[1]
                if (raw.startsWith("blob:")) return@forEach // isca
                val url = sanitize(raw)
                if (url.startsWith("http") && videos.none { it.url == url }) {
                    Log.e(tag, "[5] source: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            }
        }

        if (videos.isEmpty()) {
            Log.e(tag, "!!! Nenhuma estratégia retornou vídeo. pageUrl=$pageUrl")
        } else {
            Log.e(tag, "=== Total: ${videos.size} vídeo(s) ===")
        }

        return sortByQuality(videos.distinctBy { it.url })
    }

    // ==================== HELPERS ====================

    private fun buildVideo(url: String, pageUrl: String, baseHeaders: Headers): Video {
        val cleanUrl = sanitize(url)
        val videoHeaders = baseHeaders.newBuilder()
            .add("Accept", "*/*")
            .add("Referer", pageUrl)
            .build()
        val quality = detectQuality(cleanUrl)
        return Video(cleanUrl, quality, cleanUrl, videoHeaders)
    }

    private fun sanitize(url: String): String = url
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("\\/", "/")
        .replace("\\\\", "\\")
        .trimEnd('\\', '"', '\'', ';')

    private fun normalizeIframeUrl(raw: String, pageUrl: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> {
            val origin = Regex("""^(https?://[^/]+)""").find(pageUrl)?.value ?: ""
            origin + raw
        }
        else -> raw
    }

    private fun sortByQuality(videos: List<Video>): List<Video> =
        videos.sortedByDescending { extractResolution(it.quality) }

    private fun extractResolution(label: String): Int {
        val m = Regex("""(\d{3,4})p""").find(label)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun detectQuality(url: String): String = when {
        url.contains("1080p", ignoreCase = true) -> "1080p"
        url.contains("720p", ignoreCase = true) -> "720p"
        url.contains("480p", ignoreCase = true) -> "480p"
        url.contains("360p", ignoreCase = true) -> "360p"
        else -> "Default"
    }

    private fun fetchHtml(url: String, referer: String): String? = try {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", uaDesktop)
            .header("Referer", referer)
            .build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) {
        Log.e(tag, "fetchHtml erro: ${e.message}")
        null
    }

    /**
     * Reproduz o algoritmo JS do site:
     *   const decodedBase64 = atob(decodeURIComponent(obfuscated.split("").reverse().join("")));
     *   const realUrl = decodeURIComponent(decodedBase64.split("|")[0]);
     */
    private fun deobfuscate(obfuscated: String): String? = try {
        val reversed = obfuscated.reversed()
        val urlDecoded = URLDecoder.decode(reversed, "UTF-8")
        val bytes = Base64.decode(urlDecoded, Base64.DEFAULT)
        val asLatin1 = String(bytes, Charsets.ISO_8859_1)
        val rawUrl = asLatin1.substringBefore("|")
        val finalUrl = URLDecoder.decode(rawUrl, "UTF-8").trim()
        finalUrl.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        Log.e(tag, "deobfuscate erro: ${e.message}")
        null
    }
}
