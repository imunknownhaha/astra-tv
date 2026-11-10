# Implementation status — 0.1.0 development build

This repository contains working implementation code and built APKs. It is not a production-certification claim. Builds, unit tests and lint passed on the Fedora host; television playback and remote behavior require runtime acceptance. No mockup images were supplied with the brief.

| Area | Implemented | Limits / verification still needed |
|---|---|---|
| TV foundation | Native Compose for TV controls, dark theme, Leanback launcher, icons/banner, safe margins, visible focus, Back, lazy lists | Source-reviewed; physical remote/readability/font-scale/IME checks pending |
| M3U | URL/local SAF import, groups/logos/guide IDs, HTTP opt-in, duplicates, bounded parsing, refresh, multiple sources, merge/separate, rename/remove | Local picker availability varies. Relative addresses need a network base. Rotating M3U URLs change identity |
| Live playback | Media3 HLS/DASH/progressive support, audio focus, one reusable player, live/channel/last keys, track selection, aspect controls, buffering and bounded retry | Actual codecs, track selection, switching, lifecycle and error recovery must be tested on TV. No DRM provisioning or non-HTTP protocols |
| EPG | XMLTV, offset-aware times, source-scoped mapping, 3-hour lazy guide, now marker, details/live action, optional 12-hour WorkManager refresh | Exact guide-ID matching, no manual mapping editor. Verify short-cell readability and provider timezone quirks |
| Movies | Xtream catalog/category/poster browsing, local search, supplied descriptions, favorites, resume/start-over | No external metadata service, trailers, ratings, sorting by release date or provider-specific VOD-info enrichment |
| Series | Xtream series-info, seasons, episode lists/playback, watched from progress, continue watching, explicit next episode | Response variants and full series/episode playback need provider testing. No automatic next-episode autoplay; no manual watched toggle |
| Catch-up | Advertised archive filtering, past-guide program action, Xtream UTC timeshift, selected M3U Unix-second templates | No provider promise. Unsupported formats/providers need adapters; actual archive access requires an authorized provider |
| Search/favorites/history | Debounced local name/category search across media, per-profile favorites and recent history, >95% watched state, saved positions | SQL LIKE rather than FTS; list windows grow by 200 on Load more. No library export/import |
| Profiles/parental controls | Local profiles, entry PIN, management PIN, restricted category enforcement including cached episode parent, PBKDF2 hashes, session cooldown | Local UI protection, not OS account administration. PIN reset requires clearing data. Parent must configure management PIN before restrictions |
| Settings | HTTP policy, aspect, merging, source scope, sort, guide refresh, management PIN | Fixed dark identity; no configurable theme, font or overscan sliders (system scaling supported) |
| Updates | Manual GitHub release check/notes, user download, APK package/version/signer checks, installation permission and FileProvider intent | Not exercised against a real signed update. No automatic updater or key rotation. Requires configured published repository |
| Distribution | Wrapper, debug APK, R8 unsigned release APK, external-key signing configuration, CI/release workflows, Fedora/ADB/Downloader docs | No production signing credentials, GitHub target/publication or Downloader code supplied. Unsigned APK cannot install |
| Tests | 16 passing JVM/Robolectric tests, lint with no errors, compiled navigation instrumentation APK | Instrumentation execution and emulator screenshots unavailable in this environment; physical-TV acceptance and profiling pending |
| Recording/external player | Feasibility documented | Deferred; no nonfunctional buttons advertise these features |

## Release gates

Before calling this production-ready: install on actual Google TV, complete TESTING.md with authorized provider streams, capture TV renders and inspect focus/short guide cells, measure performance on constrained hardware, resolve provider-specific adapters as needed, supply and verify a stable production signing key, and publish a real HTTPS APK URL. No benchmark, screenshot, playback-success or Downloader-code outcome is invented here.
