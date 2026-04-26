package com.trustableai.koru.bridge

import android.webkit.WebView
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.TelemetryFrame
import org.json.JSONObject

class WebViewEventDispatcher(private val webView: WebView) {
    fun dispatchTelemetry(frame: TelemetryFrame) {
        dispatch(BridgePayloads.telemetryFrame(frame))
    }

    fun dispatchDecision(decision: CoachingDecision) {
        dispatch(BridgePayloads.coachingDecision(decision))
    }

    fun dispatchStatus(status: LiveBackendStatus) {
        dispatch(BridgePayloads.backendStatus(status))
    }

    private fun dispatch(jsonPayload: String) {
        val escapedPayload = JSONObject.quote(jsonPayload)
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                  if (typeof window.__koruDispatchNativeEvent === 'function') {
                    window.__koruDispatchNativeEvent($escapedPayload);
                    return;
                  }
                  window.__koruPendingNativeEvents = window.__koruPendingNativeEvents || [];
                  window.__koruPendingNativeEvents.push($escapedPayload);
                })();
                """.trimIndent(),
                null,
            )
        }
    }
}
