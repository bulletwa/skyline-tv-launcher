package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Customize dock. Press OK on an app to pick it up, move it with Up/Down, press OK to drop it.
 * Right while carrying (or hold OK) takes it out of the dock. Apps not in the dock are listed below; OK adds them.
 */
final class DockDialog {
    private final Activity act;
    private final AppsHost host;
    private final GlassDialog dlg;
    private final LinearLayout list;
    private final float d;
    private final List<String> dock = new ArrayList<>();
    private int grabbed = -1;
    private int pickedFrom;
    private final List<String> beforePickUp = new ArrayList<>();
    private boolean reset;

    DockDialog(Activity act, AppsHost host) {
        this.act = act;
        this.host = host;
        d = act.getResources().getDisplayMetrics().density;
        dock.addAll(host.dock());
        dlg = new GlassDialog(act, 620).title("Customize dock");
        dlg.message("Press OK on an app to pick it up, move it with Up and Down, then press OK to drop it. "
                + "Back puts it down where it was. Press Right while holding an app to remove it from the dock.");
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        dlg.body().addView(list, new LinearLayout.LayoutParams(-1, -2));
        // Back while carrying an app puts it down; it only leaves the dialog when nothing is picked up.
        dlg.onBack(() -> {
            if (grabbed < 0) return false;
            dock.clear(); // changed my mind: back to the order from before it was picked up
            dock.addAll(beforePickUp);
            grabbed = -1;
            render(pickedFrom);
            return true;
        });
        dlg.button("Reset to default", () -> {
            reset = true;
            dock.clear();
        }, false);
        dlg.button("Done", null, false);
        dlg.onClosed(() -> {
            if (reset) host.saveDock(null);
            else if (!dock.equals(host.dock())) host.saveDock(new ArrayList<>(dock));
        });
        render(0);
    }

    GlassDialog dialog() {
        return dlg;
    }

    void show() {
        dlg.show();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : host.apps()) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    /** Rebuilds the list and puts focus on the row with this tag (dock index, or 1000+ for "not in dock"). */
    private void render(final int focusTag) {
        list.removeAllViews();
        dock.removeIf(p -> find(p) == null || host.hidden().contains(p));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(8), dp(6), 0, dp(4));
        list.addView(GlassDialog.makeSection(act, "In your dock"), hp);

        if (dock.isEmpty()) {
            TextView t = GlassDialog.text(act, 14, 300, Color.argb(140, 255, 255, 255));
            t.setText("Nothing here. Add apps from the list below.");
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            list.addView(t);
        }
        for (int i = 0; i < dock.size(); i++) addDockRow(i);

        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(-1, -2);
        hp2.setMargins(dp(8), dp(14), 0, dp(4));
        list.addView(GlassDialog.makeSection(act, "Not in the dock"), hp2);
        int n = 0;
        for (AppEntry e : host.apps()) {
            if (dock.contains(e.pkg) || host.hidden().contains(e.pkg)) continue;
            addOtherRow(e, 1000 + n++);
        }
        focusTag(focusTag);
    }

    private void focusTag(int tag) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c.getTag() instanceof Integer && (Integer) c.getTag() == tag) {
                c.requestFocus();
                return;
            }
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) {
                list.getChildAt(i).requestFocus();
                return;
            }
        }
    }

    private void addDockRow(final int idx) {
        AppEntry e = find(dock.get(idx));
        boolean held = idx == grabbed;
        View row = GlassDialog.makeRow(act, e.label, held ? "Up/Down move, OK drop, Right remove, Back cancel" : null, "#" + (idx + 1));
        row.setTag(idx);
        row.setLongClickable(true);
        row.setOnClickListener(v -> {
            if (grabbed == idx) { // drop it here
                grabbed = -1;
            } else {
                grabbed = idx;
                pickedFrom = idx;
                beforePickUp.clear();
                beforePickUp.addAll(dock);
            }
            render(idx);
        });
        row.setOnLongClickListener(v -> {
            grabbed = -1;
            dock.remove(idx);
            render(Math.min(idx, dock.size() - 1));
            return true;
        });
        row.setOnKeyListener((v, code, ev) -> {
            if (grabbed != idx || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                int to = ListOps.move(dock, idx, idx + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
                grabbed = to;
                render(to);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { // take it out of the dock
                grabbed = -1;
                dock.remove(idx);
                render(Math.min(idx, dock.size() - 1));
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_LEFT; // sideways focus changes would drop it by accident
        });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addOtherRow(final AppEntry e, final int tag) {
        View row = GlassDialog.makeRow(act, e.label, null, "Add");
        row.setTag(tag);
        row.setOnClickListener(v -> {
            dock.add(e.pkg);
            grabbed = -1;
            render(dock.size() - 1);
        });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
}
