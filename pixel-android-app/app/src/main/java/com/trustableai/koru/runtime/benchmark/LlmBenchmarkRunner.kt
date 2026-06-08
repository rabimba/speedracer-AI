package com.trustableai.koru.runtime.benchmark

import android.content.Context
import android.os.Process
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.runtime.EdgeInferenceMetricsTracker
import com.trustableai.koru.runtime.ModelAssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LlmBenchmarkRun(
    val runIndex: Int,
    val outputTokens: Int,
    val promptTokens: Int,
    val latencyMs: Long,
    val ttftMs: Long,
    val tokensPerSecond: Double,
)

data class LlmBenchmarkStrategyResult(
    val strategy: String,
    val backend: String,
    val runs: List<LlmBenchmarkRun>,
    val medianTokensPerSecond: Double,
    val p95TokensPerSecond: Double,
    val medianTtftMs: Long,
    val p95TtftMs: Long,
    val medianLatencyMs: Long,
    val error: String? = null,
) {
    fun toJson(): JSONObject {
        val runsJson = JSONArray()
        runs.forEach { run ->
            runsJson.put(
                JSONObject()
                    .put("runIndex", run.runIndex)
                    .put("outputTokens", run.outputTokens)
                    .put("promptTokens", run.promptTokens)
                    .put("latencyMs", run.latencyMs)
                    .put("ttftMs", run.ttftMs)
                    .put("tokensPerSecond", run.tokensPerSecond),
            )
        }
        return JSONObject()
            .put("strategy", strategy)
            .put("backend", backend)
            .put("medianTokensPerSecond", medianTokensPerSecond)
            .put("p95TokensPerSecond", p95TokensPerSecond)
            .put("medianTtftMs", medianTtftMs)
            .put("p95TtftMs", p95TtftMs)
            .put("medianLatencyMs", medianLatencyMs)
            .put("error", error ?: JSONObject.NULL)
            .put("runs", runsJson)
    }
}

class LlmBenchmarkRunner(
    private val context: Context,
    private val modelAssetManager: ModelAssetManager = ModelAssetManager(context),
) {
    private val tag = "KoruLlmBenchmark"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KoruLlmBenchmark").apply { isDaemon = true }
    }

    fun modelReady(): Boolean {
        val status = modelAssetManager.installStatus()
        return status.isPresent && status.checksumVerified && status.supportsNativeAndroid
    }

    fun modelPath(): String = modelAssetManager.installStatus().filePath

    fun runStrategy(
        config: LiteRtInferenceConfig,
        window: EdgeReasoningWindow = LiteRtPromptFactory.sampleWindow(),
        runs: Int = 3,
        inferenceTimeoutMs: Long = 120_000L,
    ): LlmBenchmarkStrategyResult {
        if (!modelReady()) {
            return failedResult(config, "Model not ready at ${modelPath()}")
        }

        val prompt = LiteRtPromptFactory.promptFor(window, config.promptStyle)
        val measuredRuns = mutableListOf<LlmBenchmarkRun>()
        var inference: LlmInference? = null

        return try {
            inference = createInference(config)
            if (config.runWarmupPass) {
                runSingleInference(
                    inference = inference,
                    config = config,
                    prompt = prompt,
                    runIndex = -1,
                    inferenceTimeoutMs = inferenceTimeoutMs,
                )
            }

            repeat(runs) { index ->
                val run =
                    runSingleInference(
                        inference = inference,
                        config = config,
                        prompt = prompt,
                        runIndex = index,
                        inferenceTimeoutMs = inferenceTimeoutMs,
                    )
                measuredRuns += run
                Log.i(
                    tag,
                    "${config.name} run=$index tok/s=${"%.1f".format(run.tokensPerSecond)} " +
                        "ttft=${run.ttftMs}ms latency=${run.latencyMs}ms out=${run.outputTokens}",
                )
            }

            summarize(config, measuredRuns)
        } catch (error: Exception) {
            Log.e(tag, "${config.name} failed", error)
            failedResult(config, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { inference?.close() }
        }
    }

    fun runMatrix(
        configs: List<LiteRtInferenceConfig> = LiteRtInferenceConfig.benchmarkMatrix(),
        runsPerStrategy: Int = 3,
    ): List<LlmBenchmarkStrategyResult> = configs.map { runStrategy(it, runs = runsPerStrategy) }

    fun writeReport(
        results: List<LlmBenchmarkStrategyResult>,
        outputFile: File,
        deviceModel: String,
    ): File {
        val payload =
            JSONObject()
                .put("deviceModel", deviceModel)
                .put("modelPath", modelPath())
                .put("generatedAtMs", System.currentTimeMillis())
                .put(
                    "strategies",
                    JSONArray().apply { results.forEach { put(it.toJson()) } },
                )
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(payload.toString(2))
        Log.i(tag, "Wrote benchmark report to ${outputFile.absolutePath}")
        return outputFile
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun createInference(config: LiteRtInferenceConfig): LlmInference {
        val options =
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath())
                .setMaxTokens(config.maxTokens)
                .setMaxTopK(config.maxTopK)
                .setPreferredBackend(config.backend)
                .build()
        return LlmInference.createFromOptions(context, options)
    }

    private fun runSingleInference(
        inference: LlmInference,
        config: LiteRtInferenceConfig,
        prompt: String,
        runIndex: Int,
        inferenceTimeoutMs: Long,
    ): LlmBenchmarkRun {
        val future =
            executor.submit(
                Callable {
                    if (config.urgentThreadPriority) {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    }
                    val sessionOptions =
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTopK(config.sessionTopK)
                            .setTopP(config.sessionTopP)
                            .setTemperature(config.sessionTemperature)
                            .setRandomSeed(17)
                            .build()
                    val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                    try {
                        session.addQueryChunk(prompt)
                        val startedAtNanos = System.nanoTime()
                        val promptTokens = tokenCount(session, prompt)
                        var ttftMs = 0L
                        var firstPartialSeen = false
                        val responseFuture =
                            session.generateResponseAsync(
                                ProgressListener { partialResult, done ->
                                    if (!firstPartialSeen && partialResult.isNotBlank()) {
                                        firstPartialSeen = true
                                        ttftMs = elapsedMsSince(startedAtNanos)
                                    }
                                    if (done && ttftMs <= 0L) {
                                        ttftMs = elapsedMsSince(startedAtNanos)
                                    }
                                },
                            )
                        val response = responseFuture.get(inferenceTimeoutMs, TimeUnit.MILLISECONDS)
                        val latencyMs = elapsedMsSince(startedAtNanos)
                        val outputTokens = tokenCount(session, response).coerceAtLeast(1)
                        if (ttftMs <= 0L) ttftMs = latencyMs
                        LlmBenchmarkRun(
                            runIndex = runIndex,
                            outputTokens = outputTokens,
                            promptTokens = promptTokens,
                            latencyMs = latencyMs,
                            ttftMs = ttftMs,
                            tokensPerSecond =
                                EdgeInferenceMetricsTracker.tokensPerSecond(outputTokens, latencyMs),
                        )
                    } finally {
                        session.close()
                    }
                },
            )
        return future.get(inferenceTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun summarize(
        config: LiteRtInferenceConfig,
        runs: List<LlmBenchmarkRun>,
    ): LlmBenchmarkStrategyResult {
        val tokRates = runs.map { it.tokensPerSecond }.sorted()
        val ttfts = runs.map { it.ttftMs }.sorted()
        val latencies = runs.map { it.latencyMs }.sorted()
        return LlmBenchmarkStrategyResult(
            strategy = config.name,
            backend = config.backend.name,
            runs = runs,
            medianTokensPerSecond = percentile(tokRates, 0.50),
            p95TokensPerSecond = percentile(tokRates, 0.95),
            medianTtftMs = percentileLong(ttfts, 0.50),
            p95TtftMs = percentileLong(ttfts, 0.95),
            medianLatencyMs = percentileLong(latencies, 0.50),
        )
    }

    private fun failedResult(
        config: LiteRtInferenceConfig,
        message: String,
    ): LlmBenchmarkStrategyResult =
        LlmBenchmarkStrategyResult(
            strategy = config.name,
            backend = config.backend.name,
            runs = emptyList(),
            medianTokensPerSecond = 0.0,
            p95TokensPerSecond = 0.0,
            medianTtftMs = 0L,
            p95TtftMs = 0L,
            medianLatencyMs = 0L,
            error = message,
        )

    private fun tokenCount(session: LlmInferenceSession, text: String): Int {
        if (text.isBlank()) return 0
        return runCatching { session.sizeInTokens(text) }
            .getOrDefault((text.length / 4).coerceAtLeast(1))
            .coerceAtLeast(0)
    }

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
}
