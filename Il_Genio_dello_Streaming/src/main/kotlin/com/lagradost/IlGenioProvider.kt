package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.SupervideoExtractor
import com.lagradost.extractors.MixdropExtractor
import com.lagradost.extractors.DroploadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class IlGenio : MainAPI() {
    override var mainUrl = "https://ilgeniodellostreaming.beer"
    override var name = "Il Genio dello Streaming"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
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
        "$mainUrl/fantastico/" to "Fantastico",
        "$mainUrl/intrattenimento/" to "Intrattenimento",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/miniserie-tv/" to "Miniserie TV",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/musicale/" to "Musicale",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/reality/" to "Reality",
        "$mainUrl/sitcom/" to "Sitcom",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/soap-opera/" to "Soap Opera",
        "$mainUrl/spionaggio/" to "Spionaggio",
        "$mainUrl/sentimentale/" to "Sentimentale",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/talk-show/" to "Talk Show",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/tv-show/" to "TV Show",
        "$mainUrl/talent-show/" to "Talent Show",
        "$mainUrl/western/" to "Western",
        "$mainUrl/cinema/" to "Cinema",
        "$mainUrl/film-natalizi-streaming/" to "Film Natalizi",
        "$mainUrl/sub-ita/" to "Sub-ITA",
        "$mainUrl/netflix-streaming/" to "Netflix",
        "$mainUrl/prossimamente/" to "Prossimamente"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if ("?" in request.data) "${request.data}&page=$page" else "${request.data}page/$page/"
        val doc = app.get(url).document

        val items = doc.select("div.poster").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt")?.trim().orEmpty()
            val poster = fixUrl(img.attr("src"))
            val href = fixUrl(aTag.attr("href"))
            newMovieSearchResponse(title, href) { this.posterUrl = poster }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$encoded"
        val doc = app.get(url).document

        return doc.select("div.poster").mapNotNull {
            val aTag = it.selectFirst("a[href]") ?: return@mapNotNull null
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt")?.trim().orEmpty()
            val poster = fixUrl(img.attr("src"))
            val href = fixUrl(aTag.attr("href"))
            newMovieSearchResponse(title, href) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)
        val doc = res.document
        val html = res.text

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Sconosciuto"
        val poster = doc.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }

        val trama = doc.select("div[itemprop=description].wp-content p").joinToString("\n") {
            it.text().trim()
        }.ifBlank { null }

        val rating = doc.selectFirst("div.starstruck-rating span.dt_rating_vgs")
            ?.text()
            ?.replace(",", ".")
            ?.toDoubleOrNull()

        val dateText = doc.selectFirst("div.extra span.date")?.text()
        val year = dateText?.takeLast(4)?.takeIf { it.all { ch -> ch.isDigit() } }

        val tags = mutableListOf<String>()
        if (year != null) tags.add("Anno: $year")
        if (rating != null) tags.add("IMDb: $rating")

        val episodes = parseEpisodes(doc, html, url)

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

    private suspend fun parseEpisodes(doc: Document, html: String, referer: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val supportedHosts = listOf("supervideo", "mixdrop", "dropload")

        val regex = Regex("""data-num="(\d+)x(\d+)"\s*data-title="([^"]+?)([sS]ub-[iI]ta)?".*?<div class="mirrors"(.*?)<!---""", RegexOption.DOT_MATCHES_ALL)

        for (match in regex.findAll(html)) {
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
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
                episodes.add(
                    newEpisode(combined) {
                        this.name = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} - $title [$lang]"
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) return episodes

        val sources = mutableListOf<String>()
        val script = doc.selectFirst("script[src~=(?i)guardahd\\.stream/(ldl|ddl)/tt\\d+]")?.attr("src")

        if (script != null) {
            val scriptUrl = when {
                script.startsWith("//") -> "https:$script"
                script.startsWith("/") -> "https://guardahd.stream$script"
                else -> script
            }

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
            val rows = generatedDoc.select("table#download-table tr[onclick]")

            for (row in rows) {
                val onclick = row.attr("onclick")
                val link = Regex("""window\.open\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
                if (link == null || link.contains("mostraguarda.stream")) continue
                val host = supportedHosts.find { link.contains(it) } ?: continue
                sources.add("$link|$host")
            }
        } else {
            val rows = doc.select("table#download-table tr[onclick]")
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
            episodes.add(newEpisode(combined) {
                name = "Film Completo"
            })
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
                    "mixdrop" -> {
                        MixdropExtractor().getUrl(url, null, subtitleCallback) {
                            callback(ExtractorLink("Mixdrop", "Mixdrop", it.url, it.referer, it.quality, it.isM3u8))
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
                Log.e("IlGenio", "❌ loadLinks error: ${e.message}")
            }
        }

        return found
    }
}
