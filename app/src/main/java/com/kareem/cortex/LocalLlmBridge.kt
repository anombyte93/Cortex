package com.kareem.cortex

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

/** Thin JVM bridge around the prebuilt llama.cpp Android AAR. */
object LocalLlmBridge {
    const val RUNTIME_VERSION = "llama-android 0.1.1 • llama.cpp b9878"

    data class SelfTestResult(
        val ok: Boolean,
        val text: String,
        val systemInfo: String,
        val tokensGenerated: Int,
        val tokensPerSecond: Float,
        val durationMs: Long,
        val error: String,
    )

    data class CompletionResult(
        val text: String,
        val tokensGenerated: Int,
        val tokensPerSecond: Float,
        val durationMs: Long,
        val modelLoadMs: Long,
        val generationMs: Long,
        val promptEvalMs: Long,
        val tokenGenerationMs: Long,
        val cacheHit: Boolean,
    )

    private val cacheLock = Any()
    private var cachedModel: dev.ffmpegkit.llama.LlamaModel? = null
    private var cachedModelPath: String = ""
    private var cachedAtMs: Long = 0L

    @JvmStatic
    fun runtimeInfo(): String = Llama.getSystemInfo()

    @JvmStatic
    fun selfTest(modelPath: String): SelfTestResult = runBlocking {
        val threads = min(4, max(2, Runtime.getRuntime().availableProcessors() - 2))
        val config = LlamaConfig(
            contextSize = 1024,
            threads = threads,
            gpuLayers = 0,
            temperature = 0.0f,
            topP = 0.9f,
            topK = 20,
            seed = 7,
        )
        val started = System.currentTimeMillis()
        var model: dev.ffmpegkit.llama.LlamaModel? = null
        try {
            val info = Llama.getSystemInfo()
            model = Llama.loadModel(modelPath, config)
            val result = Llama.complete(
                model,
                prompt = "Reply with exactly CORTEX_LOCAL_OK and nothing else. /no_think",
                systemPrompt = "You are a deterministic runtime health check. Output exactly CORTEX_LOCAL_OK. Do not explain. /no_think",
                maxTokens = 48,
            )
            val text = result.text.trim()
            val ok = text.contains("CORTEX_LOCAL_OK", ignoreCase = true)
            SelfTestResult(
                ok = ok,
                text = text,
                systemInfo = info,
                tokensGenerated = result.tokensGenerated,
                tokensPerSecond = result.tokensPerSecond,
                durationMs = System.currentTimeMillis() - started,
                error = if (ok) "" else "Unexpected self-test response",
            )
        } catch (t: Throwable) {
            SelfTestResult(
                ok = false,
                text = "",
                systemInfo = try { Llama.getSystemInfo() } catch (_: Throwable) { "" },
                tokensGenerated = 0,
                tokensPerSecond = 0f,
                durationMs = System.currentTimeMillis() - started,
                error = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""),
            )
        } finally {
            model?.let {
                try { Llama.releaseModel(it) } catch (_: Throwable) {}
            }
        }
    }

    private fun productionThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return min(4, max(2, cores - 2))
    }

    private fun productionConfig(): LlamaConfig = LlamaConfig(
        contextSize = 2048,
        threads = productionThreads(),
        gpuLayers = 0,
        temperature = 0.0f,
        topP = 1.0f,
        topK = 1,
        seed = 7,
    )

    @JvmStatic
    fun completeCached(modelPath: String, prompt: String, systemPrompt: String, maxTokens: Int): CompletionResult = synchronized(cacheLock) {
        runBlocking {
            val config = productionConfig()
            val totalStarted = System.currentTimeMillis()
            var loadMs = 0L
            var hit = cachedModel != null && cachedModelPath == modelPath
            if (!hit) {
                cachedModel?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
                cachedModel = null
                cachedModelPath = ""
                val loadStarted = System.currentTimeMillis()
                cachedModel = Llama.loadModel(modelPath, config)
                loadMs = System.currentTimeMillis() - loadStarted
                cachedModelPath = modelPath
                cachedAtMs = System.currentTimeMillis()
                hit = false
            }
            val model = cachedModel ?: throw IllegalStateException("Local model cache is empty after load")
            val generationStarted = System.currentTimeMillis()
            val result = Llama.complete(model, prompt, systemPrompt, maxTokens)
            val generationMs = System.currentTimeMillis() - generationStarted
            CompletionResult(
                text = result.text.trim(),
                tokensGenerated = result.tokensGenerated,
                tokensPerSecond = result.tokensPerSecond,
                durationMs = System.currentTimeMillis() - totalStarted,
                modelLoadMs = loadMs,
                generationMs = generationMs,
                promptEvalMs = result.promptEvalTimeMs,
                tokenGenerationMs = result.generateTimeMs,
                cacheHit = hit,
            )
        }
    }

    @JvmStatic
    fun completeOnce(modelPath: String, prompt: String, systemPrompt: String, maxTokens: Int): CompletionResult = runBlocking {
        val config = productionConfig()
        val started = System.currentTimeMillis()
        var model: dev.ffmpegkit.llama.LlamaModel? = null
        var loadMs = 0L
        try {
            val loadStarted = System.currentTimeMillis()
            model = Llama.loadModel(modelPath, config)
            loadMs = System.currentTimeMillis() - loadStarted
            val generationStarted = System.currentTimeMillis()
            val result = Llama.complete(model, prompt, systemPrompt, maxTokens)
            val generationMs = System.currentTimeMillis() - generationStarted
            CompletionResult(
                result.text.trim(),
                result.tokensGenerated,
                result.tokensPerSecond,
                System.currentTimeMillis() - started,
                loadMs,
                generationMs,
                result.promptEvalTimeMs,
                result.generateTimeMs,
                false,
            )
        } finally {
            model?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
        }
    }

    @JvmStatic
    fun releaseCached() = synchronized(cacheLock) {
        cachedModel?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
        cachedModel = null
        cachedModelPath = ""
        cachedAtMs = 0L
    }

    @JvmStatic
    fun cachedModelAgeMs(): Long = synchronized(cacheLock) {
        if (cachedModel == null || cachedAtMs <= 0L) 0L else max(0L, System.currentTimeMillis() - cachedAtMs)
    }
}
