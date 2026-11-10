# Release and distribution

## Stable production signing identity

The debug APK is usable for development sideloading. The optimized release artifact produced without signing environment variables is **unsigned and not installable**. Supply your own stable release key to produce the installable production APK. Keep that identity for every update; Android will reject another signing certificate.

Create a private key outside the repository (interactive passwords avoid shell history):

```sh
mkdir -p "$HOME/.local/share/astra-signing"
keytool -genkeypair -v -keystore "$HOME/.local/share/astra-signing/astra-release.jks" \
  -alias astra -keyalg RSA -keysize 4096 -validity 10000
export ASTRA_KEYSTORE="$HOME/.local/share/astra-signing/astra-release.jks"
export ASTRA_KEY_ALIAS=astra
read -rs 'ASTRA_STORE_PASSWORD?Keystore password: '; export ASTRA_STORE_PASSWORD; echo
read -rs 'ASTRA_KEY_PASSWORD?Key password: '; export ASTRA_KEY_PASSWORD; echo
./gradlew testDebugUnitTest assembleRelease
```

The `read` syntax above is for zsh. In bash use `read -rs -p 'Keystore password: ' ASTRA_STORE_PASSWORD` and similarly for the key password. Store secrets in a password manager/CI secrets. Never commit keys, credentials, local.properties or passwords.

Before release, increment `versionCode` and `versionName` in `app/build.gradle.kts`. `versionName` must match the numeric tag without `v` (e.g. tag `v0.1.0`, name `0.1.0`). Verify:

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk
```

Install the signed artifact on a physical TV and complete `TESTING.md` before public distribution. Release shrinks code/resources and may expose issues absent from debug, so test the exact signed APK.

## GitHub Releases

1. Create or select a repository and push this project. No repository was specified in the brief, so this session does not publish anything.
2. In the GitHub repository create environment `release` and secrets `ASTRA_KEYSTORE_BASE64`, `ASTRA_STORE_PASSWORD`, `ASTRA_KEY_ALIAS`, `ASTRA_KEY_PASSWORD`. Base64 is a transport format, not encryption; treat the first secret as the key itself. Add environment reviewers if wanted.
3. The CI workflow runs unit tests, lint and a debug build. The release workflow runs when a `v*` tag is pushed, validates the version, builds the signed release and uses GitHub's CLI to attach it and its SHA-256 file to the tagged release.
4. Release assets are named `astra-tv-0.1.0.apk` and `astra-tv-0.1.0.apk.sha256` for version 0.1.0. The app update checker selects this exact name and rejects foreign package IDs, old version codes and mismatched signing certificates before invoking Android's installer.
5. Download the published asset and compare SHA-256 and signer again. The final URL must be publicly accessible over HTTPS without an account.

Manual publication, after creating/pushing a real tag:

```sh
cp app/build/outputs/apk/release/app-release.apk astra-tv-0.1.0.apk
sha256sum astra-tv-0.1.0.apk > astra-tv-0.1.0.apk.sha256
gh release create v0.1.0 astra-tv-0.1.0.apk astra-tv-0.1.0.apk.sha256 \
  --verify-tag --title 'Astra TV 0.1.0' --notes-file docs/RELEASE_NOTES.md
```

The URL will be `https://github.com/OWNER/REPOSITORY/releases/download/v0.1.0/astra-tv-0.1.0.apk` **only after you publish it**. `OWNER/REPOSITORY` is a placeholder to replace, not an existing hosted release.

## Downloader by AFTVnews

1. Install Downloader on the Google TV and enable its Install unknown apps permission when prompted.
2. Enter the real public HTTPS APK asset URL from your published release in Downloader. Complete the Android installer confirmation.
3. To obtain a short code, open the [AFTVnews URL Shortener](https://go.aftvnews.com/) in a normal browser and follow its current flow with that hosted HTTPS APK URL. Account requirements or verification are external actions. Test the resulting code on the TV before sharing it.
4. A build does not generate a Downloader code. This project has no hosted APK URL or code until publication and shortener setup are performed.

## Updating in the app

In About & updates set `OWNER/REPOSITORY`, then choose Save & check. Checks are manual only. Release notes are displayed as text. Download is user-initiated and HTTPS; the file is capped at 150 MiB. The app validates package ID, version code and signing certificate. Choose Install, grant per-app installation permission if Android requires it, return and choose Install again. The system requires installation approval; there is no silent installation. Key rotation is deliberately not supported in this initial release; keep the same signing key.
