package com.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dock order, hidden apps and launch recency. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Saved dock order, or null when the user never customised it. */
    List<String> dock() {
        String s = sp.getString("dock", null);
        if (s == null) return null;
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) for (String p : s.split(",")) out.add(p);
        return out;
    }

    void setDock(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        sp.edit().putString("dock", sb.toString()).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet("hidden", new HashSet<String>()));
    }

    void setHidden(Set<String> h) {
        sp.edit().putStringSet("hidden", new HashSet<>(h)).apply();
    }

    // ---- home screen cards

    /** Master switch: off gives a clean home screen without cards. */
    boolean cardsShown() {
        return !sp.getString("cards_show", "1").equals("0");
    }

    void setCardsShown(boolean on) {
        sp.edit().putString("cards_show", on ? "1" : "0").apply();
    }

    /** Which cards are switched on (kept even while the master switch is off). */
    java.util.List<String> cardsEnabled() {
        return CardsConfig.parse(sp.getString("cards_on", null));
    }

    void setCardsEnabled(java.util.List<String> ids) {
        sp.edit().putString("cards_on", CardsConfig.join(ids)).apply();
    }

    /** With no cards showing, Up from the dock hides the apps too (clean mode). */
    boolean cleanOnUp() {
        return !sp.getString("clean_up", "1").equals("0");
    }

    void setCleanOnUp(boolean on) {
        sp.edit().putString("clean_up", on ? "1" : "0").apply();
    }

    /** True: Skyline takes over Home. False: Google TV's own home is left alone. */
    boolean skylineHome() {
        return !sp.getString("home_mode", "skyline").equals("googletv");
    }

    void setSkylineHome(boolean on) {
        sp.edit().putString("home_mode", on ? "skyline" : "googletv").apply();
    }

    /** The performance overlay (RAM, resolution, FPS); off until the user turns it on. */
    boolean perfOverlay() {
        return sp.getString("perf_overlay", "0").equals("1");
    }

    void setPerfOverlay(boolean on) {
        sp.edit().putString("perf_overlay", on ? "1" : "0").apply();
    }

    void remove(String key) {
        sp.edit().remove(key).apply();
    }

    String str(String key, String def) {
        return sp.getString(key, def);
    }

    void putStr(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    long lastLaunch(String pkg) {
        return sp.getLong("t_" + pkg, 0L);
    }

    void markLaunched(String pkg) {
        sp.edit().putLong("t_" + pkg, System.currentTimeMillis())
                .putInt("n_" + pkg, sp.getInt("n_" + pkg, 0) + 1).apply();
    }

    /** The package launched most often (at least {@code min} times), or null. */
    String favourite(java.util.Collection<String> candidates, int min) {
        String best = null;
        int bestN = min - 1;
        for (String p : candidates) {
            int n = sp.getInt("n_" + p, 0);
            if (n > bestN) {
                best = p;
                bestN = n;
            }
        }
        return best;
    }
}
