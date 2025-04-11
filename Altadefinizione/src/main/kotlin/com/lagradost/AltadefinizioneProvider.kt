package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.SupervideoExtractor
import com.lagradost.extractors.DroploadExtractor
import com.lagradost.extractors.MixdropExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Altadefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionegratis.store"
    override var name = "Altadefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Tutti i Film",
        "$mainUrl/cinema/" to "Cinema",
        "$mainUrl/netflix-streaming/" to "Netflix",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crimine",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/erotico/" to "Erotico",
        "$mainUrl/famiglia/" to "Familiare",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("div.wrapperImage").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val title = it.selectFirst("h2.titleFilm a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val poster = fixUrl(img)
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", params = mapOf(
            "story" to query,
            "do" to "search",
            "subaction" to "search",
            "titleonly" to "3"
        )).document

        return doc.select("div.wrapperImage").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val title = it.selectFirst("h2.titleFilm a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val poster = fixUrl(img)
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Sconosciuto"
        val poster = doc.selectFirst("div.col-lg-3.thumbPhoto img")?.attr("src")?.let { fixUrl(it) }
        val trama = doc.selectFirst("p#sfull")?.ownText()?.trim()
        val anno = doc.select("li").firstOrNull { it.selectFirst("label")?.text()?.contains("Anno") == true }
            ?.ownText()?.trim()
        val qualita = doc.selectFirst("span#def")?.attr("data-value")?.trim()
        val episodes = getEpisodes(doc)

        val tags = listOfNotNull("Streaming", "Italiano", qualita?.takeIf { it.isNotBlank() }, anno?.let { "Anno: $it" })

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = trama
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = trama
                this.tags = tags
            }
        }
    }

    private suspend fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val supportedHosts = listOf("supervideo", "dropload", "mixdrop", "doodstream")

        val seasonDivs = doc.select("div.tab-pane[id^=season-]")
        if (seasonDivs.isNotEmpty()) {
            for (seasonDiv in seasonDivs) {
                val seasonNum = seasonDiv.attr("id").removePrefix("season-").toIntOrNull() ?: continue
                for (li in seasonDiv.select("ul > li")) {
                    val aTag = li.selectFirst("a[data-link]") ?: continue
                    val epNum = aTag.attr("data-num").toIntOrNull()
                    val epTitle = aTag.attr("data-title").ifBlank { "Episodio $epNum" }
                    val mirrors = li.select("div.mirrors a.mr")

                    val links = mirrors.mapNotNull {
                        val url = it.attr("data-link")
                        val host = supportedHosts.find { h -> url.contains(h) }
                        if (host != null) "$url|$host" else null
                    }

                    if (links.isNotEmpty()) {
                        val combined = links.joinToString("||")
                        episodes.add(Episode(combined).apply {
                            this.name = "$epNum - $epTitle"
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        } else {
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
                    "mixdrop" -> {
                        MixdropExtractor().getUrl(url, null, subtitleCallback) {
                            callback(ExtractorLink("Mixdrop", "Mixdrop", it.url, it.referer, it.quality, it.isM3u8))
                        }
                        found = true
                    }
                }
            } catch (e: Exception) {
                Log.e("Altadefinizione", "loadLinks -> ERROR: $url - ${e.message}")
            }
        }

        return found
    }
}
