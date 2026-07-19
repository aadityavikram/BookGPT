# Settings and security

The Settings component manages credentials, model selection, library location, and backup actions.

## Stored settings

- OpenAI API key
- selected chat model
- selected embedding model
- persisted library document-tree URI
- active book used by chat

The API key uses `EncryptedSharedPreferences` with an Android Keystore-backed AES-256 master key. Other settings are stored locally by `SettingsRepository`.

## Permissions

The app declares:

- internet access
- notification permission
- foreground-service permission
- foreground data-sync service permission

Book storage is accessed through Android's Storage Access Framework. The app requests a document-tree grant instead of broad filesystem access.

## Network and privacy boundaries

- OpenAI receives book text during embedding.
- OpenAI receives questions, retrieved passages, and conversation context during chat.
- BookGPT does not send queries to a web search provider or use internet search results as answer sources.
- Room stores extracted book text, embeddings, and conversations locally.
- Backups exclude the API key and original books but contain extracted text and chats.

Internet permission remains necessary for OpenAI embeddings, reranking, answer generation, summaries, and conversation titles. The current direct-to-OpenAI design avoids operating a custom backend, but places key protection and API usage control on the user's device. A determined attacker with control of a device may still recover runtime secrets.

## Development rules

- Never add real keys to source, Gradle files, resources, logs, tests, screenshots, or fixtures.
- Redact authorization headers and prompts from diagnostics.
- Review third-party SDK data practices before adding telemetry or crash reporting.
- Treat restored backups and imported documents as untrusted input.
- Explain API cost and data transmission to users before production distribution.

## Model changes

Chat-model changes affect future chat operations. Embedding-model changes invalidate existing book vectors and require reindexing.

## Main implementation

- `ui/settings/SettingsScreen.kt`
- `data/settings/SettingsRepository.kt`
- `domain/Config.kt`
- `di/AppModule.kt`
- `app/src/main/AndroidManifest.xml`
