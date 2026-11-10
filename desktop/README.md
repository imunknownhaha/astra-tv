# Astra for Fedora

Native GTK 3 library browsing with a reusable mpv playback window. This first
Linux preview supports M3U URL/local files, Xtream Live/Movies/Series, XMLTV and
Xtream catch-up, search, categories, merged or individual source browsing,
favorites, history/resume, profiles and category restrictions with PINs.

Install on Fedora (Intel/AMD 64-bit):

```sh
sudo dnf install ./astra-0.1.4-1.noarch.rpm
astra
```

The RPM is architecture-independent Python (`noarch`); it runs on x86_64 Fedora
and resolves native GTK/mpv dependencies through dnf. It is not an Android APK
inside a Linux wrapper. The RPM is an unsigned development preview; published
SHA-256 checksums allow verifying the downloaded bytes.

Use Add playlist to import your own authorized source. HTTP is blocked initially;
allow it in Settings if required. Provider addresses/passwords are AES-GCM
encrypted with a per-user local key. The key and library live in
`$XDG_DATA_HOME/astra` (normally `~/.local/share/astra`) with restrictive file
permissions. Back up the whole directory together; deleting the key makes stored
credentials unreadable. Data is separate from Android; no sync is implemented.
Metadata such as channel names and guide titles is stored in plaintext locally.

Live TV uses categories, a channel list and a video preview with current/next
programme details from your XMLTV guide. Hover for half a second to preview; click
a channel or Watch full screen to watch. Previews are muted, limited to one stream,
and stop when leaving Live TV, losing focus or opening full playback. Hover
previews can be disabled above the list. Preview playback does not add history.
At narrower widths the preview stacks beneath the list.

Double-click a movie/title or choose Play / details. The separate native mpv
window provides video, subtitles, audio selection, seeking, volume, aspect ratio
and full screen. Space pauses, arrows seek, F toggles full screen, J cycles
subtitles, # cycles audio, Shift+A cycles aspect ratios. Playback resumes movies
and episodes; history and progress are saved every ten seconds and on close.
Select Retry playback if a stream fails. Next episode appears after loading a
series. Codec support and hardware decoding depend on the system and source.
No recording, DRM, automatic APK updates, Android/Linux sync or bundled media.

Imports stream M3U/Xtream into indexed SQLite, commit atomically, preserve old
catalogs when refresh fails, and run outside the UI thread. Browsing uses 200-item
pages, explicit Load more and debounced search. Downloads and decompressed guides
are limited to 128 MiB. Xtream episode detail is fetched only for selected series.
Optional guide refresh runs at launch when data is over twelve hours old, not as
a persistent background service.

Build with Fedora `rpm-build` installed:

```sh
./desktop/build-rpm.sh
```

Run tests with `PYTHONPATH=desktop python3 -m unittest discover -s desktop/tests`.
Runtime dependencies: python3-gobject, gtk3, python3-cryptography, python3-ijson,
python3-defusedxml, mpv, mpv-libs and libglvnd-egl. Embedded previews use
Gtk.GLArea and libmpv OpenGL, with a 15 fps filter and 12 MiB forward cache.
Driver support and source availability affect preview playback. mpv's documented IPC interface is used over an anonymous
socketpair; stream URLs never appear in process arguments or app logs.

Sources: [Fedora mpv package](https://packages.fedoraproject.org/pkgs/mpv/mpv/index.html),
[mpv IPC documentation](https://mpv.io/manual/stable/#json-ipc),
[PyGObject documentation](https://pygobject.gnome.org/).
