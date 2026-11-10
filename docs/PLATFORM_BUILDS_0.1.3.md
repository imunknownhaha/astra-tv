# Platform builds 0.1.3

The handheld build shares the TV player's import, database, security and playback
code. It adds a separate package, standard phone launcher, touch controls,
compact bottom navigation, wider navigation rail, a full destination drawer and
horizontal categories. Both orientations are allowed. Activity recreation keeps
the retained player instead of stopping it during rotation. Video controls are
revealed/hidden by touch, and system Back retains its existing behavior.

The Fedora app is a separate native GTK/Python implementation backed by indexed
SQLite and one mpv process. Runtime dependencies are declared in the RPM,
including mpv >= 0.38 for the structured loadfile IPC signature. The package
contains architecture-independent Python and resolves system-native dependencies
through dnf on x86_64 Fedora. Its GTK library and mpv window both run directly on
Linux; no Android emulator or browser runtime is included.

## Validation

- 30 shared Android parser/import/persistence/migration/query/focus tests passed
  in `testDebugUnitTest`.
- Three handheld activity tests passed: 320dp portrait import/Xtream form,
  favorite/search/Back; 800dp landscape rail/drawer; 360dp portrait library at
  1.3 font scale. Each activity scenario runs in its own worker process so
  Robolectric cannot reuse an invalidation tracker after resetting application
  state. Shared tests run once in the debug suite rather than being duplicated.
- The dialog scenario uses a narrower 320dp phone because of the documented
  [Robolectric Dialog/TextField sizing issue](https://github.com/robolectric/robolectric/issues/8460).
- Native GTK smoke test loaded a 200-row synthetic catalog, toggled a favorite,
  visited all thirteen destinations, and captured the library on a private Xvfb
  display. The exact RPM payload was extracted and exercised, without changing
  the host installation.
- Nine Linux tests passed, including real local HTTP fixture imports for M3U,
  Xtream Live/Movies/Series and episodes, optional endpoint failures, failed
  refresh rollback, encrypted credential persistence, PIN/profile restrictions,
  XMLTV/gzip and unsafe-XML rollback, catch-up, source deletion and an indexed
  20,000-channel catalog with 200-row pages.
- A generated six-second local video played in real mpv 0.41.0. Pause and saved
  position/duration were verified, and the stream address was absent from process
  arguments. This verifies the IPC playback path, not a provider's codec lineup.
- RPM file lists, explicit dependencies and payload digests were checked.

The phone merged manifest is inspected separately: normal Android launcher,
touchscreen required, no Leanback feature and no landscape lock. Phone overlays
suppress only the TV-specific lint checks (`MissingLeanbackLauncher`,
`ImpliedTouchscreenHardware` and the touchscreen-only `UnsupportedTvHardware`), because lint reads the pre-merge TV declarations.
They do not suppress phone compatibility, security or code checks.

No physical Android phone or onn device was connected. Android UI assertions are
Robolectric host tests, not hardware screenshots, frame-rate benchmarks or
provider playback certification. Linux testing used the Fedora-family 44 host
with GTK 3 and mpv in a private X11 display; real Wayland/GPU/codec behavior still
needs acceptance on the target computer. There is no Android/Linux library sync.

See the release's SHA256SUMS.txt for the final distributed bytes. Android phone
builds use the existing development certificate and R8/resource shrinking; the
RPM is an unsigned development preview. The TV Downloader destination remains
on the already-published 0.1.2 TV package.
