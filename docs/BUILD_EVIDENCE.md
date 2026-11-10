# Build evidence — 2026-11-10

Final source builds on Fedora Linux x86_64, JDK Temurin 21.0.13, Android SDK 37.0, Build Tools 36.0.0, Gradle 9.6.0. The included wrapper separately downloaded the official distribution and passed its pinned SHA-256 checksum.

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest lintDebug
```

**Result: BUILD SUCCESSFUL. 16 tests, 0 failures, 0 errors. Android lint: 0 errors, 15 warnings, 1 hint.** Warnings include dependency update suggestions, KAPT migration advice and source/style/API recommendations; they were not hidden using a blanket lint baseline.

Unit suites: ParserTest 12; LibraryPersistenceTest 4 (Robolectric API 34). The cached restricted-episode regression is included in the final passing suite.

Android apksigner verified the debug APK with one Android Debug signer and v2 signing. AAPT confirmed `com.astratv.player`, versionName `0.1.0`, versionCode `1`, minSdk `26`, targetSdk `37`, the Leanback launcher, TV banner, Leanback requirement and optional touchscreen.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| astra-tv-0.1.0-debug.apk | 19061349 | `936662abc141ea60a085cd285977cf99d9e4760a533c080c5bfd83d794c33581` |
| astra-tv-0.1.0-release-unsigned.apk | 2957581 | `fe7546c3b512baa8b327ac8b388b43bd0c4548b22ab06fe36abfa33e9784468b` |
| astra-tv-0.1.0-navigation-tests.apk | 1159159 | `be77007fcf14b52b376b88289488a7ad3072b47e76b57da85ee3d82e6baf5b3b` |

The release APK is R8-shrunk and **unsigned**. It is not installable until signed with the owner’s production key. No production signing identity was created, supplied, committed or published. The debug APK is installable for development; installation was not verified on a TV.

## Runtime test limit

Two software-emulator attempts used the official Android TV API 30 x86 image. `/dev/kvm` was unavailable; acceleration was disabled. The emulator reached guest initialization/system_server in diagnostic kernel logs but never supplied a usable online ADB device during the attempts. No valid application screenshot was captured. The final `connectedDebugAndroidTest` attempt failed with `DeviceException: No connected devices!`. The navigation test APK compiled successfully; its tests were **not executed**. This is an environment test limitation, not a passing navigation result.

No physical Google TV was connected. HLS/MPEG-TS playback, decoder output, audio/subtitle selection, D-pad focus/restore, provider catch-up, installation/update flows and real TV performance remain unverified on hardware. No benchmark figures are claimed.

## Independent review

The native source reviewer returned **ship, source review only**, after verifying management unlock reset, restricted episode parent inheritance/enforcement, missing saved-profile recovery and unavailable next-episode behavior. That verdict did not approve rendering or device behavior. DESIGN.md and its token sidecar were checked by a separate documenter. Native code was not passed through an HTML/CSS design detector.

The packaged source archive includes raw build, test, lint, wrapper, APK metadata/signature and instrumentation-attempt evidence. Instructions and known limits are in README.md, STATUS.md, TESTING.md and RELEASING.md.
