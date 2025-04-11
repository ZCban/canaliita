package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class CalcioStreamingLatProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://calciostreaming.lat"
    override var name = "CalcioStreamingLat"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val links = document.select("a.small")

        val items = links.mapNotNull { link ->
            try {
                val href = link.attr("href")
                if ("embed.php" in href) return@mapNotNull null

                val text = link.selectFirst("b")?.text()?.trim() ?: return@mapNotNull null
                val split = text.split(" ", limit = 2)
                if (split.size < 2) return@mapNotNull null

                val time = split[0]
                val teams = split[1]

                newLiveSearchResponse(
                    name = "$teams - $time",
                    url = fixUrl(href),
                    type = TvType.Live
                ) {
                    posterUrl = "https://i.imgur.com/svLxgqC.png"
                }
            } catch (e: Exception) {
                null
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Eventi in diretta", items, isHorizontalImages = true))
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Prende la pagina e segue eventuali redirect per avere il dominio corretto
        val response = app.get(url)
        val finalUrl = response.url

        return newLiveStreamLoadResponse(
            name = "Guarda Live",
            url = finalUrl,
            dataUrl = finalUrl
        ) {
            posterUrl = "https://i.imgur.com/svLxgqC.png"
            plot = "Stream live da CalcioStreamingLat"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val doc = response.document
        val html = doc.toString()
        val finalUrl = response.url
        val m3u8Regex = Regex("""https?:\/\/[^\s"'\\]+\.m3u8""")

        val headers = mapOf("Referer" to finalUrl)

        // 1. Link .m3u8 diretti
        m3u8Regex.findAll(html).map { it.value }.distinct().forEach { m3u8 ->
            callback(
                ExtractorLink(
                    this.name,
                    "Diretto",
                    m3u8,
                    finalUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers
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
                            isM3u8 = true,
                            headers = headers
                        )
                    )
                }
            } catch (_: Exception) {
                // fetch fallita, ignorata
            }
        }

        return true
    }
}
