package com.trustableai.koru.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface

class KoruJsBridge(
    private val onStartLiveSession: (String) -> Unit,
    private val onStopLiveSession: () -> Unit,
    private val onSetActiveCoach: (String) -> Unit,
    private val onSetAudioEnabled: (Boolean) -> Unit,
    private val onRequestBackendStatus: () -> Unit,
) {
    private val logTag = "KoruMainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun startLiveSession(configJson: String) {
        Log.d(logTag, "Bridge startLiveSession payload=$configJson")
        mainHandler.post { onStartLiveSession(configJson) }
    }

    @JavascriptInterface
    fun stopLiveSession() {
        Log.d(logTag, "Bridge stopLiveSession")
        mainHandler.post(onStopLiveSession)
    }

    @JavascriptInterface
    fun setActiveCoach(coachId: String) {
        Log.d(logTag, "Bridge setActiveCoach coach=$coachId")
        mainHandler.post { onSetActiveCoach(coachId) }
    }

    @JavascriptInterface
    fun setAudioEnabled(enabled: Boolean) {
        Log.d(logTag, "Bridge setAudioEnabled enabled=$enabled")
        mainHandler.post { onSetAudioEnabled(enabled) }
    }

    @JavascriptInterface
    fun requestBackendStatus() {
        Log.d(logTag, "Bridge requestBackendStatus")
        mainHandler.post(onRequestBackendStatus)
    }

    @JavascriptInterface
    fun getBridgeVersion(): String {
        Log.d(logTag, "Bridge getBridgeVersion -> android-bridge-2")
        return "android-bridge-2"
    }
}
