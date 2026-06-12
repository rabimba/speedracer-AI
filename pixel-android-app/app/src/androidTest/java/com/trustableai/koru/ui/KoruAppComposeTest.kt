package com.trustableai.koru.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RecordedSessionSummary
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.service.KoruTelemetryService
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.Description
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.FileInputStream

class KoruAppComposeTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule =
        RuleChain
            .outerRule(InteractiveDeviceRule())
            .around(
                GrantPermissionRule.grant(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
            .around(composeRule)

    @Before
    fun resetSessionBus() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        targetContext.startService(KoruTelemetryService.stopIntent(targetContext))
        KoruSessionBus.resetLiveState()
        Thread.sleep(300)
    }

    @Test
    fun sessionSetupModeSourceAndAudioControlsRender() {
        waitForSetupScreen()
        composeRule.onNodeWithText("Paddock").assertIsDisplayed()
        composeRule.onNodeWithText("Diagnostics").assertIsDisplayed()
        composeRule.onNodeWithTag("option-mode-telemetry-+-map").assertIsDisplayed()
        composeRule.onNodeWithTag("option-mode-can-interface-check").assertIsDisplayed()
        composeRule.onNodeWithTag("option-source-phone-imu-+-gps").assertIsDisplayed()
        composeRule.onNodeWithTag("option-can-bitrate-s8-1m").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("setup-start-session").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("option-source-synthetic").performScrollTo().performClick()
        composeRule.onNodeWithTag("option-mode-device-test").performScrollTo().performClick()
        composeRule.onNodeWithTag("option-mode-camera-feedback").performScrollTo().performClick()

        composeRule.onNodeWithTag("audio-toggle").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("audio-check").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("audio-toggle").performClick()
        composeRule.onNodeWithTag("audio-toggle").assertIsOff()
    }

    @Test
    fun goalSelectorEnforcesMaximumThreeGoals() {
        waitForSetupScreen()
        composeRule.onNodeWithTag("goal-braking").performScrollTo().performClick()
        composeRule.onNodeWithTag("goal-throttle").performScrollTo().performClick()
        composeRule.onNodeWithTag("goal-vision").performScrollTo().performClick()
        composeRule.onNodeWithTag("goal-lines").performScrollTo().performClick()

        composeRule.onNodeWithTag("goal-braking").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("goal-throttle").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("goal-vision").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("goal-lines").performScrollTo().assertIsNotSelected()
    }

    @Test
    fun startStopRendersActiveSessionState() {
        waitForSetupScreen()
        composeRule.onNodeWithTag("option-source-synthetic").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-start-session").performScrollTo().performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("track-mode").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("track-mode").assertIsDisplayed()
        composeRule.onNodeWithTag("mute-session").assertIsDisplayed()
        composeRule.onNodeWithTag("hold-stop-session").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Diagnostics").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("hold-stop-session").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("Saved Session").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved Session").assertIsDisplayed()
    }

    @Test
    fun liveHudShowsPriorityDecisionAndSavedSessionStatus() {
        waitForSetupScreen()
        val nowMs = System.currentTimeMillis()
        composeRule.onNodeWithText("Signal + text").performScrollTo().performClick()

        composeRule.runOnIdle {
            KoruSessionBus.tryEmitStatus(
                LiveBackendStatus(
                    backend = RuntimeBackend.DETERMINISTIC,
                    state = LiveBackendState.READY,
                    detail = "Ready for test",
                    usesOnDeviceModel = false,
                    supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                ),
            )
            KoruSessionBus.tryEmitDecision(
                CoachingDecision(
                    path = CoachingPath.HOT,
                    action = CoachAction.BRAKE,
                    text = "Brake now",
                    priority = 0,
                    cornerPhase = CornerPhase.BRAKE_ZONE,
                    timestampMs = nowMs,
                    backend = RuntimeBackend.DETERMINISTIC,
                    latencyMs = 12L,
                ),
            )
            KoruSessionBus.tryEmitSavedSession(
                RecordedSessionArtifact(
                    schemaVersion = 2,
                    id = "session-test",
                    mode = SessionMode.TELEMETRY,
                    trackName = "Sonoma Raceway",
                    coachId = "superaj",
                    startedAtMs = nowMs - 1_000L,
                    endedAtMs = nowMs,
                    summary =
                        RecordedSessionSummary(
                            sessionId = "session-test",
                            mode = SessionMode.TELEMETRY,
                            trackName = "Sonoma Raceway",
                            coachId = "superaj",
                            frameCount = 10,
                            decisionCount = 1,
                            durationSeconds = 1.0,
                        ),
                    frames = emptyList(),
                    decisions = emptyList(),
                    audioEvents = emptyList(),
                ),
            )
        }

        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithTag("track-mode").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("track-mode").assertIsDisplayed()
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("Brake now").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Brake now").assertIsDisplayed()
        composeRule.onNodeWithText("P0 HOT BRAKE BRAKE_ZONE").assertIsDisplayed()

        composeRule.runOnIdle {
            KoruSessionBus.tryEmitStatus(
                LiveBackendStatus(
                    backend = RuntimeBackend.DETERMINISTIC,
                    state = LiveBackendState.IDLE,
                    detail = "Android session idle",
                    usesOnDeviceModel = false,
                    supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                ),
            )
        }
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("Saved Session").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved Session").assertIsDisplayed()
        composeRule.onNodeWithText("10 frames (0 preview), 1 decisions, ended completed").assertIsDisplayed()
    }

    @Test
    fun diagnosticsDestinationContainsHardwareAndModelHealth() {
        waitForSetupScreen()
        composeRule.onNodeWithText("Diagnostics").performClick()

        composeRule.onNodeWithTag("diagnostics-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Backend Status").assertIsDisplayed()
        composeRule.onNodeWithText("Accelerator Comparison").assertIsDisplayed()
        composeRule.onNodeWithText("Camera Lane").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("edge-inference-metrics").performScrollTo().assertIsDisplayed()
    }

    private fun waitForSetupScreen() {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("setup-screen").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Setup").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("setup-screen").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithText("Setup").performClick()
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("setup-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("setup-screen").assertIsDisplayed()
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return fd.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    private class InteractiveDeviceRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    shell("input keyevent KEYCODE_HOME")
                    Thread.sleep(750)
                    assumeFalse(
                        "Connected Compose tests require an unlocked device so MainActivity can render.",
                        isDeviceLocked(),
                    )
                    base.evaluate()
                }
            }

        private fun isDeviceLocked(): Boolean {
            val trust = shell("dumpsys trust")
            val policy = shell("dumpsys window policy")
            return trust.contains("deviceLocked=1") ||
                (policy.contains("showing=true") && policy.contains("secure=true"))
        }

        private fun shell(command: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return fd.use {
                FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
            }
        }
    }
}
