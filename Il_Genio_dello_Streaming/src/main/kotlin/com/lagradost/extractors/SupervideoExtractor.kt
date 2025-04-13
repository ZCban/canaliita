package com.lagradost.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import android.util.Log

class SupervideoExtractor : ExtractorApi() {
    override var name = "Supervideo"
    override var mainUrl = "https://supervideo.cc/"
    override val requiresReferer = false // O true se necessario

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i("SupervideoExtractor", "🔎 Estrazione in corso: $url")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        try {
            val response = app.get(url, headers = headers)
            val body = response.body.string()
            Log.i("SupervideoExtractor", "✅ Pagina caricata, lunghezza: ${body.length}")

            val evalRegex = Regex("""eval\(function\(p,a,c,k,e,(?:r|d).*?\n""")
            val evalBlock = evalRegex.find(body)?.value ?: run {
                Log.e("SupervideoExtractor", "❌ Nessun blocco eval() trovato.")
                return
            }

            var unpacked = evalBlock
            var videoUrl: String? = null

            for (i in 1..5) {
                Log.i("SupervideoExtractor", "▶ Passaggio unpack $i...")
                unpacked = getAndUnpack(unpacked)
                Log.i("SupervideoExtractor", "✅ Unpacked $i, size: ${unpacked.length}")

                videoUrl = Regex("""file\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"""")
                    .find(unpacked)?.groupValues?.get(1)

                if (!videoUrl.isNullOrEmpty()) break
            }

            if (videoUrl.isNullOrEmpty()) {
                Log.e("SupervideoExtractor", "❌ URL video non trovato dopo 5 passaggi.")
                return
            }

            Log.i("SupervideoExtractor", "✅ Video URL estratto: $videoUrl")

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "Supervideo",
                    url = videoUrl,
                    referer = referer ?: "",
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        } catch (e: Exception) {
            Log.e("SupervideoExtractor", "❌ Errore durante l'estrazione: ${e.message}")
        }
    }
}
