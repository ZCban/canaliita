package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.DroploadExtractor
import com.lagradost.extractors.SupervideoExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class FilmSenzaLimiti : MainAPI() {
    override var mainUrl = "https://filmsenzalimiti.food"
    override var name = "FilmSenzaLimiti"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/erotico/" to "Erotico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/spionaggio/" to "Spionaggio",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western",
        "$mainUrl/cinema/" to "Cinema"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data.trimEnd('/')}/page/$page/"
        Log.d("FilmSenzaLimiti", "getMainPage -> URL: $url")
        val doc = app.get(url).document
        val items = doc.select("a[href*='/guarda/']").mapNotNull { aTag ->
            val titleDiv = aTag.selectFirst("div.title") ?: return@mapNotNull null
            val imgDiv = aTag.selectFirst("div[style*='background-image']") ?: return@mapNotNull null
            val style = imgDiv.attr("style")
            val imgMatch = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            val poster = imgMatch?.let { fixUrl(it) } ?: return@mapNotNull null
            val title = titleDiv.text().trim()
            val href = fixUrl(aTag.attr("href"))

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php",
            params = mapOf("story" to query, "do" to "search", "subaction" to "search")
        ).document

        return doc.select("a[href*='/guarda/']").mapNotNull { aTag ->
            val titleDiv = aTag.selectFirst("div.title") ?: return@mapNotNull null
            val imgDiv = aTag.selectFirst("div[style*='background-image']") ?: return@mapNotNull null
            val style = imgDiv.attr("style")
            val imgMatch = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            val poster = imgMatch?.let { fixUrl(it) } ?: return@mapNotNull null
            val title = titleDiv.text().trim()
            val href = fixUrl(aTag.attr("href"))

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("FilmSenzaLimiti", "load -> URL: $url")
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.removeSuffix(" streaming") ?: "Sconosciuto"
        val poster = doc.selectFirst("img.movieimg")?.attr("src")?.let { fixUrl(it) }
        val rating = doc.selectFirst("div.imdb span.rating")?.text()
        val infoDiv = doc.selectFirst("#info")
        val trama = if (infoDiv?.html()?.contains("<br>") == true) {
            infoDiv.html().split("<br>", limit = 2).getOrNull(1)?.let {
                Jsoup.parse(it).text().trim()
            }
        } else {
            infoDiv?.text()?.replace("+info", "")?.trim()
        }

        val episodes = getEpisodes(doc)
        Log.d("FilmSenzaLimiti", "load -> Found ${episodes.size} episode(s)")

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = trama
                this.tags = listOfNotNull("Streaming", "Italiano", rating?.let { "Rating: $it" })
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = trama
                this.tags = listOfNotNull("Streaming", "Italiano", rating?.let { "Rating: $it" })
            }
        }
    }

    private suspend fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val supportedHosts = listOf("supervideo", "dropload", "mixdrop", "doodstream")

        val seasonDivs = doc.select("div.tab-pane.fade[id^='season-']")
        if (seasonDivs.isNotEmpty()) {
            Log.d("FilmSenzaLimiti", "getEpisodes -> Serie TV rilevata")
            for (seasonDiv in seasonDivs) {
                val seasonNum = seasonDiv.attr("id").removePrefix("season-").toIntOrNull() ?: continue
                for (li in seasonDiv.select("li")) {
                    val epAnchor = li.selectFirst("a[data-num]") ?: continue
                    val epNum = epAnchor.attr("data-num").toIntOrNull()
                    val epTitle = epAnchor.attr("data-title").takeIf { it.isNotBlank() } ?: epAnchor.text()

                    val links = li.select("div.mirrors2 a.mr")
                        .mapNotNull { it.attr("data-link").takeIf { link -> supportedHosts.any { host -> link.contains(host) } } }

                    if (links.isNotEmpty()) {
                        val combined = links.joinToString("||") { "$it|${supportedHosts.find { host -> it.contains(host) }}" }
                        episodes.add(Episode(combined).apply {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        } else {
            Log.d("FilmSenzaLimiti", "getEpisodes -> Nessuna stagione trovata, controllo film")
            val rows = doc.select("table#download-table tr[onclick]")
            val sources = mutableListOf<String>()

            if (rows.isEmpty()) {
                val script = doc.selectFirst("script[src*=\"guardahd.stream/ddl/\"]")?.attr("src")
                if (script != null) {
                    val scriptUrl = when {
                        script.startsWith("//") -> "https:$script"
                        script.startsWith("/") -> "https://guardahd.stream$script"
                        else -> script
                    }

                    val jsText = app.get(scriptUrl).text
                    val htmlParts = jsText.lines()
                        .filter { it.trim().startsWith("document.write(") }
                        .map {
                            it.trim()
                                .removePrefix("document.write(\"")
                                .removeSuffix("\");")
                                .replace("\\'", "'")
                        }

                    val fakeHtml = htmlParts.joinToString("")
                    val generatedDoc = Jsoup.parse(fakeHtml)
                    val jsRows = generatedDoc.select("table#download-table tr[onclick]")

                    for (row in jsRows) {
                        val onclick = row.attr("onclick")
                        val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                        if (link == null || link.contains("mostraguarda.stream")) continue
                        val host = supportedHosts.find { link.contains(it) } ?: continue
                        sources.add("$link|$host")
                    }
                }
            } else {
                for (row in rows) {
                    val onclick = row.attr("onclick")
                    val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                    if (link == null || link.contains("mostraguarda.stream")) continue
                    val host = supportedHosts.find { link.contains(it) } ?: continue
                    sources.add("$link|$host")
                }
            }

            if (sources.isNotEmpty()) {
                val combined = sources.joinToString("||")
                episodes.add(Episode(combined).apply { name = "Film Completo" })
                Log.d("FilmSenzaLimiti", "getEpisodes -> Aggiunto 1 episodio con ${sources.size} sorgenti")
            }
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = data.split("||").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        var found = false
        for ((url, source) in links) {
            try {
                Log.d("FilmSenzaLimiti", "loadLinks -> Trying: $url [$source]")
                when (source) {
                    "supervideo" -> {
                        SupervideoExtractor().getUrl(url, null, subtitleCallback) {
                            callback(ExtractorLink("Supervideo", "Supervideo", it.url, it.referer, it.quality, it.isM3u8))
                        }
                        found = true
                    }
                    "dropload" -> {
                        DroploadExtractor().getUrl(url, null, subtitleCallback) {
                            callback(ExtractorLink("Dropload", "Dropload", it.url, it.referer, it.quality, it.isM3u8))
                        }
                        found = true
                    }
                }
            } catch (e: Exception) {
                Log.e("FilmSenzaLimiti", "loadLinks -> ERROR: $url - ${e.message}")
            }
        }

        return found
    }
}
