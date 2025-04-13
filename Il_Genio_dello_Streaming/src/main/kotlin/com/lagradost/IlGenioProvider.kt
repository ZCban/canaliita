package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.extractors.SupervideoExtractor
import com.lagradost.extractors.MixdropExtractor
import com.lagradost.extractors.DroploadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class IlGenio : MainAPI() {
    override var mainUrl = "https://ilgeniodellostreaming.beer"
    override var name = "Il Genio dello Streaming"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?story=$encodedQuery&do=search&subaction=search"
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
            ?.text()?.replace(",", ".")?.toDoubleOrNull()

        val dateText = doc.selectFirst("div.extra span.date")?.text()
        val year = dateText?.takeLast(4)?.takeIf { it.all { ch -> ch.isDigit() } }

        val tags = mutableListOf<String>()
        if (year != null) tags.add("Anno: $year")
        if (rating != null) tags.add("IMDb: $rating")

        val episodes = mutableListOf<Episode>()

        val episodeElements = doc.select("div[id^=season-] li")

        Log.d("IlGenio", "📺 Episodi trovati: ${episodeElements.size}")

        for (episode in episodeElements) {
            val aTag = episode.selectFirst("a[data-num][data-title][data-link]") ?: continue

            val num = aTag.attr("data-num") // es: "1x1"
            val season = num.substringBefore("x").toIntOrNull() ?: continue
            val episodeNum = num.substringAfter("x").toIntOrNull() ?: continue
            val titleEp = aTag.attr("data-title").trim()

            val mirrorsBlock = episode.select("div.mirrors a[data-link]")

            val links = mirrorsBlock.mapNotNull {
                val link = it.attr("data-link").trim()
                if (link.isNotBlank()) "$link|${getHostName(link)}" else null
            }

            if (links.isNotEmpty()) {
                val combined = links.joinToString("||")
                Log.d("IlGenio", "🎞️ Episodio $season x $episodeNum - $titleEp con ${links.size} host")
                episodes.add(
                    newEpisode(combined) {
                        name = "${season}x${episodeNum} - $titleEp"
                        this.season = season
                        this.episode = episodeNum
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = trama
                this.tags = tags
                this.rating = rating?.toInt()
            }
        }

        Log.d("IlGenio", "🎬 Nessun episodio trovato, provo parsing JS per film...")

        val script = doc.selectFirst("script[src~=(?i)guardahd\\.stream/(ldl|ddl)/tt\\d+]")?.attr("src")
        var sourceDoc = doc

        if (script != null) {
            val scriptUrl = when {
                script.startsWith("//") -> "https:$script"
                script.startsWith("/") -> "https://guardahd.stream$script"
                else -> script
            }
            val jsText = app.get(scriptUrl, referer = url).text
            val htmlParts = jsText.lines().filter { it.trim().startsWith("document.write(") }.map {
                it.removePrefix("document.write(\"").removeSuffix("\");").replace("\\'", "'")
            }
            val generatedHtml = htmlParts.joinToString("")
            sourceDoc = Jsoup.parse(generatedHtml)
            Log.d("IlGenio", "✅ Trovati ${htmlParts.size} blocchi document.write()")
        } else {
            Log.d("IlGenio", "❌ Nessuno script JS trovato")
        }

        val rows = sourceDoc.select("table#download-table tr[onclick]")
        Log.d("IlGenio", "📊 Righe tabella trovate: ${rows.size}")

        val sources = mutableListOf<String>()

        for (row in rows) {
            val onclick = row.attr("onclick")
            val link = Regex("window\\.open\\(\\s*'([^']+)'").find(onclick)?.groupValues?.get(1)?.trim() ?: continue
            if ("mostraguarda.stream" in link) continue

            val host = getHostName(link)

            val tds = row.select("td.hide-on-mobile")
            val risoluzione = tds.getOrNull(1)?.text()?.trim() ?: ""
            val peso = tds.getOrNull(3)?.text()?.trim() ?: ""
            val audio = tds.getOrNull(4)?.text()?.trim() ?: ""

            val infoParts = listOf(risoluzione, audio, peso).filter { it.isNotBlank() }
            val label = if (infoParts.isNotEmpty()) "$host [${infoParts.joinToString(" - ")}]" else host

            Log.d("IlGenio", "▶️ Link Film: $label => $link")
            sources.add("$link|$host")
        }

        if (sources.isNotEmpty()) {
            val combined = sources.joinToString("||")
            episodes.add(newEpisode(combined) { name = "Film Completo" })
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
            this.posterUrl = poster
            this.plot = trama
            this.tags = tags
            this.rating = rating?.toInt()
        }
    }

    private fun getHostName(link: String): String {
        return try {
            val uri = java.net.URI(link)
            uri.host.removePrefix("www.").split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
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
                Log.d("IlGenio", "🔍 Estraggo da $source - $url")
                when (source.lowercase()) {
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
                    else -> Log.d("IlGenio", "⚠️ Host non gestito: $source")
                }
            } catch (e: Exception) {
                Log.e("IlGenio", "❌ Errore in loadLinks: ${e.message}")
            }
        }

        return found
    }
}
