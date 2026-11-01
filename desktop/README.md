# Square for Mac

A native macOS port of [Square](https://github.com/Lelonio/Square): Spotify Premium, YouTube Music and your own music files in one app.

It runs the same Rust engine as the Android app (librespot for Spotify, plus a Symphonia decoder for YouTube Music's AAC streams and local files), with a Compose for Desktop interface in place of the Android one.

## Install

1. Download `Square-mac-arm64.dmg` from the **mac-latest** release of this repository (Apple Silicon Macs).
2. Open it and drag **Square** into Applications.
3. The app is not signed with an Apple developer certificate, so the first time, **right-click Square in Applications and choose Open**, then Open again. After that it opens normally.
   If macOS still refuses ("damaged"), run once in Terminal: `xattr -dr com.apple.quarantine /Applications/Square.app`

## Using it

- **First launch** asks where music should come from: YouTube Music (no account), Spotify (your Premium account, signed in through your browser), or folders on this Mac. Switch any time from the sidebar or Settings.
- **Queue** can mix sources. Right-click any song for Play next, Add to queue, Like, Start radio, Go to album or artist. Right-click an album or playlist to save it to your Library.
- **Now Playing** (click the song in the player bar, or ⌘L) shows the cover and synced lyrics. Click a line to jump to it.
- **Keyboard**: Space play/pause, ⌘→ / ⌘← next/previous, ⌘↑ / ⌘↓ volume, ⌘F search, ⌘1 Home, ⌘2 Library, ⌘3 Queue, ⌘S shuffle, ⌘R repeat, Esc back.
- With Spotify signed in, the Mac shows up as a Spotify Connect device named "Square on …".

Settings and the Spotify sign-in live in `~/Library/Application Support/Square`.

## What the Mac version does not have (yet)

Compared with the Android app: music videos and canvas clips, the audio effects (speed, pitch, reverb, karaoke), crossfade, downloads and offline mode, the Spotify DJ, friends' activity, signed-in YouTube Music libraries, translation of lyrics, and the refracting glass shader (the Mac uses a blurred-artwork backdrop with translucent panels). The macOS media keys and Now Playing widget are not wired up yet; use the in-app shortcuts.

## Building it yourself

Needs JDK 21 and Rust.

```bash
cargo build --release --manifest-path native/Cargo.toml
cp native/target/release/libsquarecore.dylib desktop/resources/macos-arm64/
./gradlew -p desktop run          # run from source
./gradlew -p desktop packageDmg   # build the .dmg into desktop/build/compose/binaries
```

`Square --selftest` (or `./gradlew -p desktop run --args=--selftest`) checks the engine, a YouTube Music search, a stream address and decoding without opening a window.

CI: `.github/workflows/mac.yml` builds and self-tests every push to the `macos` branch on a macOS runner, publishes the dmg on the `mac-latest` release, and writes its logs to the `ci-log` branch.

## Disclaimer

As with the Android app: not affiliated with Spotify or Google. Spotify playback re-implements Spotify's protocol, which Spotify's terms forbid, and YouTube Music is read through private endpoints. Use at your own risk. GPL-3.0.
