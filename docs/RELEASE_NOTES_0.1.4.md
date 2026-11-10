# Astra Linux 0.1.4

Live TV now has a traditional category/channel/preview layout. Hover over a channel
for 500 ms to start a muted embedded preview; click to watch in full screen.
Current and next programme details appear when an XMLTV guide is available.

Only one preview runs at a time. Moving between channels cancels pending previews;
leaving Live TV, losing focus, disabling previews, or opening full playback releases
the embedded player. Preview playback does not create history. Narrow windows stack
the preview below the channel list and offer categories in a dropdown.

Fedora dependencies now include mpv-libs and libglvnd-egl, resolved by dnf.
Existing playlists, profiles, settings and credentials stay in the same local data
directory. This release changes Linux only; phone and TV APKs are unchanged.

Validation uses unit tests and a native GTK/OpenGL integration test with generated
video, fixture channels and guide data. It checks decoded pixels, hover coalescing,
responsive layout, filtering, cleanup, and full playback handoff. Real provider
streams and every GPU/Wayland driver combination have not been tested.
