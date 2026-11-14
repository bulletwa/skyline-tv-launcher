# Prompt: build "Skyline", a tvOS-style Android TV launcher

Paste everything below the line into Claude Code (or any agentic coding Claude) in an empty folder.

---

You are going to build **Skyline**, a custom Android TV home-screen launcher, from scratch, in an empty project folder. Work autonomously: create the Gradle project, write all code, write unit tests, build it, and fix every error until `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass. Do not stop to ask questions; where this spec is silent, choose sensible defaults that match the look and feel described. Keep a short README documenting build, install and debug.

## 1. Platform and constraints

- Language: **Java 8 only**, plain Android **Views** (no Compose, no Kotlin, no AndroidX/appcompat, no third-party libraries). `compileSdk 33`, `minSdk 26`, `targetSdk 33`, application id and namespace `com.tvlauncher`, app label "Skyline", `versionName 0.1`.
- Test deps only: `junit:junit:4.13.2`, `org.json:json:20231013` (JVM tests with `unitTests.returnDefaultValues = true`). Lint must not abort the build.
- Target device: a 32-bit ARM Android 11 TV, 1920x1080 at 320dpi (= 960x540dp), 2 GB RAM, weak CPU. Everything must be cheap: no per-frame allocation, shared worker pools whose threads exit after 20 s idle, animation stops when the launcher is not on screen.
- Font: **Outfit** variable TTF in `assets/Outfit.ttf`, loaded through a small `Fonts` helper, used with weights 200 to 600 (the clock is weight 200, thin). Download it from Google Fonts (OFL) if it is not available.
- No API keys in source. The only optional key is a WAQI token read from git-ignored `local.properties` (`waqi.token=...`) and exposed through `BuildConfig.WAQI_TOKEN` (`buildFeatures.buildConfig true`). Ship a `local.properties.example`.
- No smart-home features, no camera, no microphone/voice, no HDMI-input features other than the PlayStation switch described below.
- Code style: small single-purpose classes, pure-Java logic separated from Android code so it can be unit-tested on the JVM, comments only where the "why" is not obvious.

## 2. Manifest

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS` (granted over adb, used for the accessibility service). Features `leanback` and `touchscreen` both `required=false`. `<queries>` for MAIN/LEANBACK_LAUNCHER and MAIN/LAUNCHER.
- `MainActivity`: exported, `singleTask`, `stateNotNeeded`, `resumeWhilePausing`, landscape, handles config changes itself, filters for `MAIN+LEANBACK_LAUNCHER` and `MAIN+HOME+DEFAULT`. Application has `banner` and `icon` drawables (generate simple PNGs), `allowBackup=false`, hardware accelerated.
- `MediaListenerService` (empty `NotificationListenerService`, needed so media sessions can be read), `SleepReceiver` (not exported), `HomeWatcherService` (accessibility service, section 9).

## 3. Home screen layout (960x540dp, Views, D-pad only)

Full-screen sky wallpaper behind everything (section 6). Over it, top to bottom, with 20dp top margin and a page padding of about 48dp:

1. **Header (76dp)**. Left: a big thin clock (54sp, weight 200, 12 or 24 h as per system) then a 1dp vertical divider (30dp tall, 18dp side margins) then the date in upper case with letter spacing 0.16 ("THURSDAY 5 NOVEMBER", 14sp, weight 300), then below/inline an **info line** with weather: temperature, condition, and "AQI 212" (see section 7). Right: two `RoundButton`s (36dp circles, 12dp apart): **Wi-Fi/network** and **Settings gear**. Round buttons draw a translucent glass disc and a hand-drawn vector icon (Canvas paths, no drawables) and get a soft focus ring/scale when focused.
2. **Card row (220dp tall, 10dp top margin)**: up to three cards side by side (section 5). When cards are switched off the header scales up and centres in the freed space ("header grows to fill").
3. **Dock band (125dp tall, 6dp bottom margin)**: a rounded (28dp radius) frosted-glass strip (`GlassDrawable`) holding the **dock carousel** of app banners (180x~101dp banners with 16dp gaps), plus a small "all apps" entry/button at the end of the strip that opens the All Apps grid.
4. **All Apps grid**: a full-screen scrolling grid of every launchable app (banner tiles, labels under them), reached from the dock; Back returns.

Focus rules (D-pad): Up/Down move between header buttons, cards and dock; Left/Right never leave a row. Up from the dock goes to the cards; if no cards are shown, Up from the dock goes to the header, and one more Up from the header buttons enters **clean mode** (everything but the header hides, header scales/centres; setting `clean_up` can disable this). Down or Back leaves clean mode. Focus is never placed on invisible views.

## 4. The dock: a looping ring with depth of field

- Apps sit on a **ring**: a pure-maths class `DockFocus`: `offset(index, position, count)` wraps into [-n/2, n/2); `step(index, dir, count)` wraps around; with fewer than 3 apps (`MIN_FOR_LOOP`) there is no loop and apps just sit in a row; `nearestTarget(position,index,count)` takes the short way round; `seamFade(offset,count)` fades an app to 0 over the last 0.6 of an app width before it jumps across the ring seam.
- Depth of field from distance `d` (in app widths): `blur(d)=smoothstep(|d|/1.9)`. Centre app scale 1.08, far scale 0.76, far alpha 0.25; scale/alpha/defocus all lerp with `blur` and are multiplied by an `amount` (1 when the dock has focus, 0 otherwise, so nothing is highlighted when focus is elsewhere). Defocus = a pre-blurred copy of the banner cross-faded over the sharp one (blur each banner once and cache it). No highlight plate or glow.
- `DockCarousel` animates the position float (ease-in-out ~250 ms) when you press Left/Right and animates swaps. `TileView` draws a banner (rounded corners, app banner bitmap or a generated `AppArtDrawable` with the app's icon on a tinted gradient when it has no banner, label under it), supports the blurred copy and a **jiggle** (iOS-style rotation wobble) when editing.
- Default dock order: the user's saved order, else recent/popular apps. The dock also keeps one "favourite" app (most launched, at least N launches) for the Spotlight card.

### Dock editing
Holding OK (300 ms, key repeat) on a dock app: the tile **swells and jiggles** and a compact frosted menu appears above it: **Open app / Move / Remove**. Only one menu may exist at a time. Move mode: Left/Right swap the app around the ring with animation, OK or Back confirms. The release after the hold must not launch the app. Settings has "Customize dock" (list dialog to add/remove/reorder) and "Hidden apps" (apps hidden from the grid). Launches are timestamped and counted in prefs (`t_<pkg>`, `n_<pkg>`).

## 5. Cards

Interface `CardController { CardView view(); void start(); void stop(); String debug(); }`. `CardsConfig` (pure Java, tested): ids `spotlight, news, nowplaying, weather, ps5`, display order fixed in that order, **max 3 on at once**, unknown ids and duplicates dropped on parse, defaults `spotlight,news`, `set(current,id,on)` refuses a 4th card. Prefs: `cards_show` (master switch "1"/"0"), `cards_on` (csv, kept even when the master switch is off). `CardsDialog` toggles them (rows disabled when full). `CardView` is a ~300x220dp rounded glass card: full-bleed image, bottom gradient scrim, small upper-case accent label with a coloured dot, big title, subtitle, optional extra view; focus = scale up ~1.05 and a bright border; OK triggers the card's action. Cards stop all fetching/cycling/listeners when the activity is paused.

- **Spotlight**: Wikipedia REST `https://en.wikipedia.org/api/rest_v1/feed/featured/YYYY/MM/DD` (`WikiParse`, tested): picture of the day (widened thumbnail ~960px), and trending/most-read articles; cycles every ~10 s with cross-fade and dots (`DotsView`); one slide is "your favourite app" (the most-launched app) which launches it; opening an article opens its Wikipedia URL via an intent.
- **News**: `Catalog` of RSS channels (tested; filter by category and country; `forCountry(cc)` returns global ones plus that country's): BBC World, Al Jazeera, BBC Technology, The Verge, Ars Technica, BBC Science, NASA (global); The Hindu, The Hindu Sci-Tech, Times of India, NDTV, Indian Express, Hindustan Times, India Today, Scroll, LiveMint, Firstpost (country `IN`). Categories: World, India, Technology, Science, Business. Each channel has a brand colour int. `FeedParser` (tested) parses RSS/Atom (title, link, date, image from `media:content`/`enclosure`/first `<img>`), `NewsRepo` fetches with caching and merges followed channels newest-first, `NewsController` cycles headlines with the channel name as accent, image background (via `ImageLoader`, an LRU bitmap cache that frees on trim-memory) and OK opens the link. `NewsChannelsDialog`: searchable, category-filtered list with a "Following only" toggle; follow/unfollow with OK.
- **Now Playing**: reads active `MediaSession`s through `MediaSessionManager.getActiveSessions(ComponentName(MediaListenerService))`, shows title, artist, app name, album art (or a gradient from art colours, `NowPlayingArt`), play/pause state; OK opens the player app; shows "Nothing playing" otherwise. Needs Notification access (document the `adb shell cmd notification allow_listener com.tvlauncher/com.tvlauncher.MediaListenerService` command).
- **Weather**: no network of its own, fed by `SkyController`. Big temperature (44sp, weight 200), condition, "Feels like", a `WeekStrip` (7 day forecast: day name, small condition glyph, high/low) and a `DayArc` (sun path arc with current position). Painted in the colours of the current sky. OK opens a details glass dialog (humidity, wind, UV, visibility, AQI, hourly strip). Keep its contrast look exactly: white text on the sky-coloured card.
- **PlayStation**: shows "PlayStation 5, On/Standby/Not found" and an HDMI-port label; OK switches the TV to the console's HDMI input (section 8). Never focused automatically on home (it would wake the console).

## 6. The sky wallpaper (the signature feature)

The wallpaper is a procedurally drawn, live sky that follows the **real sun, moon and weather** for the user's location. It is a 960x540 `SurfaceView` (`SkyView`, 32-bit `RGBA_8888`: 16-bit produced visible banding) with a software canvas thread at ~12 fps (~25 fps while raining), sleeping with wait/notify (no polling) when the activity is paused and freeing layers on `onTrimMemory(UI_HIDDEN)`.

**Pure model (`SkyModel`, `WeatherState`, `WeatherParse`, all unit-tested):**
- Solar and lunar position from lat/lon/time (standard astronomical formulae; sun elevation, azimuth, moon phase and elevation, day/night, `morning()` flag). Season from calendar month and hemisphere (`auto/spring/summer/autumn/winter`).
- Clear-sky colours interpolated by sun elevation through keys `{elev, top, horizon}`: -18 `#040614/#0A0E22`, -12 `#080E2C/#1C244E`, -8 `#142256/#60466E`, -4 `#284282/#D67A6A`, -1 `#466EB9/#FF9448`, 3 `#4E84D0/#FFC482`, 8 `#4284DE/#C8D6EE`, 20 `#2872E1/#94C6F5`, 90 `#1C64DC/#82BEF5`; warm band at the horizon mixed 14% toward `#FF8C8C` (morning) or `#FF5A28` (evening).
- `weatherSky(elev, morning, weather)`: cloud>25% greys both colours; snow mixes toward `#AAB4C3/#C8D0DC` (scaled by daylight 0.2..1) and a lavender-white tint; rain toward `#586680/#8291A0` (storm ×0.62) with a teal-slate horizon tint `#6F9096`; clear days get a slightly richer blue; fog mixes toward grey by `fogAmount`; AQI>100 adds a brown smog haze (`#96693C` horizon, `#7A6A58` top).
- WMO weather codes map to flags and strengths (fog 45/48; drizzle 51-57; rain 61-67; showers 80-82; snow 71-77, 85-86; thunderstorm 95-99), `fogAmount()` from code + visibility, rain/snow intensity 0.35/0.6/0.85 by code.
- `Scenes`: fixed test/preview skies, names `sunrise day golden dusk night overcast rain storm fog nightfog smog snow partly drizzle hot moonrise sunsetlow`, plus `auto` (live). Settings > Wallpaper previews them; choosing one saves it (`scene` pref).

**Rendering:**
- `SkyRenderer` draws the still base (gradient, sun disc/glow/rays, moon with phase, bottom fade, vignette, haze, grain) once per change. `SkyStyle` interface with `ClassicStyle` and `VanGoghStyle` (painted swirling-brushstroke look using the same sampled colours so weather/contrast/ink behave identically); Settings has a Style toggle. A season setting drives trees/meadow/snow in the painted style.
- `SkyPlan` (pure, tested) decides which layers a sky gets (thick cloud and rain hide stars; fog/haze soften them). `SkyLayers` builds tileable ARGB strips with colour, strength and bottom fade baked in (plain ARGB blits are the fast path; avoid tinted alpha masks): drifting cloud decks, puffs and wisps, **three fog/mist banks** at three speeds (tall feathered layers fading to nothing at top and bottom, colour taken from the sky behind: `SkyPlan.mistColor`), smog haze, twinkling stars, occasional shooting star, lightning flashes with a freezable peak for tests.
- **Rain and snow** (`Precip`, `PrecipInteraction`, `Obstacles`, `WaterFlow`, all tested where pure): particles collide with the on-screen cards and dock (`ObstacleFeed` supplies their rounded rectangles): rain splashes on card tops, leaves a thin **water film** that runs downhill with the wind, beads over rounded corners and drips off the ends onto the dock; snow settles in smooth anti-aliased drifts on cards and dock (buffer must cover ~290 dock bins), flakes opaque white. Particles that hit a card respawn above the gaps/side margins part of the time (rain 20%, snow 40%) so the dock gets weather too. Storm horizon band is dimmed so rain stays visible down to the dock.
- **Readable text over any sky** (`Contrast`, WCAG maths, tested): a dark veil is *baked into* the sky picture and cloud layers (not drawn over the screen, so rain stays bright) strong enough for 4.5:1 for header text and 3:1 for the big clock. **Ink** (`InkController` + `InkSampler`): every 0.7 s sample the *real* pixels behind the clock, date, weather line, divider and the two round buttons (PixelCopy of the live sky surface, or the still bitmap when "Animated sky" is Off), and choose white or dark ink (`Contrast.inkFor`); icons count their own tint. Debug extra `--es ink dark|white|auto`.
- Settings toggle "Animated sky" (default on): off = still picture only, minimum power.

**Weather data (`SkyController`, listener-based, refresh ~every 10-15 min and on location change):**
- Open-Meteo forecast `https://api.open-meteo.com/v1/forecast` (current, hourly, daily: temperature, apparent temp, humidity, wind, UV, cloud cover, visibility, precipitation, weather code, sunrise/sunset), METAR from `https://aviationweather.gov/api/data/metar?format=json&bbox=...` to correct fog/visibility from the nearest station.
- **Air quality (`Aqi`, tested)**: header shows India's CPCB AQI. With a WAQI token: `https://api.waqi.info/feed/geo:LAT;LON/?token=...` converts the station's pollutant concentrations (US EPA index per pollutant as WAQI reports) into CPCB sub-indices (breakpoint tables for PM2.5 and PM10, take the max) and shows "AQI 212". Without a token: Open-Meteo air-quality `https://air-quality-api.open-meteo.com/v1/air-quality?...&current=us_aqi,pm2_5,pm10`, converted the same way, marked "AQI ~88" (approximate). Include AQI category colours and word.
- Location: manual (Settings > Weather Location, searchable via Open-Meteo geocoding `https://geocoding-api.open-meteo.com/v1/search?name=`, store `loc` = `lat|lon|name`, `cc` country code, `loc_manual=1`) or auto from IP via `https://ipwho.is/`. A `Http` helper with timeouts and a UA; all parsing in pure `WeatherParse` with fixture-based tests.

## 7. Settings (`SettingsFlow`) and the glass dialog system

All dialogs use one **frosted-glass `GlassDialog`** builder (custom Dialog/Window, not AlertDialog): rounded translucent panel with blurred sky behind (`Glass`, `GlassDrawable`; capture a downscaled blurred copy of the home screen as the backdrop), title, section headers, rows (label, optional description, right-aligned value, focusable with highlighted pill), buttons, message text, a width param (e.g. 640dp), an **anchored compact menu** mode (used for the dock menu), `onClosed` callbacks so sub-dialogs return to their parent (Back walks up one level), and a `describe()` text dump of its rows for debugging. Row value updates in place (`setRowValue`).

Settings sections and rows:
- **Appearance**: Wallpaper (Animated sky On/Off, Style Classic/Van Gogh, Season auto/spring/summer/autumn/winter, then the list of scenes with "Selected" marker and Auto sky), Weather Location.
- **Home**: Cards, Home screen (Skyline / Google TV's own home: choosing Google TV sets `home_mode=googletv` and launches `com.google.android.apps.tv.launcherx/.home.HomeActivity`), Customize dock, PlayStation input, News channels, Hidden apps.
- **System shortcuts** (each tries a list of intents, toast "This TV has no screen for that"): Wi-Fi, Bluetooth & remotes (TCL/AOSP accessory activities then Bluetooth settings), Display, Sound, Apps, About, Developer options.
- **Sleep timer** (`SleepTimer`, tested): options Off, 15, 30, 45, 60, 90, 120 min; uses `AlarmManager` + `SleepReceiver` to turn the screen off (lock/sleep via device admin-free path that works on the TV, e.g. `KEYCODE_SLEEP` injection is not allowed, so use the best available method and fall back to a full-screen black "sleep" overlay with the screen dimmed); describes the remaining time.
- `Prefs` (SharedPreferences "launcher"): dock csv, hidden set, cards keys, `clean_up`, `home_mode`, scene, style, season, animated, location keys, per-app launch time/count, sleep end time, generic `str/putStr/remove`.

## 8. PlayStation HDMI switch (`Ps5`, `HdmiPick`, `HdmiInputDialog`, `Ps5Banner`)

- Use only public `TvInputManager`: list inputs of `TYPE_HDMI` with their port state. `HdmiPick` (pure, tested) chooses the PlayStation: prefer an input whose label contains "PlayStation"/"PS5", parse the port from CEC ids like `.../HDMI200004` (regex `HDMI([0-9A-Fa-f])[0-9A-Fa-f]{3}[0-9A-Fa-f]{2}$`), report On/Standby/Disconnected, label "HDMI 2".
- Switching = an `ACTION_VIEW` of the input's passthrough URI (`TvContract.buildChannelUriForPassthroughInput`) with type `vnd.android.cursor.item/channel`, component `com.tcl.tv/com.tcl.tv.TVActivity`. Settings > PlayStation input lets the user choose the input if auto-detect is wrong (saved in prefs). A "PS5" dock tile (generated banner, `Ps5Banner`) is added once at the end of the dock; launching it is not counted as "recent". Logcat tag `Ps5`.

## 9. Taking over Home (`HomeWatcherService`)

The TV ignores `cmd package set-home-activity`, so an **AccessibilityService** limited to package `com.google.android.apps.tv.launcherx`, event `typeWindowStateChanged`, `canRetrieveWindowContent=false`, starts `MainActivity` whenever Google TV's home window appears (only if pref `home_mode == skyline`; also on `onServiceConnected` within 3 minutes of boot if the launcher is not resumed). To hide the Google TV flash: `MainActivity.onPause` stores a 640x360 `HomeSnapshot` of the home screen (clock excluded so it is never stale); the service immediately adds it as a full-screen `TYPE_ACCESSIBILITY_OVERLAY` (not focusable, not touchable) when the stock home appears, and removes it ~160 ms after the launcher reports it is resumed (`launcherShown()`), with a 2.5 s safety timeout; late stock-home events are ignored while ours is resumed. Provide `deploy.sh` that builds, `adb install -r`, grants `WRITE_SECURE_SETTINGS`, `appops set com.tvlauncher APP_AUTO_START allow`, enables the accessibility service via `settings put secure enabled_accessibility_services`, starts the app first (the service only binds reliably while the process runs) and verifies binding. Document that `force-stop` unbinds it and a re-deploy fixes it.

## 10. Power and memory behaviour

`onPause`: stop sky thread (sleep), stop all cards, remove listeners, snapshot the home. `onTrimMemory(UI_HIDDEN)`: free cloud/mist layers and picture caches (rebuild on return). Cache per-draw allocations (grid labels, button/tile gradients, open-gap columns for precipitation). One sky render per animated change. Target: ~7% of one core while visible, ~0% when another app is in front.

## 11. Debug and test tooling (build this too, it is part of the app)

- `DebugTools.dumpState` answers a debug-only broadcast `com.tvlauncher.DUMP` with a compact text dump: focused view, mode (clean/grid/dock), open dialog rows (`>` marks focus), dock order, header texts, ink state, dialog count, carousel numbers, card summaries, plus a generic **tree** of every visible text with its screen rectangle and an `issues:` line (cut-off text, text over text, off-screen text, focus on an invisible view). Also a fake media session (`--es fakeplay 'Title|Artist'`).
- Launch extras: `--es scene <name|auto>`, `--es strike hold|off`, `--es cards spotlight,nowplaying,weather`, `--es cardsshow 0|1`, `--es ink dark|white|auto`, `--el holdcover <ms>`.
- `tools/tv.sh` (deploy, test, keys, tree, shot, dock, logs, perf, check) wrapping adb to a configurable `ip:port`, and `tools/scenarios.sh` with PASS/FAIL flows (open Settings and every entry, All Apps grid, each card type, dock move/menu, clean mode).

## 12. Unit tests to write (pure JVM)

`SkyModelTest` (sun/moon positions, colours at key elevations, season), `SkyPlanTest`, `ContrastTest` (WCAG ratios, ink choice), `WeatherParseTest` + `WeatherFlowTest` (fixtures for Open-Meteo, METAR, geocoding, IP), `AqiTest` (CPCB breakpoints, WAQI conversion), `DockFocusTest` (ring wrap, scale/alpha/blur, seam fade, no-loop under 3 apps), `ObstaclesTest`, `PrecipTest`, `CardsConfigTest`, `NewsTest` (RSS/Atom fixtures), `CatalogTest`, `WikiParseTest`, `HdmiPickTest`, `SleepTimerTest`, `ListOpsTest`. Every pure class needs coverage; all must pass.

## 13. Deliverables and order of work

1. Gradle project + manifest + fonts + resources; `Prefs`, `Fonts`, `Http`, `Pools`.
2. Pure logic + tests (sections 4, 5, 6, 8, 12) until green.
3. Sky wallpaper (`SkyRenderer`, `SkyLayers`, `SkyView`, `SkyController`), then header, ink, glass dialogs.
4. Dock carousel, editing, All Apps grid, then cards, Settings.
5. `HomeWatcherService`, `deploy.sh`, debug tooling, README.
6. Build the APK, run the tests, and finish with a short summary of what exists and what you could not verify without a real TV.

Visual identity to keep throughout: tvOS-like calm, thin Outfit type, frosted glass, soft focus scaling (no hard outlines), restrained motion, text always readable over any weather.
