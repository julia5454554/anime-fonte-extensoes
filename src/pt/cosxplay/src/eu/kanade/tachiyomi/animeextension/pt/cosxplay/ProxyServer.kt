package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

class ProxyServer(private val client: OkHttpClient) : NanoHTTPD("127.0.0.1", 0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/proxy") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        val params = session.parameters
        val urlParam = params["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing url")
        val refParam = params["ref"]?.firstOrNull()

        val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
        val referer = refParam?.let { URLDecoder.decode(it, "UTF-8") } ?: "https://cosxplay.com/"

        val reqHeaders = Headers.Builder()
            .set("User-Agent", UA)
            .set("Referer", referer)
            .set("Origin", "https://cosxplay.com")
            .set("Accept", "*/*")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val reqBuilder = Request.Builder().url(targetUrl).headers(reqHeaders).get()
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null) reqBuilder.header("Range", rangeHeader)

        Log.d(TAG, "→ ${if (rangeHeader != null) "RANGE=$rangeHeader " else ""}$targetUrl")

        val upstream = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            Log.e(TAG, "upstream exception", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "err")
        }

        Log.d(TAG, "← ${upstream.code} (len=${upstream.header("Content-Length")}, range=${upstream.header("Content-Range")})")

        if (upstream.code >= 400) {
            val errMsg = "upstream ${upstream.code}"
            upstream.close()
            return newFixedLengthResponse(Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", errMsg)
        }

        val body = upstream.body
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "no body")

        val mime = upstream.header("Content-Type") ?: "video/mp4"
        val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val length = body.contentLength()

        val nano = if (length >= 0) {
            newFixedLengthResponse(status, mime, body.byteStream(), length)
        } else {
            newChunkedResponse(status, mime, body.byteStream())
        }
        upstream.header("Content-Range")?.let { nano.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { nano.addHeader("Accept-Ranges", it) }
        return nano
    }

    companion object {
        private const val TAG = "CosXplayProxy"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
