package com.trustableai.koru.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.trustableai.koru.R
import com.trustableai.koru.bridge.KoruJsBridge
import com.trustableai.koru.bridge.WebViewEventDispatcher
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.service.KoruTelemetryService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var dispatcher: WebViewEventDispatcher

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        dispatcher = WebViewEventDispatcher(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                dispatcher.dispatchStatus(KoruSessionBus.status.value)
            }
        }

        webView.addJavascriptInterface(
            KoruJsBridge(
                startLiveSession = ::startLiveSession,
                stopLiveSession = ::stopLiveSession,
                setActiveCoach = ::setActiveCoach,
                setAudioEnabled = ::setAudioEnabled,
                requestBackendStatus = ::requestBackendStatus,
            ),
            "AndroidBridge",
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { KoruSessionBus.telemetry.collect { dispatcher.dispatchTelemetry(it) } }
                launch { KoruSessionBus.decisions.collect { dispatcher.dispatchDecision(it) } }
                launch { KoruSessionBus.status.collect { dispatcher.dispatchStatus(it) } }
            }
        }

        webView.loadUrl("file:///android_asset/web/index.html#/live")
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }

    private fun startLiveSession(configJson: String) {
        ContextCompat.startForegroundService(this, KoruTelemetryService.startIntent(this, configJson))
    }

    private fun stopLiveSession() {
        ContextCompat.startForegroundService(this, KoruTelemetryService.stopIntent(this))
    }

    private fun setActiveCoach(coachId: String) {
        ContextCompat.startForegroundService(this, KoruTelemetryService.setCoachIntent(this, coachId))
    }

    private fun setAudioEnabled(enabled: Boolean) {
        ContextCompat.startForegroundService(this, KoruTelemetryService.setAudioIntent(this, enabled))
    }

    private fun requestBackendStatus() {
        ContextCompat.startForegroundService(this, KoruTelemetryService.requestStatusIntent(this))
        dispatcher.dispatchStatus(KoruSessionBus.status.value)
    }
}
