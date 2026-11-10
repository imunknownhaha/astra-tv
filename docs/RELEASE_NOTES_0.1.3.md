# Astra 0.1.3 — Android phones and Fedora desktop

This release adds two optimized platform builds. The existing TV release and
Downloader code 1723835 continue to install the TV 0.1.2 build.

## Android phones

Install `astra-phone-0.1.3.apk` on Android 8.0 or newer. This is a separate
`com.astratv.player.mobile` app with its own local library. It supports the existing
M3U/Xtream import, encrypted credentials, Live TV, XMLTV/catch-up, movies, series,
favorites, history/resume, profiles, PIN/category restrictions and player options.

The phone UI uses Material touch buttons, a bottom navigation bar on compact
screens, a navigation rail on wider screens, and a drawer containing every
library destination. Categories scroll horizontally. Both orientations are
supported. Tap the video to hide/show controls; system Back closes playback.
The build uses R8/resource shrinking and is non-debuggable. This preview uses the
existing development signing certificate; it is a sideloaded preview, not a
Google Play release. Saved data is separate from the TV app.

## Fedora Linux (Intel/AMD 64-bit)

Download `astra-0.1.3-1.noarch.rpm` and install with:

```sh
sudo dnf install ./astra-0.1.3-1.noarch.rpm
```

Launch **Astra** from the app menu or run `astra`. `noarch` means the app is
architecture-independent Python; dnf supplies native dependencies for your
Fedora system, including GTK and mpv. The package is an unsigned development
preview; SHA256SUMS.txt is supplied.

The native GTK library provides M3U URL/local file and Xtream imports, Live TV,
movies/series/episodes, search, categories, individual/merged sources, favorites,
history/resume, profiles and PIN/category restrictions. XMLTV supports plain or
gzip guides and provider-supported catch-up. Playback opens one reusable mpv
window with hardware decoding when available, audio/subtitle selection, seek,
aspect ratio and full screen. Imports and queries run off the UI thread; SQLite
uses indexed, 200-item browsing pages. Credentials/stream addresses are encrypted
locally and passed to mpv over private IPC, not process arguments.

The first Linux preview has its own local database; it does not synchronize with
Android. Guide refresh runs at launch when older than twelve hours. Codec support
depends on the system. Recording and DRM playback are not included. See
`desktop/README.md` for data locations, playback shortcuts and source build steps.

Checksums and source are included with the release. Test evidence and known
validation limits are documented in `docs/PLATFORM_BUILDS_0.1.3.md`.
