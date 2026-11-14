package com.tvlauncher;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which HDMI input is the PlayStation, and what state it is in. Pure Java, so it can be tested. */
final class HdmiPick {
    private HdmiPick() {}

    // android.media.tv.TvInputManager.INPUT_STATE_*
    static final int CONNECTED = 0, STANDBY = 1, DISCONNECTED = 2;

    /** One HDMI input as the TV lists it. */
    static final class Row {
        final String id, label, parentId;
        /** State of this input; of its hardware port (parent) when it has one, else -1. */
        final int state, parentState;

        Row(String id, String label, String parentId, int state, int parentState) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.parentId = parentId;
            this.state = state;
            this.parentState = parentState;
        }

        /**
         * A console's CEC input reports "connected" even while the console sleeps; the hardware port it is plugged
         * into is what tells on / standby / unplugged, so that wins when there is one.
         */
        int effectiveState() {
            return parentState >= 0 ? parentState : state;
        }
    }

    private static final Pattern CEC_ID = Pattern.compile("HDMI([0-9A-Fa-f])[0-9A-Fa-f]{3}[0-9A-Fa-f]{2}$");

    /** HDMI port number from a CEC device input id like ".../HDMI200004" (physical address 2.0.0.0 = port 2); -1 if unknown. */
    static int port(String inputId) {
        if (inputId == null) return -1;
        Matcher m = CEC_ID.matcher(inputId);
        return m.find() ? Integer.parseInt(m.group(1), 16) : -1;
    }

    static boolean looksLikePlayStation(String label) {
        String l = label.toLowerCase(Locale.ENGLISH);
        return l.contains("playstation") || l.contains("ps5") || l.contains("ps 5");
    }

    /**
     * The input to treat as the PlayStation: the one you chose in Settings (if it still exists), else the first
     * input whose name says PlayStation, preferring a CEC device input over a bare port. Null when none.
     */
    static Row choose(List<Row> rows, String savedId) {
        if (savedId != null && !savedId.isEmpty()) {
            for (Row r : rows) if (r.id.equals(savedId)) return r;
        }
        Row best = null;
        for (Row r : rows) {
            if (!looksLikePlayStation(r.label)) continue;
            if (best == null || (best.parentId == null && r.parentId != null)) best = r;
        }
        return best;
    }

    static String statusWord(int state) {
        switch (state) {
            case CONNECTED: return "On";
            case STANDBY: return "Standby";
            case DISCONNECTED: return "Not connected";
        }
        return "Unknown";
    }

    /** "HDMI 2", or "" when the port is not known. */
    static String portText(String inputId) {
        int p = port(inputId);
        return p > 0 ? "HDMI " + p : "";
    }
}
