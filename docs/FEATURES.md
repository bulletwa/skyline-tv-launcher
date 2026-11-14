# Features and behaviour

How the launcher behaves on the TV and why. Code map: [ARCHITECTURE.md](ARCHITECTURE.md).

## Taking over Home

This TV ignores `cmd package set-home-activity`, so `HomeWatcherService` (an accessibility service) starts the
launcher whenever Google TV's home window appears. The service only binds reliably while the app process is running,
which is why `deploy.sh` starts the app before checking the binding. `adb shell am force-stop com.tvlauncher`
unbinds it: run `tools/deploy.sh` again.

## Air quality

The header shows India's CPCB AQI. With a free WAQI token it is a real monitoring-station reading ("AQI 212");
without one it falls back to Open-Meteo's modelled estimate, which under-reads badly over north India, so it is
marked "AQI ~88". Put the token in `local.properties` (see `local.properties.example`): `waqi.token=...`.
Get one at https://aqicn.org/data-platform/token.

## Cards and clean mode

Settings → Cards: hide all cards for a clean home screen, or choose up to three of Spotlight, News, Now Playing
and Weather. With no cards showing, Up from the dock hides the apps too; Down or Back brings them back.
To add a card: implement `CardController`, add its id to `CardsConfig`, register it in `MainActivity` (`HomeCards`).

Now Playing reads media sessions (Apple Music, Spotify, YouTube Music ...), which needs Notification access for the
launcher: Settings → Apps → Special app access → Notification access, or
`adb shell cmd notification allow_listener com.tvlauncher/com.tvlauncher.MediaListenerService`.

## PlayStation (HDMI input)

A PlayStation card (Settings → Cards) and a "PS5" dock tile switch the TV to the console's HDMI input. The console is
found through the TV's own input list (`TvInputManager`): this TCL names the CEC input "PlayStation 5" and reports
standby on its HDMI port. Switching VIEWs the input's passthrough URI, typed `vnd.android.cursor.item/channel`,
aimed at TCL's TV app (`com.tcl.tv/.TVActivity`, the only handler on this TV). If auto-detect picks the wrong input,
choose it in Settings → PlayStation input. The dock tile is added once, at the end of the dock.
Debug: `adb logcat -s Ps5:D` lists every HDMI input and its state.

