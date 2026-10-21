package com.example.chatai.llama

import android.content.Context
import android.util.Log
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper around llama.cpp. All native calls are serialised onto a
 * single-threaded dispatcher because llama_context is not thread safe.
 */
class LlamaEngineImpl private constructor(
    private val nativeLibDir: String
) : LlamaEngine {

    companion object {
        private const val TAG = "LlamaEngineImpl"

        @Volatile
        private var instance: LlamaEngine? = null

        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                instance ?: run {
                    val dir = context.applicationInfo.nativeLibraryDir
                    require(dir.isNotBlank()) { "Native library directory is unavailable" }
                    LlamaEngineImpl(dir).also { instance = it }
                }
            }
    }

    @FastNative
    private external fun init(nativeLibDir: String)

    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun prepare(nCtx: Int, nThreads: Int): Int

    @FastNative
    private external fun nativeSetSampling(temp: Float, topK: Int, topP: Float, seed: Int)

    @FastNative
    private external fun systemInfo(): String

    @FastNative
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative
    private external fun processSystemPrompt(systemPrompt: String): Int

    @FastNative
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative
    private external fun generateNextToken(): String?

    @FastNative
    private external fun replayTurn(content: String, isUser: Boolean): Int

    @FastNative
    private external fun nativeContextUsage(): IntArray

    @FastNative
    private external fun unload()

    @FastNative
    private external fun shutdown()

    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private var activeModelId: String? = null

    init {
        llamaScope.launch {
            try {
                _state.value = EngineState.Initializing
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = EngineState.Initialized
                Log.i(TAG, "Native library ready: ${systemInfo()}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialise native library", t)
                _state.value = EngineState.Error(t)
            }
        }
    }

    override suspend fun loadModel(
        modelId: String,
        path: String,
        nCtx: Int,
        nThreads: Int
    ) {
        withContext(llamaDispatcher) {
            val current = _state.value
            check(current is EngineState.Initialized || current is EngineState.ModelReady) {
                "Cannot load a model while ${current::class.simpleName}"
            }

            try {
                val file = File(path)
                require(file.exists()) { "Model file not found: $path" }
                require(file.isFile) { "Not a file: $path" }
                require(file.canRead()) { "Cannot read: $path" }

                _state.value = EngineState.LoadingModel

                if (load(path) != 0) {
                    throw IOException("Model load failed (unsupported format or corrupt file)")
                }
                if (prepare(nCtx, nThreads) != 0) {
                    throw IOException("Failed to create llama context")
                }

                cancelRequested = false
                activeModelId = modelId
                _state.value = EngineState.ModelReady
                Log.i(TAG, "Model ready: $modelId (n_ctx=$nCtx)")
            } catch (t: Throwable) {
                Log.e(TAG, "Model load failed", t)
                _state.value = EngineState.Error(t)
                throw t
            }
        }
    }

    override suspend fun setSampling(temp: Float, topK: Int, topP: Float, seed: Int) =
        withContext(llamaDispatcher) {
            require(_state.value is EngineState.ModelReady) {
                "Sampling can only change while a model is ready"
            }
            nativeSetSampling(temp, topK, topP, seed)
        }

    /**
     * Starts a fresh conversation: the native side clears the KV cache and
     * re-applies the system prompt. Safe to call repeatedly.
     */
    override suspend fun resetConversation(systemPrompt: String) =
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "System prompt must not be blank" }
            require(_state.value is EngineState.ModelReady) {
                "Cannot reset conversation while ${_state.value::class.simpleName}"
            }

            _state.value = EngineState.ProcessingSystemPrompt
            val code = processSystemPrompt(systemPrompt)
            if (code != 0) {
                val error = IllegalStateException("processSystemPrompt failed with code $code")
                _state.value = EngineState.Error(error)
                throw error
            }
            _state.value = EngineState.ModelReady
        }

    override fun sendUserPrompt(message: String, maxTokens: Int): Flow<String> = flow {
        require(message.isNotBlank()) { "Prompt must not be blank" }
        require(_state.value is EngineState.ModelReady) {
            "Cannot send a prompt while ${_state.value::class.simpleName}"
        }

        try {
            cancelRequested = false
            _state.value = EngineState.ProcessingUserPrompt

            val code = processUserPrompt(message, maxTokens)
            if (code != 0) {
                Log.e(TAG, "processUserPrompt failed with code $code")
                _state.value = EngineState.ModelReady
                return@flow
            }

            _state.value = EngineState.Generating
            while (!cancelRequested) {
                val piece = generateNextToken() ?: break
                if (piece.isNotEmpty()) emit(piece)
            }

            _state.value = EngineState.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Generation cancelled by collector")
            _state.value = EngineState.ModelReady
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Generation failed", t)
            _state.value = EngineState.Error(t)
            throw t
        }
    }.flowOn(llamaDispatcher)

    override fun stopGeneration() {
        cancelRequested = true
    }

    override suspend fun replayUserTurn(message: String) {
        withContext(llamaDispatcher) {
            require(_state.value is EngineState.ModelReady) {
                "Cannot replay a turn while ${_state.value::class.simpleName}"
            }
            val code = replayTurn(message, true)
            if (code != 0) {
                throw IllegalStateException("replayTurn(user) failed with code $code")
            }
        }
    }

    override suspend fun replayAssistantTurn(message: String) {
        withContext(llamaDispatcher) {
            require(_state.value is EngineState.ModelReady) {
                "Cannot replay a turn while ${_state.value::class.simpleName}"
            }
            val code = replayTurn(message, false)
            if (code != 0) {
                throw IllegalStateException("replayTurn(assistant) failed with code $code")
            }
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            require(_state.value is EngineState.ModelReady) { "Benchmark needs a loaded model" }
            _state.value = EngineState.Benchmarking
            try {
                benchModel(pp, tg, pl, nr)
            } finally {
                _state.value = EngineState.ModelReady
            }
        }

    override fun unloadModel() {
        cancelRequested = true
        runBlocking(llamaDispatcher) {
            val current = _state.value
            if (current is EngineState.ModelReady || current is EngineState.Error) {
                _state.value = EngineState.UnloadingModel
                unload()
                activeModelId = null
                _state.value = EngineState.Initialized
            }
        }
    }

    override fun destroy() {
        cancelRequested = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is EngineState.Uninitialized -> Unit
                is EngineState.Initialized -> shutdown()
                else -> {
                    unload()
                    shutdown()
                }
            }
            _state.value = EngineState.Uninitialized
        }
        llamaScope.cancel()
        instance = null
    }

    override fun loadedModelId(): String? = activeModelId

    override fun contextUsage(): ContextUsage? {
        if (_state.value !is EngineState.ModelReady &&
            _state.value !is EngineState.Generating &&
            _state.value !is EngineState.ProcessingUserPrompt
        ) {
            return null
        }
        val raw = nativeContextUsage() ?: return null
        if (raw.size < 2) return null
        return ContextUsage(usedTokens = raw[0], totalTokens = raw[1])
    }
}
