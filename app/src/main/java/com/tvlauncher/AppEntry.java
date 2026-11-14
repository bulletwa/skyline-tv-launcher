package com.tvlauncher;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;

/** One thing you can launch: an installed app, or the special PlayStation (HDMI input) entry. */
final class AppEntry {
    final String label;
    /** Package name for apps; {@link Ps5#DOCK_ID} for the PlayStation. */
    final String pkg;
    final ComponentName component; // null for special entries
    final ActivityInfo info;       // null for special entries
    /** "HDMI 2" under the PlayStation's name; empty for apps. */
    final String detail;

    AppEntry(String label, ActivityInfo info) {
        this.label = label;
        this.info = info;
        this.pkg = info.packageName;
        this.component = new ComponentName(info.packageName, info.name);
        this.detail = "";
    }

    private AppEntry(String label, String id, String detail) {
        this.label = label;
        this.pkg = id;
        this.component = null;
        this.info = null;
        this.detail = detail;
    }

    static AppEntry playStation(String name, String port) {
        return new AppEntry(name, Ps5.DOCK_ID, port);
    }

    boolean isPlayStation() {
        return info == null;
    }
}
