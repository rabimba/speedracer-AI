package com.trustableai.koru.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.trustableai.koru.R
import com.trustableai.koru.camera.CameraLaneAnalyzer
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val tag = "KoruMainActivity"
    private val viewModel: LiveSessionViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var cameraPreview: PreviewView? = null
    private var cameraBoundPreview: PreviewView? = null
    private var pendingLocationConfig: LiveSessionConfig? = null
    @Volatile private var lastCameraStatusUpdateMs = 0L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLaneIfReady()
            } else {
                viewModel.setCameraStatus(getString(R.string.camera_lane_denied))
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val config = pendingLocationConfig
            pendingLocationConfig = null
            if (granted && config != null) {
                Log.d(tag, "Location permission granted; starting native live session")
                viewModel.startSession(config)
            } else if (!granted) {
                KoruSessionBus.tryEmitStatus(
                    LiveBackendStatus(
                        backend = RuntimeBackend.DETERMINISTIC,
                        state = LiveBackendState.ERROR,
                        detail = "Phone IMU + GPS requires precise location permission.",
                        usesOnDeviceModel = false,
                        supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                    ),
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            KoruApp(
                viewModel = viewModel,
                onStartRequested = ::startLiveSessionWithPermissions,
                onBindCameraPreview = { previewView ->
                    cameraPreview = previewView
                    bindCameraLaneIfReady()
                },
            )
        }
        ensureCameraPermission()
    }

    override fun onDestroy() {
        VisionFeatureStore.clear()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startLiveSessionWithPermissions() {
        val config = viewModel.currentConfig()
        if (requiresFineLocation(config) && !hasFineLocationPermission()) {
            pendingLocationConfig = config
            KoruSessionBus.tryEmitStatus(
                LiveBackendStatus(
                    backend = RuntimeBackend.DETERMINISTIC,
                    state = LiveBackendState.STARTING,
                    detail = "Requesting location permission for phone IMU + GPS telemetry.",
                    usesOnDeviceModel = false,
                    supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                ),
            )
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        viewModel.startSession(config)
    }

    private fun requiresFineLocation(config: LiveSessionConfig): Boolean {
        return (config.sessionMode == SessionMode.TELEMETRY || config.sessionMode == SessionMode.DEVICE_TEST) &&
            config.telemetrySource == TelemetrySourceKind.PHONE_IMU_GPS
    }

    private fun ensureCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLaneIfReady()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bindCameraLaneIfReady() {
        val previewView = cameraPreview ?: return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (cameraBoundPreview === previewView) return
        cameraBoundPreview = previewView

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzerUseCase ->
                    analyzerUseCase.setAnalyzer(
                        cameraExecutor,
                        CameraLaneAnalyzer { snapshot ->
                            VisionFeatureStore.update(snapshot)
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastCameraStatusUpdateMs >= CAMERA_STATUS_UPDATE_INTERVAL_MS) {
                                lastCameraStatusUpdateMs = nowMs
                                runOnUiThread {
                                    viewModel.setCameraStatus(
                                        String.format(
                                            Locale.US,
                                            "Camera lane active | fps %.1f | motion %.2f | balance %.2f",
                                            snapshot.framesPerSecond,
                                            snapshot.motionEnergy,
                                            snapshot.lateralBalance,
                                        ),
                                    )
                                }
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
                cameraBoundPreview = null
                viewModel.setCameraStatus("Camera lane error: ${error.message ?: "unknown"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    companion object {
        private const val CAMERA_STATUS_UPDATE_INTERVAL_MS = 250L
    }
}
