package com.kartonikurde

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KartonikurdePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KartonikurdeProvider())
    }
}