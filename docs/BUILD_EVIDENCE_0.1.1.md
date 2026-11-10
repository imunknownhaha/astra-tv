# Build evidence — 0.1.1

Date: 2026-11-10. Fedora x86_64, JDK 21, Gradle 9.6.0, Android SDK 37.0.

`./gradlew testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL. **26 tests, zero failures; lint zero errors.**

- ParserTest: 13, including credential-free crash-summary verification.
- LibraryPersistenceTest: 4.
- XtreamImportTest: 8, using a synthetic local MockWebServer: live/movie/series import, >40 MB generated streaming JSON, malformed refresh after multiple batches, optional VOD failure, invisible/stale pending rows, stable favorite/progress identities, nullable response shapes and stable hash compatibility. No paid provider or actual user credentials were used.
- ImportedLibraryUiTest: 1 on Robolectric API 34, covering welcome-to-imported-catalog composition and saving a favorite with remote-style OK input. This is a host native-UI regression, not physical onn device verification.

APK: `com.astratv.player`, versionName `0.1.1`, versionCode `2`, minimum API 26. Signature verified; signer SHA-256 remains `590f705931bec682d48ba8c50408154a679c9acf85a854e68ef2848137ce119e`, matching 0.1.0.

APK SHA-256: `d4db3f5b61c9c90d90cb62daba2cfd4bd5f64664aa2916b31b80497a452b4933`.

A user reported two loading-time crashes after 10–15 seconds on an onn 4K Pro. No device crash trace was available; the actual crash cause cannot be asserted. This build removes the identified full-catalog memory risk and adds local diagnostics for any recurrence. The reported onn device and its provider still require confirmation by the user.

The existing Downloader code 1723835 is preserved by updating its original compatibility APK asset. The new release also provides a version-named APK. Source, package ID, signing certificate and upgrade version were checked before publication.
