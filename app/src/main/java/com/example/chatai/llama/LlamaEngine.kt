package com.example.chatai.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Core inference operations exposed to the app layer. */
interface LlamaEngine {

    val state: StateFlow<EngineState>

    /** Loads a GGUF model and creates its context. */
    suspend fun loadModel(modelId: String, path: String, nCtx: Int, nThreads: Int)

    /** Rebuilds the sampler. Only valid while a model is ready. */
    suspend fun setSampling(temp: Float, topK: Int, topP: Float, seed: Int)

    /**
     * Clears the KV cache and applies [systemPrompt]. Call this when starting a
     * new conversation; repeated calls are supported.
     */
    suspend fun resetConversation(systemPrompt: String)

    /** Streams generated text fragments until EOG, the token budget, or [stopGeneration]. */
    fun sendUserPrompt(message: String, maxTokens: Int): Flow<String>

    /**
     * Feeds a past user turn into the KV cache without generating, so a reopened
     * conversation keeps its context.
     */
    suspend fun replayUserTurn(message: String)

    /** Feeds a past assistant reply into the KV cache without generating. */
    suspend fun replayAssistantTurn(message: String)

    /** Requests that the in-flight generation stop after the current token. */
    fun stopGeneration()

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String

    /** Frees the model and context, leaving the native backend initialised. */
    fun unloadModel()

    /** Frees everything, including the ggml backends. */
    fun destroy()

    fun loadedModelId(): String?

    /**
     * Tokens currently held in the KV cache versus the model's context window.
     * Returns null when no model is loaded.
     */
    fun contextUsage(): ContextUsage?
}

/** [usedTokens] covers the system prompt plus replayed history plus generated replies. */
data class ContextUsage(val usedTokens: Int, val totalTokens: Int) {
    val fraction: Float
        get() = if (totalTokens <= 0) 0f else (usedTokens.toFloat() / totalTokens).coerceIn(0f, 1f)

    val percent: Int get() = (fraction * 100).toInt()
}

sealed class EngineState {
    data object Uninitialized : EngineState()
    data object Initializing : EngineState()
    data object Initialized : EngineState()
    data object LoadingModel : EngineState()
    data object UnloadingModel : EngineState()
    data object ModelReady : EngineState()
    data object Benchmarking : EngineState()
    data object ProcessingSystemPrompt : EngineState()
    data object ProcessingUserPrompt : EngineState()
    data object Generating : EngineState()
    data class Error(val cause: Throwable) : EngineState()
}

val EngineState.isModelLoaded: Boolean
    get() = this is EngineState.ModelReady ||
        this is EngineState.Benchmarking ||
        this is EngineState.ProcessingSystemPrompt ||
        this is EngineState.ProcessingUserPrompt ||
        this is EngineState.Generating

val EngineState.canStartConversation: Boolean
    get() = this is EngineState.ModelReady
