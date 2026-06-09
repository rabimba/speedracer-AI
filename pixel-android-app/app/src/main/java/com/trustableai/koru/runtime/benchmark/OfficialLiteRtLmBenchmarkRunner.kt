package com.trustableai.koru.runtime.benchmark

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import com.trustableai.koru.BuildConfig
import com.trustableai.koru.model.ModelInstallStatus
import com.trustableai.koru.runtime.ModelAssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class OfficialLiteRtLmAccelerator {
    CPU,
    GPU,
    NPU,
}

data class OfficialLiteRtLmBenchmarkConfig(
    val accelerator: OfficialLiteRtLmAccelerator,
    val strategy: String,
    val modelFileName: String,
    val prefillTokens: Int = 256,
    val decodeTokens: Int = 64,
    val npuNativeLibraryDir: String? = null,
)

data class OfficialLiteRtLmBenchmarkRun(
    val runIndex: Int,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val initMs: Long,
    val ttftMs: Long,
    val latencyMs: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

data class OfficialLiteRtLmBenchmarkResult(
    val accelerator: OfficialLiteRtLmAccelerator,
    val backend: String,
    val strategy: String,
    val modelPath: String,
    val measurementType: String,
    val runs: List<OfficialLiteRtLmBenchmarkRun>,
    val medianTokensPerSecond: Double,
    val p95TokensPerSecond: Double,
    val medianPrefillTokensPerSecond: Double,
    val medianTtftMs: Long,
    val p95TtftMs: Long,
    val medianLatencyMs: Long,
    val state: String? = null,
    val detail: String? = null,
    val error: String? = null,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("accelerator", accelerator.name)
            .put("backend", backend)
            .put("strategy", strategy)
            .put("modelPath", modelPath)
            .put("measurementType", measurementType)
            .put("medianTokensPerSecond", medianTokensPerSecond)
            .put("p95TokensPerSecond", p95TokensPerSecond)
            .put("medianPrefillTokensPerSecond", medianPrefillTokensPerSecond)
            .put("medianTtftMs", medianTtftMs)
            .put("p95TtftMs", p95TtftMs)
            .put("medianLatencyMs", medianLatencyMs)
            .put("state", state ?: JSONObject.NULL)
            .put("detail", detail ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .put("runs", JSONArray().apply {
                runs.forEach { run ->
                    put(
                        JSONObject()
                            .put("runIndex", run.runIndex)
                            .put("promptTokens", run.prefillTokens)
                            .put("outputTokens", run.decodeTokens)
                            .put("initMs", run.initMs)
                            .put("ttftMs", run.ttftMs)
                            .put("latencyMs", run.latencyMs)
                            .put("prefillTokensPerSecond", run.prefillTokensPerSecond)
                            .put("tokensPerSecond", run.decodeTokensPerSecond),
                    )
                }
            })
}

class OfficialLiteRtLmBenchmarkRunner(
    private val context: Context,
    private val modelAssetManager: ModelAssetManager = ModelAssetManager(context),
) {
    private val tag = "KoruOfficialLiteRtLm"

    fun modelPathFor(config: OfficialLiteRtLmBenchmarkConfig): String =
        statusFor(config).filePath

    fun statusFor(config: OfficialLiteRtLmBenchmarkConfig): ModelInstallStatus =
        when (config.accelerator) {
            OfficialLiteRtLmAccelerator.NPU -> modelAssetManager.liteRtLmNpuInstallStatus()
            OfficialLiteRtLmAccelerator.GPU,
            OfficialLiteRtLmAccelerator.CPU -> modelAssetManager.liteRtLmInstallStatus()
        }

    fun runStrategy(
        config: OfficialLiteRtLmBenchmarkConfig,
        runs: Int,
        timeoutMs: Long = 240_000L,
    ): OfficialLiteRtLmBenchmarkResult {
        val status = statusFor(config)
        if (!status.isPresent || !status.checksumVerified || !status.supportsNativeAndroid) {
            return failedResult(
                config = config,
                status = status,
                message = status.issue ?: "Model not ready at ${status.filePath}",
            )
        }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "KoruOfficialLiteRtLm-${config.accelerator.name}").apply { isDaemon = true }
        }
        val measuredRuns = mutableListOf<OfficialLiteRtLmBenchmarkRun>()
        return try {
            repeat(runs.coerceAtLeast(1)) { index ->
                val run =
                    executor.submit(
                        Callable {
                            runBenchmarkOnce(
                                config = config,
                                modelPath = status.filePath,
                                runIndex = index,
                            )
                        },
                    ).get(timeoutMs, TimeUnit.MILLISECONDS)
                measuredRuns += run
                Log.i(
                    tag,
                    "${config.strategy} run=$index decodeTok/s=${"%.1f".format(run.decodeTokensPerSecond)} " +
                        "prefillTok/s=${"%.1f".format(run.prefillTokensPerSecond)} ttft=${run.ttftMs}ms",
                )
            }
            summarize(config, status.filePath, measuredRuns)
        } catch (error: Exception) {
            Log.e(tag, "${config.strategy} failed", error)
            failedResult(
                config = config,
                status = status,
                message = error.cause?.message ?: error.message ?: error.javaClass.simpleName,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    fun defaultMatrix(npuNativeLibraryDir: String? = null): List<OfficialLiteRtLmBenchmarkConfig> =
        listOf(
            OfficialLiteRtLmBenchmarkConfig(
                accelerator = OfficialLiteRtLmAccelerator.GPU,
                strategy = "gpu_litertlm_benchmark",
                modelFileName = BuildConfig.DEFAULT_MODEL_FILENAME,
            ),
            OfficialLiteRtLmBenchmarkConfig(
                accelerator = OfficialLiteRtLmAccelerator.CPU,
                strategy = "cpu_litertlm_benchmark",
                modelFileName = BuildConfig.DEFAULT_MODEL_FILENAME,
            ),
            OfficialLiteRtLmBenchmarkConfig(
                accelerator = OfficialLiteRtLmAccelerator.NPU,
                strategy = "npu_litertlm_benchmark",
                modelFileName = BuildConfig.DEFAULT_NPU_MODEL_FILENAME,
                npuNativeLibraryDir = npuNativeLibraryDir ?: defaultNpuLibraryDir(),
            ),
        )

    @OptIn(ExperimentalApi::class)
    private fun runBenchmarkOnce(
        config: OfficialLiteRtLmBenchmarkConfig,
        modelPath: String,
        runIndex: Int,
    ): OfficialLiteRtLmBenchmarkRun {
        val startedAtNanos = System.nanoTime()
        val info =
            benchmark(
                modelPath = modelPath,
                backend = backendFor(config),
                prefillTokens = config.prefillTokens,
                decodeTokens = config.decodeTokens,
                cacheDir = cacheDirFor(config),
            )
        val elapsedMs = elapsedMsSince(startedAtNanos)
        return OfficialLiteRtLmBenchmarkRun(
            runIndex = runIndex,
            prefillTokens = info.lastPrefillTokenCount,
            decodeTokens = info.lastDecodeTokenCount,
            initMs = (info.initTimeInSecond * 1000.0).toLong(),
            ttftMs = (info.timeToFirstTokenInSecond * 1000.0).toLong(),
            latencyMs = elapsedMs,
            prefillTokensPerSecond = info.lastPrefillTokensPerSecond,
            decodeTokensPerSecond = info.lastDecodeTokensPerSecond,
        )
    }

    private fun backendFor(config: OfficialLiteRtLmBenchmarkConfig): Backend =
        when (config.accelerator) {
            OfficialLiteRtLmAccelerator.CPU -> Backend.CPU(numOfThreads = 4)
            OfficialLiteRtLmAccelerator.GPU -> Backend.GPU()
            OfficialLiteRtLmAccelerator.NPU -> Backend.NPU(config.npuNativeLibraryDir ?: defaultNpuLibraryDir())
        }

    private fun defaultNpuLibraryDir(): String =
        context.applicationInfo.nativeLibraryDir ?: DEFAULT_PIXEL_NPU_LIBRARY_DIR

    private fun cacheDirFor(config: OfficialLiteRtLmBenchmarkConfig): String =
        when (config.accelerator) {
            OfficialLiteRtLmAccelerator.GPU -> ":nocache"
            OfficialLiteRtLmAccelerator.CPU,
            OfficialLiteRtLmAccelerator.NPU ->
                File(context.cacheDir, "litertlm-benchmark-${config.accelerator.name.lowercase()}").absolutePath
        }

    private fun summarize(
        config: OfficialLiteRtLmBenchmarkConfig,
        modelPath: String,
        runs: List<OfficialLiteRtLmBenchmarkRun>,
    ): OfficialLiteRtLmBenchmarkResult {
        val decodeRates = runs.map { it.decodeTokensPerSecond }.sorted()
        val prefillRates = runs.map { it.prefillTokensPerSecond }.sorted()
        val ttfts = runs.map { it.ttftMs }.sorted()
        val latencies = runs.map { it.latencyMs }.sorted()
        return OfficialLiteRtLmBenchmarkResult(
            accelerator = config.accelerator,
            backend = "LITERT_LM",
            strategy = config.strategy,
            modelPath = modelPath,
            measurementType = "token_generation",
            runs = runs,
            medianTokensPerSecond = percentile(decodeRates, 0.50),
            p95TokensPerSecond = percentile(decodeRates, 0.95),
            medianPrefillTokensPerSecond = percentile(prefillRates, 0.50),
            medianTtftMs = percentileLong(ttfts, 0.50),
            p95TtftMs = percentileLong(ttfts, 0.95),
            medianLatencyMs = percentileLong(latencies, 0.50),
        )
    }

    private fun failedResult(
        config: OfficialLiteRtLmBenchmarkConfig,
        status: ModelInstallStatus,
        message: String,
    ): OfficialLiteRtLmBenchmarkResult =
        OfficialLiteRtLmBenchmarkResult(
            accelerator = config.accelerator,
            backend = "LITERT_LM",
            strategy = config.strategy,
            modelPath = status.filePath,
            measurementType = "token_generation",
            runs = emptyList(),
            medianTokensPerSecond = 0.0,
            p95TokensPerSecond = 0.0,
            medianPrefillTokensPerSecond = 0.0,
            medianTtftMs = 0L,
            p95TtftMs = 0L,
            medianLatencyMs = 0L,
            state = "ERROR",
            detail = status.issue,
            error = message,
        )

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(1L)

    private fun percentile(values: List<Double>, quantile: Double): Double {
        if (values.isEmpty()) return 0.0
        val index = ((values.size - 1) * quantile).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun percentileLong(values: List<Long>, quantile: Double): Long {
        if (values.isEmpty()) return 0L
        val index = ((values.size - 1) * quantile).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }

    companion object {
        const val DEFAULT_PIXEL_NPU_LIBRARY_DIR = "/vendor/lib64"
    }
}
