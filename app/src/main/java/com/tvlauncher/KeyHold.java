package com.tvlauncher;

import android.view.KeyEvent;

/**
 * Detects a long press of the Mute key by time, not by the system's auto-repeat (some remotes send no repeats).
 * The caller feeds key events and, when {@link #onKey} returns true, checks {@link #due} after {@link #HOLD_MS}.
 */
final class KeyHold {
    static final long HOLD_MS = 800;
    private long downAt = -1;
    private boolean fired;
    private boolean swallowing;

    static boolean isMute(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_MUTE;
    }

    /** True when a new press of Mute starts (repeats of the same press return false). */
    boolean onKey(int keyCode, boolean down, long now) {
        if (!isMute(keyCode)) return false;
        if (!down) {
            downAt = -1;
            fired = false;
            return false;
        }
        if (downAt >= 0) return false; // auto-repeat of a press already being timed
        downAt = now;
        fired = false;
        return true;
    }

    /** True once per press, when Mute has been down for HOLD_MS. */
    boolean due(long now) {
        if (fired || downAt < 0 || now - downAt < HOLD_MS) return false;
        fired = true;
        swallowing = true;
        return true;
    }

    /**
     * After a long press has toggled the panel, the rest of that press (repeats and the release) must not reach the TV:
     * the release is what mutes. Call before {@link #onKey}; true means consume the event.
     */
    boolean swallow(int keyCode, boolean down) {
        if (!swallowing || !isMute(keyCode)) return false;
        if (!down) swallowing = false;
        return true;
    }
}
