package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.SupervideoExtractor
import com.lagradost.extractors.DroploadExtractor
import com.lagradost.extractors.MixdropExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Casacinema : MainAPI() {
    override var mainUrl = "https://casacinema.living"
    override var name = "CasaCinema"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/cinema/" to "Cinema"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if ("?" in request.data) "${request.data}&page=$page" else "${request.data}page/$page/"
        val doc = app.get(url).document

        val items = doc.select("div.posts").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val titleDiv = it.selectFirst("div.title") ?: return@mapNotNull null
            val bgDiv = it.selectFirst("div[style*='background-image']") ?: return@mapNotNull null

            val href = fixUrl(aTag.attr("href"))
            val title = titleDiv.text().trim()
            val style = bgDiv.attr("style")
            val poster = Regex("background-image:\\s*url\\((.*?)\\)").find(style)?.groupValues?.get(1)?.let { fixUrl(it) } ?: return@mapNotNull null

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/cinema/?story=$encodedQuery&do=search&subaction=search"
        val doc = app.get(url).document

        return doc.select("div.posts").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val titleDiv = it.selectFirst("div.title") ?: return@mapNotNull null
            val bgDiv = it.selectFirst("div[style*='background-image']") ?: return@mapNotNull null

            val href = fixUrl(aTag.attr("href"))
            val title = titleDiv.text().trim()
            val style = bgDiv.attr("style")
            val poster = Regex("background-image:\\s*url\\((.*?)\\)").find(style)?.groupValues?.get(1)?.let { fixUrl(it) } ?: return@mapNotNull null

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
        val poster = doc.selectFirst("img.thumbnail")?.attr("src")?.let { fixUrl(it) }
        val trama = doc.selectFirst("div.pad > p")?.text()?.trim()
        val rating = doc.selectFirst("div.rating .value")?.text()?.replace(",", ".")?.toDoubleOrNull()
        val annoText = doc.select("div.element").firstOrNull { it.text().contains(" - ") }?.text()
        val anno = annoText?.split(" - ")?.firstOrNull()?.trim()?.takeIf { it.all { ch -> ch.isDigit() } }

        val episodes = parseEpisodes(doc, html, url)

        val tags = mutableListOf<String>()
        if (anno != null) tags.add("Anno: $anno")
        if (rating != null) tags.add("IMDb: $rating")

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = trama
                this.tags = tags
                this.rating = rating?.toInt()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = trama
                this.tags = tags
                this.rating = rating?.toInt()
            }
        }
    }

    private suspend fun parseEpisodes(doc: Document, rawHtml: String, referer: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val supportedHosts = listOf("supervideo", "dropload", "mixdrop")

        // Serie TV
        val regex = Regex("""data-num=\"(\d+)x(\d+)\"\s*data-title=\"([^\"]+?)(Sub-ita|sub-ita)?\".*?<div class=\"mirrors\"(.*?)<!---""", RegexOption.DOT_MATCHES_ALL)

        for (match in regex.findAll(rawHtml)) {
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull()
            val title = match.groupValues[3].trim()
            val lang = match.groupValues[4].ifBlank { "ITA" }.uppercase()
            val block = match.groupValues[5]

            val links = Regex("data-link=\"([^\"]+)\"").findAll(block).mapNotNull {
                val link = it.groupValues[1]
                val host = supportedHosts.find { h -> link.contains(h) }
                if (host != null) "$link|$host" else null
            }.toList()

            if (links.isNotEmpty()) {
                val combined = links.joinToString("||")
                episodes.add(Episode(combined).apply {
                    name = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} - $title [$lang]"
                    this.season = season
                    this.episode = episode
                })
            }
        }

        // Film
        if (episodes.isEmpty()) {
            Log.d("CasaCinema", "🎬 Parsing as MOVIE...")

            val sources = mutableListOf<String>()
            val rows = doc.select("table#download-table tr[onclick]")

            if (rows.isNotEmpty()) {
                Log.d("CasaCinema", "📊 Found ${rows.size} row(s) in download-table")

                for (row in rows) {
                    val onclick = row.attr("onclick")
                    val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                    if (link == null || link.contains("mostraguarda.stream")) continue

                    val host = supportedHosts.find { link.contains(it) } ?: continue
                    Log.d("CasaCinema", "▶️ Link trovato [$host]: $link")
                    sources.add("$link|$host")
                }
            } else {
                Log.d("CasaCinema", "🔎 Nessuna riga nella tabella, provo a cercare script JS...")

                val script = doc.selectFirst("script[src~=(?i)guardahd\\.stream/(ldl|ddl)/tt\\d+]")?.attr("src")
                if (script != null) {
                    val scriptUrl = when {
                        script.startsWith("//") -> "https:$script"
                        script.startsWith("/") -> "https://guardahd.stream$script"
                        else -> script
                    }

                    Log.d("CasaCinema", "📥 Scarico script JS: $scriptUrl")

                    val jsText = app.get(scriptUrl, referer = referer).text
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

                    Log.d("CasaCinema", "📊 JS: trovate ${jsRows.size} righe tabella")

                    for (row in jsRows) {
                        val onclick = row.attr("onclick")
                        val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                        if (link == null || link.contains("mostraguarda.stream")) continue

                        val host = supportedHosts.find { link.contains(it) } ?: continue
                        Log.d("CasaCinema", "▶️ Link trovato [$host]: $link")
                        sources.add("$link|$host")
                    }
                } else {
                    Log.d("CasaCinema", "❌ Script JS guardahd.stream non trovato.")
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
                Log.e("CasaCinema", "loadLinks error: ${e.message}")
            }
        }

        return found
    }
}

