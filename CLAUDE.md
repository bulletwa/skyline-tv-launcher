# Skyline (TV Launcher)

Custom tvOS-style Android TV launcher. Java + Views only, minSdk 26 / targetSdk 33, Outfit font. Target: TCL Smart TV Pro
(Android 11, 1920x1080 at 320dpi = 960x540dp, 2 GB RAM, 32-bit ARM) at `192.168.1.6:5555` over ADB.
Source: `app/src/main/java/com/tvlauncher/`. Tests: `app/src/test/java/com/tvlauncher/` (pure-logic JVM tests).

## Working on it cheaply (do this first)
- `tools/tv.sh deploy` build + install (one line, errors only on failure). `tools/tv.sh test` unit tests (one line).
- `tools/tv.sh keys HOME D OK HOLD BACK s1 ...` sends keys and prints a text dump of the screen: focus, mode, open dialog rows
  (`>` = focused), dock order, header texts, text/icon ink, dialog count, menu/icon geometry and gap, carousel/lift/jiggle numbers.
  Read it first; take a screenshot whenever it can't settle the question (looks, colour, blur, animation, overlap, "does this look odd").
  A screenshot that finds something the dump missed means: add that field to `DebugTools.dumpState` afterwards.
- The dump is generic, not dock-only: `tools/tv.sh tree` lists every visible text with its screen rectangle for *any* screen or open dialog
  (cards, news, settings, grid), and `issues:` in every dump flags cut-off text, text over text, off-screen text and focus on an invisible view.
  New screens get this for free; add a field to `DebugTools` only for feature-specific state.
- `tools/tv.sh check [menu|move|clean|screens|cards|ink|all]` PASS/FAIL scenarios (`tools/scenarios.sh`): `screens` opens Settings and every
  entry plus the All Apps grid; `cards` shows each card type temporarily and restores the user's cards setting. Add a scenario when a flow breaks.
- `tools/tv.sh shot [w] [l,t,r,b]` small JPEG, optionally cropped (screen px) to the part that matters; a screensaver is woken automatically by `keys`. `dock`, `logs`, `perf` print a few lines.
- `tools/inkcheck.sh [scene...]` compares the logger's measured sky brightness with real pixels (`tools/pxl.py` reads PNGs).
- Add fields to `DebugTools.dumpState` when a new UI state needs checking, rather than taking screenshots.
- Read files with grep + line ranges. Do not re-read whole files already seen. Batch adb steps into one call.
- zsh: write `a(){ adb -s 192.168.1.6:5555 "$@"; }`, never `A="adb -s ..."; $A ...`. No `timeout`, no `sed -i` without `''`.

## Rules from the user
- Ask before uninstalling/disabling apps on the TV, rebooting it, or changing system settings (beyond the tools/deploy.sh grants).
- No API keys in source (WAQI token is in git-ignored `local.properties`). No smart-home, camera, mic/voice or HDMI-input *features*
  beyond the PS5 switch already built.
- Do not change the fog, storm-band or weather-card contrast looks (user said keep). Skip screen recordings (FLAG_SECURE gives black frames).
- Be economical: minimal recaps, text checks first but screenshots are fine when text isn't enough, one deploy per batch of changes.

## Map (what lives where)
Home screen
- `MainActivity` (~900 lines): lifecycle, UI build, dock/grid lists, PlayStation entry, key dispatch.
  Helpers split out: `InkController` (text/icon colour, measured via `InkSampler`), `DockEditor` (hold-OK menu, jiggle, Move),
  `CleanMode` (clean mode + clock block sizing), `ObstacleFeed` (cards/dock rectangles for rain and snow),
  `DebugTools` (state dump broadcast, tree, issues, fake media session).
- `DockCarousel` + `DockFocus`: looping dock ring, depth of field, lift, animated swap. `TileView`: app banner (blur, jiggle).
- `RoundButton` header buttons. `GlassDialog`/`GlassDrawable`/`Glass`: frosted dialogs (anchored compact menu, `describe()` for the dump).
- `HomeWatcherService` (accessibility): takes over Home from Google TV and shows a cover; `HomeSnapshot` is its picture.
Sky and weather
- `SkyController` (fetch, state, listener) -> `SkyRenderer` (base frame) + `SkyLayers`/`SkyPlan` (cloud/mist strips) -> `SkyView`
  (SurfaceView thread: clouds, stars, rain/snow fall, lightning). `PrecipInteraction` (split from SkyView): splashes, snow piles, water film and
  drips on the cards and dock, using `Obstacles`, `WaterFlow`, `Precip`.
- `SkyModel` sun/moon/colours, `WeatherState`/`Weather*` parsing, `Scenes` fixed test skies (`--es scene rain`). `Contrast`: WCAG maths.
Cards and content
- `HomeCards`, `CardsConfig`, `CardsDialog`, `CardView`, `CardController` + `SpotlightController`, `NewsController`, `NowPlayingController`,
  `WeatherCardController`, `Ps5CardController`. News: `News*`, Wikipedia spotlight: `Wiki*`.
Apps and settings
- `AppRepo`/`AppEntry` (installed apps, banners), `Prefs`, `SettingsFlow` (+ `DockDialog`, `HiddenAppsDialog`, `NewsChannelsDialog`,
  `WeatherLocationDialog`), `Ps5`/`HdmiPick`/`HdmiInputDialog`/`Ps5Banner` (PlayStation HDMI switch), `Pools`, `ImageLoader`.

## Behaviours worth knowing
- Dock is a ring; Up from the dock goes to cards, else clean mode: cards hidden, the dock stays as a compact full-width bar (`CleanMode.DOCK_CLEAN_SCALE`,
  `MainActivity.setDockScale`), the clock block centred at its normal size. In clean mode Left/Right turn the bar without expanding it; Down grows it
  (animated) around the centred app. With cards off the header is centred too.
- Performance overlay (`PerfOverlay`, owned by `HomeWatcherService`): RAM, swap, resolution, FPS, Wi-Fi, live speed. Toggle: long-press Mute or Settings →
  Performance. Debug: `--es perf on|off`. This TV has a fixed 1080p UI, a pinned CPU clock and no readable system CPU load, so there is no CPU line.
- Hold OK (300 ms) on a dock app: it swells and jiggles; menu Open app / Move / Remove sits above it. Only one menu may exist (`dialogs=`).
- Text and the two header icons pick white or dark ink from the *real* sky pixels (PixelCopy of the live sky, or the still wallpaper when
  "Animated sky" is Off) every 0.7 s; icons count their own tint. If `ink:` shows `- ; -` nothing is being measured.
- Home takeover needs the accessibility service bound; only adb setting changes rebind it (`tools/deploy.sh` does that). `APP_AUTO_START` is allowed.
- Debug launch extras: `--es scene <name|auto>`, `--es cards`, `--es ink dark|white|auto`, `--es fakeplay`, see README.md.

## Verify before saying done
`tools/tv.sh test` (all pass), `tools/tv.sh deploy`, then `tools/tv.sh check all` or the relevant `keys` dump.
