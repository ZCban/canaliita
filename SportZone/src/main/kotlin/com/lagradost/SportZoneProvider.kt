package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class SportZoneProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://sportzone.lat"
    override var name = "SportZone"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val events = document.select(".list-group-item")

        val homeItems = events.mapNotNull { element ->
            try {
                val link = element.parent()?.attr("href") ?: return@mapNotNull null
                val teams = element.selectFirst(".cat_item")?.text()?.trim() ?: return@mapNotNull null
                val time = element.selectFirst(".da")?.text()?.trim() ?: ""
                LiveSearchResponse(
                    "$teams - $time",
                    fixUrl(link),
                    this.name,
                    TvType.Live,
                    "https://i.imgur.com/svLxgqC.png"
                )
            } catch (e: Exception) {
                null
            }
        }

        return HomePageResponse(
            listOf(HomePageList("Eventi in diretta", homeItems, isHorizontalImages = true))
        )
    }

    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            "Guarda Live",
            url,
            this.name,
            url,
            "https://i.imgur.com/svLxgqC.png",
            plot = "Stream live da SportZone"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.toString()
        val m3u8Regex = Regex("""https?:\/\/[^\s"'\\]+\.m3u8""")

        // 1. Link diretti .m3u8
        m3u8Regex.findAll(html).map { it.value }.distinct().forEach { m3u8 ->
            callback(
                ExtractorLink(
                    this.name,
                    "Diretto",
                    m3u8,
                    data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        // 2. fetch() JS
        val fetchRegex = Regex("""fetch\((.*?)\)""")
        val fetchCalls = fetchRegex.findAll(html).mapNotNull { match ->
            val raw = match.groupValues[1].trim()
            try {
                if (raw.startsWith("{")) {
                    val fixed = raw.replace("'", "\"").replace(Regex("""(\w+):"""), "\"$1\":")
                    val json = JSONObject(fixed)
                    val url = json.optString("url")
                    val method = json.optString("method", "GET").uppercase()
                    if (url.isNotBlank()) Pair(method, url) else null
                } else if (raw.startsWith("\"")) {
                    Pair("GET", raw.removeSurrounding("\""))
                } else null
            } catch (_: Exception) {
                null
            }
        }

        for ((method, fetchUrl) in fetchCalls) {
            try {
                val responseText = if (method == "POST") {
                    app.post(fixUrl(fetchUrl), data = mapOf<String, String>()).text
                } else {
                    app.get(fixUrl(fetchUrl)).text
                }

                m3u8Regex.findAll(responseText).map { it.value }.distinct().forEach { m3u8 ->
                    callback(
                        ExtractorLink(
                            this.name,
                            "Fetch",
                            m3u8,
                            fetchUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            } catch (_: Exception) {
                // ignora fetch fallite
            }
        }

        return true
    }
}
