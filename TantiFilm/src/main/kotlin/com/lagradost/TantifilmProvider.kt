package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.SupervideoExtractor
import com.lagradost.extractors.DroploadExtractor
import com.lagradost.extractors.MixdropExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Tantifilm : MainAPI() {
    override var mainUrl = "https://tantifilm.diy"
    override var name = "Tantifilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/serie-tv/" to "Serie TV",
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
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western",
        "$mainUrl/netflix-streaming/" to "Netflix"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("div.mediaWrapAlt, div.film").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val title = it.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val poster = fixUrl(img)

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select("div.mediaWrapAlt, div.film").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val title = it.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val poster = fixUrl(img)

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)
        val doc = res.document
        val html = res.text

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Sconosciuto"
        val poster = doc.selectFirst("img[data=movie-poster]")?.attr("src")?.let { fixUrl(it) }
        val trama = doc.selectFirst("div.content-left-film p")?.text()?.trim()
        val episodes = parseEpisodes(doc, html, url)

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = trama
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = trama
            }
        }
    }

    private suspend fun parseEpisodes(doc: Document, rawHtml: String, referer: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val supportedHosts = listOf("supervideo", "dropload", "mixdrop")

        val seasonDivs = doc.select("div.tab-pane[id^=season-]")
        if (seasonDivs.isNotEmpty()) {
            Log.d("Tantifilm", "🔍 Parsing Serie TV structure")
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
                        Log.d("Tantifilm", "📺 Episodio $epNum trovato con ${links.size} link")
                        episodes.add(Episode(combined).apply {
                            this.name = "$epNum - $epTitle"
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        } else {
            Log.d("Tantifilm", "🎬 Parsing as FILM...")
            val rows = doc.select("table#download-table tr[onclick]")
            val sources = mutableListOf<String>()

            if (rows.isEmpty()) {
                Log.d("Tantifilm", "🔎 Nessuna riga tabella, provo script JS...")
                val script = doc.selectFirst("script[src~=(?i)guardahd\\.stream/(ldl|ddl)/tt\\d+]")?.attr("src")
                if (script != null) {
                    val scriptUrl = when {
                        script.startsWith("//") -> "https:$script"
                        script.startsWith("/") -> "https://guardahd.stream$script"
                        else -> script
                    }
                    Log.d("Tantifilm", "📥 Scarico script JS: $scriptUrl")
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

                    Log.d("Tantifilm", "📊 JS: trovate ${jsRows.size} righe tabella")

                    for (row in jsRows) {
                        val onclick = row.attr("onclick")
                        val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                        if (link == null || link.contains("mostraguarda.stream")) continue
                        val host = supportedHosts.find { link.contains(it) } ?: continue
                        Log.d("Tantifilm", "▶️ Link trovato [$host]: $link")
                        sources.add("$link|$host")
                    }
                } else {
                    Log.d("Tantifilm", "❌ Script guardahd.stream non trovato")
                }
            } else {
                Log.d("Tantifilm", "📊 Tabella presente nel DOM: ${rows.size} righe")
                for (row in rows) {
                    val onclick = row.attr("onclick")
                    val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                    if (link == null || link.contains("mostraguarda.stream")) continue
                    val host = supportedHosts.find { link.contains(it) } ?: continue
                    Log.d("Tantifilm", "▶️ Link trovato [$host]: $link")
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
                Log.e("Tantifilm", "loadLinks -> ERROR: $url - ${e.message}")
            }
        }

        return found
    }
}
