package eu.kanade.tachiyomi.animeextension.pt.megahentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.megahentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MegaHentai :
    DooPlay(
        "pt-BR",
        "Mega Hentai",
        "https://megahentai.biz",
    ) {

    // Bibliotecas de extração
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // Regex para extrair número do episódio do título
    private val episodeNumberRegex = "(?:Epis[oó]dio\\s+)([0-9]+(?:\\.[0-9]+)?)".toRegex(RegexOption.IGNORE_CASE)

    // ============================== Popular ===============================
    // O site não tem uma página de "populares" óbvia, mantemos a lógica anterior
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("article.item, div.item")
        val animes = items.map { parseAnimeFromCard(it) }
        
        // Seletor de próxima página padrão do DooPlay para listagens de anime
        val hasNextPage = document.select(".pagination span.current + a, .pagination a:contains(arrow_pag)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest (CORRIGIDO) ===============================
    
    // CORREÇÃO: Seletor para o botão de paginação na página de lançamentos (numérica)
    // Procuramos o span atual e vemos se há um link 'inactive' logo após
    override fun latestUpdatesNextPageSelector() = "div.pagination span.current + a.inactive"

    override fun latestUpdatesRequest(page: Int): Request {
        // CORREÇÃO: A rota correta de lançamentos é /assistir-hentai-online/
        val url = if (page == 1) {
            "$baseUrl/assistir-hentai-online/"
        } else {
            "$baseUrl/assistir-hentai-online/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Na página de episódios, os itens geralmente estão em div.epi
        val items = document.select("div.epi article, article.item, div.item")
        
        val animes = items.map { parseAnimeFromCard(it, isEpisodeCard = true) }
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeFromCard(element: Element, isEpisodeCard: Boolean = false): SAnime {
        val anime = SAnime.create()
        
        // Seletores variam ligeiramente entre card de anime e card de episódio
        val titleSelector = if (isEpisodeCard) ".data h3 a, h2 a" else ".data h3 a"
        anime.title = element.select(titleSelector).text().trim()
        
        val url = element.select(".poster a, a.w-full").attr("href")
        anime.setUrlWithoutDomain(url)
        
        val img = element.select(".poster img, img.w-full").first()
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src") ?: ""
        return anime
    }

    // =========================== Anime Details ============================
    // MegaHentai tem uma estrutura Info bem DooPlay
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        // DooPlay às vezes redireciona da página do episódio para a do anime, 
        // a classe base gerencia isso no getRealAnimeDoc.
        val doc = getRealAnimeDoc(document)
        
        val sheader = doc.selectFirst("div.sheader")
        
        if (sheader == null) {
            // Fallback simples se a estrutura sheader não existir
            return SAnime.create().apply {
                setUrlWithoutDomain(doc.location())
                title = doc.selectFirst("h1")?.text()?.trim()
                    ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty() }
                    ?: ""
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
                description = doc.selectFirst("div.wp-content, meta[name='description']")?.text()?.trim()
                status = SAnime.UNKNOWN
            }
        }
        
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            
            val posterImg = sheader.selectFirst("div.poster > img")
            title = posterImg?.attr("alt")?.trim() ?: sheader.selectFirst("div.data > h1")?.text()?.trim() ?: ""
            thumbnail_url = posterImg?.attr("abs:src") ?: posterImg?.attr("src")
            
            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            // Tenta pegar a sinopse
            val synopsis = doc.selectFirst("div#info div.wp-content p")?.text()?.trim()
            
            // Tenta pegar o título alternativo se houver
            val altTitle = sheader.selectFirst("div.data > span.extra-title")?.text()?.trim()
            
            description = buildString {
                if (!altTitle.isNullOrBlank()) append("Título Alternativo: $altTitle\n\n")
                if (!synopsis.isNullOrBlank()) append(synopsis)
            }
            
            status = SAnime.UNKNOWN
        }
    }

    // ============================ Episodes List ============================
    // Usamos a implementação padrão do DooPlay, que funciona bem se getRealAnimeDoc funcionar.
    // Ela lê a estrutura div#seasons -> ul.episodios.
    override fun episodeListSelector() = "ul.episodios li"
    
    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")
        val url = link?.attr("href") ?: ""
        episode.setUrlWithoutDomain(url)
        
        val name = link?.text()?.trim() ?: ""
        episode.name = name
        
        // Tenta extrair o número do episódio
        val number = element.selectFirst(".numerando")?.text()?.trim()
            ?.substringBefore("-")?.trim()?.toFloatOrNull()
            ?: episodeNumberRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 0f
            
        episode.episode_number = number
        return episode
    }

    // ============================ Video Links (CORRIGIDO) =============================
    
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        // CORREÇÃO: A lógica DooPlay padrão envolve iterar sobre as opções de player
        // e fazer chamadas AJAX para obter a URL do embed.
        
        // Seleciona todas as opções de player disponíveis na página do episódio
        val playerOptions = document.select("ul#playeroptionsul li")
        
        for (player in playerOptions) {
            val playerListName = player.select("span.title").text().trim()
            
            // Dados necessários para a chamada AJAX do DooPlay
            val post = player.attr("data-post")
            val nume = player.attr("data-nume")
            val type = player.attr("data-type")
            
            if (post.isEmpty() || nume.isEmpty() || type.isEmpty()) continue
            
            // Cria a requisição para a API do DooPlay que retorna a URL do iframe
            val apiUrl = "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume"
            val apiRequest = GET(apiUrl, headers.newBuilder().add("Referer", document.location()).build())
            
            // Executa a chamada AJAX de forma síncrona (dentro do fluxo do Aniyomi)
            runCatching {
                client.newCall(apiRequest).execute().use { apiResponse ->
                    if (!apiResponse.isSuccessful) return@runCatching
                    
                    // A resposta é um JSON, extraímos a embed_url usando Regex simples
                    val responseBody = apiResponse.body.string()
                    val embedUrl = responseBody
                        .substringAfter("\"embed_url\":\"", "")
                        .substringBefore("\"", "")
                        .replace("\\/", "/") // Corrige barras escapadas no JSON
                        
                    if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                        // Com a URL do iframe em mãos, usamos os extratores
                        videoList.addAll(extractVideosFromEmbed(embedUrl, playerListName))
                    }
                }
            }
        }
        
        // Fallback: Tenta pegar o iframe que já estiver carregado na página (menos comum no DooPlay moderno)
        if (videoList.isEmpty()) {
            document.select("div.source-box iframe, div.embed-holder iframe").firstOrNull()?.let { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (src.isNotBlank() && src.startsWith("http")) {
                    videoList.addAll(extractVideosFromEmbed(src, "Iframe Fallback"))
                }
            }
        }
        
        return videoList
    }

    // Função auxiliar para gerenciar os extratores com base na URL do embed
    private fun extractVideosFromEmbed(embedUrl: String, playerName: String): List<Video> {
        return when {
            "blogger.com" in embedUrl || "blogspot.com" in embedUrl -> {
                // BloggerExtractor geralmente retorna várias qualidades (360p, 720p, etc)
                bloggerExtractor.videosFromUrl(embedUrl, headers)
            }
            else -> {
                // Usa o extrator universal para outros domínios (StreamWish, etc)
                universalExtractor.videosFromUrl(embedUrl, headers, playerName)
            }
        }
    }
}
