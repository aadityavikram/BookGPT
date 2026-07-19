# BookGPT documentation

This directory contains setup, architecture, and component documentation for BookGPT Android.

## Start here

- [Getting started](getting-started.md): prerequisites, build commands, and first-run setup
- [Architecture](architecture.md): layers, dependencies, and major data flows
- [UI and features](ui-and-features.md): screens and user workflows

## Component guides

- [Library and storage](library-and-storage.md)
- [Document ingestion](document-ingestion.md)
- [Indexing worker](indexing-worker.md)
- [Retrieval and RAG](retrieval-and-rag.md)
- [Chat agent](chat-agent.md)
- [OpenAI integration](openai-integration.md)
- [Data model](data-model.md)
- [Settings and security](settings-and-security.md)
- [Backup and restore](backup-and-restore.md)

## Development and operations

- [Testing](testing.md)
- [Release and operations](release-and-operations.md)

## Source layout

```text
app/src/main/java/com/bookgpt/android/
├── data/       # Persistence and external-system implementations
├── di/         # Hilt dependency graph
├── domain/     # Core ingestion, retrieval, and agent logic
├── ui/         # Compose screens, navigation, and ViewModels
└── worker/     # WorkManager indexing
```

The project is a single `:app` Gradle module. Its authoritative dependency versions are in `gradle/libs.versions.toml`, while Android build settings are in `app/build.gradle.kts`.
