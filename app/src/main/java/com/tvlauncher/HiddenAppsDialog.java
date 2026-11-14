package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/** Apps you hid from the home screen; OK brings one back. */
final class HiddenAppsDialog {
    private final Activity act;
    private final AppsHost host;
    private final GlassDialog dlg;
    private final LinearLayout list;
    private final Set<String> hidden = new HashSet<>();
    private final float d;

    HiddenAppsDialog(Activity act, AppsHost host) {
        this.act = act;
        this.host = host;
        d = act.getResources().getDisplayMetrics().density;
        hidden.addAll(host.hidden());
        dlg = new GlassDialog(act, 560).title("Hidden apps");
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        dlg.body().addView(list, new LinearLayout.LayoutParams(-1, -2));
        dlg.button("Done", null, false);
        dlg.onClosed(() -> {
            if (!hidden.equals(host.hidden())) host.saveHidden(new HashSet<>(hidden));
        });
        render();
    }

    GlassDialog dialog() {
        return dlg;
    }

    void show() {
        dlg.show();
    }

    private void render() {
        list.removeAllViews();
        boolean any = false;
        for (final AppEntry e : host.apps()) {
            if (!hidden.contains(e.pkg)) continue;
            any = true;
            View row = GlassDialog.makeRow(act, e.label, null, "Show");
            row.setOnClickListener(v -> {
                hidden.remove(e.pkg);
                render();
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!any) {
            TextView t = GlassDialog.text(act, 15, 300, Color.argb(150, 255, 255, 255));
            t.setText("No hidden apps. Hide one from its menu (hold OK on an app).");
            t.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
            list.addView(t);
        } else {
            list.getChildAt(0).requestFocus();
        }
    }
}
