package com.trustableai.koru.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.activity.result.contract.ActivityResultContracts
import com.trustableai.koru.R
import com.trustableai.koru.camera.CameraLaneAnalyzer
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.bridge.KoruJsBridge
import com.trustableai.koru.bridge.WebViewEventDispatcher
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.runtime.CameraDirectSessionController
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.service.KoruTelemetryService
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val tag = "KoruMainActivity"
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updateCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLane()
            } else {
                updateCameraStatus(getString(R.string.camera_lane_denied))
            }
        }
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraPreview: PreviewView
    private lateinit var cameraStatusText: TextView
    private lateinit var webView: WebView
    private lateinit var dispatcher: WebViewEventDispatcher
    private lateinit var cameraDirectController: CameraDirectSessionController
    private var activeSessionMode: SessionMode? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.camera_preview)
        cameraStatusText = findViewById(R.id.camera_lane_status)
        webView = findViewById(R.id.webview)
        dispatcher = WebViewEventDispatcher(webView)
        cameraDirectController = CameraDirectSessionController(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        cameraExecutor = Executors.newSingleThreadExecutor()

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
                onStartLiveSession = ::startLiveSession,
                onStopLiveSession = ::stopLiveSession,
                onSetActiveCoach = ::setActiveCoach,
                onSetAudioEnabled = ::setAudioEnabled,
                onRequestBackendStatus = ::requestBackendStatus,
            ),
            "AndroidBridge",
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { KoruSessionBus.telemetry.collect { dispatcher.dispatchTelemetry(it) } }
                launch { KoruSessionBus.decisions.collect { dispatcher.dispatchDecision(it) } }
                launch { KoruSessionBus.status.collect { dispatcher.dispatchStatus(it) } }
                launch { KoruSessionBus.savedSessions.collect { dispatcher.dispatchSessionSaved(it) } }
            }
        }

        Log.d(tag, "Loading WebView entrypoint https://appassets.androidplatform.net/assets/web/index.html#/live")
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html#/live")
        ensureCameraPermission()
    }

    override fun onDestroy() {
        VisionFeatureStore.clear()
        cameraDirectController.shutdown()
        cameraExecutor.shutdown()
        webView.removeJavascriptInterface("AndroidBridge")
        super.onDestroy()
        webView.destroy()
    }

    private fun startLiveSession(configJson: String) {
        val config = LiveSessionConfig.fromJson(configJson)
        activeSessionMode = config.sessionMode
        Log.d(tag, "startLiveSession mode=${config.sessionMode} track=${config.trackName}")
        when (config.sessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.start(config)
            SessionMode.TELEMETRY -> ContextCompat.startForegroundService(this, KoruTelemetryService.startIntent(this, configJson))
        }
    }

    private fun stopLiveSession() {
        Log.d(tag, "stopLiveSession mode=$activeSessionMode")
        when (activeSessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.stop()
            SessionMode.TELEMETRY -> dispatchServiceIntent(KoruTelemetryService.stopIntent(this))
            null -> Unit
        }
        activeSessionMode = null
    }

    private fun setActiveCoach(coachId: String) {
        cameraDirectController.setActiveCoach(coachId)
        if (activeSessionMode == SessionMode.TELEMETRY) {
            dispatchServiceIntent(KoruTelemetryService.setCoachIntent(this, coachId))
        }
    }

    private fun setAudioEnabled(enabled: Boolean) {
        cameraDirectController.setAudioEnabled(enabled)
        if (activeSessionMode == SessionMode.TELEMETRY) {
            dispatchServiceIntent(KoruTelemetryService.setAudioIntent(this, enabled))
        }
    }

    private fun requestBackendStatus() {
        dispatcher.dispatchStatus(KoruSessionBus.status.value)
    }

    private fun dispatchServiceIntent(intent: Intent) {
        super.startService(intent)
    }

    private fun ensureCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> {
                updateCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLane()
            }

            shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA) -> {
                updateCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }

            else -> {
                updateCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    private fun bindCameraLane() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = cameraPreview.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzerUseCase ->
                    analyzerUseCase.setAnalyzer(
                        cameraExecutor,
                        CameraLaneAnalyzer { snapshot ->
                            VisionFeatureStore.update(snapshot)
                            runOnUiThread {
                                updateCameraStatus(
                                    String.format(
                                        Locale.US,
                                        "Camera lane active  |  fps %.1f  |  motion %.2f  |  balance %.2f",
                                        snapshot.framesPerSecond,
                                        snapshot.motionEnergy,
                                        snapshot.lateralBalance,
                                    ),
                                )
                            }
                        },
                    )
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (error: Exception) {
                Log.e(tag, "Failed to bind camera lane", error)
                updateCameraStatus("Camera lane error: ${error.message ?: "unknown"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCameraStatus(text: String) {
        cameraStatusText.text = text
    }
}
