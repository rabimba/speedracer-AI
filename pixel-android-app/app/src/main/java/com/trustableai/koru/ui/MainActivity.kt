package com.trustableai.koru.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import com.trustableai.koru.R
import com.trustableai.koru.bridge.KoruJsBridge
import com.trustableai.koru.bridge.WebViewEventDispatcher
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.service.KoruTelemetryService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val tag = "KoruMainActivity"
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var webView: WebView
    private lateinit var dispatcher: WebViewEventDispatcher

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        dispatcher = WebViewEventDispatcher(webView)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
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
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                return assetLoader.shouldInterceptRequest(url) ?: super.shouldInterceptRequest(view, request)
            }

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

        Log.d(tag, "Loading WebView entrypoint https://appassets.androidplatform.net/assets/web/index.html#/live")
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html#/live")
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
        dispatchServiceIntent(KoruTelemetryService.stopIntent(this))
    }

    private fun setActiveCoach(coachId: String) {
        dispatchServiceIntent(KoruTelemetryService.setCoachIntent(this, coachId))
    }

    private fun setAudioEnabled(enabled: Boolean) {
        dispatchServiceIntent(KoruTelemetryService.setAudioIntent(this, enabled))
    }

    private fun requestBackendStatus() {
        dispatcher.dispatchStatus(KoruSessionBus.status.value)
    }

    private fun dispatchServiceIntent(intent: Intent) {
        super.startService(intent)
    }
}
