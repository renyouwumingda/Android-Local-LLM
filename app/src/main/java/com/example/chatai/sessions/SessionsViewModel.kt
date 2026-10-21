package com.example.chatai.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chatai.chat.DEFAULT_SYSTEM_PROMPT
import com.example.chatai.chat.ChatViewModel
import com.example.chatai.data.AppDatabase
import com.example.chatai.data.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionRow(
    val id: Long,
    val title: String,
    val modelId: String,
    val updatedAt: Long
)

data class SessionsUiState(
    val sessions: List<SessionRow> = emptyList(),
    /** False until the first database emission arrives, so callers do not mistake
     *  "not loaded yet" for "no sessions exist". */
    val loaded: Boolean = false
)

class SessionsViewModel(private val context: Context) : ViewModel() {

    private val db = AppDatabase.get(context)

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            db.sessionDao().observeAll().collect { rows ->
                _uiState.update {
                    SessionsUiState(
                        sessions = rows.map {
                            SessionRow(it.id, it.title, it.modelId, it.updatedAt)
                        },
                        loaded = true
                    )
                }
            }
        }
    }

    /** Creates a session for [modelId] and returns its row id. */
    suspend fun createSession(modelId: String): Long {
        val now = System.currentTimeMillis()
        return db.sessionDao().insert(
            SessionEntity(
                title = ChatViewModel.UNTITLED,
                modelId = modelId,
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch {
            db.sessionDao().rename(id, title, System.currentTimeMillis())
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            db.sessionDao().delete(id)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SessionsViewModel(context.applicationContext) as T
            }
    }
}
