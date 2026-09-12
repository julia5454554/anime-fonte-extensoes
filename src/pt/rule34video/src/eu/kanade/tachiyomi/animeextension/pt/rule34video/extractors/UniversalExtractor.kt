package eu.kanade.tachiyomi.animeextension.pt.rule34video.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import java.net.URLDecoder

class UniversalExtractor {

    private val tag = "Rule34Video-Extractor"

    // Pega o conteúdo de: const obfuscated = "...";
    private val obfuscatedRegex = """obfuscated\s*=\s*"([^"]+)"""".toRegex()

    fun videosFromHtml(html: String, pageUrl: String, baseHeaders: Headers): List<Video> {
        val match = obfuscatedRegex.find(html)
        if (match == null) {
            Log.e(tag, "Bloco 'obfuscated' não encontrado na página.")
            return emptyList()
        }

        val realUrl = deobfuscate(match.groupValues[1])
        if (realUrl.isNullOrBlank()) {
            Log.e(tag, "Falha ao desofuscar URL.")
            return emptyList()
        }

        Log.e(tag, "URL extraída: $realUrl")

        val videoHeaders = baseHeaders.newBuilder()
            .add("Accept", "*/*")
            .add("Referer", pageUrl)
            .build()

        // Site entrega apenas 1 qualidade por vídeo.
        val quality = detectQuality(realUrl)
        return listOf(Video(realUrl, quality, realUrl, videoHeaders))
    }

    /**
     * Reproduz o algoritmo JS:
     *   const decodedBase64 = atob(decodeURIComponent(obfuscated.split("").reverse().join("")));
     *   const realUrl = decodeURIComponent(decodedBase64.split("|")[0]);
     */
    private fun deobfuscate(obfuscated: String): String? = try {
        // 1) reverse
        val reversed = obfuscated.reversed()

        // 2) URL-decode (remove %XX escapados pelo JS)
        val urlDecoded = URLDecoder.decode(reversed, "UTF-8")

        // 3) Base64-decode
        val bytes = Base64.decode(urlDecoded, Base64.DEFAULT)

        // 4) bytes → string (o conteúdo é URL-encoded de novo + "|salt")
        val asLatin1 = String(bytes, Charsets.ISO_8859_1)

        // 5) pega só a parte antes do "|" e dá URL-decode final
        val rawUrl = asLatin1.substringBefore("|")
        val finalUrl = URLDecoder.decode(rawUrl, "UTF-8").trim()

        finalUrl.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        Log.e(tag, "Erro no deobfuscate: ${e.message}")
        null
    }

    private fun detectQuality(url: String): String = when {
        url.contains("1080p", ignoreCase = true) -> "1080p"
        url.contains("720p", ignoreCase = true) -> "720p"
        url.contains("480p", ignoreCase = true) -> "480p"
        url.contains("360p", ignoreCase = true) -> "360p"
        else -> "Default"
    }
}
