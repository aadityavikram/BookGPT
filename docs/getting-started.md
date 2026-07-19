# Getting started

## Prerequisites

- Android Studio and Android SDK 35
- JDK 17
- Android 8.0/API 26 or newer device or emulator
- Internet access
- OpenAI API key with sufficient model access and quota

Android Studio normally writes the local SDK path to the ignored `local.properties` file.

## Build

Open the repository in Android Studio, sync Gradle, select the `app` run configuration, and run it on a compatible device.

PowerShell commands from the repository root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat testDebugUnitTest
```

The project uses Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.1, compile/target SDK 35, and JVM 17.

## First-run setup

1. Open **Settings**.
2. Enter an OpenAI API key and save it.
3. Select a writable folder using the Android document-tree picker.
4. Open **Library** and import TXT, PDF, or EPUB files.
5. Keep network access available while the foreground indexing work completes.
6. Open **Chat**, select a book if desired, and ask a question.

The chosen folder permission is persisted. BookGPT creates a `BookGPT` child directory and copies imported files into it.

## Common problems

- **Indexing does not start:** verify network connectivity, API key validity, model access, and OpenAI quota.
- **Notifications are absent:** allow notification permission on supported Android versions. Indexing still uses a foreground service.
- **A book has no useful text:** scanned PDF pages depend on OCR quality; malformed or protected documents may not parse.
- **Answers use the wrong index after a model change:** reindex books after changing the embedding model.
- **The library folder is unavailable:** select a new writable folder if Android revokes access or the storage device is removed.

See [OpenAI integration](openai-integration.md) for API failures and [Indexing worker](indexing-worker.md) for retry behavior.
