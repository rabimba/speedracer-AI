package com.trustableai.koru.runtime.benchmark

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private lateinit var runner: OfficialLiteRtLmBenchmarkRunner

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runner = OfficialLiteRtLmBenchmarkRunner(context)
    }

    @After
    fun tearDown() {
    }

    @Test
    fun compareCpuGpuNpuTokenGenerationSpeed() {
        val args = InstrumentationRegistry.getArguments()
        val runsPerStrategy = args.getString("runsPerStrategy")?.toIntOrNull() ?: 1
        val npuNativeLibraryDir =
            args.getString("npuNativeLibraryDir")
                ?.takeIf { it.isNotBlank() }
        val acceleratorFilter = args.getString("accelerator")?.uppercase()
        val reportSuffix = args.getString("reportSuffix")?.takeIf { it.isNotBlank() } ?: ""
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allConfigs = runner.defaultMatrix(npuNativeLibraryDir)
        val configs =
            acceleratorFilter
                ?.let { filter -> allConfigs.filter { it.accelerator.name == filter } }
                ?.takeIf { it.isNotEmpty() }
                ?: allConfigs
        val results = configs.map { config ->
            runner.runStrategy(config = config, runs = runsPerStrategy)
        }
        val genericModelPath =
            runner.modelPathFor(allConfigs.first { it.accelerator == OfficialLiteRtLmAccelerator.GPU })
        val npuModelPath =
            runner.modelPathFor(allConfigs.first { it.accelerator == OfficialLiteRtLmAccelerator.NPU })
        val report =
            JSONObject()
                .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                .put("generatedAtMs", System.currentTimeMillis())
                .put("modelPath", genericModelPath)
                .put("npuModelPath", npuModelPath)
                .put("modelReadyForNativeAndroid", results.any { it.error == null && it.medianTokensPerSecond > 0.0 })
                .put(
                    "note",
                    "GPU, CPU, and NPU use the official LiteRT-LM benchmark API. GPU/CPU use the generic Gemma 4 E2B LiteRT-LM artifact; NPU uses the Pixel Tensor G5 artifact when available.",
                )
                .put("accelerators", JSONArray().apply {
                    results.forEach { result -> put(result.toJson()) }
                })
        val outputName = "accelerator-comparison-report${reportSuffix}.json"
        val outputFile = File(context.getExternalFilesDir(null), outputName)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(report.toString(2))
        val publicReportPath = "/sdcard/Download/koru-${outputName}"
        shell("cp ${outputFile.absolutePath} $publicReportPath")
        Log.i(TAG, "Wrote accelerator comparison report to ${outputFile.absolutePath}")
        Log.i(TAG, "Copied accelerator comparison report to $publicReportPath")
        Log.i(TAG, report.toString())

        assertTrue("Accelerator comparison report missing at ${outputFile.absolutePath}", outputFile.exists())
    }

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
