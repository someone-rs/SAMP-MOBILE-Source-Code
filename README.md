# SAMP Mobile Client — Multi-ABI + Server Browser

SA-MP 0.3.7 multiplayer client for GTA:SA Android (Build 6.9 base).
Fork of [almetserik-sudo/SAMP-MOBILE-Source-Code](https://github.com/almetserik-sudo/SAMP-MOBILE-Source-Code),
modernized for today's devices.

![Build](https://github.com/someone-rs/SAMP-MOBILE-Source-Code/actions/workflows/build.yml/badge.svg)

## What's new in this fork

- **Multi-ABI builds** — `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
  (upstream was `armeabi-v7a` only). CI builds and uploads `libsamp.so`
  for all four ABIs on every push.
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

Requirements: Android NDK r25 or newer (r27 recommended).

```bash
# all ABIs
ndk-build -j$(nproc)

# a single ABI
ndk-build APP_ABI=arm64-v8a -j$(nproc)

# output: libs/<abi>/libsamp.so
```

Or just push — GitHub Actions builds all four ABIs and attaches the
`.so` files as workflow artifacts.

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
