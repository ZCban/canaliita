package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class CalcioCodesProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://calcio.codes"
    override var name = "Calcio Codes"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/streaming-gratis-calcio-1.php").document
        val events = document.select("ul.kode_ticket_list > li")

        val homeItems = events.mapNotNull { element ->
            val teams = element.select("div.ticket_title > h2").joinToString(" VS ") { it.text().trim() }
            val time = element.selectFirst("div.kode_ticket_text > p")?.text()?.trim() ?: ""
            val link = element.selectFirst("div.ticket_btn > a")?.attr("href") ?: return@mapNotNull null

            LiveSearchResponse(
                "$teams - $time",
                fixUrl(link),
                this.name,
                TvType.Live,
                "https://i.imgur.com/svLxgqC.png"
            )
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
            plot = "Stream live da Calcio Codes"
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

        // 1. Link .m3u8 diretti
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

        // 2. Analisi fetch()
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
            } catch (e: Exception) {
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
                // fetch fallita, ignorata
            }
        }

        return true
    }
}
