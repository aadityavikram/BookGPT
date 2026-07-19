# Library and storage

The library component manages imported book files, their Room records, and indexing status.

## Import

`LibraryScreen` uses Android's document picker to accept multiple TXT, PDF, and EPUB files. `LibraryViewModel` delegates import operations to `LibraryRepository`, while `BookStorage` handles document-tree access.

Imported files are copied into a `BookGPT` child directory under the folder selected in Settings. File names are sanitized and made unique to prevent accidental overwrites. Persisted URI permission allows access after app restarts without broad filesystem permissions.

## Library operations

Users can:

- import one or more supported books
- monitor indexing state
- retry failed indexing
- replace a book file
- delete individual books
- clear or reindex library content

Replacing a source file updates the book and schedules a fresh index. Deleting a book removes its database-owned chunks and embeddings through foreign-key cascades and removes the managed file when appropriate.

## Storage constraints

- The selected folder must remain writable and available.
- Removable storage can become unavailable.
- Android may revoke document-tree access, requiring the user to choose a folder again.
- Source files are not embedded in BookGPT database backups.
- Storage uses the Storage Access Framework and `DocumentFile`; no all-files permission is requested.

## Main implementation

- `ui/library/LibraryScreen.kt`
- `ui/library/LibraryViewModel.kt`
- `data/library/LibraryRepository.kt`
- `data/storage/BookStorage.kt`

For parsing after import, see [Document ingestion](document-ingestion.md). For durable processing, see [Indexing worker](indexing-worker.md).
