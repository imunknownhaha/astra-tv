# Astra Linux 0.1.5

Xtream live playback previously generated only MPEG-TS URLs. Both embedded preview
and fullscreen playback now try HLS once after MPEG-TS fails. Settings → Live stream
format also offers HLS first or MPEG-TS only, effective without reimporting the
catalog. Rewrites apply only to imported Xtream live URLs; M3U, movies, episodes,
and catch-up addresses retain their supplied formats and credentials.

Player errors now expose only a redacted HTTP status when available. Raw network
logs, login details and stream addresses stay out of the interface and logs. A
failed stream no longer forces open an empty mpv drop-file window. Player command
rejections are also reported. The numeric-locale fix remains included.

Sixteen desktop unit tests pass. The native GTK/OpenGL integration test now uses
real H.264/AAC HLS media, a redirect and an intentionally rejected MPEG-TS route.
It waits for decoded preview pixels and advancing fullscreen playback, verifies
both fall back to HLS, and checks both failure paths display a sanitized HTTP 404.
Existing hover, layout, filtering and lifecycle checks remain covered.

This is a Linux compatibility update. The reported source has not yet been
confirmed working with this update; synthetic tests do not establish provider
compatibility. The fallback makes no account API requests. Android builds are unchanged.
