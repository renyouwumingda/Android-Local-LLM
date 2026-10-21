package com.example.chatai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chatai.chat.ChatMessage
import com.example.chatai.chat.ChatViewModel
import com.example.chatai.llama.ContextUsage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSessions: () -> Unit,
    onOpenModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the newest content while a response streams in.
    LaunchedEffect(state.messages.size, state.streamingText.length) {
        val total = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { "新对话" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        EngineStatusLine(state.engineLabel, state.contextUsage)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Default.Menu, contentDescription = "会话列表")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Default.Add, contentDescription = "模型管理")
                    }
                }
            )
        },
        bottomBar = {
            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = state.engineReady || state.isGenerating,
                isGenerating = state.isGenerating,
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.send(input)
                        input = ""
                    }
                },
                onStop = viewModel::stop
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                EmptyState(engineReady = state.engineReady)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (state.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            MessageBubble(
                                ChatMessage(
                                    id = -1,
                                    role = "assistant",
                                    content = state.streamingText
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Engine label plus a thin context-window gauge. */
@Composable
private fun EngineStatusLine(label: String, usage: ContextUsage?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (label == "就绪") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (usage != null) {
            Spacer(Modifier.width(10.dp))
            ContextGauge(usage)
        }
    }
}

@Composable
private fun ContextGauge(usage: ContextUsage) {
    val warn = usage.fraction >= 0.85f
    val color = if (warn) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { usage.fraction },
            modifier = Modifier
                .width(44.dp)
                .height(4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "上下文 ${usage.percent}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (warn) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(engineReady: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (engineReady) "开始对话吧" else "还没有可用的模型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (engineReady) "模型完全运行在这台手机上" else "点右上角 + 下载或加载一个模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.content.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (message.interrupted) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "已中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…", style = MaterialTheme.typography.bodyMedium) },
                enabled = enabled && !isGenerating,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
            )
            Spacer(Modifier.width(8.dp))
            FilledCircleButton(
                isGenerating = isGenerating,
                enabled = enabled && (isGenerating || value.isNotBlank()),
                onSend = onSend,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun FilledCircleButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = if (enabled) {
            if (isGenerating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape
    ) {
        IconButton(
            onClick = if (isGenerating) onStop else onSend,
            enabled = enabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isGenerating) Icons.Default.Close else Icons.Default.Send,
                contentDescription = if (isGenerating) "停止" else "发送",
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
