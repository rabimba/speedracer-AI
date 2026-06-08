package com.trustableai.koru.runtime.reasoner

import android.content.Context
import android.os.Process
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.RuntimeAccelerator
import com.trustableai.koru.runtime.EdgeInferenceMetricsTracker
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.ModelAssetManager
import com.trustableai.koru.runtime.PhraseCatalog
import com.trustableai.koru.runtime.benchmark.LiteRtInferenceConfig
import com.trustableai.koru.runtime.benchmark.LiteRtPromptFactory
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LiteRtLmReasoner(
    private val context: Context,
    private val modelAssetManager: ModelAssetManager,
    private val phraseCatalog: PhraseCatalog,
    private var coachId: String,
) : OnDeviceReasoner {
    override val backend: RuntimeBackend = RuntimeBackend.LITERTLM
    private val tag = "KoruLiteRtReasoner"
    private val inferenceConfig = LiteRtInferenceConfig.PRODUCTION
    private val inferenceLock = Any()
    private val executorLock = Any()
    private var llmInference: LlmInference? = null
    private var inferenceExecutor: ExecutorService = newInferenceExecutor()
    private var usable = false
    @Volatile private var disabledUntilMs = 0L

    fun setCoach(coachId: String) {
        this.coachId = coachId
    }

    override suspend fun warmup(): LiveBackendStatus {
        val nowMs = System.currentTimeMillis()
        if (nowMs < disabledUntilMs) {
            return LiveBackendStatus(
                backend = backend,
                state = LiveBackendState.UNAVAILABLE,
                detail = "MediaPipe LiteRT temporarily disabled after an inference timeout.",
                model = "Gemma 4 E2B",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.EDGE),
                accelerator = RuntimeAccelerator.NONE,
            )
        }
        val dependencyPresent = runCatching {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        }.isSuccess
        val installStatus = modelAssetManager.installStatus()
        val modelReady =
            dependencyPresent &&
                installStatus.isPresent &&
                installStatus.checksumVerified &&
                installStatus.supportsNativeAndroid

        val creationResult =
            if (modelReady) {
                runCatching {
                    val options =
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(installStatus.filePath)
                            .setMaxTokens(inferenceConfig.maxTokens)
                            .setMaxTopK(inferenceConfig.maxTopK)
                            .setPreferredBackend(inferenceConfig.backend)
                            .build()
                    val warmedInference =
                        synchronized(inferenceLock) {
                            llmInference?.close()
                            LlmInference.createFromOptions(context, options).also { llmInference = it }
                        }
                    if (inferenceConfig.runWarmupPass) {
                        runWarmupInference(warmedInference)
                    }
                }
            } else {
                null
            }

        usable = creationResult?.isSuccess == true
        if (usable) disabledUntilMs = 0L
        val detail = when {
            usable ->
                "MediaPipe LiteRT GPU reasoner ready at ${installStatus.filePath}."
            creationResult?.exceptionOrNull() != null ->
                "MediaPipe LiteRT warmup failed: ${creationResult.exceptionOrNull()?.message ?: "unknown error"}"
            !dependencyPresent ->
                "LiteRT-LM dependency not present at runtime."
            installStatus.issue != null ->
                installStatus.issue
            else ->
                modelAssetManager.recommendedDownloadAction()
        }

        return LiveBackendStatus(
            backend = backend,
            state = when {
                usable -> LiveBackendState.READY
                creationResult?.exceptionOrNull() != null -> LiveBackendState.ERROR
                else -> LiveBackendState.UNAVAILABLE
            },
            detail = detail,
            model = "Gemma 4 E2B",
            usesOnDeviceModel = usable,
            supportedPaths = listOf(CoachingPath.EDGE),
            accelerator = if (usable) RuntimeAccelerator.MEDIAPIPE_LITERT else RuntimeAccelerator.NONE,
        )
    }

    override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision? {
        if (System.currentTimeMillis() < disabledUntilMs) return null
        if (!usable) return null
        val inference = synchronized(inferenceLock) { llmInference } ?: return null
        val inferenceResult = runBoundedInference(inference, promptFor(window), window.triggerId) ?: return null
        return parseDecision(inferenceResult.response, window)
    }

    override suspend fun close() {
        val retiredInference =
            synchronized(inferenceLock) {
                val current = llmInference
                llmInference = null
                usable = false
                current
            }
        closeInferenceAsync(retiredInference)
        synchronized(executorLock) {
            inferenceExecutor.shutdownNow()
            inferenceExecutor = newInferenceExecutor()
        }
    }

    private data class InferenceResult(
        val response: String,
        val outputTokens: Int,
        val latencyMs: Long,
    )

    private fun runBoundedInference(
        inference: LlmInference,
        prompt: String,
        triggerId: String,
    ): InferenceResult? {
        val future =
            synchronized(executorLock) {
                inferenceExecutor.submit(
                    Callable {
                        if (inferenceConfig.urgentThreadPriority) {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                        }
                        val sessionOptions =
                            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setTopK(inferenceConfig.sessionTopK)
                                .setTopP(inferenceConfig.sessionTopP)
                                .setTemperature(inferenceConfig.sessionTemperature)
                                .setRandomSeed(17)
                                .build()
                        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                        try {
                            session.addQueryChunk(prompt)
                            val startedAtNanos = System.nanoTime()
                            val promptTokens = tokenCount(session, prompt)
                            val responseFuture =
                                session.generateResponseAsync(
                                    ProgressListener { partialResult, done ->
                                        val elapsedMs = elapsedMsSince(startedAtNanos)
                                        val partialTokens = tokenCount(session, partialResult)
                                        if (partialTokens > 0) {
                                            KoruSessionBus.tryEmitEdgeInferenceMetrics(
                                                EdgeInferenceMetricsTracker.build(
                                                    backend = backend,
                                                    triggerId = triggerId,
                                                    outputTokens = partialTokens,
                                                    latencyMs = elapsedMs,
                                                    promptTokens = promptTokens,
                                                ),
                                            )
                                        }
                                        if (done) {
                                            val outputTokens = tokenCount(session, partialResult)
                                            KoruSessionBus.tryEmitEdgeInferenceMetrics(
                                                EdgeInferenceMetricsTracker.build(
                                                    backend = backend,
                                                    triggerId = triggerId,
                                                    outputTokens = outputTokens,
                                                    latencyMs = elapsedMs,
                                                    promptTokens = promptTokens,
                                                ),
                                            )
                                        }
                                    },
                                )
                            val response = responseFuture.get(MAX_INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            val latencyMs = elapsedMsSince(startedAtNanos)
                            val outputTokens = tokenCount(session, response)
                            KoruSessionBus.tryEmitEdgeInferenceMetrics(
                                EdgeInferenceMetricsTracker.build(
                                    backend = backend,
                                    triggerId = triggerId,
                                    outputTokens = outputTokens,
                                    latencyMs = latencyMs,
                                    promptTokens = promptTokens,
                                ),
                            )
                            InferenceResult(
                                response = response,
                                outputTokens = outputTokens,
                                latencyMs = latencyMs,
                            )
                        } finally {
                            session.close()
                        }
                    },
                )
            }

        return try {
            future.get(MAX_INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            retireAfterTimeout(inference, triggerId)
            null
        } catch (error: Exception) {
            Log.w(tag, "LiteRT reasoner failed for $triggerId", error)
            null
        }
    }

    private fun tokenCount(session: LlmInferenceSession, text: String): Int {
        if (text.isBlank()) return 0
        return runCatching { session.sizeInTokens(text) }
            .getOrDefault(estimateTokenCount(text))
            .coerceAtLeast(0)
    }

    private fun estimateTokenCount(text: String): Int {
        return (text.length / 4.0).toInt().coerceAtLeast(1)
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long {
        return ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(1L)
    }

    private fun retireAfterTimeout(inference: LlmInference, triggerId: String) {
        Log.w(tag, "LiteRT reasoner timed out for $triggerId after ${MAX_INFERENCE_TIMEOUT_MS}ms; disabling EDGE GPU lane")
        disabledUntilMs = System.currentTimeMillis() + TIMEOUT_BACKOFF_MS
        synchronized(inferenceLock) {
            if (llmInference === inference) {
                llmInference = null
                usable = false
                closeInferenceAsync(inference)
            }
        }
        synchronized(executorLock) {
            inferenceExecutor.shutdownNow()
            inferenceExecutor = newInferenceExecutor()
        }
    }

    private fun closeInferenceAsync(inference: LlmInference?) {
        if (inference == null) return
        Thread(
            {
                runCatching { inference.close() }
                    .onFailure { error -> Log.w(tag, "LiteRT inference close failed", error) }
            },
            "KoruLiteRtClose",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun newInferenceExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "KoruLiteRtInference").apply { isDaemon = true }
        }
    }

    private fun promptFor(window: EdgeReasoningWindow): String {
        return LiteRtPromptFactory.promptFor(window, inferenceConfig.promptStyle)
    }

    private fun runWarmupInference(inference: LlmInference) {
        runCatching {
            synchronized(executorLock) {
                inferenceExecutor.submit(
                    Callable {
                        if (inferenceConfig.urgentThreadPriority) {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                        }
                        val sessionOptions =
                            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setTopK(inferenceConfig.sessionTopK)
                                .setTopP(inferenceConfig.sessionTopP)
                                .setTemperature(inferenceConfig.sessionTemperature)
                                .setRandomSeed(17)
                                .build()
                        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                        try {
                            session.addQueryChunk("{\"speak\":false,\"action\":\"MAINTAIN\",\"priority\":1,\"text\":\"warmup\",\"confidence\":0.0}")
                            session.generateResponseAsync().get(WARMUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        } finally {
                            session.close()
                        }
                    },
                ).get(WARMUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }.onFailure { error ->
            Log.w(tag, "LiteRT warmup pass failed; continuing with cold engine", error)
        }
    }

    private fun parseDecision(response: String, window: EdgeReasoningWindow): ReasonerDecision {
        val phraseId = phraseCatalog.phraseIdFor(window.actionHint, window.skillLevel, coachId)
        val jsonText = extractJson(response)
        if (jsonText == null) {
            return structuredFallbackDecision(window, backend, phraseId, confidence = 0.72)
        }

        return runCatching {
            val json = JSONObject(jsonText)
            val action =
                runCatching {
                    CoachAction.valueOf(json.optString("action", window.actionHint.name))
                }.getOrDefault(window.actionHint)
            val text = json.optString("text", window.suggestedText).ifBlank { window.suggestedText }
            val priority = json.optInt("priority", window.priority).coerceIn(1, 3)
            ReasonerDecision(
                speak = json.optBoolean("speak", true),
                action = action,
                priority = priority,
                phraseId = phraseId,
                confidence = json.optDouble("confidence", 0.72).coerceIn(0.0, 1.0),
                text = text.take(MAX_TEXT_CHARS),
            )
        }.getOrElse {
            structuredFallbackDecision(window, backend, phraseId, confidence = 0.72)
        }
    }

    private fun extractJson(response: String): String? {
        val trimmed = response.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    companion object {
        private const val MAX_TEXT_CHARS = 120
        private const val MAX_INFERENCE_TIMEOUT_MS = 650L
        private const val WARMUP_TIMEOUT_MS = 15_000L
        private const val TIMEOUT_BACKOFF_MS = 30_000L
    }
}
