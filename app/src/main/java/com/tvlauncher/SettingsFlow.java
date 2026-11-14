package com.tvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

/**
 * The launcher's Settings dialog and everything it opens. Sub-dialogs return here when closed, so Back
 * walks up one level; shortcuts into the system settings leave the launcher.
 */
final class SettingsFlow {
    private final Activity act;
    private final Prefs prefs;
    private final SkyController sky;
    private final NewsController news;
    private final AppsHost host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    SettingsFlow(Activity act, Prefs prefs, SkyController sky, NewsController news, AppsHost host) {
        this.act = act;
        this.prefs = prefs;
        this.sky = sky;
        this.news = news;
        this.host = host;
    }

    // ------------------------------------------------------------------ labels

    private String wallpaperLabel() {
        String s = prefs.str("scene", "auto");
        return s.equals("auto") ? "Auto sky" : Scenes.label(s);
    }

    private String perfLabel() {
        HomeWatcherService s = HomeWatcherService.instance;
        return s != null && s.perfShown() ? "On" : "Off";
    }

    private String placeLabel() {
        String loc = prefs.str("loc", null);
        if (loc == null) return "Detecting…";
        String[] p = loc.split("\\|");
        return p.length > 2 ? p[2] : "Set";
    }

    // ------------------------------------------------------------------ main dialog

    void open() {
        GlassDialog dlg = new GlassDialog(act, 640).title("Settings");

        dlg.section("Appearance");
        dlg.row("Wallpaper", "Live sky from the weather and time, or a fixed scene", wallpaperLabel(), this::openWallpaper, false);
        dlg.row("Weather Location", "Sets the sun, moon and weather", placeLabel(), this::openLocation, false);

        dlg.section("Home");
        dlg.row("Cards", "Hide the cards for a clean look, or choose which ones to show",
                prefs.cardsShown() ? prefs.cardsEnabled().size() + " on" : "Hidden", this::openCards, false);
        dlg.row("Home screen", "Skyline, or Google TV's own home. Open Skyline from Google TV's apps to switch back",
                "Skyline", this::openGoogleTv, false);
        dlg.row("Customize dock", "Choose and arrange the apps in the dock", null, this::openDock, false);
        dlg.row("PlayStation input", "Which HDMI input your PlayStation is on", null, this::openPs5Input, false);
        dlg.row("News channels", "Choose what the news card shows", null, this::openNews, false);
        int hidden = host.hidden().size();
        dlg.row("Hidden apps", null, hidden == 0 ? "None" : String.valueOf(hidden), this::openHidden, false);

        dlg.section("System shortcuts");
        shortcut(dlg, "Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS));
        shortcut(dlg, "Bluetooth & remotes",
                component("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
                component("com.tcl.android.tv.settings", "com.tcl.android.tv.settings.accessories.AccessoriesActivity"),
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        shortcut(dlg, "Display", new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        shortcut(dlg, "Sound",
                component("com.android.tv.settings", "com.android.tv.settings.device.displaysound.SoundActivity"),
                new Intent(Settings.ACTION_SOUND_SETTINGS));
        shortcut(dlg, "Apps", new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        shortcut(dlg, "About", new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        shortcut(dlg, "Developer options", new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));

        dlg.section("Performance");
        final View perf = dlg.row("Performance overlay", "RAM, resolution and FPS on the left, over any app. Also a long press of Mute",
                perfLabel(), null, true);
        perf.setOnClickListener(v -> {
            HomeWatcherService s = HomeWatcherService.instance;
            if (s == null) {
                Toast.makeText(act, "Needs the Skyline Home accessibility service", Toast.LENGTH_SHORT).show();
                return;
            }
            s.setPerfOverlay(!s.perfShown());
            GlassDialog.setRowValue(perf, perfLabel());
        });

        dlg.section("Sleep timer");
        dlg.row("Sleep timer", "Turns the screen off after the time you choose",
                SleepTimer.describe(SleepTimer.endTime(prefs), System.currentTimeMillis()), this::openSleep, false);

        dlg.button("Close", null, false);
        dlg.show();
    }

    private static Intent component(String pkg, String cls) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
    }

    /** A row that opens a system screen, trying each intent until one works. */
    private void shortcut(GlassDialog dlg, String label, final Intent... tries) {
        dlg.row(label, null, null, () -> {
            for (Intent i : tries) {
                try {
                    act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(act, "This TV has no screen for that", Toast.LENGTH_SHORT).show();
        }, false);
    }

    /** Shows a sub-dialog and comes back to Settings when it closes. */
    private void sub(GlassDialog d) {
        d.onClosed(() -> ui.post(this::open));
        d.show();
    }

    // ------------------------------------------------------------------ sub dialogs

    private void openGoogleTv() {
        Intent gtv = new Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setComponent(component("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity").getComponent());
        try {
            prefs.setSkylineHome(false);
            act.startActivity(gtv);
        } catch (Exception e) {
            prefs.setSkylineHome(true);
            Toast.makeText(act, "Google TV home is not available", Toast.LENGTH_SHORT).show();
        }
    }

    private String seasonLabel() {
        return "auto".equals(sky.seasonSetting()) ? "Auto (" + sky.seasonShown() + ")" : sky.seasonShown();
    }

    private String styleLabel() {
        return "vangogh".equals(sky.styleName()) ? "Van Gogh" : "Classic";
    }

    private void openWallpaper() {
        final GlassDialog dlg = new GlassDialog(act, 560).title("Wallpaper");
        dlg.message("Pick a fixed scene to preview it behind this panel, or let the sky follow the real weather.");
        final View motion = dlg.row("Animated sky", "Drifting clouds and mist, twinkling stars. Off uses less power.",
                sky.isAnimated() ? "On" : "Off", null, true);
        motion.setOnClickListener(v -> {
            sky.setAnimated(!sky.isAnimated());
            GlassDialog.setRowValue(motion, sky.isAnimated() ? "On" : "Off");
        });
        final View look = dlg.row("Style", "Classic, or painted in the manner of Van Gogh", styleLabel(), null, true);
        look.setOnClickListener(v -> {
            sky.setStyle("vangogh".equals(sky.styleName()) ? "classic" : "vangogh");
            GlassDialog.setRowValue(look, styleLabel());
        });
        final View season = dlg.row("Season", "The trees, meadow and snow follow the calendar, or pick one", seasonLabel(), null, true);
        season.setOnClickListener(v -> {
            String[] order = {"auto", "spring", "summer", "autumn", "winter"};
            int i = java.util.Arrays.asList(order).indexOf(sky.seasonSetting());
            sky.setSeason(order[(i + 1) % order.length]);
            GlassDialog.setRowValue(season, seasonLabel());
        });
        dlg.section("Sky");
        final String current = prefs.str("scene", "auto");
        final java.util.List<View> rows = new java.util.ArrayList<>();
        final java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add("auto");
        keys.addAll(java.util.Arrays.asList(Scenes.NAMES));
        for (final String key : keys) {
            final View row = dlg.row(key.equals("auto") ? "Auto sky" : Scenes.label(key),
                    key.equals("auto") ? "Follows the sun, moon and your weather" : null,
                    key.equals(current) ? "Selected" : "", null, true);
            rows.add(row);
            row.setOnClickListener(v -> {
                prefs.putStr("scene", key);
                sky.setScene(Scenes.build(key));
                for (int i = 0; i < rows.size(); i++) {
                    GlassDialog.setRowValue(rows.get(i), keys.get(i).equals(key) ? "Selected" : "");
                }
            });
        }
        dlg.button("Done", null, false);
        sub(dlg);
    }

    private void openLocation() {
        WeatherLocationDialog w = new WeatherLocationDialog(act, prefs, place -> {
            String oldCc = prefs.str("cc", "");
            if (place == null) {
                prefs.remove("loc");
                prefs.remove("cc");
                prefs.remove("loc_manual");
            } else {
                prefs.putStr("loc", SkyController.encodeLoc(place.lat, place.lon, place.name));
                prefs.putStr("cc", place.cc);
                prefs.putStr("loc_manual", "1");
            }
            sky.locationChanged();
            if (place != null && !place.cc.equals(oldCc)) news.channelsChanged();
        });
        w.dialog().onClosed(() -> ui.post(this::open));
        w.show();
    }

    private void openCards() {
        sub(new CardsDialog(act, prefs, host::cardsChanged).dialog());
    }

    private void openPs5Input() {
        sub(new HdmiInputDialog(act, prefs, new Ps5(act, prefs), host::playStationChanged).dialog());
    }

    private void openDock() {
        sub(new DockDialog(act, host).dialog());
    }

    private void openHidden() {
        sub(new HiddenAppsDialog(act, host).dialog());
    }

    private void openNews() {
        NewsChannelsDialog n = new NewsChannelsDialog(act, news.repo(), news::channelsChanged);
        n.dialog().onClosed(() -> ui.post(this::open));
        n.show();
    }

    private void openSleep() {
        final GlassDialog dlg = new GlassDialog(act, 480).title("Sleep timer");
        dlg.message("The screen turns off when the time is up. Choose Off to cancel.");
        long end = SleepTimer.endTime(prefs);
        for (final int min : SleepTimer.OPTIONS) {
            dlg.row(SleepTimer.optionLabel(min), null, null, () -> {
                SleepTimer.set(act, prefs, min);
                Toast.makeText(act, min == 0 ? "Sleep timer off" : "Sleeping in " + SleepTimer.optionLabel(min),
                        Toast.LENGTH_SHORT).show();
            }, false);
        }
        if (end > System.currentTimeMillis()) {
            dlg.message("Currently set: " + SleepTimer.describe(end, System.currentTimeMillis()));
        }
        dlg.button("Back", null, false);
        sub(dlg);
    }
}
