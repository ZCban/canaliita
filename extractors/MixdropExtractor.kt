package com.lagradost.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

class MixdropExtractor : ExtractorApi() {
    override var name = "Mixdrop"
    override var mainUrl = "https://mixdrop.sb"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // ✅ Correggi il link se contiene /f/ e rimuovi query string come ?download
            val fixedUrl = url.replace("/f/", "/e/").substringBefore("?")
            Log.i("MixdropExtractor", "🔁 Converted URL: $fixedUrl")

            val response = app.get(fixedUrl)
            val body = response.body.string()
            Log.i("MixdropExtractor", "✅ Page loaded, body length: ${body.length}")

            // 🔍 Cerca blocco eval obfuscato
            val packedScript = Regex("""(eval\(function\(p,a,c,k,e,[\s\S]+?</script>)""")
                .find(body)?.groupValues?.get(1)

            if (packedScript == null) {
                Log.e("MixdropExtractor", "❌ No eval() block found.")
                return
            }

            val unpacked = getAndUnpack(packedScript)
            Log.i("MixdropExtractor", "🧩 Unpacked JS length: ${unpacked.length}")

            // 🎯 Estrai URL video (es. .mp4)
            val videoUrl = Regex("""MDCore\.\w+\s*=\s*"([^"]+\.mp4[^"]*)"""")
                .findAll(unpacked)
                .map { it.groupValues[1] }
                .firstOrNull()

            if (videoUrl.isNullOrEmpty()) {
                Log.e("MixdropExtractor", "❌ No video URL found in unpacked JS.")
                return
            }

            val fullUrl = if (videoUrl.startsWith("http")) videoUrl else "https:$videoUrl"
            Log.i("MixdropExtractor", "✅ Extracted video URL: $fullUrl")

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "Mixdrop",
                    url = fullUrl,
                    referer = referer ?: "",
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        } catch (e: Exception) {
            Log.e("MixdropExtractor", "❌ Error during extraction: ${e.message}")
        }
    }
}

