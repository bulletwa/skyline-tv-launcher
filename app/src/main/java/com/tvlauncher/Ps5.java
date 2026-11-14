package com.tvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The PlayStation on one of the TV's HDMI inputs: find it, read whether it is on, and jump to it. Uses only the
 * public TvInputManager (no special permission). Switching asks TCL's TV app to tune the input's passthrough URI,
 * which is what its own "Inputs" list does.
 */
final class Ps5 {
    /** The id used for the PlayStation in the dock's saved list. */
    static final String DOCK_ID = "special:ps5";
    private static final String TAG = "Ps5";
    private static final String TV_PKG = "com.tcl.tv", TV_ACTIVITY = "com.tcl.tv.TVActivity";

    /** The console as found right now. */
    static final class Info {
        final String inputId, name, parentId;
        final int state; // HdmiPick.CONNECTED / STANDBY / DISCONNECTED

        Info(String inputId, String name, String parentId, int state) {
            this.inputId = inputId;
            this.name = name;
            this.parentId = parentId;
            this.state = state;
        }

        String port() {
            return HdmiPick.portText(inputId);
        }

        String statusWord() {
            return HdmiPick.statusWord(state);
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final TvInputManager tim;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TvInputManager.TvInputCallback callback;

    Ps5(Context c, Prefs prefs) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
    }

    boolean available() {
        return tim != null;
    }

    /** Every HDMI input the TV lists (devices and bare ports), with the state of the port each is plugged into. */
    List<HdmiPick.Row> inputs() {
        List<HdmiPick.Row> out = new ArrayList<>();
        if (tim == null) return out;
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.getType() != TvInputInfo.TYPE_HDMI) continue;
                String parent = i.getParentId();
                int own = tim.getInputState(i.getId());
                int parentState = parent == null ? -1 : tim.getInputState(parent);
                out.add(new HdmiPick.Row(i.getId(), String.valueOf(i.loadLabel(ctx)), parent, own, parentState));
                Log.d(TAG, "hdmi input: id=" + i.getId() + " label=" + i.loadLabel(ctx) + " parent=" + parent
                        + " state=" + own + " parentState=" + parentState);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not list inputs", e);
        }
        return out;
    }

    /** The PlayStation (your choice in Settings, else detected by name), or null. */
    Info find() {
        List<HdmiPick.Row> rows = inputs();
        HdmiPick.Row r = HdmiPick.choose(rows, prefs.str("ps5_input", ""));
        if (r == null) return null;
        return new Info(r.id, r.label, r.parentId, r.effectiveState());
    }

    /** Calls {@code onChange} (on the UI thread) whenever an input is added, removed or changes state. */
    void listen(final Runnable onChange) {
        if (tim == null || callback != null) return;
        callback = new TvInputManager.TvInputCallback() {
            @Override public void onInputStateChanged(String inputId, int state) { onChange.run(); }
            @Override public void onInputAdded(String inputId) { onChange.run(); }
            @Override public void onInputRemoved(String inputId) { onChange.run(); }
            @Override public void onInputUpdated(String inputId) { onChange.run(); }
        };
        try {
            tim.registerCallback(callback, ui);
        } catch (RuntimeException e) {
            callback = null;
        }
    }

    void stopListening() {
        if (tim == null || callback == null) return;
        try {
            tim.unregisterCallback(callback);
        } catch (RuntimeException ignored) {
        }
        callback = null;
    }

    /** The intent that tunes an input: VIEW its passthrough URI, typed as a channel, aimed at TCL's TV app. */
    static Intent switchIntent(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "vnd.android.cursor.item/channel")
                .setComponent(new ComponentName(TV_PKG, TV_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** @return true when the request was handed to the TV app */
    boolean switchTo(Activity a, Info info) {
        if (info == null) return false;
        try {
            Log.i(TAG, "switching to " + info.inputId);
            a.startActivity(switchIntent(info.inputId));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "switch failed", e);
            return false;
        }
    }
}
