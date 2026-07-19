# Backup and restore

`BackupManager` exports and restores BookGPT application data as a ZIP archive selected through Android's document APIs.

## Backup contents

Backups contain:

- the Room database
- readable JSON metadata/manifests
- books and extracted chunks
- quantized embeddings
- conversations and chat messages

Backups do not contain:

- the OpenAI API key
- original TXT, PDF, or EPUB files

Because extracted text and conversations are included, backup files can contain sensitive personal or copyrighted content. Store and share them accordingly.

## Restore validation

Before activation, restore checks:

- archive and schema compatibility
- required database tables
- database integrity
- expected backup structure
- a maximum accepted size of 2 GiB

Restore replaces active database content and then restarts the application so Room does not continue using stale connections. This is a destructive operation for the current local database; users should export a current backup first.

Original book files are not restored. Restored records may therefore need their sources to be imported or relinked for operations that depend on the original documents.

## Compatibility

Database migrations must remain compatible with databases restored from older supported backups. When changing the schema or archive format:

1. increment the relevant version
2. retain or explicitly reject old formats
3. add migration and malformed-archive tests
4. verify restore on a clean device and an upgraded installation

## Main implementation

- `data/backup/BackupManager.kt`
- `ui/settings/SettingsScreen.kt`
- `data/db/BookGptDatabase.kt`
