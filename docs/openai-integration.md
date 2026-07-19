# OpenAI integration

BookGPT calls OpenAI directly from the Android application.

## Endpoints

- `POST /v1/embeddings`: book chunks and retrieval queries
- `POST /v1/chat/completions`: reranking, answer generation, summaries, and titles
- Server-sent events from chat completions: incremental answer streaming

The default chat model is `gpt-4o-mini`; the default embedding model is `text-embedding-3-small`. Selectable models and processing limits are defined in `domain/Config.kt`.

## Authentication

The user enters an API key in Settings. An OkHttp interceptor adds it as a bearer token to OpenAI requests. The key is stored with `EncryptedSharedPreferences` using an Android Keystore-backed AES-256 master key.

Do not hard-code keys, commit them, log them, include them in crash reports, or add them to backups.

## Failure classes

The client distinguishes:

- invalid authentication
- denied permission or unavailable model
- exhausted quota
- rate limiting
- timeout/network failure
- OpenAI server failure
- invalid requests

Transient errors can be retried with backoff. Authentication, permissions, quota, and invalid requests generally require configuration or account action.

## Cost and data handling

Indexing sends extracted book chunks to the embeddings endpoint. Chat sends the question, selected source passages, instructions, and conversation context to chat completions. Users should understand this data flow before importing sensitive material.

Costs depend on book size, reindex frequency, conversation volume, and selected models. Replacing an embedding model requires reindexing and incurs new embedding requests.

## Main implementation

- `data/openai/OpenAiApi.kt`
- `data/openai/OpenAiClient.kt`
- `data/openai/OperationFailure.kt`
- `data/settings/SettingsRepository.kt`
- `di/AppModule.kt`
