# Data model

BookGPT uses Room database version 4 for indexed content and chat history.

## Tables

- **books**: imported-book metadata, managed file location, index status, and model-related state
- **chunks**: extracted text segments and source metadata
- **embeddings**: quantized vectors associated with chunks
- **conversations**: chat sessions, titles, focus, and summary state
- **chat_messages**: ordered user and assistant messages with source information

Books own chunks, and chunks own embeddings. Conversations own chat messages. Foreign-key cascades remove dependent rows when a parent is deleted.

## Access

DAOs in `data/db/Daos.kt` expose observable library and conversation data, transactional writes, and paged embedding reads. Paging is important because a large library can contain many vectors.

Index replacement is transactional so retrieval does not consume a partially rebuilt index.

## Migrations

`BookGptDatabase` declares explicit migrations:

- version 1 to 2
- version 2 to 3
- version 3 to 4

Any schema change must increment the version, add a migration, and include a migration test. Destructive fallback is unsuitable for production because it would erase indexes and conversations.

## Lifecycle considerations

- Deleting a book cascades through its chunks and embeddings.
- Deleting a conversation cascades through its messages.
- Changing the embedding model invalidates vectors even if the schema itself is unchanged.
- Backup restore validates the database before replacing active data.
- Original book files are managed separately through the Storage Access Framework.

## Main implementation

- `data/db/Entities.kt`
- `data/db/Daos.kt`
- `data/db/BookGptDatabase.kt`
- `di/AppModule.kt`
