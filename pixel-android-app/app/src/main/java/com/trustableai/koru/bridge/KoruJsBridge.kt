package com.trustableai.koru.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class KoruJsBridge(
    private val startLiveSession: (String) -> Unit,
    private val stopLiveSession: () -> Unit,
    private val setActiveCoach: (String) -> Unit,
    private val setAudioEnabled: (Boolean) -> Unit,
    private val requestBackendStatus: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun startLiveSession(configJson: String) {
        mainHandler.post { startLiveSession(configJson) }
    }

    @JavascriptInterface
    fun stopLiveSession() {
        mainHandler.post(stopLiveSession)
    }

    @JavascriptInterface
    fun setActiveCoach(coachId: String) {
        mainHandler.post { setActiveCoach(coachId) }
    }

    @JavascriptInterface
    fun setAudioEnabled(enabled: Boolean) {
        mainHandler.post { setAudioEnabled(enabled) }
    }

    @JavascriptInterface
    fun requestBackendStatus() {
        mainHandler.post(requestBackendStatus)
    }
}
