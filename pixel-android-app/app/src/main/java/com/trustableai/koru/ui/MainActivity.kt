package com.trustableai.koru.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
    private val tag = "KoruMainActivity"
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
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    tag,
                    "WebView console: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                )
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(tag, "WebView finished loading $url")
                dispatcher.dispatchStatus(KoruSessionBus.status.value)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                Log.e(
                    tag,
                    "WebView load error for ${request?.url}: ${error?.errorCode} ${error?.description}",
                )
                super.onReceivedError(view, request, error)
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

        Log.d(tag, "Loading WebView entrypoint file:///android_asset/web/index.html#/live")
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
