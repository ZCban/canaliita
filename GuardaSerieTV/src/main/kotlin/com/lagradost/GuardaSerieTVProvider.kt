package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import com.lagradost.extractors.DroploadExtractor
import com.lagradost.extractors.SupervideoExtractor

class GuardaSerieTV : MainAPI() {
    override var mainUrl = "https://guardaserietv.live"
    override var name = "GuardaSerieTV"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/serietv-streaming/" to "Tutte le Serie TV",
        "$mainUrl/serietv-popolari/" to "Popolari",
        "$mainUrl/netflix-gratis/" to "Netflix",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/dramma/" to "Dramma",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantastico/" to "Fantastico",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data.trimEnd('/')}/page/$page"
        Log.d("GuardaSerieTV", "getMainPage -> URL: $url")

        val doc = app.get(url).document
        val items = doc.select(".mlnh-thumb a").mapNotNull {
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt") ?: return@mapNotNull null
            val poster = img.attr("src").replace("/60x85-0-85/", "/400x600-0-85/").let { src -> if (src.startsWith("/")) "$mainUrl$src" else src }

            Log.d("GuardaSerieTV", "getMainPage -> Found: $title")

            newTvSeriesSearchResponse(title, it.attr("href")) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("GuardaSerieTV", "search -> Query: $query")

        val doc = app.get(
            "$mainUrl/index.php",
            params = mapOf(
                "story" to query,
                "do" to "search",
                "subaction" to "search"
            )
        ).document

        return doc.select(".mlnh-thumb a").mapNotNull {
            val img = it.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt") ?: return@mapNotNull null
            val poster = img.attr("src").replace("/60x85-0-85/", "/400x600-0-85/").let { src -> if (src.startsWith("/")) "$mainUrl$src" else src }

            Log.d("GuardaSerieTV", "search -> Result: $title")

            newTvSeriesSearchResponse(title, it.attr("href")) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("GuardaSerieTV", "load -> URL: $url")

        val document = app.get(url).document
        val title = document.selectFirst("h1")!!.text().removeSuffix(" streaming")
        val description = document.selectFirst("div.tv_info_right")?.textNodes()?.joinToString("")?.removeSuffix("!")?.trim()
        val rating = document.selectFirst("span.post-ratings")?.text()
        val year = document.select("div.tv_info_list > ul")
            .find { it.text().contains("Anno") }
            ?.text()
            ?.substringBefore("-")
            ?.filter { it.isDigit() }
            ?.toIntOrNull()

        val poster = Regex("poster: '(.*)'")
            .find(document.html())
            ?.groups?.lastOrNull()?.value
            ?.let { fixUrl(it) }
            ?: fixUrl(document.selectFirst("#cover")!!.attr("src"))

        Log.d("GuardaSerieTV", "load -> Title: $title, Year: $year, Rating: $rating")
        Log.d("GuardaSerieTV", "load -> Description: ${description?.take(100)}...")
        Log.d("GuardaSerieTV", "load -> Poster: $poster")

        val episodes = getEpisodes(document)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = listOfNotNull("Streaming", "Italiano", rating?.let { "Rating: $it" })
        }
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        doc.select("div[id^='season']").forEach { seasonDiv ->
            val seasonId = seasonDiv.attr("id")
            val seasonNumber = Regex("""season(\d+)""").find(seasonId)?.groupValues?.get(1)?.toIntOrNull()
            Log.d("GuardaSerieTV", "getEpisodes -> Season: $seasonNumber")

            seasonDiv.select("ul li").forEach { li ->
                val label = li.selectFirst("a[data-num]")?.text() ?: return@forEach
                val episodeNum = Regex("""\d+x(\d+)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val links = li.select("a.mr")
                    .mapNotNull { it.attr("data-link") }
                    .filter { it.contains("supervideo") || it.contains("dropload") }

                Log.d("GuardaSerieTV", "getEpisodes -> Episode: $label, Links: ${links.size}")

                if (links.isNotEmpty()) {
                    val combined = links.joinToString("||") { link ->
                        "$link|${if ("supervideo" in link) "supervideo" else "dropload"}"
                    }
                    episodes.add(
                        Episode(combined).apply {
                            name = label
                            season = seasonNumber
                            episode = episodeNum
                        }
                    )
                }
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
        Log.d("GuardaSerieTV", "loadLinks -> Data: $data")

        val links = data.split("||").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        var foundLink = false

        for ((url, source) in links) {
            Log.d("GuardaSerieTV", "loadLinks -> Trying: $url, Source: $source")
            try {
                when (source) {
                    "supervideo" -> {
                        SupervideoExtractor().getUrl(url, null, subtitleCallback) {
                            Log.d("GuardaSerieTV", "loadLinks -> Supervideo success: ${it.url}")
                            callback(
                                ExtractorLink(
                                    name = "Supervideo",
                                    source = "Supervideo",
                                    url = it.url,
                                    referer = it.referer,
                                    quality = it.quality,
                                    isM3u8 = it.isM3u8
                                )
                            )
                        }
                        foundLink = true
                    }
                    "dropload" -> {
                        DroploadExtractor().getUrl(url, null, subtitleCallback) {
                            Log.d("GuardaSerieTV", "loadLinks -> Dropload success: ${it.url}")
                            callback(
                                ExtractorLink(
                                    name = "Dropload",
                                    source = "Dropload",
                                    url = it.url,
                                    referer = it.referer,
                                    quality = it.quality,
                                    isM3u8 = it.isM3u8
                                )
                            )
                        }
                        foundLink = true
                    }
                }
            } catch (e: Exception) {
                Log.e("GuardaSerieTV", "loadLinks -> Error loading: $url - ${e.message}")
            }
        }

        return foundLink
    }
}
