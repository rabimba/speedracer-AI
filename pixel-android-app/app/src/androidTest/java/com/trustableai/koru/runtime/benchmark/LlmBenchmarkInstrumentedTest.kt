package com.trustableai.koru.runtime.benchmark

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trustableai.koru.runtime.ModelAssetManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class LlmBenchmarkInstrumentedTest {
    private lateinit var runner: LlmBenchmarkRunner

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("ignoreLock") != "true") {
            assumeUnlockedDevice()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runner = LlmBenchmarkRunner(context, ModelAssetManager(context))
        assumeTrue("Staged LiteRT-LM model required at ${runner.modelPath()}", runner.modelReady())
    }

    @After
    fun tearDown() {
        if (::runner.isInitialized) {
            runner.close()
        }
    }

    @Test
    fun benchmarkLiteRtStrategyMatrix() {
        val args = InstrumentationRegistry.getArguments()
        val runsPerStrategy = args.getString("runsPerStrategy")?.toIntOrNull() ?: 3
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configs =
            args.getString("strategies")?.split(",")?.mapNotNull { name ->
                LiteRtInferenceConfig.benchmarkMatrix().find { it.name == name.trim() }
            }?.takeIf { it.isNotEmpty() } ?: LiteRtInferenceConfig.benchmarkMatrix()
        val results = runner.runMatrix(configs = configs, runsPerStrategy = runsPerStrategy)
        val reportFile =
            runner.writeReport(
                results = results,
                outputFile = File(context.getExternalFilesDir(null), "llm-benchmark-report.json"),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})",
            )

        results.forEach { result ->
            Log.i(
                TAG,
                "strategy=${result.strategy} backend=${result.backend} " +
                    "medianTokPerSec=${"%.1f".format(result.medianTokensPerSecond)} " +
                    "p95TokPerSec=${"%.1f".format(result.p95TokensPerSecond)} " +
                    "medianTtftMs=${result.medianTtftMs} p95TtftMs=${result.p95TtftMs} " +
                    "error=${result.error ?: "none"}",
            )
        }

        val successful = results.filter { it.error == null && it.medianTokensPerSecond > 0.0 }
        assertTrue("Expected at least one successful benchmark strategy", successful.isNotEmpty())
        assertTrue("Benchmark report missing at ${reportFile.absolutePath}", reportFile.exists())
    }

    @Test
    fun benchmarkProductionConfig() {
        val result = runner.runStrategy(LiteRtInferenceConfig.PRODUCTION, runs = 5)
        Log.i(
            TAG,
            "production medianTokPerSec=${"%.1f".format(result.medianTokensPerSecond)} " +
                "medianTtftMs=${result.medianTtftMs} medianLatencyMs=${result.medianLatencyMs}",
        )
        assertTrue(result.error ?: "production benchmark failed", result.error == null)
        assertTrue(result.medianTokensPerSecond > 0.0)
    }

    private fun assumeUnlockedDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { fd ->
            FileInputStream(fd.fileDescriptor).readBytes()
        }
        val trust =
            instrumentation.uiAutomation.executeShellCommand("dumpsys trust").use { fd ->
                FileInputStream(fd.fileDescriptor).bufferedReader().readText()
            }
        val policy =
            instrumentation.uiAutomation.executeShellCommand("dumpsys window policy").use { fd ->
                FileInputStream(fd.fileDescriptor).bufferedReader().readText()
            }
        val locked =
            trust.contains("deviceLocked=1") ||
                (policy.contains("showing=true") && policy.contains("secure=true"))
        assumeTrue("Device must be unlocked for on-device LLM benchmarks", !locked)
    }

    companion object {
        private const val TAG = "KoruLlmBenchmark"
    }
}
