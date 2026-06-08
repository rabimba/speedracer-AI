package com.trustableai.koru.runtime.benchmark

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.trustableai.koru.runtime.PhraseCatalog
import com.trustableai.koru.runtime.reasoner.AiCoreReasoner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class AcceleratorComparisonInstrumentedTest {
    private lateinit var runner: LlmBenchmarkRunner

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runner = LlmBenchmarkRunner(context)
    }

    @After
    fun tearDown() {
        if (::runner.isInitialized) {
            runner.close()
        }
    }

    @Test
    fun compareCpuGpuNpuTokenGenerationSpeed() {
        val args = InstrumentationRegistry.getArguments()
        val runsPerStrategy = args.getString("runsPerStrategy")?.toIntOrNull() ?: 1
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configs =
            listOf(
                LiteRtInferenceConfig.PRODUCTION.copy(
                    name = "gpu_token_generation",
                    backend = LlmInference.Backend.GPU,
                ),
                LiteRtInferenceConfig.PRODUCTION.copy(
                    name = "cpu_token_generation",
                    backend = LlmInference.Backend.CPU,
                ),
            )
        val liteRtResults = configs.map { config ->
            runner.runStrategy(config = config, runs = runsPerStrategy)
        }
        val phraseCatalog = PhraseCatalog(context)
        val aiCoreStatus = runBlocking {
            phraseCatalog.ensureLoaded()
            AiCoreReasoner(context, phraseCatalog, "superaj").warmup()
        }
        val report =
            JSONObject()
                .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                .put("generatedAtMs", System.currentTimeMillis())
                .put("modelPath", runner.modelPath())
                .put("modelReadyForNativeAndroid", runner.modelReady())
                .put(
                    "note",
                    "GPU and CPU use MediaPipe LlmInference token generation. NPU is reported as AICore status because this build scaffolds AICore and does not expose a token-generation API.",
                )
                .put("accelerators", JSONArray().apply {
                    liteRtResults.forEach { result ->
                        put(result.toAcceleratorJson())
                    }
                    put(
                        JSONObject()
                            .put("accelerator", "NPU")
                            .put("backend", "AICORE")
                            .put("measurementType", "status_only")
                            .put("medianTokensPerSecond", JSONObject.NULL)
                            .put("p95TokensPerSecond", JSONObject.NULL)
                            .put("medianTtftMs", JSONObject.NULL)
                            .put("medianLatencyMs", JSONObject.NULL)
                            .put("state", aiCoreStatus.state.name)
                            .put("detail", aiCoreStatus.detail)
                            .put("error", "AICore token-generation benchmark is not implemented in this build.")
                            .put("acceleratorRuntime", aiCoreStatus.accelerator.name),
                    )
                })
        val outputFile = File(context.getExternalFilesDir(null), "accelerator-comparison-report.json")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(report.toString(2))
        val publicReportPath = "/sdcard/Download/koru-accelerator-comparison-report.json"
        shell("cp ${outputFile.absolutePath} $publicReportPath")
        Log.i(TAG, "Wrote accelerator comparison report to ${outputFile.absolutePath}")
        Log.i(TAG, "Copied accelerator comparison report to $publicReportPath")
        Log.i(TAG, report.toString())

        assertTrue("Accelerator comparison report missing at ${outputFile.absolutePath}", outputFile.exists())
    }

    private fun LlmBenchmarkStrategyResult.toAcceleratorJson(): JSONObject =
        JSONObject()
            .put("accelerator", backend)
            .put("backend", "MEDIAPIPE_LITERT")
            .put("measurementType", "token_generation")
            .put("strategy", strategy)
            .put("medianTokensPerSecond", medianTokensPerSecond)
            .put("p95TokensPerSecond", p95TokensPerSecond)
            .put("medianTtftMs", medianTtftMs)
            .put("p95TtftMs", p95TtftMs)
            .put("medianLatencyMs", medianLatencyMs)
            .put("error", error ?: JSONObject.NULL)
            .put("runs", JSONArray().apply {
                runs.forEach { run ->
                    put(
                        JSONObject()
                            .put("runIndex", run.runIndex)
                            .put("outputTokens", run.outputTokens)
                            .put("promptTokens", run.promptTokens)
                            .put("latencyMs", run.latencyMs)
                            .put("ttftMs", run.ttftMs)
                            .put("tokensPerSecond", run.tokensPerSecond),
                    )
                }
            })

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return fd.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    companion object {
        private const val TAG = "KoruAccelBenchmark"
    }
}
