
package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SkyStreamingAsiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SkyStreamingAsia())
    }
}

