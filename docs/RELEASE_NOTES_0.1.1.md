# Astra TV 0.1.1 development update

Addresses a large-catalog memory risk in Xtream login/import after a user reported repeated loading-time crashes on onn 4K Pro. The exact device crash cause remains unconfirmed without a trace.

- Stream live/movie/series catalog JSON instead of retaining complete responses and combined media lists.
- Encrypt/write batches of 128 to hidden staging rows; publish atomically.
- Keep the last good library on failures, clean interrupted staging on next launch, and preserve favorites/resume IDs.
- Display import progress; recover when optional movie/series endpoints are unavailable.
- Avoid Formatter allocations for stable IDs and two-minute timeouts during healthy catalog processing.
- Show a credential-free local crash/low-memory diagnostic on the next launch when available.
- Supply WorkManager configuration for reliable on-demand initialization.

Install the 0.1.1 debug APK over 0.1.0; the signing identity and application ID are unchanged. The existing Downloader code 1723835 is retained through a compatibility asset at its original URL. Android installation, real-provider compatibility and the reported onn device still need user verification. No provider login data was used in tests.
