package com.example.chatai.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chatai.data.AppDatabase
import com.example.chatai.data.MessageEntity
import com.example.chatai.data.SessionEntity
import com.example.chatai.llama.EngineState
import com.example.chatai.llama.LlamaEngine
import com.example.chatai.llama.LlamaEngineImpl
import com.example.chatai.model.ModelCatalog
import com.example.chatai.model.ModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val interrupted: Boolean = false
) {
    val isUser: Boolean get() = role == "user"
}

data class ChatUiState(
    val sessionId: Long = 0,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val engineReady: Boolean = false,
    val engineLabel: String = "未加载",
    val error: String? = null
)

const val DEFAULT_SYSTEM_PROMPT =
    "你是一个乐于助人的中文助手。回答简洁、准确，使用简体中文。"

class ChatViewModel(
    private val context: Context,
    private val sessionId: Long
) : ViewModel() {

    private val db = AppDatabase.get(context)
    private val repository = ModelRepository(context)
    private val engine: LlamaEngine = LlamaEngineImpl.getInstance(context)

    private val _uiState = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var thinkingFilterEnabled = false
    private var conversationRestored = false

    init {
        observeMessages()
        observeEngine()
        loadSession()
    }

    /**
     * Re-checks whether the session's model is available and loads it if needed.
     * Called when returning from the model manager, where the user may have just
     * installed or deleted a model while this ViewModel already existed.
     */
    fun refreshModel() {
        viewModelScope.launch {
            val session = db.sessionDao().findById(sessionId) ?: return@launch
            val recorded = ModelCatalog.find(context, session.modelId)
            val usable = sequenceOf(recorded)
                .plus(ModelCatalog.load(context).asSequence())
                .filterNotNull()
                .firstOrNull { repository.isInstalled(it) }

            if (usable == null) {
                _uiState.update { it.copy(engineLabel = "未安装模型") }
                return@launch
            }

            if (usable.id != session.modelId) {
                db.sessionDao().setModelId(sessionId, usable.id)
            }
            thinkingFilterEnabled = usable.hasThinkingBlock
            ensureModelLoaded(usable.id, usable.fileName, usable.recommendedNCtx)
            restoreHistory()
        }
    }

    private fun loadSession() {
        viewModelScope.launch {
            val session = db.sessionDao().findById(sessionId)
            if (session == null) return@launch

            _uiState.update { it.copy(title = session.title) }

            // A session records the model it was created with, but that model may
            // have been deleted since, or the user may have installed a different
            // one. Prefer the recorded model, then any installed model.
            val recorded = ModelCatalog.find(context, session.modelId)
            val usable = sequenceOf(recorded)
                .plus(ModelCatalog.load(context).asSequence())
                .filterNotNull()
                .firstOrNull { repository.isInstalled(it) }

            if (usable == null) {
                _uiState.update { it.copy(engineLabel = "未安装模型") }
                return@launch
            }

            if (usable.id != session.modelId) {
                db.sessionDao().setModelId(sessionId, usable.id)
            }

            thinkingFilterEnabled = usable.hasThinkingBlock
            ensureModelLoaded(usable.id, usable.fileName, usable.recommendedNCtx)
            restoreHistory()
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            db.messageDao().observeForSession(sessionId).collect { rows ->
                _uiState.update { state ->
                    state.copy(
                        messages = rows.map {
                            ChatMessage(it.id, it.role, it.content, it.interrupted)
                        }
                    )
                }
            }
        }
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engine.state.collect { state ->
                _uiState.update {
                    it.copy(
                        engineReady = state is EngineState.ModelReady,
                        engineLabel = when (state) {
                            is EngineState.Uninitialized -> "未初始化"
                            is EngineState.Initializing -> "初始化中"
                            is EngineState.Initialized -> "未加载模型"
                            is EngineState.LoadingModel -> "加载模型中"
                            is EngineState.UnloadingModel -> "卸载中"
                            is EngineState.ModelReady -> "就绪"
                            is EngineState.Benchmarking -> "性能测试中"
                            is EngineState.ProcessingSystemPrompt -> "准备对话中"
                            is EngineState.ProcessingUserPrompt -> "处理中"
                            is EngineState.Generating -> "生成中"
                            is EngineState.Error -> "错误"
                        },
                        error = (state as? EngineState.Error)?.cause?.message ?: it.error
                    )
                }
            }
        }
    }

    /**
     * Replays stored turns through the native layer so a reopened conversation
     * keeps its context. The KV cache is not persisted, so this is the only way
     * to make follow-up questions behave as if the session had never closed.
     */
    private suspend fun restoreHistory() {
        if (conversationRestored) return
        val history = db.messageDao().listForSession(sessionId)
            .filter { it.content.isNotBlank() }
        if (history.isEmpty()) {
            conversationRestored = true
            return
        }

        runCatching {
            engine.resetConversation(DEFAULT_SYSTEM_PROMPT)
            for (message in history) {
                when (message.role) {
                    "user" -> engine.replayUserTurn(message.content)
                    "assistant" -> engine.replayAssistantTurn(message.content)
                }
            }
            conversationRestored = true
        }.onFailure { t ->
            _uiState.update { it.copy(error = "恢复对话上下文失败：${t.message}") }
        }
    }

    private suspend fun ensureModelLoaded(modelId: String, fileName: String, nCtx: Int) {
        if (engine.loadedModelId() == modelId && engine.state.value is EngineState.ModelReady) {
            return
        }
        val path = java.io.File(repository.modelsDir(), fileName).absolutePath
        runCatching {
            engine.loadModel(modelId, path, nCtx, 0)
            engine.resetConversation(DEFAULT_SYSTEM_PROMPT)
        }.onFailure { t ->
            _uiState.update { it.copy(error = "加载模型失败：${t.message}") }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _uiState.value.isGenerating) return

        generationJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()

                // Persist the user turn first so it survives a crash mid-generation.
                db.messageDao().insert(
                    MessageEntity(
                        sessionId = sessionId,
                        role = "user",
                        content = prompt,
                        createdAt = now
                    )
                )
                maybeRetitle(prompt)
                db.sessionDao().touch(sessionId, now)

                _uiState.update { it.copy(isGenerating = true, streamingText = "", error = null) }

                val builder = StringBuilder()
                val filter = ThinkingBlockFilter(thinkingFilterEnabled)
                var failed: String? = null

                engine.sendUserPrompt(prompt, MAX_NEW_TOKENS)
                    .catch { t ->
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        failed = t.message
                    }
                    .collect { piece ->
                        val visible = filter.feed(piece)
                        if (visible.isNotEmpty()) {
                            builder.append(visible)
                            _uiState.update { it.copy(streamingText = builder.toString()) }
                        }
                    }

                val tail = filter.finish()
                if (tail.isNotEmpty()) {
                    builder.append(tail)
                }

                val finalText = builder.toString()
                // Only persist a reply once it has content; an empty row would
                // render as a dangling "..." bubble after a stop or a failure.
                if (finalText.isNotBlank() || failed != null) {
                    db.messageDao().insert(
                        MessageEntity(
                            sessionId = sessionId,
                            role = "assistant",
                            content = finalText.ifBlank { "（生成失败）" },
                            createdAt = System.currentTimeMillis(),
                            interrupted = failed != null
                        )
                    )
                }
                db.sessionDao().touch(sessionId, System.currentTimeMillis())

                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = failed?.let { msg -> "生成中断：$msg" }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The user pressed stop, or the screen went away. Keep whatever
                // text was already produced and mark the turn interrupted.
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isGenerating = false, streamingText = "", error = t.message ?: "未知错误")
                }
            }
        }
    }

    fun stop() {
        engine.stopGeneration()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false, streamingText = "") }
    }

    /** Clears the KV cache so the next turn starts from the system prompt again. */
    fun newConversation() {
        viewModelScope.launch {
            runCatching { engine.resetConversation(DEFAULT_SYSTEM_PROMPT) }
            _uiState.update { it.copy(messages = emptyList(), streamingText = "", error = null) }
        }
    }

    private suspend fun maybeRetitle(firstPrompt: String) {
        val session = db.sessionDao().findById(sessionId) ?: return
        if (session.title != UNTITLED) return
        val title = firstPrompt.replace(Regex("\\s+"), " ").take(20)
        db.sessionDao().rename(sessionId, title, System.currentTimeMillis())
        _uiState.update { it.copy(title = title) }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }

    companion object {
        const val MAX_NEW_TOKENS = 512
        const val UNTITLED = "新对话"

        fun factory(context: Context, sessionId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(context.applicationContext, sessionId) as T
            }
    }
}
