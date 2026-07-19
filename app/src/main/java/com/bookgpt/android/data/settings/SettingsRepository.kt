package com.bookgpt.android.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bookgpt.android.domain.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = createPrefs(context)
    private val _hasApiKey = MutableStateFlow(getApiKey().isNotBlank())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _activeBookTitle = MutableStateFlow(prefs.getString(KEY_ACTIVE_BOOK, null))
    val activeBookTitle: StateFlow<String?> = _activeBookTitle.asStateFlow()

    private val _libraryTreeUri = MutableStateFlow(
        prefs.getString(KEY_LIBRARY_TREE_URI, null)?.let(Uri::parse),
    )
    val libraryTreeUri: StateFlow<Uri?> = _libraryTreeUri.asStateFlow()

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty()
    fun getChatModel(): String =
        prefs.getString(KEY_CHAT_MODEL, Config.DEFAULT_OPENAI_MODEL) ?: Config.DEFAULT_OPENAI_MODEL

    fun getEmbeddingModel(): String =
        prefs.getString(KEY_EMBEDDING_MODEL, Config.DEFAULT_EMBEDDING_MODEL)
            ?: Config.DEFAULT_EMBEDDING_MODEL

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
        _hasApiKey.value = apiKey.trim().isNotBlank()
    }

    fun setChatModel(model: String) {
        require(model in Config.CHAT_MODELS)
        prefs.edit().putString(KEY_CHAT_MODEL, model).apply()
    }

    fun setEmbeddingModel(model: String) {
        require(model in Config.EMBEDDING_MODELS)
        prefs.edit().putString(KEY_EMBEDDING_MODEL, model).apply()
    }

    fun setActiveBook(title: String?) {
        prefs.edit().putString(KEY_ACTIVE_BOOK, title).apply()
        _activeBookTitle.value = title
    }

    fun setLibraryTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_LIBRARY_TREE_URI, uri?.toString()).apply()
        _libraryTreeUri.value = uri
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val PREFS_NAME = "bookgpt_secure_settings"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_ACTIVE_BOOK = "active_book_title"
        private const val KEY_LIBRARY_TREE_URI = "library_tree_uri"
        private const val KEY_CHAT_MODEL = "chat_model"
        private const val KEY_EMBEDDING_MODEL = "embedding_model"
    }
}
