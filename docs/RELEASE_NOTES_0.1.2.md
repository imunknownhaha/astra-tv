# Astra TV 0.1.2 — performance update

- Distribute an R8-optimized, non-debuggable APK using the existing signing identity.
- Indexed channel browsing and startup cleanup; existing data migrates without clearing playlists, credentials, favorites or resume positions.
- Stop off-screen library updates during playback; retain list position and Back/focus restoration.
- Bound image downloads, decoding and caches; reuse decrypted artwork URLs in memory.
- Remove repeated live-history writes and hidden playback-position updates; keep on-demand resume snapshots.
- Keep live TV, Xtream/M3U, EPG, movies/series, favorites/history, profiles, parental controls and playback controls.

30 host tests pass and both lint variants report no errors. See docs/PERFORMANCE_0.1.2.md for measured host results and real-TV verification limits. The APK is about 3 MB and remains development-signed. Install over the existing app, confirm version 0.1.2. Downloader code 1723835 is retained by updating its original compatibility asset. The first launch adds the new database index.
