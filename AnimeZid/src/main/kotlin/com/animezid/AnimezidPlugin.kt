package com.animezid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimezidPlugin : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(AnimezidRpmHostExtractor())
        registerExtractorAPI(AnimezidUpnShareExtractor())
        registerExtractorAPI(AnimezidStreamP2PExtractor())
        registerExtractorAPI(AnimezidEasyVidPlayExtractor())
        registerExtractorAPI(AnimezidUqloadExtractor())
        registerExtractorAPI(AnimezidUqloadToExtractor())
        registerExtractorAPI(AnimezidStreamWishExtractor())
        registerExtractorAPI(AnimezidStreamRubyExtractor())
        registerExtractorAPI(AnimezidStreamRubyComExtractor())
        registerExtractorAPI(AnimezidMegaMaxExtractor())
        registerExtractorAPI(AnimezidVidTubeExtractor())
        registerExtractorAPI(AnimezidAbyssExtractor())
        registerExtractorAPI(AnimezidPlayMateExtractor())
        registerExtractorAPI(AnimezidByseExtractor())
        registerExtractorAPI(AnimezidMp4UploadExtractor())
        registerExtractorAPI(AnimezidSaveFilesExtractor())

        registerMainAPI(Animezid())
    }
}
