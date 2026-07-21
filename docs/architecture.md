# Architecture

BookGPT is a single-module, layered MVVM-style Android application.

## Layers

- **UI (`ui/`)**: Jetpack Compose screens, navigation, dialogs, UI state, and Hilt ViewModels.
- **Domain (`domain/`)**: format loading, chunking, vector retrieval, reranking, context assembly, and agent orchestration.
- **Data (`data/`)**: Room persistence, OpenAI networking, document storage, settings, and backup.
- **Workers (`worker/`)**: durable, foreground book indexing through WorkManager.
- **Dependency injection (`di/`)**: Hilt providers for database, repositories, clients, and services.

UI code depends on ViewModels and domain/repository abstractions. Domain orchestration combines local persistence with external services. Hilt builds the object graph in `AppModule.kt`.

## Application startup

`BookGptApp` initializes Hilt, PDFBox, notification infrastructure, and custom WorkManager injection. `MainActivity` hosts the Compose UI. `BookGptNavHost` exposes three primary destinations: Library, Chat, and Settings.

## Indexing flow

```text
Android document picker
  -> copy file into selected BookGPT folder
  -> create/update Room book record
  -> enqueue unique network-constrained work
  -> load TXT, PDF, or EPUB
  -> OCR blank PDF pages when needed
  -> detect chapters and split text
  -> request OpenAI embeddings in batches
  -> quantize vectors to int8
  -> store chunks and embeddings transactionally
```

## Chat flow

```text
User question
  -> read the selected focus (one book or All books)
  -> embed the query
  -> search vectors only within the selected library scope
  -> rerank candidate passages with OpenAI
  -> return a local not-found response when retrieval is empty
  -> otherwise combine library sources, recent messages, and rolling summary
  -> stream a library-grounded OpenAI answer
  -> persist answer and source metadata
```

Chat never searches the web. Selecting a specific book never falls back to other books; selecting **All books** searches the full indexed library.

## State and concurrency

ViewModels expose observable state to Compose, while repositories and services use Kotlin coroutines and Flow. WorkManager makes indexing durable across process interruption. Database writes group index replacement operations so partially generated indexes do not become the active index.

## Key entry points

- `BookGptApp.kt`: process initialization
- `MainActivity.kt`: activity and Compose host
- `ui/BookGptNavHost.kt`: app navigation
- `di/AppModule.kt`: dependency graph
- `worker/IndexingWorker.kt`: indexing orchestration
- `domain/agent/BookAgent.kt`: chat orchestration
