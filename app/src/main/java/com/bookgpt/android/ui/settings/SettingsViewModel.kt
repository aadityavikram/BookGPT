package com.bookgpt.android.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookgpt.android.data.backup.BackupManager
import com.bookgpt.android.data.library.LibraryRepository
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.data.storage.BookStorage
import com.bookgpt.android.domain.Config
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKeyDraft: String = "",
    val hasApiKey: Boolean = false,
    val saved: Boolean = false,
    val libraryPath: String? = null,
    val chatModel: String = Config.DEFAULT_OPENAI_MODEL,
    val embeddingModel: String = Config.DEFAULT_EMBEDDING_MODEL,
    val chatModels: List<String> = Config.CHAT_MODELS,
    val embeddingModels: List<String> = Config.EMBEDDING_MODELS,
    val modelMessage: String? = null,
    val backupMessage: String? = null,
    val backupInProgress: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val bookStorage: BookStorage,
    private val libraryRepository: LibraryRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val draft = MutableStateFlow(settingsRepository.getApiKey())
    private val saved = MutableStateFlow(false)
    private val chatModel = MutableStateFlow(settingsRepository.getChatModel())
    private val embeddingModel = MutableStateFlow(settingsRepository.getEmbeddingModel())
    private val modelMessage = MutableStateFlow<String?>(null)
    private val backupMessage = MutableStateFlow<String?>(null)
    private val backupInProgress = MutableStateFlow(false)

    private val baseState = combine(
        draft,
        settingsRepository.hasApiKey,
        saved,
        settingsRepository.libraryTreeUri,
    ) { draftKey, hasKey, wasSaved, _ ->
        BaseState(draftKey, hasKey, wasSaved, bookStorage.displayPath())
    }

    private val modelState = combine(
        baseState,
        chatModel,
        embeddingModel,
        modelMessage,
    ) { base, selectedChatModel, selectedEmbeddingModel, message ->
        ModelState(base, selectedChatModel, selectedEmbeddingModel, message)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        modelState,
        backupMessage,
        backupInProgress,
    ) { model, backupStatus, isBackingUp ->
        SettingsUiState(
            apiKeyDraft = model.base.apiKeyDraft,
            hasApiKey = model.base.hasApiKey,
            saved = model.base.saved,
            libraryPath = model.base.libraryPath,
            chatModel = model.chatModel,
            embeddingModel = model.embeddingModel,
            modelMessage = model.message,
            backupMessage = backupStatus,
            backupInProgress = isBackingUp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onApiKeyChange(value: String) {
        draft.value = value
        saved.value = false
    }

    fun save() {
        settingsRepository.setApiKey(draft.value)
        saved.value = true
    }

    fun setLibraryFolder(uri: Uri) {
        bookStorage.setRoot(uri)
    }

    fun setChatModel(model: String) {
        settingsRepository.setChatModel(model)
        chatModel.value = model
        modelMessage.value = "Chat model changed to $model."
    }

    fun setEmbeddingModel(model: String) {
        if (model == embeddingModel.value) return
        settingsRepository.setEmbeddingModel(model)
        embeddingModel.value = model
        viewModelScope.launch {
            libraryRepository.clearIndex()
            modelMessage.value = "Embedding model changed. Re-index your books from Library."
        }
    }

    fun exportBackup(destination: Uri) {
        viewModelScope.launch {
            backupInProgress.value = true
            backupMessage.value = null
            try {
                backupManager.export(destination)
                backupMessage.value = "Backup created successfully."
            } catch (error: Exception) {
                backupMessage.value = error.message ?: "Backup failed"
            } finally {
                backupInProgress.value = false
            }
        }
    }

    fun restoreBackup(source: Uri) {
        viewModelScope.launch {
            backupInProgress.value = true
            backupMessage.value = "Restoring backup…"
            try {
                backupManager.restore(source)
            } catch (error: Exception) {
                backupMessage.value = error.message ?: "Restore failed"
                backupInProgress.value = false
            }
        }
    }

    private data class BaseState(
        val apiKeyDraft: String,
        val hasApiKey: Boolean,
        val saved: Boolean,
        val libraryPath: String?,
    )

    private data class ModelState(
        val base: BaseState,
        val chatModel: String,
        val embeddingModel: String,
        val message: String?,
    )
}
