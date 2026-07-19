# Release and operations

The repository currently supports debug development builds. Release signing, CI, and a formal publishing workflow are not configured.

## Build configuration

- application module: `:app`
- minimum SDK: 26
- compile/target SDK: 35
- JVM: 17
- Gradle wrapper: 8.11.1
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.1

Authoritative values live in `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `gradle/wrapper/gradle-wrapper.properties`.

## Before releasing

- choose a stable application ID, version code, and version name
- configure a securely managed release keystore
- verify release minification/resource-shrinking behavior and keep rules
- add CI for unit tests, lint, and debug/release compilation
- test all Room migration paths
- test backup compatibility and rollback expectations
- verify API-key redaction in logs and crash reporting
- review OpenAI and DuckDuckGo terms and privacy disclosures
- add app icon, screenshots, store copy, and an end-user privacy policy
- document supported languages and OCR limitations
- test API 26 and current Android versions
- add a repository license if redistribution is intended

## Operational considerations

- OpenAI availability, model access, quotas, and pricing can change independently of app releases.
- Hard-coded model lists may require application updates as models are added or retired.
- Indexing large books can produce material embedding cost and network usage.
- HTML web-search parsing can break when DuckDuckGo markup changes.
- Database and backup format changes require compatibility planning.
- Foreground-service and notification requirements should be reviewed for each target-SDK update.

## Release verification

On a clean physical device, verify:

1. install and first-run configuration
2. API-key save and error handling
3. TXT, PDF, scanned PDF, and EPUB import
4. background/foreground indexing behavior
5. focused and all-library chat
6. citations, streaming, and web fallback
7. process death and restart recovery
8. backup export and destructive restore
9. model changes and required reindexing
10. upgrade from the previous production database version
