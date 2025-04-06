
package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CalcioCodesPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra il provider CalcioCodes
        registerMainAPI(CalcioCodesProvider())
    }
}
