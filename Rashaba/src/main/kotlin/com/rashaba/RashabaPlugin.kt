package com.rashaba

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RashabaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RashabaProvider())
    }
}