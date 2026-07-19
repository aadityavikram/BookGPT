# UI and features

BookGPT uses Jetpack Compose and Material 3 with a dark red/black visual theme.

## Navigation

`BookGptNavHost` provides three bottom-navigation destinations:

- **Library**: import and manage books
- **Chat**: ask questions and manage conversations
- **Settings**: credentials, models, storage, and backups

## Library

The Library screen supports multi-file import, indexing progress, failure messages, retry, replacement, deletion, clearing, and reindexing. Index state reflects Room and WorkManager activity.

## Chat

The Chat screen supports:

- streaming assistant responses
- an authoritative one-book or **All books** library scope
- displayed chapter/page citations
- multiple persistent conversations
- automatic conversation titles
- rename, switch, clear, and delete actions
- long-chat rolling summaries
- a scoped not-found response when the selected book or library has no matching passage

Chat does not search the web. A focused book is never widened to the rest of the library; **All books** is the only mode that retrieves from every indexed book.

## Settings

The Settings screen supports:

- securely saving an OpenAI API key
- selecting chat and embedding models
- choosing the managed library folder
- exporting a backup
- restoring a backup

Changing the embedding model must clearly indicate that existing indexes need rebuilding. Restore should warn that current database content will be replaced.

## UI state

ViewModels translate repository and domain state into Compose-friendly state. Long-running actions should expose progress, success, failure, and retry behavior. Streaming chat updates incrementally rather than waiting for the complete response.

## Accessibility and UX considerations

When extending the UI, preserve content descriptions, readable contrast, scalable text, touch-target sizing, keyboard behavior, and actionable error messages. Ensure dialogs explain destructive actions and API-cost implications.

## Main implementation

- `MainActivity.kt`
- `ui/BookGptNavHost.kt`
- `ui/navigation/Routes.kt`
- `ui/library/`
- `ui/chat/`
- `ui/settings/`
