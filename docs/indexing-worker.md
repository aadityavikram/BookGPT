# Indexing worker

`IndexingWorker` turns an imported document into searchable chunks and embeddings.

## Scheduling

Each book receives unique WorkManager work, preventing duplicate indexing jobs for the same item. Work requires a network connection because embedding generation calls OpenAI.

Indexing runs as a foreground data-sync service and reports progress through notifications. The Android manifest declares the required foreground-service, data-sync, internet, and notification permissions.

## Processing

1. Mark the book as indexing.
2. Open and parse the managed source file.
3. Split extracted text into source-aware chunks.
4. Generate embeddings in bounded batches.
5. Quantize float vectors to signed 8-bit values.
6. transactionally replace stored chunks and vectors.
7. Mark the book ready or record a failure.

Quantization substantially reduces local database size. Scale data is retained so approximate vectors can be compared during retrieval.

## Failures and retries

Transient failures use exponential backoff and can be retried up to five worker attempts. Typical transient cases include timeouts, rate limits, and server/network failures. Invalid API keys, denied model access, malformed documents, and exhausted quota require user action.

The UI exposes failed status and retry controls. Changing the embedding model invalidates existing vectors and requires reindexing all affected books.

## Operational notes

- Large libraries can consume significant OpenAI embedding quota.
- Device and network interruption can delay work; WorkManager resumes eligible work.
- Foreground notification behavior varies with Android notification permission.
- New index data should become active as a complete transaction, not as a partially written index.

## Main implementation

- `worker/IndexingWorker.kt`
- `data/library/LibraryRepository.kt`
- `domain/retrieve/VectorQuantizer.kt`
- `app/src/main/AndroidManifest.xml`
