package com.cartoontime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CartoonTimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CartoonTimeProvider())
    }
}