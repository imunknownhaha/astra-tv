# Astra TV 0.1.2 performance work

## Measured host evidence

- Distributed 0.1.1 debug APK: 19,010,072 bytes. Optimized 0.1.2 preview: 2,986,217 bytes (84.3% smaller).
- Identical synthetic 20,000-channel / 200-row browse query: baseline host median 9.119 ms; latest optimized host median 1.059 ms, 3 warmups + 9 timed queries each. These are SQLite timings under Robolectric on Fedora; CPU load varies. They are not onn startup/frame-time measurements.
- Baseline query used a temporary B-tree to sort the catalog. Optimized query uses `index_items_kind_sortName_id` without a temporary sort. Startup pending-import cleanup now uses `index_items_sourceId` range lookup.
- Optimized APK manifest is non-debuggable; R8 code/resource shrinking is enabled. ART baseline.prof/profm are packaged.
- Same signer SHA-256 as installed versions: `590f705931bec682d48ba8c50408154a679c9acf85a854e68ef2848137ce119e`. Package remains com.astratv.player; version 0.1.2, code 3, minSdk 26.
- APK SHA-256: `c89aaad9512449e2cbc1d6125376424bacfb8a3ccb1fa80d28f05bf3cec8eb40`.

## Changes

- Release-optimized preview build using the existing development signing identity; no debug tooling/runtime flag in the distributed APK.
- Database v1 → v2 migration adds the indexed ASCII case-folded sort key while retaining existing source configurations, encrypted URLs, favorites and resume positions. Channel neighbor queries seek through the sort index and wrap normally.
- Compose entity models are explicitly immutable. Library observers unsubscribe while the player is visible; list/grid positions and restored channel focus survive Back. Metadata queries only restart for relevant settings and transformations/deduplication run off the UI thread.
- Live viewing no longer rewrites watch history every ten seconds. VOD snapshots remain every ten seconds while selected, skip unchanged paused positions, and persist at transitions. Position display polling runs only for visible on-demand controls.
- Artwork requests limited to four concurrent downloads/two per host and two decodes. Configured image caches: 16 MiB RAM, 64 MiB disk; decrypted URL reuse has a 1 MiB in-memory budget. No plaintext URL cache is written by ArtworkResolver.
- Media3 allocator buffer target capped at 24 MiB (decoder/device memory is additional). Playback buffering durations, bounded reconnects, codecs, audio/subtitle selection and aspect controls remain.
- Guide refresh scheduler initialization happens on IO after the first UI frame, with the same optional 12-hour policy.

## Verification and limits

`testDebugUnitTest assemblePreview lintDebug lintPreview`: passed. 30 tests; zero failures; both lint variants have zero errors. Tests include the full Xtream synthetic import/rollback suite, schema-1 migration retaining ciphertext/favorites/resume, indexed browse/order/wraparound, bounded artwork cache hits/eviction, and a native Compose host test for imported library → player branch → Back/focus restoration.

The onn 4K Pro is not connected. Real-device cold start, PSS, jank/frame times and decoder/network behavior still need device profiling; no FPS or device RAM measurement is invented. All important library/playback/guide/profile functions remain. This is an optimized development-signed preview, not a newly established production signing identity.
