package com.trustableai.koru.simrunner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class MockSonomaLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService() ?: error("LocationManager unavailable")
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPlayback()
            else -> {
                val assetPath = intent?.getStringExtra(EXTRA_SCENARIO_ASSET) ?: DEFAULT_SCENARIO_ASSET
                val playbackSpeed = intent?.getDoubleExtra(EXTRA_PLAYBACK_SPEED, 5.0) ?: 5.0
                startPlayback(assetPath, playbackSpeed)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPlayback()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPlayback(assetPath: String, playbackSpeed: Double) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Publishing Sonoma mock GPS"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing ACCESS_FINE_LOCATION; cannot publish mock GPS")
            stopSelf()
            return
        }

        val scenario =
            runCatching { SonomaScenarioLoader.load(this, assetPath) }
                .onFailure { Log.e(TAG, "Failed to load scenario $assetPath", it) }
                .getOrNull() ?: return

        installMockProvider()
        playbackJob?.cancel()
        playbackJob =
            scope.launch {
                val samples = scenario.samples
                Log.d(TAG, "Sonoma mock location playback started samples=${samples.size} playbackSpeed=$playbackSpeed")
                for (index in samples.indices) {
                    if (!isActive) break
                    publish(samples[index])
                    val next = samples.getOrNull(index + 1)
                    if (next != null) {
                        val deltaMs =
                            ((next.timestampSec - samples[index].timestampSec) * 1000.0 / max(0.1, playbackSpeed))
                                .toLong()
                                .coerceAtLeast(5L)
                        delay(deltaMs)
                    }
                }
                samples.lastOrNull()?.let { last ->
                    while (isActive) {
                        publish(last)
                        delay(100)
                    }
                }
            }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun installMockProvider() {
        runCatching {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }.onFailure { failure ->
            if (failure !is IllegalArgumentException) {
                Log.w(TAG, "Could not add GPS test provider; attempting to reuse existing provider", failure)
            }
        }
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun publish(sample: ScenarioSample) {
        val location =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = sample.latitude
                longitude = sample.longitude
                altitude = sample.altitude
                speed = sample.speedMps.toFloat()
                bearing = sample.bearingDeg.toFloat()
                accuracy = 3.0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 3.0f
                    speedAccuracyMetersPerSecond = 0.4f
                    verticalAccuracyMeters = 2.0f
                }
            }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Sonoma E2E mock location",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Koru Sonoma E2E Runner")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SimRunnerActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        const val ACTION_START = "com.trustableai.koru.simrunner.START_MOCK_SONOMA"
        const val ACTION_STOP = "com.trustableai.koru.simrunner.STOP_MOCK_SONOMA"
        const val EXTRA_SCENARIO_ASSET = "scenario_asset"
        const val EXTRA_PLAYBACK_SPEED = "playback_speed"
        const val DEFAULT_SCENARIO_ASSET = "scenarios/sonoma_beginner_training.v1.json"

        private const val CHANNEL_ID = "sonoma_e2e_mock_location"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "MockSonomaLocation"

        fun startIntent(context: Context, assetPath: String, playbackSpeed: Double): Intent {
            return Intent(context, MockSonomaLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCENARIO_ASSET, assetPath)
                .putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MockSonomaLocationService::class.java).setAction(ACTION_STOP)
        }
    }
}
