package com.tvlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sleep timer. An alarm (so it survives the process being cached) fires {@link SleepReceiver}, which asks the
 * accessibility service to lock the screen; if that is unavailable the system screensaver is started instead.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final int[] OPTIONS = {0, 15, 30, 45, 60, 90, 120};
    static final String ACTION = "com.tvlauncher.SLEEP";
    private static final String KEY = "sleep_end";

    static String optionLabel(int minutes) {
        if (minutes <= 0) return "Off";
        if (minutes < 60) return minutes + " minutes";
        int h = minutes / 60, m = minutes % 60;
        return h + (h == 1 ? " hour" : " hours") + (m == 0 ? "" : " " + m + " min");
    }

    /** "Off", or "in 45 min (10:05 PM)". */
    static String describe(long endMs, long nowMs) {
        if (endMs <= nowMs) return "Off";
        long mins = (endMs - nowMs + 59_999) / 60_000;
        String at = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(endMs));
        String left = mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " min" : mins + " min";
        return "in " + left + " (" + at + ")";
    }

    static long endTime(Prefs p) {
        try {
            return Long.parseLong(p.str(KEY, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, SleepReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** minutes <= 0 cancels. */
    static void set(Context c, Prefs p, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c));
        if (minutes <= 0) {
            p.remove(KEY);
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60_000L;
        p.putStr(KEY, String.valueOf(end));
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(c));
    }

    /** Turns the screen off; returns which method was used (for logs). */
    static String fire(Context c) {
        new Prefs(c).remove(KEY);
        HomeWatcherService s = HomeWatcherService.instance;
        if (s != null && s.sleepNow()) return "lock-screen";
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "screensaver";
        } catch (Exception e) {
            return "failed";
        }
    }
}
