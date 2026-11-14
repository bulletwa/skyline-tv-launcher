package com.tvlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Which home-screen cards exist and which are switched on. Pure Java. */
final class CardsConfig {
    private CardsConfig() {}

    static final String SPOTLIGHT = "spotlight", NEWS = "news", NOW_PLAYING = "nowplaying", WEATHER = "weather", PS5 = "ps5";

    /** Every card the launcher knows, in display order. Add new cards here and register them in MainActivity. */
    static final List<String> ALL = Arrays.asList(SPOTLIGHT, NEWS, NOW_PLAYING, WEATHER, PS5);

    /** At most this many cards side by side. */
    static final int MAX = 3;

    static String label(String id) {
        switch (id) {
            case SPOTLIGHT: return "Spotlight";
            case NEWS: return "News";
            case NOW_PLAYING: return "Now Playing";
            case WEATHER: return "Weather";
            case PS5: return "PlayStation";
        }
        return id;
    }

    static String description(String id) {
        switch (id) {
            case SPOTLIGHT: return "Wikipedia's picture of the day, trending articles and your favourite app";
            case NEWS: return "Headlines from the channels you follow";
            case NOW_PLAYING: return "What's playing in Apple Music, Spotify, YouTube Music and other apps";
            case WEATHER: return "Temperature, conditions and air quality for your location";
            case PS5: return "Shows whether your PlayStation is on, and switches the TV to its HDMI input";
        }
        return "";
    }

    static List<String> defaults() {
        return new ArrayList<>(Arrays.asList(SPOTLIGHT, NEWS));
    }

    /** Parses the saved list: unknown ids and duplicates dropped, shown in the fixed display order, capped at MAX. */
    static List<String> parse(String csv) {
        if (csv == null) return defaults();
        List<String> wanted = new ArrayList<>();
        for (String s : csv.split(",")) wanted.add(s.trim());
        List<String> out = new ArrayList<>();
        for (String id : ALL) {
            if (wanted.contains(id) && out.size() < MAX) out.add(id);
        }
        return out;
    }

    static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** True when switching this card on is not possible because MAX cards are already on. */
    static boolean full(List<String> current, String id) {
        return !current.contains(id) && current.size() >= MAX;
    }

    /** The list after switching one card on or off (unchanged if that would exceed MAX). */
    static List<String> set(List<String> current, String id, boolean on) {
        List<String> next = new ArrayList<>(current);
        if (on && !next.contains(id)) {
            if (next.size() >= MAX) return next;
            next.add(id);
        } else if (!on) {
            next.remove(id);
        }
        return parse(join(next)); // normalise the order
    }
}
