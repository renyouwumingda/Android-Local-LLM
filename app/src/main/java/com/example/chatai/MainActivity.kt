package com.example.chatai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatai.chat.ChatViewModel
import com.example.chatai.model.ModelCatalog
import com.example.chatai.model.ModelRepository
import com.example.chatai.models.ModelsScreen
import com.example.chatai.models.ModelsViewModel
import com.example.chatai.sessions.SessionsViewModel
import com.example.chatai.ui.ChatScreen
import com.example.chatai.ui.SessionsDrawer
import com.example.chatai.ui.theme.ChatAITheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatAIApp()
                }
            }
        }
    }
}

@Composable
private fun ChatAIApp() {
    val context = LocalContext.current
    val sessionsViewModel: SessionsViewModel = viewModel(
        factory = SessionsViewModel.factory(context)
    )
    val sessionsState by sessionsViewModel.uiState.collectAsStateWithLifecycle()

    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    var showModels by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Prefer a model that is already on disk; a fresh session pointing at an
    // uninstalled model leaves the composer permanently disabled.
    fun pickInstalledModelId(): String {
        val repository = ModelRepository(context)
        return ModelCatalog.load(context).firstOrNull { repository.isInstalled(it) }?.id
            ?: ModelCatalog.load(context).firstOrNull()?.id
            ?: "none"
    }

    // Pick a sensible default session once the database has actually loaded.
    // Keying off the session count alone fires while the list is still empty,
    // which used to create a brand-new session on every launch.
    LaunchedEffect(sessionsState.loaded) {
        if (sessionsState.loaded && activeSessionId == null) {
            val existing = sessionsState.sessions.firstOrNull()
            activeSessionId = existing?.id
                ?: sessionsViewModel.createSession(pickInstalledModelId())
        }
    }

    val sessionId = activeSessionId
    if (sessionId == null) {
        return
    }

    val chatViewModel: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = ChatViewModel.factory(context, sessionId)
    )
    val modelsViewModel: ModelsViewModel = viewModel(
        key = "models",
        factory = ModelsViewModel.factory(context)
    )

    if (showModels) {
        // Without this the system back button finishes the activity instead of
        // returning to the conversation.
        BackHandler { showModels = false }
        ModelsScreen(
            viewModel = modelsViewModel,
            onBack = { showModels = false }
        )
        return
    }

    // The user may have installed or removed a model while this screen was hidden.
    LaunchedEffect(showModels) {
        if (!showModels) chatViewModel.refreshModel()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawer(
                sessions = sessionsState.sessions,
                activeSessionId = sessionId,
                onSelect = { id ->
                    activeSessionId = id
                    scope.launch { drawerState.close() }
                },
                onCreate = {
                    scope.launch {
                        activeSessionId = sessionsViewModel.createSession(pickInstalledModelId())
                        drawerState.close()
                    }
                },
                onDelete = { id ->
                    sessionsViewModel.delete(id)
                    if (id == sessionId) activeSessionId = null
                }
            )
        }
    ) {
        ChatScreen(
            viewModel = chatViewModel,
            onOpenSessions = { scope.launch { drawerState.open() } },
            onOpenModels = { showModels = true }
        )
    }
}
