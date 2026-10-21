package com.example.chatai.models

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chatai.llama.EngineState
import com.example.chatai.llama.LlamaEngine
import com.example.chatai.llama.LlamaEngineImpl
import com.example.chatai.model.ModelCatalog
import com.example.chatai.model.ModelDescriptor
import com.example.chatai.model.ModelRepository
import com.example.chatai.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelRow(
    val descriptor: ModelDescriptor,
    val installed: Boolean,
    val installedBytes: Long,
    val progress: Float? = null,
    val busy: Boolean = false,
    val statusText: String = "",
    val canRun: Boolean = true
)

data class ModelsUiState(
    val rows: List<ModelRow> = emptyList(),
    val deviceRamMb: Int = 0,
    val engineLabel: String = "未加载",
    val loadedModelId: String? = null,
    val error: String? = null
)

class ModelsViewModel(private val context: Context) : ViewModel() {

    private val repository = ModelRepository(context)
    private val engine: LlamaEngine = LlamaEngineImpl.getInstance(context)

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private val transferJobs = mutableMapOf<String, Job>()
    private val progressByModel = mutableMapOf<String, Float>()
    private val busyByModel = mutableMapOf<String, String>()

    init {
        _uiState.update { it.copy(deviceRamMb = deviceRamMb()) }
        refresh()
        observeEngine()
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engine.state.collect { state ->
                _uiState.update {
                    it.copy(
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
                        loadedModelId = engine.loadedModelId(),
                        error = (state as? EngineState.Error)?.cause?.message ?: it.error
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                ModelCatalog.load(context).map { descriptor ->
                    ModelRow(
                        descriptor = descriptor,
                        installed = repository.isInstalled(descriptor),
                        installedBytes = repository.installedBytes(descriptor),
                        progress = progressByModel[descriptor.id],
                        busy = transferJobs[descriptor.id]?.isActive == true,
                        statusText = busyByModel[descriptor.id] ?: "",
                        canRun = deviceRamMb() >= descriptor.minRamMb
                    )
                }
            }
            _uiState.update { it.copy(rows = rows, loadedModelId = engine.loadedModelId()) }
        }
    }

    fun download(descriptor: ModelDescriptor) {
        if (transferJobs[descriptor.id]?.isActive == true) return

        val required = (descriptor.sizeBytes * 1.2).toLong()
        val available = repository.modelsDir().usableSpace
        if (available in 1 until required) {
            _uiState.update {
                it.copy(error = "存储空间不足：需要约 ${required / 1024 / 1024}MB，可用 ${available / 1024 / 1024}MB")
            }
            return
        }

        busyByModel[descriptor.id] = "下载中"
        transferJobs[descriptor.id] = viewModelScope.launch {
            try {
                repository.download(descriptor).collect { progress ->
                    when (progress) {
                        is TransferProgress.Running -> {
                            progressByModel[descriptor.id] = progress.fraction
                            busyByModel[descriptor.id] =
                                "${formatMb(progress.bytes)} / ${formatMb(progress.total)}"
                        }
                        is TransferProgress.Verifying -> {
                            busyByModel[descriptor.id] = "校验中"
                        }
                        is TransferProgress.Done -> {
                            progressByModel.remove(descriptor.id)
                            busyByModel.remove(descriptor.id)
                            _uiState.update { it.copy(error = null) }
                        }
                        is TransferProgress.Failed -> {
                            progressByModel.remove(descriptor.id)
                            busyByModel.remove(descriptor.id)
                            _uiState.update { it.copy(error = progress.message) }
                        }
                    }
                    refresh()
                }
            } catch (t: Throwable) {
                busyByModel.remove(descriptor.id)
                progressByModel.remove(descriptor.id)
                _uiState.update { it.copy(error = t.message ?: "下载失败") }
            } finally {
                refresh()
            }
        }
    }

    fun cancelDownload(descriptor: ModelDescriptor) {
        transferJobs.remove(descriptor.id)?.cancel()
        busyByModel.remove(descriptor.id)
        progressByModel.remove(descriptor.id)
        refresh()
    }

    fun delete(descriptor: ModelDescriptor) {
        viewModelScope.launch {
            cancelDownload(descriptor)
            withContext(Dispatchers.IO) { repository.delete(descriptor) }
            if (engine.loadedModelId() == descriptor.id) {
                engine.unloadModel()
            }
            refresh()
        }
    }

    fun importFromUri(uri: Uri) {
        val id = "imported-${System.currentTimeMillis()}"
        busyByModel[id] = "导入中"
        transferJobs[id] = viewModelScope.launch {
            try {
                repository.importFromUri(uri).collect { progress ->
                    when (progress) {
                        is TransferProgress.Running -> {
                            progressByModel[id] = progress.fraction
                            busyByModel[id] = "已复制 ${formatMb(progress.bytes)}"
                        }
                        is TransferProgress.Done -> {
                            busyByModel.remove(id)
                            progressByModel.remove(id)
                            _uiState.update {
                                it.copy(error = "已导入 ${progress.file.name}，可在文件目录中手动选择使用")
                            }
                        }
                        is TransferProgress.Failed -> {
                            busyByModel.remove(id)
                            progressByModel.remove(id)
                            _uiState.update { it.copy(error = progress.message) }
                        }
                        is TransferProgress.Verifying -> Unit
                    }
                }
            } catch (t: Throwable) {
                busyByModel.remove(id)
                progressByModel.remove(id)
                _uiState.update { it.copy(error = t.message ?: "导入失败") }
            }
        }
    }

    fun load(descriptor: ModelDescriptor) {
        viewModelScope.launch {
            val path = repository.fileFor(descriptor).absolutePath
            runCatching {
                engine.loadModel(
                    modelId = descriptor.id,
                    path = path,
                    nCtx = descriptor.recommendedNCtx,
                    nThreads = 0
                )
            }.onFailure { t ->
                _uiState.update { it.copy(error = "加载失败：${t.message}") }
            }
            refresh()
        }
    }

    fun unload() {
        viewModelScope.launch {
            engine.unloadModel()
            refresh()
        }
    }

    private fun deviceRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    private fun formatMb(bytes: Long): String = "${bytes / (1024 * 1024)}MB"

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ModelsViewModel(context.applicationContext) as T
            }
    }
}
