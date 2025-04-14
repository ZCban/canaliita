package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class SkyStreamingAsia : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://skystreaming.asia"
    override var name = "SkyStreamingAsia"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val TAG = "SkyStreamingAsia"
        private const val FALLBACK_POSTER = "https://skystreaming.asia/content/auto_site_logo.png"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 🔒 Blocca caricamenti successivi alla prima pagina
        if (page > 1) {
            Log.i(TAG, "Ignorata pagina $page: il sito ha solo una pagina.")
            return newHomePageResponse(emptyList())
        }

        Log.i(TAG, "Caricamento homepage da $mainUrl")

        val response = app.get(mainUrl, referer = mainUrl)
        val document = response.document
        val eventDivs = document.select("div.mediathumb")

        val items = eventDivs.mapNotNull { div ->
            try {
                val aTag = div.selectFirst("a.mediacontainer") ?: return@mapNotNull null
                val href = fixUrl(aTag.attr("href"))
                val title = aTag.attr("title")?.trim() ?: return@mapNotNull null

                val style = aTag.selectFirst("span.mediabg")?.attr("style") ?: ""
                val rawPoster = Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.get(1)
                val poster = if (rawPoster != null) {
                    try {
                        val head = app.head(rawPoster)
                        if (head.code == 200) rawPoster else FALLBACK_POSTER
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore HEAD poster homepage: $rawPoster", e)
                        FALLBACK_POSTER
                    }
                } else {
                    FALLBACK_POSTER
                }

                Log.i(TAG, "Evento trovato: $title - Link: $href")

                newLiveSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.Live
                ) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore parsing evento homepage: ${e.localizedMessage}", e)
                null
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Eventi in diretta", items, isHorizontalImages = true))
        )
    }

    override suspend fun load(url: String): LoadResponse {
        Log.i(TAG, "Caricamento load() per URL: $url")

        val response = app.get(url)
        val document = response.document

        val styleUrl = document.selectFirst("span.mediabg")?.attr("style")
        val rawPoster = Regex("""url\((.*?)\)""").find(styleUrl ?: "")?.groupValues?.get(1)
        val extractedFromStyle = if (rawPoster != null) {
            try {
                val head = app.head(rawPoster)
                if (head.code == 200) rawPoster else null
            } catch (e: Exception) {
                Log.e(TAG, "Errore HEAD poster in load: $rawPoster", e)
                null
            }
        } else null

        val fallbackImage = document.selectFirst("img[src*=auto_site_logo.png]")?.attr("src")
        val posterUrl = extractedFromStyle ?: fallbackImage ?: FALLBACK_POSTER

        Log.i(TAG, "Poster usato in load(): $posterUrl")

        return newLiveStreamLoadResponse(
            name = "Guarda Live",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            plot = "Stream live da SkyStreamingAsia"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "loadLinks() chiamato con data: $data")

        val embedUrl = Regex("""/view/.+/([^/]+)$""").find(data)?.groupValues?.get(1)?.let {
            "$mainUrl/embed/$it"
        } ?: run {
            Log.e(TAG, "Embed URL non trovato.")
            return false
        }

        Log.i(TAG, "Embed URL generato: $embedUrl")

        val embedRes = app.get(embedUrl, referer = mainUrl)
        val html = embedRes.text
        val m3u8Regex = Regex("""https?:\/\/[^\s"'<>\\]+\.m3u8""")
        val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to data)

        val m3u8Links = m3u8Regex.findAll(html).map { it.value }.distinct()

        m3u8Links.forEach { m3u8 ->
            Log.i(TAG, "Link M3U8 diretto trovato: $m3u8")
            callback(
                ExtractorLink(
                    this.name,
                    "Diretto",
                    m3u8,
                    data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers
                )
            )
        }

        if (m3u8Links.any()) return true

        val channelId = Regex("""calcio\.php\?id=([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        if (channelId == null) {
            Log.e(TAG, "channel_id non trovato.")
            return false
        }

        Log.i(TAG, "Channel ID: $channelId")

        val serverLookupUrl = "https://4kwebplay.xyz/server_lookup.php?channel_id=$channelId"
        val lookupHeaders = headers + ("Referer" to "https://4kwebplay.xyz/calcio.php?id=$channelId")

        val lookupRes = app.get(serverLookupUrl, referer = lookupHeaders["Referer"] ?: "")
        val serverKey = try {
            JSONObject(lookupRes.text).optString("server_key", "calcio")
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing server_lookup: ${e.localizedMessage}", e)
            "calcio"
        }

        val fullChannel = if (!channelId.startsWith(serverKey)) {
            serverKey + channelId
        } else channelId

        val m3u8Url = "https://${serverKey}new.newkso.ru/$serverKey/$fullChannel/mono.m3u8"

        Log.i(TAG, "Link M3U8 alternativo generato: $m3u8Url")

        callback(
            ExtractorLink(
                this.name,
                "Server Lookup",
                m3u8Url,
                data,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = headers
            )
        )

        return true
    }
}
