package com.trustableai.koru.simrunner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class SonomaTrainingE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val runnerContext: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val targetPackage = args.getString("targetPackage") ?: "com.trustableai.koru.debug"
    private val scenarioName = args.getString("scenario") ?: "sonoma_beginner_training.v1"
    private val playbackSpeed = args.getString("playbackSpeed")?.toDoubleOrNull() ?: 5.0

    @After
    fun stopMockProvider() {
        runnerContext.startService(MockSonomaLocationService.stopIntent(runnerContext))
    }

    @Test
    fun beginnerSonomaTrainingSessionRunsThroughNativeCockpit() {
        grantTargetPermissions()
        startMockLocationPlayback()
        launchTargetApp()

        clickText("Telemetry + Camera")
        clickText("Phone IMU + GPS")
        clickText("Sonoma")
        clickText("Braking")
        clickText("Throttle")
        clickText("Vision")
        clickText("Start Session")

        assertTrue("Stop Session did not appear", waitForText("Stop Session", 20_000))
        assertForegroundServiceRunning()

        Thread.sleep(22_000)

        clickText("Stop Session", timeoutMs = 20_000)
        assertTrue("Saved Session panel did not appear", waitForText("Saved Session", 25_000))
    }

    private fun startMockLocationPlayback() {
        val scenarioAsset = "scenarios/$scenarioName.json"
        val intent = MockSonomaLocationService.startIntent(runnerContext, scenarioAsset, playbackSpeed)
        ContextCompat.startForegroundService(runnerContext, intent)
        Thread.sleep(1_500)
    }

    private fun launchTargetApp() {
        prepareInteractiveDisplay()
        assertTrue(
            "Pixel is still locked. Unlock the device before running the Sonoma E2E harness.",
            !isDeviceLocked(),
        )
        val launchIntent =
            runnerContext.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(targetPackage, "com.trustableai.koru.ui.MainActivity")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        runnerContext.startActivity(launchIntent)
        if (!device.wait(Until.hasObject(By.pkg(targetPackage)), 7_500)) {
            shell("am start -W -n $targetPackage/com.trustableai.koru.ui.MainActivity")
        }
        assertTrue(
            "Target app $targetPackage did not launch",
            device.wait(Until.hasObject(By.pkg(targetPackage)), 15_000),
        )
        dismissPermissionDialogs()
        assertTrue("Native cockpit did not render", waitForText("Session Initialization", 20_000))
    }

    private fun prepareInteractiveDisplay() {
        device.wakeUp()
        listOf(
            "input keyevent KEYCODE_WAKEUP",
            "wm dismiss-keyguard",
            "cmd dreams stop-dreaming",
            "input keyevent KEYCODE_BACK",
            "input keyevent KEYCODE_HOME",
        ).forEach { shell(it) }
        Thread.sleep(750)
    }

    private fun isDeviceLocked(): Boolean {
        val trust = shell("dumpsys trust")
        val policy = shell("dumpsys window policy")
        return trust.contains("deviceLocked=1") ||
            (policy.contains("showing=true") && policy.contains("secure=true"))
    }

    private fun grantTargetPermissions() {
        shell("pm clear $targetPackage")
        shell("pm grant $targetPackage ${Manifest.permission.CAMERA}")
        shell("pm grant $targetPackage ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("pm grant $targetPackage ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $targetPackage ${Manifest.permission.POST_NOTIFICATIONS}")
        }

        val runnerPackage = runnerContext.packageName
        shell("pm grant $runnerPackage ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("pm grant $runnerPackage ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $runnerPackage ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        shell("appops set $runnerPackage android:mock_location allow")
        shell("appops set $runnerPackage mock_location allow")
    }

    private fun clickText(text: String, timeoutMs: Long = 10_000) {
        val node = waitForObject(text, timeoutMs)
        assertNotNull("Could not find UI text '$text'", node)
        clickBestTarget(node!!)
        Thread.sleep(350)
        dismissPermissionDialogs()
    }

    private fun clickBestTarget(node: UiObject2) {
        var target: UiObject2? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        (target ?: node).click()
    }

    private fun waitForText(text: String, timeoutMs: Long): Boolean {
        return waitForObject(text, timeoutMs) != null
    }

    private fun waitForObject(text: String, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.text(text))?.let { return it }
            device.findObject(By.textContains(text))?.let { return it }
            runCatching {
                UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text)
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun dismissPermissionDialogs() {
        listOf("While using the app", "Only this time", "Allow", "OK").forEach { label ->
            device.findObject(By.text(label))?.click()
        }
    }

    private fun assertForegroundServiceRunning() {
        val dumpsys = shell("dumpsys activity services $targetPackage")
        assertTrue(
            "KoruTelemetryService not visible in dumpsys while session is active:\n$dumpsys",
            dumpsys.contains("KoruTelemetryService"),
        )
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return fd.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
