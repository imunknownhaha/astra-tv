# Test evidence and acceptance

Build host: Fedora Linux x86_64; JDK Temurin 21; SDK platform 37.0; Build Tools 36.0.0. Date: 2026-11-10, America/Chicago.

Automated verification commands:

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest lintDebug
```

The final exact results, counts and hashes are recorded in `BUILD_EVIDENCE.md` after packaging. Do not interpret an APK build as proof of runtime playback or physical-TV performance.

## Automated coverage

- Extended M3U: quoted commas, metadata, relative stream URLs, duplicate stream removal with duplicate guide IDs preserved, stable refresh identity, BOM, bad rows, missing headers, empty files, HLS media-playlist rejection, HTTP opt-in and invalid schemes.
- XMLTV: explicit offsets, timezone conversion, titles/entities, descriptions, malformed timestamps, and DTD/external entity rejection.
- Xtream: categories, credentials encoded as URL segments, nullable metadata, archive flags, series episode ordering and supplied descriptions.
- Security/reliability: salted PIN derivation/verification, malformed hashes, bounded exponential retries and numeric update-version comparison.
- Room on Robolectric API 34: item upsert/pruning, profile-isolated favorites/history, persisted resume after DB reopen, source-scoped guide windows, and restrictions applying to cached episode parent categories.
- Instrumentation APK: initial setup uses real import controls; sidebar D-pad focus/OK and system Back. These tests are included and compiled; execute them on an empty TV emulator/device using `./gradlew connectedDebugAndroidTest`. Runtime execution status is explicitly recorded in BUILD_EVIDENCE.md.

No paid IPTV subscription is required for these tests. Parser/DB tests use local synthetic input. The [fixture workflow](../fixtures/README.md) generates authorized synthetic HLS and guide data for device tests.

## Device acceptance still required

Use the exact debug or signed release APK and record device model, Android version, TV resolution, date, network type, stream format and results. Test a 1080p TV and another density/resolution; also increase system font scale to 1.3. Restore default scale afterward.

1. Empty install: visible initial focus; reach all controls with arrows/OK/Back; TV IME can edit every field and password values are masked. Check local picker unavailable handling.
2. Import URL/local M3U, malformed/large M3U, HTTP blocked then explicitly enabled. Confirm errors preserve the last good catalog; duplicates remain stable. Refresh and restart; favorites survive.
3. Play generated HLS; check moving picture and audio. Pause/resume, native audio focus, background/foreground cleanup, Back returning to the selected row, rapid channel changes, channel keys and last-channel recall. Verify reconnection stops after three failures and switching cancels a pending retry.
4. Test genuine provider HLS and MPEG-TS, progressive VOD longer than two minutes, ABR, codec rejection, multiple audio/subtitle tracks, aspect ratios and subtitle legibility. These are provider/hardware checks; synthetic parser tests do not verify them.
5. Import generated XMLTV. Confirm now/upcoming, local timezone, date rollovers, missing guide IDs, descriptions, horizontal schedule navigation and direct live play. Inspect short program cells and long translated/channel names for clipping. Refresh worker remains optional and network-constrained.
6. With an authorized Xtream-compatible provider: confirm authentication failures, all catalog categories, series-info variants, season/episode selection, progress/continue watching, >95% watched status and disabled last-episode navigation. Test real provider catch-up with a past program within and outside its window. UTC/provider time shifts may need an adapter.
7. Profiles: separate favorites/history; a child cannot enter management without the PIN. Verify management access is revoked after switching profiles. Load an adult series first, then restrict the parent category and confirm cached episodes disappear from search/favorites/history/resume and cannot play. Restart to verify persisted restrictions. Test wrong PIN cooldown and missing-saved-profile recovery.
8. Updates: use a private test repository with same-key higher-version APK. Confirm release notes, manual-only networking, download size limits, rejection of corrupt APK/foreign package/wrong signer/old version, permission round trip, and visible Android install confirmation. Never publish debug signing identities as production.
9. Install the signed production APK via ADB and a real HTTPS Downloader URL/code. Confirm TV launcher icon/banner, startup, update over previous same-key install and low-storage errors.
10. On Chromecast with Google TV or another 2 GB TV: measure cold startup, PSS via `adb shell dumpsys meminfo com.astratv.player`, frame time/jank via Android Studio Profiler/Perfetto, 10k-channel browse/search and repeated switching. No benchmark numbers are claimed in this project without these measurements.

The source-only independent review checked four security/reliability fixes and returned a scoped ship verdict. It did not certify rendering, remote execution, codec playback, performance or installation.

## 0.1.1 update verification

See BUILD_EVIDENCE_0.1.1.md: 26 tests pass, including synthetic end-to-end Xtream imports, streaming a generated >40 MB response, rollback/recovery, diagnostics privacy and native Compose import-to-library/remote-OK behavior on Robolectric. This does not certify the reported onn hardware.
