# Development

## Prerequisites
- `openjdk@17` and Android SDK platform 33 (Homebrew paths are set in `tools/deploy.sh`; SDK path in `local.properties`).
- The TV reachable over ADB (default `192.168.1.6:5555`).
- Optional WAQI token for real air-quality readings: copy `local.properties.example` to `local.properties`.

## Build, test, deploy
```bash
./gradlew testDebugUnitTest   # pure-logic JVM tests (app/src/test)
tools/deploy.sh [ip:port]     # build, install, grants, make sure the Home service is bound
```

## Helper scripts (`tools/`)
| Script | Purpose |
|---|---|
| `tv.sh` | `deploy`, `test`, `keys`, `state`, `tree`, `shot`, `dock`, `logs`, `perf`, `check`: cheap text-first TV driver |
| `scenarios.sh` | PASS/FAIL UI scenarios behind `tv.sh check` |
| `inkcheck.sh`, `pxl.py`, `rectstat.py` | compare measured text-ink brightness with real screenshot pixels |
| `deploy.sh` | build + install + grants |

Screenshots land in `shots/` (git-ignored). Run scripts from the project root.

## Debug launch extras

```bash
adb shell am start -n com.tvlauncher/.MainActivity --es scene rain      # sunrise day golden dusk night overcast rain storm fog smog snow | auto
adb shell am start -n com.tvlauncher/.MainActivity --es strike hold     # freeze a lightning strike at its peak (storm scene); --es strike off to release
```

```bash
adb shell "am start -n com.tvlauncher/.MainActivity --es cards spotlight,nowplaying,weather"   # saved
adb shell "am start -n com.tvlauncher/.MainActivity --es cardsshow 0"                            # hide all cards (saved)
adb shell "am start -n com.tvlauncher/.MainActivity --es fakeplay 'Midnight City|M83'"           # fake media session; --es fakeplay off
```

Scene previews are not saved; choose a wallpaper in Settings to keep one. Quote values that contain spaces.


## Driving the TV cheaply

Text first, pictures last. `tools/tv.sh keys HOME D D OK` sends keys and prints one short state dump
(focus, mode, open dialog rows with `>` on the focused one, dock order, header texts, ink, cards). It comes from a debug-only
broadcast (`am broadcast -a com.tvlauncher.DUMP`, handled in `MainActivity.dumpState`). Other commands: `state`, `shot [width]`
(small JPEG), `deploy` and `test` (one line each unless they fail), `dock`, `logs`, `perf`. Look at a screenshot only for things
text cannot show: colours, blur, animation, overlap.
