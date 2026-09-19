package com.shahid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShahidPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShahidProvider())
    }
}