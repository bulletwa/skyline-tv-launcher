# Architecture and internals

Rendering, power and takeover details, plus the code map. Written against the TCL Smart TV Pro (Android 11, 32-bit ARM, 2 GB RAM).

## Code map

| Area | Files |
|---|---|
| Home screen | `MainActivity`, `TileView`, `RoundButton`, `CardView`, `DotsView` |
| Sky (pure model + tests) | `SkyModel`, `WeatherState`, `WeatherParse`, `Scenes` |
| Sky (Android) | `SkyRenderer`, `SkyController`, `RainView`, `Precip` |
| Glass | `Glass`, `GlassDrawable`, `GlassDialog` |
| News + spotlight | `FeedParser`, `Catalog`, `NewsRepo`, `NewsController`, `SpotlightController`, `ImageLoader`, `WikiParse` |
| Settings | `SettingsFlow`, `WeatherLocationDialog`, `DockDialog`, `HiddenAppsDialog`, `NewsChannelsDialog`, `SleepTimer` |

The fuller, current map (clean mode, dock, cards, sky layers, PlayStation) is in [CLAUDE.md](../CLAUDE.md#map-what-lives-where).

## Animated sky

Settings → Wallpaper → "Animated sky" (on by default). The still sky (gradient, sun, moon, glow, bottom fade) is drawn
once per change; the motion is separate layers on a `SkyView` surface (software canvas, 960x540, ~12 fps, ~25 fps while
raining): drifting cloud decks / puffs / wisps, fog and mist banks (three speeds), smog haze, twinkling stars and the
occasional shooting star. `SkyPlan` decides which layers a sky gets (pure Java, tested), `SkyLayers` builds them as
tileable ARGB strips with colour, strength and the bottom fade baked in (plain ARGB blits are the fast path on this TV;
tinted alpha-only masks were several times slower), `SkyView` slides and wraps them. Cost on this TV, as a share of one
core: clear night ~11%, day ~14%, overcast ~18%, fog ~21%, rain ~50%, storm ~63%; 0% with the switch off or while the
launcher is not on screen. Frame timing is logged every 5 s: `adb logcat -s SkyView:I`.

Fog is three tall, feathered layers (each fades to nothing at its top and bottom) that overlap into one veil and take
their colour from the sky behind them (`SkyPlan.mistColor`). The sky surface is 32-bit: the default 16-bit surface
turned smooth gradients into visible horizontal stripes. Rain splashes on, and snow settles on, the cards and the dock:
`MainActivity.updateObstacles` sends their rounded rectangles to `SkyView`, `Obstacles` does the collision maths.

Readability: `Contrast` computes, per sky, how much of a dark veil white text needs at three heights (4.5:1 for the
header text, 3:1 for the big clock). The veil is baked into the sky picture and the cloud layers (`SkyRenderer.veilGradient`,
`SkyLayers.bakeVeil`), not laid over the screen, so rain and snow stay bright on top of it. Snow drifts are smooth
anti-aliased paths; the storm's horizon band is dimmed so rain stays visible down to the dock. The TV's display is
`FLAG_SECURE`, so `screenrecord` records black frames; use `adb exec-out screencap -p` (about 2 s per frame).

Weather and the cards: particles that hit a card respawn above the gaps and side margins a share of the time (rain 20%, snow
40%; `Obstacles.openSample`) so the dock gets weather too. Rain leaves a thin water film on the tops of the cards and the dock
(`WaterFlow`): it runs downhill and with the wind, beads over the rounded corners and drips off the ends, and the drips
fall onto the dock below. Snow drifts are smooth paths (the drawing buffer must cover the dock's ~290 bins). Flakes are
opaque white. The log line from `adb logcat -s SkyView:I` ends with `landed: dock N cards N drips N` per 5 s.
Text colour: `MainActivity.applyInk` picks white or dark ink for the clock, date, weather line, divider and the two round
buttons from the sky behind them (`Contrast.inkFor`); with the contrast veil white always passes today, so dark only
appears on a sky the veil cannot darken enough. `--es ink dark|white|auto` forces it for testing.

## Power use when something else is on screen

`MainActivity.onPause` (any other app, or a translucent panel such as the TV's settings, took focus) puts the sky thread to
sleep (wait/notify, no polling) and stops the cards (no cycling, fetching or media/HDMI listeners). `onTrimMemory(UI_HIDDEN)`
(a full-screen app is in front) also frees the cloud/mist layers and the picture caches; they are rebuilt when you come back.
Measured on the TV: launcher visible ~7% of one core; settings panel in front 0%; YouTube in front 0%, memory 66 -> 51 MB.
Worker pools use threads that exit after 20 s idle (`Pools`), per-draw allocations are cached (grid labels, button and tile
gradients, open gap columns for rain/snow), and an animated sky change renders one picture instead of two.
Home never focuses the PS5 tile (it switches input and wakes the console), and launching it is not counted as "recent".

## If Home falls back to Google TV after a long app session

TCL's memory manager (TGuard) can kill the launcher process while a heavy app such as YouTube plays; Android then wants to
restart the Home service, and TCL's boot manager refuses unless the app has the auto-start permission
(`appops get com.tvlauncher APP_AUTO_START` must say `allow`; set with `adb shell appops set com.tvlauncher APP_AUTO_START allow`).
With it allowed, a SIGKILL of the process is followed by a service restart about a second later (verified on this TV). An in-app
self-repair through secure settings does not work on this TV: only changes made through adb rebind a dead accessibility service.
If it ever happens anyway, `tools/deploy.sh` restores it.

## Hiding Google TV's home on the way back

Pressing Home first opens Google TV's home, then the service starts ours. To hide that gap, `MainActivity.onPause` saves a
640x360 picture of the home screen (`HomeSnapshot`, clock left out so it is never out of date) and `HomeWatcherService`
puts it over the screen as an accessibility overlay the moment the stock home window appears, before our launcher is even
asked to start. The cover comes off ~160 ms after our launcher is resumed (2.5 s safety timeout; late events for Google's
home are ignored while ours is already up). Measured on the TV: cover up ~300-380 ms from the TV's settings, ~800 ms from
YouTube (the launcher was fully stopped). `--el holdcover 6000` holds it on screen for a screenshot; `--el holdcover 0` resets.
The cover time is logged: `adb logcat -s HomeWatcher:I`.

