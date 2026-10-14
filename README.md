# SAMP Mobile Client — Multi-ABI + Server Browser

SA-MP 0.3.7 multiplayer client for GTA:SA Android (Build 6.9 base).
Fork of [almetserik-sudo/SAMP-MOBILE-Source-Code](https://github.com/almetserik-sudo/SAMP-MOBILE-Source-Code),
modernized for today's devices.

![Build](https://github.com/someone-rs/SAMP-MOBILE-Source-Code/actions/workflows/build.yml/badge.svg)

## What's new in this fork

- **Full Gradle project** — `./gradlew assembleDebug` now builds a real APK:
  Java launcher (server list, nickname, Play button), in-game game-data
  downloader, and the C++ client compiled via ndk-build — mirroring the
  SA-MP Mobile Launcher layout.
- **In-game data download** — first launch downloads the complete game-data
  package from the same source the launcher uses, with progress UI and
  version-pinned completion (never asks twice).
- **Multi-ABI builds** — `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
  (upstream was `armeabi-v7a` only). CI builds the native libraries and
  the APK, uploading both as artifacts on every push.
- **Modern NDK** — migrated off the long-removed `gnustl_static` to
  `c++_static` (libc++), `APP_PLATFORM` bumped to `android-21`
  (the minimum the 64-bit ABIs require).
- **In-game server browser** — no more hand-editing `settings.ini`:
  - Favorites list with add / delete, persisted to `SAMP/servers.ini`
  - Connection history (last 10 servers)
  - **Live server info** via the SA-MP query protocol: server name,
    players, max players, gamemode and ping — queried on a background
    thread so the UI never stutters
  - One-tap connect, plus a "Play Offline" option
  - Legacy behavior preserved: set `[client] browser=0` in `settings.ini`
    to auto-connect from the ini like before

## Building

### Full APK (recommended)

This is now a standard Android Gradle project — Java launcher + C++ client,
same shape as the SA-MP Mobile Launcher:

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

The native `libsamp.so` (all four ABIs) is built automatically through
`app/src/main/jni/` (which delegates to the shared `Android.mk` /
`Application.mk` at the repo root — single source of truth).

Requirements: JDK 17, Android SDK (platform 34, build-tools 34), NDK r27.
CI builds both the native libraries and the debug APK on every push.

### Native library only

```bash
# all ABIs (needs ANDROID_NDK_HOME pointing at NDK r25+)
ndk-build -j$(nproc)

# a single ABI
ndk-build APP_ABI=arm64-v8a -j$(nproc)

# output: libs/<abi>/libsamp.so
```

## App structure

| Path | Contents |
|---|---|
| `app/src/main/java/com/sampmobile/fork/` | Launcher UI (server list, nickname), game bootstrap, data downloader |
| `app/src/main/java/.../LauncherActivity.java` | Entry screen: favorites, add/edit/delete, Play |
| `app/src/main/java/.../GameActivity.java` | Loads the client, writes `settings.ini`, starts the game |
| `app/src/main/java/.../GameDataManager.java` | One-time game-data download (same source the SA-MP Mobile Launcher uses) |
| `app/src/main/jni/` | Thin ndk-build bridge to the C++ sources |

### Game data download

On first launch `GameDataManager` fetches the update config, then downloads
**one** complete data package (`files.zip`, hosted on MediaFire — the same
data source as the SA-MP Mobile Launcher) with progress UI, verifies and
extracts it, and never asks again (version-pinned via a marker file).
No maintenance kill-switch: the config is only used for the data URL,
version, size and sentinel file.

### Native client init

`GameActivity` loads `samp`, then resolves the proprietary game engine
(`libGTASA.so`): bundled copy first, then a previously extracted copy in the
app's files dir, otherwise it asks you to pick your GTA:SA APK and extracts
`lib/<abi>/libGTASA.so` from it automatically (one time only). Then it writes
`SAMP/settings.ini` from the chosen server + nickname, and calls the native
`initializeSAMP(storagePath)`. The native side then hooks the engine and
shows the in-game server browser. Game assets and the engine are not part of
this repository — the engine always comes from your own GTA:SA APK.

## Server browser

On launch the client shows the server browser overlay (until you connect):

- **Refresh** re-queries every server (name / players / ping / gamemode).
- **Add Server** saves a favorite as `name | host | port`.
- Favorites and history live in `/storage/emulated/0/Documents/SAMP/servers.ini`:

```ini
[favorites]
count=1
name0=My RP Server
host0=play.example.com
port0=7777

[history]
count=2
...
```

Tap **Connect** on any row to join. The server is added to history
automatically.

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android Gradle module (launcher UI, manifest, resources) |
| `main.cpp` / `main.h` | JNI entry, init flow, connect logic |
| `serverbrowser.*` | Server browser UI + SA-MP query client |
| `settings.*` | `settings.ini` loader |
| `gui/` | Dear ImGui overlay (chat, dialogs, spawn screen) |
| `net/` | RakNet-based SA-MP netcode (0.3.7) |
| `game/` | GTA:SA function hooks, patches, utilities |
| `util/` | ARM hooking helpers |
| `vendor/` | RakNet, Dear ImGui, inih, md5 |

## Notes

- **ABI reality check:** GTA:SA Android itself ships as `armeabi-v7a`
  only, so the injected client runs as `armeabi-v7a` in practice. The
  `arm64-v8a` / `x86` / `x86_64` builds compile cleanly and are provided
  for completeness, tooling and future use.
- The ARM hooking code in `util/` targets 32-bit ARM (Thumb-2), matching
  the game.
- You need a copy of GTA:SA Android (v2.0) to run this; game assets are
  not included here.

## Credits

- Original SAMP Mobile source: MTHS1901 / almetserik-sudo
- Multi-ABI + server browser fork: someone-rs
