# Testing

## Run tests

From the repository root on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
```

For a complete debug build:

```powershell
.\gradlew.bat assembleDebug
```

## Current unit coverage

Local JVM tests cover:

- chapter-aware chunking
- cosine similarity and ranking
- vector quantization accuracy and size
- citation/source formatting and title detection
- recent-message window splitting for conversation context
- OpenAI HTTP failure classification

Tests are under `app/src/test/java/com/bookgpt/android/`.

## Important coverage gaps

The project does not currently have meaningful automated coverage for:

- Compose UI and navigation
- Room schema migrations
- repository and WorkManager integration
- TXT/PDF/EPUB loaders
- PDF OCR
- Storage Access Framework behavior
- backup and restore
- OpenAI server-sent-event streaming
- DuckDuckGo HTML parsing
- end-to-end indexing and RAG

## Recommended strategy

1. Add Room migration tests for every supported upgrade path.
2. Unit-test loaders with small deterministic fixtures.
3. Test OpenAI and web clients with a mock HTTP server.
4. Use WorkManager test helpers for success, retry, cancellation, and duplicate-work cases.
5. Add instrumented tests for document-tree permissions and backup replacement.
6. Add Compose tests for import, indexing failure, conversation management, and destructive confirmations.
7. Keep external model calls out of deterministic CI; use protocol fixtures and opt-in integration tests.

Any change to chunking, retrieval thresholds, prompt assembly, database schema, or archive format should update its corresponding tests.
