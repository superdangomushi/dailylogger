package com.ishilab.transcriber.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ishilab.transcriber.model.ModelManager
import com.ishilab.transcriber.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TranscriptItem(val name: String, val path: String, val sizeBytes: Long)

data class UiState(
    val downloadedModels: Set<WhisperModel> = emptySet(),
    val downloading: WhisperModel? = null,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,
    val transcripts: List<TranscriptItem> = emptyList(),
) {
    val anyModelReady: Boolean get() = downloadedModels.isNotEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val modelManager = ModelManager(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        refresh()
    }

    fun refresh() {
        val downloaded = WhisperModel.entries.filter { modelManager.isDownloaded(it) }.toSet()
        val dir = File(getApplication<Application>().filesDir, "transcripts")
        val items = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?.map { TranscriptItem(it.name, it.absolutePath, it.length()) }
            ?: emptyList()
        _ui.update { it.copy(downloadedModels = downloaded, transcripts = items) }
    }

    fun download(model: WhisperModel) {
        if (_ui.value.downloading != null) return
        _ui.update { it.copy(downloading = model, downloadProgress = 0f, downloadError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelManager.download(model) { p ->
                        _ui.update { it.copy(downloadProgress = if (p < 0f) -1f else p) }
                    }
                }
                _ui.update { it.copy(downloading = null) }
                refresh()
            } catch (e: Exception) {
                _ui.update {
                    it.copy(downloading = null, downloadError = e.message ?: "ダウンロード失敗")
                }
            }
        }
    }
}
