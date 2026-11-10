# Astra TV

Native Kotlin IPTV player for Google TV / Android TV. Application ID `com.astratv.player`. Android 8.0 (API 26) or newer; remote and landscape TV required. No channels, media, credentials or subscriptions are bundled.

This is a functional **0.1.2 development build**, not a certified production release. See [implementation status](docs/STATUS.md) and [test evidence](docs/TESTING.md) for the exact scope and verification limits.


## Download the development build

[Download Astra TV 0.1.2 optimized APK](https://github.com/imunknownhaha/astra-tv/releases/download/dev-0.1.2/astra-tv-0.1.2-optimized.apk) · [Release details and source archive](https://github.com/imunknownhaha/astra-tv/releases/tag/dev-0.1.2)

**Downloader code: `1723835`** · [Short download URL](https://aftv.news/1723835)

This APK is signed with a development certificate. Playback, remote behavior and installation still require Google TV device verification. A stable production signing identity has not been configured.

Version 0.1.1 streams Xtream catalogs in small encrypted batches. The existing Downloader code is retained using a compatibility download asset; choose Update over the existing installation.

## Optimized distribution build

Run `./gradlew testDebugUnitTest assemblePreview lintPreview` to produce `app/build/outputs/apk/preview/app-preview.apk`. Preview uses release optimization and disables debuggability while keeping the existing development signing key. Keep that private key safe for compatible updates. Debug builds remain available for engineering tests and should not be used to judge TV performance. [Measured changes and limits](docs/PERFORMANCE_0.1.2.md).

## Build on Fedora

Install JDK 21, Git, unzip and Android Studio (download its Linux archive from [Android Developers](https://developer.android.com/studio)). Android Studio's SDK Manager can install the same packages as the command line below. Open this folder as the project and select JDK 21 for Gradle.

```sh
sudo dnf install java-21-openjdk-devel git unzip
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Download Google's [command-line SDK tools](https://developer.android.com/studio#command-line-tools-only), unzip under `$ANDROID_HOME/cmdline-tools/latest` (so `latest/bin/sdkmanager` exists), then:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The wrapper downloads Gradle 9.6.0. Compile/target SDK 37, AGP 9.5.1, Kotlin/Compose compiler 2.3.21, Compose BOM 2026.09.00, TV Material 1.1.0, Media3 1.11.1, Room 2.8.5. Versions are pinned. Kotlin 2.3.21 deliberately matches Room's metadata reader; Kotlin 2.4 currently fails Room annotation processing. AGP's supported legacy Kotlin/KAPT integration is explicitly enabled until that compatibility permits migration. No dynamic dependency versions.

Output: `app/build/outputs/apk/debug/app-debug.apk`. This uses Android's development certificate. It can be installed for testing; do not use that certificate as a production identity.

The source archive deliberately omits machine-specific `local.properties`, build caches, downloaded SDK/JDK, and signing keys.

## Start watching

1. Choose **Add a playlist** from the welcome screen or **Playlists**.
2. Import an extended M3U using an HTTPS URL, choose a local M3U through Android's file picker, or enter an Xtream-compatible server, username and password. The server is the origin, e.g. `https://provider.example:443`, without `/player_api.php`.
3. Select a live channel using the D-pad and OK. Save favorites using **Save**. Movies and series use the provider's catalog; M3U entries explicitly marked `media-type="movie"` go to Movies.
4. Import a provider's XMLTV URL through **Playlists → XMLTV guide**. Leave the field empty to use a guide advertised by M3U/Xtream. XMLTV guide IDs must match M3U `tvg-id` / Xtream `epg_channel_id` exactly.
5. Choose **Guide** for a three-hour timeline. Earlier/Later moves three hours; horizontal remote navigation scrolls the schedule; selecting a program opens its supplied details. Catch-up appears only for advertised archives and supported address templates.
6. Use Settings for HTTP opt-in, aspect ratio, library merging, sorting and scheduled guide refresh. The app uses a fixed, high-contrast dark TV theme and system font scaling. No theme toggle is advertised.

D-pad moves focus. OK activates a control. Back closes dialogs, exits playback to the library, returns other destinations to Live TV, then exits normally. Player controls hide after eight seconds; press a direction or OK to show them. Play/pause, channel up/down, last channel, next/previous, fast-forward/rewind and stop have remote mappings. Audio/subtitle controls enumerate tracks actually exposed by Media3; unsupported tracks are disabled.

**Local files:** Android TV must have a document picker installed. If none is available, use a TV file manager exposing Storage Access Framework or import a URL. The app requests a persistent URI grant without broad storage permissions. Relative stream addresses in a local file have no network base and are rejected.

**HTTP:** disabled by default. Enable explicitly only when the provider requires it. HTTPS redirects to HTTP are blocked while disabled. Logos remain HTTPS-only. Sources can contain network credentials, so avoid sharing screenshots of the import form.

**Profiles:** create an optional local profile; favorites/history/resume are independent. Set a management PIN before assigning hidden categories. Management protects settings, playlist changes, profiles and update configuration. An optional profile entry PIN adds another gate. PINs use PBKDF2 with random salts; failed entry attempts are rate-limited during the session. PIN recovery requires clearing application data and loses the library. These are local UI restrictions, not OS-level device administration.

## Emulator and device testing

In Android Studio Device Manager, add a **TV (1080p)** device and an Android TV image (API 30+), with graphics acceleration/KVM when available. Arrow keys, Enter and Escape/Back simulate the remote. CLI:

```sh
sdkmanager 'emulator' 'system-images;android-30;android-tv;x86'
avdmanager create avd -n AstraTV -k 'system-images;android-30;android-tv;x86' -d tv_1080p
emulator -avd AstraTV
./gradlew connectedDebugAndroidTest
```

Run the navigation tests on an empty app installation. They exercise actual D-pad focus/OK and Back. Unit tests need no provider account and use synthetic playlists and programs. [Testing instructions](docs/TESTING.md) include synthetic media and the physical-TV acceptance checklist.

## Install through ADB

Enable Developer options on the TV, enable network/USB debugging and pair as required by the device. On Fedora, allow the Android Studio emulator's local ADB connection; for USB devices use Fedora's Android udev rules when necessary.

```sh
adb connect TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.astratv.player/.MainActivity
```

Recent Google TVs can require `adb pair IP:PAIRING_PORT` and then `adb connect IP:DEBUG_PORT` instead of port 5555. Accept pairing on the television. Do not open debugging ports to the internet.

## Signed release and Downloader

Follow [release and distribution](docs/RELEASING.md). The repository includes signing configuration and GitHub Actions workflows. No private signing key, publication destination or Downloader code was supplied; none is invented.

## Troubleshooting

- **SDK not found:** create `local.properties` as above, or set `ANDROID_HOME`. API 37 is listed as `platforms;android-37.0`, not `android-37` in current sdkmanager.
- **Java/Gradle errors:** use JDK 21 and the included wrapper. Do not use the host's unrelated Gradle installation. Kotlin 2.4 is incompatible with the pinned Room processor.
- **App not installed:** check API 26+, storage, and the certificate of the existing installation. Debug and production keys differ; Android rejects an update with another signer. Uninstalling clears data.
- **App missing from launcher:** this is an Android TV-only launcher app; install on a TV, not a phone launcher. Banner and Leanback category are included.
- **HTTP source rejected:** explicitly opt in through Settings. Invalid URLs and unsupported schemes are always rejected.
- **Empty playlist or 401/403:** check the exact source and credentials with the provider. Errors never echo the authenticated URL. Refresh keeps the previous catalog if download or parsing fails.
- **No guide:** import XMLTV, check guide IDs and date/time. Times are converted from XMLTV offsets to the TV's local zone; timestamps with no offset use UTC. Guide retention is seven days past to fourteen days ahead. Missing guide data does not block live playback.
- **No catch-up:** archive flags alone are insufficient. Xtream timeshift or an M3U catch-up URL using supported placeholders is required; actual provider UTC/time-shift semantics must be checked with the provider.
- **Playback stops or codec error:** try another stream/container or audio track. Media3 cannot supply codecs absent from the device and does not bypass DRM. Retry reconnects at most three times per selection; manual Retry starts a new attempt budget.
- **No local file chooser / APK installer:** install a TV-compatible file manager or use URL import / ADB / Downloader.
- **Update blocked:** the release APK must have the same application ID and signing certificate and a higher version code. Allow Astra TV under Install unknown apps, then select Install again. Installation remains the Android system's confirmation flow.

## Implementation

Single `app` module, with `core/{model,database,datastore,network,player,security,ui}` and `feature/{epg,player,updates}` plus the root screen/ViewModel. StateFlow drives Compose. Room queries and network/parsers run away from the UI thread. Lazy lists/grids load 200-item windows; indexed cursor queries switch channels without loading the entire library. One ExoPlayer is reused for switching, released when the activity leaves the foreground, and paired with MediaSession for system controls/audio focus. Progress is saved every ten seconds and at transitions. Persistent source configuration, authenticated playback addresses, logos and request headers are encrypted with an Android Keystore AES-GCM key. Backup is disabled because the key is device-bound.

See [architecture and tradeoffs](docs/ARCHITECTURE.md). The app deliberately does not ship recording, external-player delegation, DRM configuration, cloud accounts, or any channel discovery/catalog service.
