# BookGPT Android

BookGPT is a native Android reading assistant that lets you import books and ask questions about their contents. It extracts and indexes text on-device, creates embeddings with OpenAI, retrieves relevant passages locally, and streams answers with chapter or page citations. If a question cannot be answered from the local library, BookGPT can optionally supplement the answer with web search results.

## Features

- Import multiple TXT, PDF, and EPUB books.
- Extract PDF text and run ML Kit OCR on image-only pages.
- Parse EPUB metadata and chapter structure.
- Index books in a foreground WorkManager job with progress notifications.
- Store quantized embeddings locally with Room.
- Chat with one selected book or search across the whole library.
- Stream answers and show source citations.
- Keep multiple persistent conversations with generated titles and rolling summaries.
- Fall back to DuckDuckGo search when local content is unavailable.
- Select OpenAI chat and embedding models.
- Store the OpenAI API key using Android Keystore-backed encryption.
- Export and restore database backups.

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android device or emulator running Android 8.0 (API 26) or newer
- Internet access
- An OpenAI API key with access and quota for the selected models

## Quick start

1. Clone or download the repository and open it in Android Studio.
2. Allow Gradle to sync and ensure the project uses JDK 17.
3. Run the `app` configuration on an API 26+ device or emulator.
4. Open **Settings** in BookGPT and save your OpenAI API key.
5. Choose a writable folder. The app creates a `BookGPT` subfolder there.
6. Open **Library**, import one or more supported books, and wait for indexing.
7. Open **Chat** and ask a question.

No API key or `.env` file is required at build time. The API key is entered at runtime and is not included in backups.

## Build and test

Run these commands from the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat testDebugUnitTest
```

The project currently has local JVM tests for chunking, vector similarity and quantization, citation formatting, conversation context, and OpenAI error handling. See [Testing](docs/testing.md) for details and current coverage gaps.

## Architecture

BookGPT is a single-module Kotlin application using a layered MVVM-style design:

```text
Jetpack Compose UI
        |
    ViewModels
        |
Domain services and repositories
        |
Room | WorkManager | OpenAI | Storage Access Framework | Web search
```

The main source areas are:

- `ui/`: Compose screens, navigation, and ViewModels
- `domain/`: document loading, chunking, retrieval, reranking, and chat orchestration
- `data/`: Room, settings, storage, backup, OpenAI, and web implementations
- `worker/`: foreground background-indexing work
- `di/`: Hilt dependency wiring

Read [Architecture](docs/architecture.md) for the indexing and chat flows.

## Technology stack

- Kotlin 2.1 and coroutines/Flow
- Jetpack Compose and Material 3
- Navigation Compose
- Hilt and Hilt WorkManager
- Room
- WorkManager
- Retrofit, OkHttp, and Kotlin serialization
- AndroidX Security Crypto and DocumentFile
- PDFBox Android
- ML Kit Text Recognition
- Jsoup
- JUnit 4 and AndroidX Test

## Supported files

| Format | Processing |
| --- | --- |
| TXT | Read as UTF-8 text |
| PDF | Extract text per page; OCR blank/image-only pages |
| EPUB | Read package metadata and parse HTML content |

Imported files are copied into a user-selected location through Android's Storage Access Framework. Broad filesystem permissions are not used.

## Runtime configuration

BookGPT stores the following settings on the device:

- OpenAI API key
- Chat model
- Embedding model
- User-selected library folder
- Active book used by chat

Defaults:

- Chat model: `gpt-4o-mini`
- Embedding model: `text-embedding-3-small`

Changing the embedding model invalidates existing indexes; books must be reindexed because vectors from different embedding models are not compatible.

## Data and privacy

- Original imported books remain in the user-selected `BookGPT` folder.
- Extracted text, embeddings, books, conversations, and messages are stored in Room.
- The API key is encrypted with an Android Keystore-backed master key.
- Questions, selected passages, and conversation context are sent to OpenAI to produce answers.
- Book text is sent to OpenAI in batches when embeddings are created.
- DuckDuckGo receives search queries only when web fallback is used.
- Backups include the database and readable metadata, but exclude the API key and original book files.

Review [Settings and security](docs/settings-and-security.md) before distributing or modifying the app.

## Project documentation

The complete documentation index is in [`docs/README.md`](docs/README.md). Component guides cover:

- application UI and navigation
- library and storage
- document ingestion and OCR
- indexing
- retrieval and RAG
- chat
- OpenAI integration
- database persistence
- settings and security
- backup and restore
- testing and release operations

## Current limitations

- OpenAI access and network connectivity are required for indexing and answer generation.
- OCR, EPUB parsing, storage integration, backup/restore, UI, and end-to-end RAG flows do not yet have automated integration tests.
- Release signing and a formal release pipeline are not configured.
- OpenAI use can incur API costs, particularly while embedding large books.

## License

No license file is currently included. Unless a license is added, the repository remains under the default copyright restrictions of its owner.
